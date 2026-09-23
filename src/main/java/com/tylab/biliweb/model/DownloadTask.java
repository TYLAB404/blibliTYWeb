package com.tylab.biliweb.model;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** 单个下载任务（可序列化为快照返回前端） */
public class DownloadTask implements Runnable {

    private final String id;
    private final String bvid;
    private final long cid;
    private final int quality;
    private final String videoTitle;
    private final String pageTitle;
    private final String pagePart;
    private final boolean audioOnly; // true=仅音频模式（下载音频转 mp3） // P1 / 第1集 等展示前缀

    private volatile TaskStatus status = TaskStatus.WAITING;
    private volatile double progress = 0;
    private volatile String stage = "等待中";
    private volatile String error;
    private volatile int actualQuality;
    private volatile String outputFile;
    private volatile String outputSize;
    private volatile String speedText = "";
    private volatile String etaText = "";
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final AtomicLong downloadedBytes = new AtomicLong(0);
    private volatile long totalBytes = 1;
    private volatile long createdAt = System.currentTimeMillis();
    private volatile long finishedAt;

    // 当前关联的外部进程（如 ffmpeg），用于取消时直接杀死
    @JsonIgnore
    private volatile Process currentProcess;

    @JsonIgnore
    private long lastSampleTime = 0;
    @JsonIgnore
    private long lastSampleBytes = 0;
    @JsonIgnore
    private double smoothedSpeed = 0;

    // 由 DownloadService 注入的执行体，避免 DownloadTask 直接依赖服务
    @JsonIgnore
    private Runnable action;

    public DownloadTask(String id, String bvid, long cid, int quality,
                        String videoTitle, String pageTitle, String pagePart) {
        this(id, bvid, cid, quality, videoTitle, pageTitle, pagePart, false);
    }

    public DownloadTask(String id, String bvid, long cid, int quality,
                        String videoTitle, String pageTitle, String pagePart, boolean audioOnly) {
        this.id = id;
        this.bvid = bvid;
        this.cid = cid;
        this.quality = quality;
        this.videoTitle = videoTitle;
        this.pageTitle = pageTitle;
        this.pagePart = pagePart;
        this.audioOnly = audioOnly;
    }

    public void setAction(Runnable action) { this.action = action; }

    @Override
    public void run() {
        if (action != null) {
            action.run();
        }
    }

    public boolean isCancelled() { return cancelled.get(); }
    public void cancel() {
        cancelled.set(true);
        Process p = this.currentProcess;
        if (p != null) {
            try {
                p.destroyForcibly();
            } catch (Exception ignored) {
            }
        }
    }

    public void setCurrentProcess(Process process) {
        this.currentProcess = process;
        if (cancelled.get() && process != null) {
            try {
                process.destroyForcibly();
            } catch (Exception ignored) {
            }
        }
    }

    public Process getCurrentProcess() {
        return currentProcess;
    }

    public void updateProgress(double bytesDone, double bytesTotal) {
        this.downloadedBytes.set((long) bytesDone);
        if (bytesTotal > 0) {
            this.progress = Math.min(100, bytesDone * 100.0 / bytesTotal);
        }
        long now = System.currentTimeMillis();
        if (lastSampleTime == 0) {
            lastSampleTime = now;
            lastSampleBytes = (long) bytesDone;
        } else {
            long elapsed = now - lastSampleTime;
            if (elapsed >= 500) {
                long deltaBytes = (long) bytesDone - lastSampleBytes;
                if (deltaBytes >= 0) {
                    double currentSpeed = (deltaBytes * 1000.0) / elapsed;
                    if (smoothedSpeed <= 0) {
                        smoothedSpeed = currentSpeed;
                    } else {
                        smoothedSpeed = smoothedSpeed * 0.65 + currentSpeed * 0.35;
                    }
                    this.speedText = formatSpeed(smoothedSpeed);
                    if (bytesTotal > bytesDone && smoothedSpeed > 1024) {
                        long remainBytes = (long) (bytesTotal - bytesDone);
                        long remainSecs = (long) (remainBytes / smoothedSpeed);
                        this.etaText = formatEta(remainSecs);
                    } else {
                        this.etaText = "";
                    }
                }
                lastSampleTime = now;
                lastSampleBytes = (long) bytesDone;
            }
        }
    }

    private static String formatSpeed(double bytesPerSec) {
        if (bytesPerSec < 1024) return String.format("%.0f B/s", bytesPerSec);
        double kb = bytesPerSec / 1024.0;
        if (kb < 1024) return String.format("%.1f KB/s", kb);
        double mb = kb / 1024.0;
        return String.format("%.2f MB/s", mb);
    }

    private static String formatEta(long secs) {
        if (secs < 0) return "";
        if (secs >= 3600) {
            return String.format("%02d:%02d:%02d", secs / 3600, (secs % 3600) / 60, secs % 60);
        }
        return String.format("%02d:%02d", secs / 60, secs % 60);
    }

    public String getSpeedText() { return speedText; }
    public void setSpeedText(String speedText) { this.speedText = speedText; }
    public String getEtaText() { return etaText; }
    public void setEtaText(String etaText) { this.etaText = etaText; }

    public boolean isAudioOnly() { return audioOnly; }

    public String getId() { return id; }
    public String getBvid() { return bvid; }
    public long getCid() { return cid; }
    public int getQuality() { return quality; }
    public String getVideoTitle() { return videoTitle; }
    public String getPageTitle() { return pageTitle; }
    public String getPagePart() { return pagePart; }

    public TaskStatus getStatus() { return status; }
    public void setStatus(TaskStatus status) { this.status = status; }
    public double getProgress() { return progress; }
    public void setProgress(double progress) { this.progress = progress; }
    public String getStage() { return stage; }
    public void setStage(String stage) { this.stage = stage; }
    public String getError() { return error; }
    public void setError(String error) { this.error = error; }
    public int getActualQuality() { return actualQuality; }
    public void setActualQuality(int actualQuality) { this.actualQuality = actualQuality; }
    public String getOutputFile() { return outputFile; }
    public void setOutputFile(String outputFile) { this.outputFile = outputFile; }
    public String getOutputSize() { return outputSize; }
    public void setOutputSize(String outputSize) { this.outputSize = outputSize; }
    public AtomicLong getDownloadedBytes() { return downloadedBytes; }
    public long getTotalBytes() { return totalBytes; }
    public void setTotalBytes(long totalBytes) { this.totalBytes = totalBytes; }
    public long getCreatedAt() { return createdAt; }
    public long getFinishedAt() { return finishedAt; }
    public void setFinishedAt(long finishedAt) { this.finishedAt = finishedAt; }
}
