package com.tylab.biliweb.model;

import java.util.ArrayList;
import java.util.List;

/**
 * 抖音 / TikTok 作品解析结果（支持无水印视频与图集）
 */
public class DouyinInfo {

    private String platform = "douyin";
    /** 类型：video（普通视频）或 image（图集） */
    private String type = "video";
    /** 抖音作品 ID（aweme_id） */
    private String awemeId;
    /** 作品标题 / 描述文案 */
    private String title;
    /** 封面图 URL */
    private String cover;
    /** 作者昵称 */
    private String authorName;
    /** 作者头像 URL */
    private String authorAvatar;
    /** 无水印视频播放直链（MP4） */
    private String videoUrl;
    /** 视频宽度 */
    private Integer videoWidth;
    /** 视频高度 */
    private Integer videoHeight;
    /** 视频时长（秒） */
    private Long duration;
    /** 原声/背景音乐直链（MP3） */
    private String musicUrl;
    /** 音乐标题 */
    private String musicTitle;
    /** 图集模式下的无水印高清图片列表 */
    private List<String> images = new ArrayList<>();
    /** 可选画质规格列表（如 1080P 超清 / 原画、720P 高清、540P 标清） */
    private List<VideoQuality> qualities = new ArrayList<>();
    /** 发布时间戳（秒） */
    private Long createTime;

    /**
     * 画质清晰度模型
     */
    public static class VideoQuality {
        private String quality;
        private String label;
        private String url;
        private Integer width;
        private Integer height;
        private Long dataSize;
        private String sizeDesc;

        public VideoQuality() {}

        public VideoQuality(String quality, String label, String url, Integer width, Integer height, Long dataSize, String sizeDesc) {
            this.quality = quality;
            this.label = label;
            this.url = url;
            this.width = width;
            this.height = height;
            this.dataSize = dataSize;
            this.sizeDesc = sizeDesc;
        }

        public String getQuality() { return quality; }
        public void setQuality(String quality) { this.quality = quality; }

        public String getLabel() { return label; }
        public void setLabel(String label) { this.label = label; }

        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }

        public Integer getWidth() { return width; }
        public void setWidth(Integer width) { this.width = width; }

        public Integer getHeight() { return height; }
        public void setHeight(Integer height) { this.height = height; }

        public Long getDataSize() { return dataSize; }
        public void setDataSize(Long dataSize) { this.dataSize = dataSize; }

        public String getSizeDesc() { return sizeDesc; }
        public void setSizeDesc(String sizeDesc) { this.sizeDesc = sizeDesc; }
    }

    public String getPlatform() {
        return platform;
    }

    public void setPlatform(String platform) {
        this.platform = platform;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getAwemeId() {
        return awemeId;
    }

    public void setAwemeId(String awemeId) {
        this.awemeId = awemeId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getCover() {
        return cover;
    }

    public void setCover(String cover) {
        this.cover = cover;
    }

    public String getAuthorName() {
        return authorName;
    }

    public void setAuthorName(String authorName) {
        this.authorName = authorName;
    }

    public String getAuthorAvatar() {
        return authorAvatar;
    }

    public void setAuthorAvatar(String authorAvatar) {
        this.authorAvatar = authorAvatar;
    }

    public String getVideoUrl() {
        return videoUrl;
    }

    public void setVideoUrl(String videoUrl) {
        this.videoUrl = videoUrl;
    }

    public Integer getVideoWidth() {
        return videoWidth;
    }

    public void setVideoWidth(Integer videoWidth) {
        this.videoWidth = videoWidth;
    }

    public Integer getVideoHeight() {
        return videoHeight;
    }

    public void setVideoHeight(Integer videoHeight) {
        this.videoHeight = videoHeight;
    }

    public Long getDuration() {
        return duration;
    }

    public void setDuration(Long duration) {
        this.duration = duration;
    }

    public String getMusicUrl() {
        return musicUrl;
    }

    public void setMusicUrl(String musicUrl) {
        this.musicUrl = musicUrl;
    }

    public String getMusicTitle() {
        return musicTitle;
    }

    public void setMusicTitle(String musicTitle) {
        this.musicTitle = musicTitle;
    }

    public List<String> getImages() {
        return images;
    }

    public void setImages(List<String> images) {
        this.images = images;
    }

    public List<VideoQuality> getQualities() {
        return qualities;
    }

    public void setQualities(List<VideoQuality> qualities) {
        this.qualities = qualities;
    }

    public Long getCreateTime() {
        return createTime;
    }

    public void setCreateTime(Long createTime) {
        this.createTime = createTime;
    }
}
