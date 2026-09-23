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
                String cookie = root.path("cookie").asText("");
                if (!cookie.isEmpty()) {
                    apiClient.setCookie(cookie);
                    log.info("已从 {} 加载 Cookie 配置", cookieFilePath);
                }
            }
        } catch (IOException e) {
            log.warn("读取 Cookie 配置文件失败: {}", e.getMessage());
        }
    }

    /** 校验管理口令（支持密文哈希比对） */
    public boolean verifyToken(String token) {
        return !adminToken.isEmpty() && com.tylab.biliweb.util.SecurityUtil.verifyToken(token, adminToken);
    }

    public boolean isTokenConfigured() {
        return !adminToken.isEmpty();
    }

    /** 保存 Cookie：先校验有效性，通过后写入文件并立即生效 */
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
        // 写入文件
        File f = new File(cookieFilePath);
        File parent = f.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        ObjectNode node = mapper.createObjectNode();
        node.put("cookie", trimmed);
        node.put("updatedAt", System.currentTimeMillis());
        mapper.writeValue(f, node);
        // 立即生效
        apiClient.setCookie(trimmed);
        log.info("Cookie 已更新并生效（账号: {}）", login.uname);
        return buildStatus(true, login);
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
