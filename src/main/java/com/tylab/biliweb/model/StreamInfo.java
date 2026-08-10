package com.tylab.biliweb.model;

import java.util.List;

/** 一个媒体流（DASH 中的视频流或音频流） */
public class StreamInfo {
    private int id;          // 画质码（视频流）
    private String baseUrl;
    private List<String> backupUrls;
    private long bandwidth;
    private String codecs;
    private String mimeType;

    public int getId() { return id; }
    public void setId(int id) { this.id = id; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public List<String> getBackupUrls() { return backupUrls; }
    public void setBackupUrls(List<String> backupUrls) { this.backupUrls = backupUrls; }
    public long getBandwidth() { return bandwidth; }
    public void setBandwidth(long bandwidth) { this.bandwidth = bandwidth; }
    public String getCodecs() { return codecs; }
    public void setCodecs(String codecs) { this.codecs = codecs; }
    public String getMimeType() { return mimeType; }
    public void setMimeType(String mimeType) { this.mimeType = mimeType; }
}
