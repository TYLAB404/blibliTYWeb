package com.tylab.biliweb.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** 安全工具类：加盐 SHA-256 哈希比对与防时序攻击 */
public class SecurityUtil {

    /**
     * 校验用户输入的明文口令是否匹配（支持加盐哈希 sha256:salt:hash 格式与纯哈希格式，绝证明文泄露）
     *
     * @param inputPassword 用户在页面输入的明文口令
     * @param configuredToken 配置文件中存储的口令（密文哈希或明文）
     * @return 校验是否通过
     */
    public static boolean verifyToken(String inputPassword, String configuredToken) {
        if (configuredToken == null || configuredToken.trim().isEmpty()) {
            return false;
        }
        if (inputPassword == null || inputPassword.isEmpty()) {
            return false;
        }
        String config = configuredToken.trim();
        // 密文哈希：sha256:salt:hash
        if (config.startsWith("sha256:")) {
            String[] parts = config.split(":");
            if (parts.length == 3) {
                String salt = parts[1];
                String expectedHash = parts[2].toLowerCase();
                String actualHash = sha256Hex(salt + ":" + inputPassword);
                return MessageDigest.isEqual(
                        actualHash.getBytes(StandardCharsets.UTF_8),
                        expectedHash.getBytes(StandardCharsets.UTF_8));
            } else if (parts.length == 2) {
                String expectedHash = parts[1].toLowerCase();
                String actualHash = sha256Hex(inputPassword);
                return MessageDigest.isEqual(
                        actualHash.getBytes(StandardCharsets.UTF_8),
                        expectedHash.getBytes(StandardCharsets.UTF_8));
            }
        }
        // 普通明文兼容模式（采用安全常数时间比对，避免时序侧信道攻击）
        return MessageDigest.isEqual(
                inputPassword.getBytes(StandardCharsets.UTF_8),
                config.getBytes(StandardCharsets.UTF_8));
    }

    /** 计算 SHA-256 十六进制摘要 */
    public static String sha256Hex(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 算法不可用", e);
        }
    }
}
