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

import javax.annotation.PostConstruct;
import java.io.File;
import java.io.IOException;

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

    /** 保存 Cookie：先校验有效性，通过后以 AES-256-GCM 加密写入文件并立即生效 */
    public CookieStatus saveCookie(String cookie) throws IOException {
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
        // 加密写入文件（绝证明文落地）
        saveEncryptedToFile(trimmed);
        // 立即生效
        apiClient.setCookie(trimmed);
        log.info("Cookie 已加密持久化并生效（账号: {}）", login.uname);
        return buildStatus(true, login);
    }

    /** 将 Cookie 以 AES-256-GCM 密文写入磁盘 */
    private void saveEncryptedToFile(String plainCookie) throws IOException {
        File f = new File(cookieFilePath);
        File parent = f.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        String encrypted = com.tylab.biliweb.util.SecurityUtil.encryptAesGcm(plainCookie, adminToken);
        ObjectNode node = mapper.createObjectNode();
        node.put("encrypted", encrypted);
        node.put("updatedAt", System.currentTimeMillis());
        mapper.writeValue(f, node);
    }

    /** 当前 Cookie 状态（脱敏，不含明文） */
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
        s.setUpdatedAt(f.exists() ? f.lastModified() : 0L);
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
