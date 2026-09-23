package com.tylab.biliweb.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tylab.biliweb.api.BiliApiClient;
import com.tylab.biliweb.model.CookieStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import javax.annotation.PostConstruct;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.Base64;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;

/** Cookie 管理：独立文件存储、口令鉴权、有效性自检 */
@Service
public class CookieAdminService {

    private static final Logger log = LoggerFactory.getLogger(CookieAdminService.class);

    private final BiliApiClient apiClient;
    private final String adminToken;
    private final String cookieFilePath;
    private final ObjectMapper mapper = new ObjectMapper();

    public CookieAdminService(BiliApiClient apiClient,
                              @Value("${app.cookie-admin-token:}") String adminToken,
                              @Value("${app.cookie-file:./config/cookie.json}") String cookieFilePath) {
        this.apiClient = apiClient;
        this.adminToken = adminToken == null ? "" : adminToken.trim();
        this.cookieFilePath = cookieFilePath;
    }

    @PostConstruct
    public void loadFromFile() {
        try {
            File f = new File(cookieFilePath);
            if (f.exists()) {
                JsonNode root = mapper.readTree(f);
                String plainCookie = "";
                // 优先读取 AES-256-GCM 加密密文
                if (root.has("encrypted")) {
                    String cipher = root.path("encrypted").asText("");
                    if (!cipher.isEmpty()) {
                        plainCookie = com.tylab.biliweb.util.SecurityUtil.decryptAesGcm(cipher, adminToken);
                        log.info("已从 {} 解密加载 Cookie 配置", cookieFilePath);
                    }
                } else if (root.has("cookie")) {
                    // 兼容旧版明文格式：自动升级为密文存储，消除磁盘明文残留
                    plainCookie = root.path("cookie").asText("");
                    if (!plainCookie.isEmpty()) {
                        log.info("检测到未加密的 Cookie 文件，正在自动升级为 AES-256-GCM 密文存储...");
                        saveEncryptedToFile(plainCookie);
                    }
                }
                if (!plainCookie.isEmpty()) {
                    apiClient.setCookie(plainCookie);
                }
            }
        } catch (Exception e) {
            log.warn("读取或解密 Cookie 配置文件失败: {}", e.getMessage());
        }
    }

    /** 校验管理口令（支持密文哈希比对） */
    public boolean verifyToken(String token) {
        return !adminToken.isEmpty() && com.tylab.biliweb.util.SecurityUtil.verifyToken(token, adminToken);
    }

    public boolean isTokenConfigured() {
        return !adminToken.isEmpty();
    }

    /** 保存 Cookie：先校验有效性，通过后以 AES-256-GCM 加密写入文件并立即生效（默认手动模式） */
    public CookieStatus saveCookie(String cookie) throws IOException {
        return saveCookie(cookie, "manual");
    }

    /** 保存 Cookie：支持指定配置类型（"qrcode" 扫码登录 或 "manual" 手动配置） */
    public CookieStatus saveCookie(String cookie, String loginType) throws IOException {
        String trimmed = cookie == null ? "" : cookie.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("Cookie 不能为空");
        }
        // 先用新 Cookie 检测有效性
        BiliApiClient.LoginStatus login = apiClient.checkLoginWith(trimmed);
        if (login == null) {
            throw new IllegalArgumentException("无法连接 B 站，请检查网络后重试");
        }
        if (!login.isLogin) {
            throw new IllegalArgumentException("Cookie 无效或已过期，请重新登录 B 站后复制");
        }
        String resolvedType = (loginType == null || loginType.isEmpty()) ? "manual" : loginType;
        // 加密写入文件（绝证明文落地）
        saveEncryptedToFile(trimmed, resolvedType);
        // 立即生效
        apiClient.setCookie(trimmed);
        clearCache();
        log.info("Cookie 已加密持久化并生效（方式: {}, 账号: {}）", resolvedType, login.uname);
        return buildStatus(true, login);
    }

    /** 将 Cookie 以 AES-256-GCM 密文写入磁盘（记录配置方式） */
    private void saveEncryptedToFile(String plainCookie, String loginType) throws IOException {
        File f = new File(cookieFilePath);
        File parent = f.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        String encrypted = com.tylab.biliweb.util.SecurityUtil.encryptAesGcm(plainCookie, adminToken);
        ObjectNode node = mapper.createObjectNode();
        node.put("encrypted", encrypted);
        node.put("loginType", loginType != null ? loginType : "manual");
        node.put("updatedAt", System.currentTimeMillis());
        mapper.writeValue(f, node);
    }

    private void saveEncryptedToFile(String plainCookie) throws IOException {
        saveEncryptedToFile(plainCookie, "manual");
    }

    /** 申请 B 站登录二维码（包含 Base64 图片数据，方便前端以 <img> 显示和长按保存） */
    public Map<String, Object> generateLoginQrCode() {
        BiliApiClient.QrCodeResult qr = apiClient.generateQrCode();
        Map<String, Object> res = new HashMap<>();
        res.put("url", qr.url);
        res.put("qrcodeKey", qr.qrcodeKey);
        try {
            QRCodeWriter qrCodeWriter = new QRCodeWriter();
            Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
            hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
            hints.put(EncodeHintType.MARGIN, 1);
            BitMatrix bitMatrix = qrCodeWriter.encode(qr.url, BarcodeFormat.QR_CODE, 200, 200, hints);
            ByteArrayOutputStream pngOutputStream = new ByteArrayOutputStream();
            MatrixToImageWriter.writeToStream(bitMatrix, "PNG", pngOutputStream);
            byte[] pngData = pngOutputStream.toByteArray();
            String base64Img = "data:image/png;base64," + Base64.getEncoder().encodeToString(pngData);
            res.put("qrImgBase64", base64Img);
        } catch (Exception e) {
            log.warn("生成二维码 Base64 失败: {}", e.getMessage());
        }
        return res;
    }

    /** 轮询登录二维码状态（成功自动加密保存） */
    public Map<String, Object> pollLoginQrCode(String qrcodeKey) {
        BiliApiClient.QrPollResult result = apiClient.pollQrCode(qrcodeKey);
        Map<String, Object> map = new java.util.HashMap<>();
        map.put("code", result.code);
        map.put("message", result.message);
        if (result.code == 0 && result.cookie != null && !result.cookie.isEmpty()) {
            try {
                CookieStatus status = saveCookie(result.cookie, "qrcode");
                map.put("status", status);
                map.put("success", true);
            } catch (Exception e) {
                log.error("扫码成功但保存 Cookie 异常", e);
                map.put("error", "保存 Cookie 失败: " + e.getMessage());
            }
        }
        return map;
    }

    private volatile CookieStatus cachedStatus = null;
    private volatile long cacheTime = 0L;
    private static final long CACHE_TTL_MS = 60_000L; // 60秒轻量缓存

    /** 获取缓存的 Cookie 状态，供前端公开状态栏高频读取（TTL 60秒） */
    public CookieStatus getStatusCached() {
        long now = System.currentTimeMillis();
        CookieStatus cached = this.cachedStatus;
        if (cached != null && (now - cacheTime < CACHE_TTL_MS)) {
            return cached;
        }
        CookieStatus status = getStatus();
        this.cachedStatus = status;
        this.cacheTime = now;
        return status;
    }

    public void clearCache() {
        this.cachedStatus = null;
        this.cacheTime = 0L;
    }

    /** 当前 Cookie 状态（脱敏，不含明文，实时探活） */
    public CookieStatus getStatus() {
        String cookie = apiClient.getCookie();
        if (cookie == null || cookie.isEmpty()) {
            CookieStatus s = new CookieStatus();
            s.setConfigured(false);
            s.setValid(false);
            s.setMessage("未配置 Cookie（只能下载 480P 以下画质）");
            return s;
        }
        BiliApiClient.LoginStatus login = apiClient.checkLogin();
        return buildStatus(true, login);
    }

    private CookieStatus buildStatus(boolean configured, BiliApiClient.LoginStatus login) {
        CookieStatus s = new CookieStatus();
        s.setConfigured(configured);
        File f = new File(cookieFilePath);
        long updateTime = f.exists() ? f.lastModified() : 0L;
        String type = "manual";
        if (f.exists()) {
            try {
                JsonNode root = mapper.readTree(f);
                if (root.has("updatedAt")) {
                    updateTime = root.path("updatedAt").asLong(updateTime);
                }
                if (root.has("loginType")) {
                    type = root.path("loginType").asText("manual");
                }
            } catch (Exception ignored) {
            }
        }
        s.setUpdatedAt(updateTime);
        s.setLoginType(type);
        if (login == null) {
            s.setValid(false);
            s.setMessage("无法连接 B 站进行验证，请稍后重试");
            return s;
        }
        s.setValid(login.isLogin);
        s.setUsername(login.uname);
        s.setVip(login.vipStatus == 1);
        s.setMessage(login.isLogin
                ? (login.vipStatus == 1 ? "有效（大会员账号 " + login.uname + "）" : "有效（已登录 " + login.uname + "）")
                : "Cookie 已失效，请重新登录 B 站后更新");
        return s;
    }
}
