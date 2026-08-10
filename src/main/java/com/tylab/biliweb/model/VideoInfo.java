package com.tylab.biliweb.model;

import java.util.List;

/** B 站视频基本信息（view 接口结果） */
public class VideoInfo {
    private String bvid;
    private long aid;
    private String title;
    private String cover;
    private List<VideoPage> pages;

    public String getBvid() { return bvid; }
    public void setBvid(String bvid) { this.bvid = bvid; }
    public long getAid() { return aid; }
    public void setAid(long aid) { this.aid = aid; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getCover() { return cover; }
    public void setCover(String cover) { this.cover = cover; }
    public List<VideoPage> getPages() { return pages; }
    public void setPages(List<VideoPage> pages) { this.pages = pages; }
}
