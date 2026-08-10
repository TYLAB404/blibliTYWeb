package com.tylab.biliweb.model;

/** 下载任务状态 */
public enum TaskStatus {
    WAITING("排队中"),
    DOWNLOADING("下载中"),
    MERGING("合并中"),
    COMPLETED("已完成"),
    FAILED("失败"),
    CANCELLED("已取消");

    private final String label;

    TaskStatus(String label) {
        this.label = label;
    }

    public String getLabel() { return label; }
}
