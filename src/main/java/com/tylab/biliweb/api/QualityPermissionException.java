package com.tylab.biliweb.api;

/** 画质权限不足（需要登录/大会员） */
public class QualityPermissionException extends BiliApiException {
    public QualityPermissionException(int code, String message) {
        super(code, message);
    }
}
