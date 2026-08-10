package com.tylab.biliweb.service;

/** 任务被用户取消 */
public class DownloadCancelledException extends Exception {
    public DownloadCancelledException() { super("任务已取消"); }
}
