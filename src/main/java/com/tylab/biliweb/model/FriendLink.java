package com.tylab.biliweb.model;

/** 友情链接数据模型 */
public class FriendLink {
    private String id;
    private String name;
    private String url;
    private String description;
    private long createTime;

    public FriendLink() {
    }

    public FriendLink(String id, String name, String url, String description, long createTime) {
        this.id = id;
        this.name = name;
        this.url = url;
        this.description = description;
        this.createTime = createTime;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public long getCreateTime() {
        return createTime;
    }

    public void setCreateTime(long createTime) {
        this.createTime = createTime;
    }
}
