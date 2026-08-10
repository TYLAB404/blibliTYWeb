package com.tylab.biliweb.api;

/** B 站 API 调用异常 */
public class BiliApiException extends RuntimeException {
    private final int code;

    public BiliApiException(int code, String message) {
        super(message);
        this.code = code;
    }

    public BiliApiException(String message) {
        this(-1, message);
    }

    public int getCode() { return code; }
}
