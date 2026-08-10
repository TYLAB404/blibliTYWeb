package com.tylab.biliweb.model;

/** 视频的一个分P */
public class VideoPage {
    private long cid;
    private int page;
    private String part;
    private long duration; // 秒

    public long getCid() { return cid; }
    public void setCid(long cid) { this.cid = cid; }
    public int getPage() { return page; }
    public void setPage(int page) { this.page = page; }
    public String getPart() { return part; }
    public void setPart(String part) { this.part = part; }
    public long getDuration() { return duration; }
    public void setDuration(long duration) { this.duration = duration; }
}
