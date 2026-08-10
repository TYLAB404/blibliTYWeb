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
    private final String pagePart; // P1 / 第1集 等展示前缀

    private volatile TaskStatus status = TaskStatus.WAITING;
    private volatile double progress = 0;
    private volatile String stage = "等待中";
    private volatile String error;
    private volatile int actualQuality;
    private volatile String outputFile;
    private volatile String outputSize;
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final AtomicLong downloadedBytes = new AtomicLong(0);
    private volatile long totalBytes = 1;
    private volatile long createdAt = System.currentTimeMillis();
    private volatile long finishedAt;

    // 由 DownloadService 注入的执行体，避免 DownloadTask 直接依赖服务
    @JsonIgnore
    private Runnable action;

    public DownloadTask(String id, String bvid, long cid, int quality,
                        String videoTitle, String pageTitle, String pagePart) {
        this.id = id;
        this.bvid = bvid;
        this.cid = cid;
        this.quality = quality;
        this.videoTitle = videoTitle;
        this.pageTitle = pageTitle;
        this.pagePart = pagePart;
    }

    public void setAction(Runnable action) { this.action = action; }

    @Override
    public void run() {
        if (action != null) {
            action.run();
        }
    }

    public boolean isCancelled() { return cancelled.get(); }
    public void cancel() { cancelled.set(true); }

    public void updateProgress(double bytesDone, double bytesTotal) {
        if (bytesTotal > 0) {
            this.progress = Math.min(100, bytesDone * 100.0 / bytesTotal);
        }
    }

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
