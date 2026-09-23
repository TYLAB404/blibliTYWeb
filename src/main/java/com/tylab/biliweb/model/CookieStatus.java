package com.tylab.biliweb.model;

/** Cookie 配置状态（返回前端，不回显明文） */
public class CookieStatus {
    private boolean configured;
    private boolean valid;
    private String username;
    private boolean vip;
    private String message;
    private long updatedAt;
    private String loginType; // "qrcode" (扫码登录) 或 "manual" (手动配置)

    public boolean isConfigured() { return configured; }
    public void setConfigured(boolean configured) { this.configured = configured; }
    public boolean isValid() { return valid; }
    public void setValid(boolean valid) { this.valid = valid; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public boolean isVip() { return vip; }
    public void setVip(boolean vip) { this.vip = vip; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(long updatedAt) { this.updatedAt = updatedAt; }
    public String getLoginType() { return loginType; }
    public void setLoginType(String loginType) { this.loginType = loginType; }
}
