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
    /** 发布时间戳（秒） */
    private Long createTime;

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

    public Long getCreateTime() {
        return createTime;
    }

    public void setCreateTime(Long createTime) {
        this.createTime = createTime;
    }
}
