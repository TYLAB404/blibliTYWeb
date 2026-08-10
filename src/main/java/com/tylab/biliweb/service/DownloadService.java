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
        String id = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        DownloadTask task = new DownloadTask(id, bvid, cid, quality, videoTitle, pageTitle, pagePart);
        task.setAction(() -> execute(task));
        tasks.put(id, task);
        executor.submit(task);
        return task;
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

    /** 任务主流程 */
    private void execute(DownloadTask task) {
        File dir = prepareDir(task);
        File videoFile = null;
        File audioFile = null;
        try {
            task.setStatus(TaskStatus.DOWNLOADING);

            // 1. 获取播放流
            PlayUrlResult play = apiClient.getPlayUrl(task.getBvid(), task.getCid(), task.getQuality());
            StreamInfo video = pickVideoStream(play, task);
            StreamInfo audio = pickAudioStream(play);
            if (video == null || audio == null) {
                throw new RuntimeException("无法获取音视频流（可能需要登录或大会员 Cookie）");
            }
            task.setActualQuality(video.getId());

            // 2. 下载视频流 + 音频流
            String base = new File(dir, task.getPagePart() + "_" + sanitize(task.getPageTitle())).getAbsolutePath();
            videoFile = new File(base + "_v.m4s");
            audioFile = new File(base + "_a.m4s");

            task.setTotalBytes(Math.max(1, probeTotal(play)));
            AtomicLong done = new AtomicLong(0);
            downloadStream(videoFile, urlList(video), task, "下载视频流", done);
            downloadStream(audioFile, urlList(audio), task, "下载音频流", done);

            // 3. 合并
            task.setStatus(TaskStatus.MERGING);
            task.setStage("ffmpeg 合并音视频");
            task.setProgress(100);
            String ext = task.getQuality() >= 120 ? ".mkv" : ".mp4";
            File output = ffmpegUtil.merge(videoFile.getAbsolutePath(), audioFile.getAbsolutePath(), base + ext);

            // 4. 清理临时文件
            if (videoFile.exists()) videoFile.delete();
            if (audioFile.exists()) audioFile.delete();

            task.setOutputFile(output.getAbsolutePath());
            task.setOutputSize(formatSize(output.length()));
            task.setStatus(TaskStatus.COMPLETED);
            task.setFinishedAt(System.currentTimeMillis());
            log.info("任务 {} 完成: {}", task.getId(), output.getAbsolutePath());
        } catch (DownloadCancelledException e) {
            cleanup(videoFile, audioFile);
            task.setStatus(TaskStatus.CANCELLED);
            task.setError("已取消");
            task.setFinishedAt(System.currentTimeMillis());
        } catch (QualityPermissionException e) {
            cleanup(videoFile, audioFile);
            task.setStatus(TaskStatus.FAILED);
            task.setError("画质权限不足: " + e.getMessage() + "（请配置大会员 Cookie）");
            task.setFinishedAt(System.currentTimeMillis());
        } catch (Exception e) {
            cleanup(videoFile, audioFile);
            task.setStatus(TaskStatus.FAILED);
            task.setError(e.getMessage());
            task.setFinishedAt(System.currentTimeMillis());
            log.error("任务 {} 失败", task.getId(), e);
        }
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
