package com.tylab.biliweb.service;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.tylab.biliweb.api.BiliApiClient;
import com.tylab.biliweb.api.PlayUrlResult;
import com.tylab.biliweb.api.QualityPermissionException;
import com.tylab.biliweb.model.DownloadTask;
import com.tylab.biliweb.model.StreamInfo;
import com.tylab.biliweb.model.TaskStatus;
import com.tylab.biliweb.util.FfmpegUtil;
import org.apache.http.HttpHeaders;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.util.EntityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.PreDestroy;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** 下载任务管理：创建、执行、查询、取消 */
@Service
public class DownloadService {

    private static final Logger log = LoggerFactory.getLogger(DownloadService.class);
    private static final int BUFFER_SIZE = 64 * 1024;
    /** 任务级网络错误自动重试次数 */
    private static final int TASK_AUTO_RETRY_MAX = 2;
    /** 每次重试前等待秒数 */
    private static final int TASK_AUTO_RETRY_DELAY = 5;

    private final BiliApiClient apiClient;
    private final FfmpegUtil ffmpegUtil;
    private final String downloadDir;

    private final Map<String, DownloadTask> tasks = new ConcurrentHashMap<>();
    private final ExecutorService executor;

    public DownloadService(BiliApiClient apiClient, FfmpegUtil ffmpegUtil,
                           @Value("${app.download-dir:./downloads}") String downloadDir) {
        this.apiClient = apiClient;
        this.ffmpegUtil = ffmpegUtil;
        this.downloadDir = downloadDir;
        this.executor = Executors.newFixedThreadPool(2, new ThreadFactory() {
            private final AtomicLong n = new AtomicLong();
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "download-worker-" + n.incrementAndGet());
                t.setDaemon(true);
                return t;
            }
        });
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }

    /** 创建并启动一个下载任务 */
    public DownloadTask createTask(String bvid, long cid, int quality,
                                   String videoTitle, String pageTitle, String pagePart) {
        return createTask(bvid, cid, quality, videoTitle, pageTitle, pagePart, false);
    }

    /** 创建并启动一个下载任务（audioOnly=true 时仅下载音频转 mp3） */
    public DownloadTask createTask(String bvid, long cid, int quality,
                                   String videoTitle, String pageTitle, String pagePart, boolean audioOnly) {
        // 任务去重：相同 bvid+cid+模式 且 处于活跃状态的任务不重复添加
        DownloadTask dup = findActiveTask(bvid, cid, audioOnly);
        if (dup != null) {
            throw new IllegalArgumentException("该内容已在下载列表中（" + dup.getPagePart() + "），无需重复添加");
        }
        String id = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        DownloadTask task = new DownloadTask(id, bvid, cid, quality, videoTitle, pageTitle, pagePart, audioOnly);
        task.setAction(() -> execute(task));
        tasks.put(id, task);
        executor.submit(task);
        return task;
    }

    /** 查找相同 bvid+cid+模式 的活跃任务（进行中/排队/合并），用于去重 */
    private DownloadTask findActiveTask(String bvid, long cid, boolean audioOnly) {
        for (DownloadTask t : tasks.values()) {
            if (t.getBvid().equals(bvid) && t.getCid() == cid
                    && t.isAudioOnly() == audioOnly
                    && t.getStatus() != TaskStatus.COMPLETED
                    && t.getStatus() != TaskStatus.FAILED
                    && t.getStatus() != TaskStatus.CANCELLED) {
                return t;
            }
        }
        return null;
    }

    public DownloadTask getTask(String id) {
        return tasks.get(id);
    }

    public List<DownloadTask> listTasks() {
        return new ArrayList<>(tasks.values());
    }

    public boolean cancelTask(String id) {
        DownloadTask task = tasks.get(id);
        if (task == null) return false;
        task.cancel();
        return true;
    }

    /** 任务主流程（含网络错误自动重试） */
    private void execute(DownloadTask task) {
        int attempts = 0;
        while (true) {
            File dir = prepareDir(task);
            File videoFile = null;
            File audioFile = null;
            try {
                task.setStatus(TaskStatus.DOWNLOADING);

                // 1. 获取播放流
                PlayUrlResult play = apiClient.getPlayUrl(task.getBvid(), task.getCid(), task.getQuality());
                StreamInfo video = pickVideoStream(play, task);
                StreamInfo audio = pickAudioStream(play);

                String base = new File(dir, task.getPagePart() + "_" + sanitize(task.getPageTitle())).getAbsolutePath();
                AtomicLong done = new AtomicLong(0);

                // 2. 仅音频模式：只下载音频流，转 mp3
                if (task.isAudioOnly()) {
                    if (audio == null) {
                        throw new RuntimeException("无法获取音频流（可能需要登录或大会员 Cookie）");
                    }
                    task.setActualQuality(audio.getId());
                    audioFile = new File(base + "_a.m4s");
                    task.setTotalBytes(Math.max(1, probeSingleSize(audio)));
                    downloadStream(audioFile, urlList(audio), task, "下载音频流", done);

                    task.setStatus(TaskStatus.MERGING);
                    task.setStage("ffmpeg 转码 MP3");
                    task.setProgress(100);
                    File output = ffmpegUtil.transcodeToMp3(audioFile.getAbsolutePath(), base + ".mp3");

                    if (audioFile.exists()) audioFile.delete();
                    task.setOutputFile(output.getAbsolutePath());
                    task.setOutputSize(formatSize(output.length()));
                    task.setStatus(TaskStatus.COMPLETED);
                    task.setFinishedAt(System.currentTimeMillis());
                    log.info("任务 {} 完成: {}", task.getId(), output.getAbsolutePath());
                    return;
                }

                // 3. 视频模式：下载视频流 + 音频流，再合并
                if (video == null || audio == null) {
                    throw new RuntimeException("无法获取音视频流（可能需要登录或大会员 Cookie）");
                }
                task.setActualQuality(video.getId());
                videoFile = new File(base + "_v.m4s");
                audioFile = new File(base + "_a.m4s");

                task.setTotalBytes(Math.max(1, probeTotal(play)));
                downloadStream(videoFile, urlList(video), task, "下载视频流", done);
                downloadStream(audioFile, urlList(audio), task, "下载音频流", done);

                task.setStatus(TaskStatus.MERGING);
                task.setStage("ffmpeg 合并音视频");
                task.setProgress(100);
                String ext = task.getQuality() >= 120 ? ".mkv" : ".mp4";
                File output = ffmpegUtil.merge(videoFile.getAbsolutePath(), audioFile.getAbsolutePath(), base + ext);

                if (videoFile.exists()) videoFile.delete();
                if (audioFile.exists()) audioFile.delete();

                task.setOutputFile(output.getAbsolutePath());
                task.setOutputSize(formatSize(output.length()));
                task.setStatus(TaskStatus.COMPLETED);
                task.setFinishedAt(System.currentTimeMillis());
                log.info("任务 {} 完成: {}", task.getId(), output.getAbsolutePath());
                return;
            } catch (DownloadCancelledException e) {
                cleanup(videoFile, audioFile);
                task.setStatus(TaskStatus.CANCELLED);
                task.setError("已取消");
                task.setFinishedAt(System.currentTimeMillis());
                return;
            } catch (QualityPermissionException e) {
                cleanup(videoFile, audioFile);
                task.setStatus(TaskStatus.FAILED);
                task.setError("画质权限不足: " + e.getMessage() + "（请配置大会员 Cookie）");
                task.setFinishedAt(System.currentTimeMillis());
                return;
            } catch (Exception e) {
                // 网络类错误：自动重试（限次数 + 间隔），期间可取消
                if (attempts < TASK_AUTO_RETRY_MAX && isNetworkError(e)) {
                    attempts++;
                    cleanup(videoFile, audioFile);
                    task.setError("网络异常，即将自动重试 (" + attempts + "/" + TASK_AUTO_RETRY_MAX + ")");
                    task.setStage("等待重试");
                    log.warn("任务 {} 网络异常，{} 秒后重试 ({}/{}): {}",
                            task.getId(), TASK_AUTO_RETRY_DELAY, attempts, TASK_AUTO_RETRY_MAX, e.getMessage());
                    try {
                        for (int i = 0; i < TASK_AUTO_RETRY_DELAY * 2; i++) {
                            if (task.isCancelled()) {
                                throw new DownloadCancelledException();
                            }
                            Thread.sleep(500);
                        }
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        cleanup(videoFile, audioFile);
                        task.setStatus(TaskStatus.FAILED);
                        task.setError("任务被中断");
                        task.setFinishedAt(System.currentTimeMillis());
                        return;
                    } catch (DownloadCancelledException ce) {
                        cleanup(videoFile, audioFile);
                        task.setStatus(TaskStatus.CANCELLED);
                        task.setError("已取消");
                        task.setFinishedAt(System.currentTimeMillis());
                        return;
                    }
                    continue;
                }
                cleanup(videoFile, audioFile);
                task.setStatus(TaskStatus.FAILED);
                task.setError(e.getMessage());
                task.setFinishedAt(System.currentTimeMillis());
                log.error("任务 {} 失败", task.getId(), e);
                return;
            }
        }
    }

    /** 网络类错误判断：用于任务级自动重试 */
    private boolean isNetworkError(Throwable e) {
        if (e instanceof java.net.SocketException) return true;
        if (e instanceof java.net.SocketTimeoutException) return true;
        if (e instanceof org.apache.http.conn.ConnectTimeoutException) return true;
        String msg = String.valueOf(e.getMessage()).toLowerCase();
        return msg.contains("timeout") || msg.contains("connect")
                || msg.contains("socket") || msg.contains("read timed out")
                || msg.contains("连接") || msg.contains("网络")
                || msg.contains("reset") || msg.contains("broken pipe");
    }

    /** 选择视频流：优先精确匹配请求画质，否则取可用最高画质 */
    private StreamInfo pickVideoStream(PlayUrlResult play, DownloadTask task) {
        StreamInfo exact = null;
        StreamInfo best = null;
        for (StreamInfo s : play.getVideoStreams()) {
            if (s.getId() == task.getQuality()) {
                exact = s;
            }
            if (best == null || s.getId() > best.getId()) {
                best = s;
            }
        }
        return exact != null ? exact : best;
    }

    /** 选择音频流：取 id 最大的 */
    private StreamInfo pickAudioStream(PlayUrlResult play) {
        StreamInfo best = null;
        for (StreamInfo s : play.getAudioStreams()) {
            if (best == null || s.getId() > best.getId()) {
                best = s;
            }
        }
        return best;
    }

    /** 探测音视频总大小（用于进度），失败返回 -1 由下载过程回填 */
    private long probeTotal(PlayUrlResult play) {
        long total = 0;
        for (StreamInfo s : play.getVideoStreams()) {
            long size = apiClient.probeSize(firstUrl(s));
            if (size > 0) { total += size; break; }
        }
        for (StreamInfo s : play.getAudioStreams()) {
            long size = apiClient.probeSize(firstUrl(s));
            if (size > 0) { total += size; break; }
        }
        return total;
    }

    /** 探测单个流大小（仅音频模式用） */
    private long probeSingleSize(StreamInfo s) {
        return apiClient.probeSize(firstUrl(s));
    }

    private List<String> urlList(StreamInfo s) {
        LinkedHashSet<String> set = new LinkedHashSet<>();
        if (s.getBaseUrl() != null && !s.getBaseUrl().isEmpty()) set.add(s.getBaseUrl());
        if (s.getBackupUrls() != null) set.addAll(s.getBackupUrls());
        return new ArrayList<>(set);
    }

    private String firstUrl(StreamInfo s) {
        List<String> urls = urlList(s);
        return urls.isEmpty() ? null : urls.get(0);
    }

    /** 下载一个流，主/备节点依次尝试 */
    private void downloadStream(File target, List<String> urls, DownloadTask task,
                                String stage, AtomicLong done) throws Exception {
        Exception lastErr = null;
        for (String url : urls) {
            if (task.isCancelled()) throw new DownloadCancelledException();
            task.setStage(stage);
            try {
                streamDownload(url, target, task, done);
                return;
            } catch (Exception e) {
                lastErr = e;
                if (task.isCancelled()) throw new DownloadCancelledException();
                log.warn("节点下载失败，切换备用节点: {}", e.getMessage());
                if (target.exists()) target.delete();
            }
        }
        throw lastErr;
    }

    /** 单连接流式下载到文件 */
    private void streamDownload(String url, File target, DownloadTask task, AtomicLong done) throws Exception {
        try (CloseableHttpResponse resp = apiClient.openStream(url, null)) {
            int status = resp.getStatusLine().getStatusCode();
            if (status != 200) {
                throw new RuntimeException("HTTP " + status + " 下载失败");
            }
            long contentLength = resp.getEntity() != null ? resp.getEntity().getContentLength() : -1;
            if (contentLength > 0 && task.getTotalBytes() <= 1) {
                task.setTotalBytes(contentLength);
            }
            try (InputStream in = resp.getEntity().getContent();
                 OutputStream out = new FileOutputStream(target)) {
                byte[] buf = new byte[BUFFER_SIZE];
                int n;
                while ((n = in.read(buf)) != -1) {
                    if (task.isCancelled()) throw new DownloadCancelledException();
                    out.write(buf, 0, n);
                    done.addAndGet(n);
                    task.updateProgress(done.get(), task.getTotalBytes());
                }
                out.flush();
            }
        }
    }

    private File prepareDir(DownloadTask task) {
        File dir = new File(downloadDir, sanitize(task.getBvid()));
        if (!dir.exists() && !dir.mkdirs()) {
            throw new RuntimeException("无法创建下载目录: " + dir.getAbsolutePath());
        }
        return dir;
    }

    private void cleanup(File... files) {
        for (File f : files) {
            if (f != null && f.exists()) {
                f.delete();
            }
        }
    }

    /** 文件名非法字符替换 */
    public static String sanitize(String name) {
        if (name == null) return "";
        return name.replaceAll("[\\\\/:*?\"<>|\\s]+", "_").trim();
    }

    public static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double kb = bytes / 1024.0;
        if (kb < 1024) return String.format("%.1f KB", kb);
        double mb = kb / 1024.0;
        if (mb < 1024) return String.format("%.1f MB", mb);
        return String.format("%.2f GB", mb / 1024.0);
    }
}
