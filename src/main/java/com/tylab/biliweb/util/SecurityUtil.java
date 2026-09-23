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

    private static final String AES_ALGO = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_LENGTH = 128;

    /**
     * 使用 AES-256-GCM 认证加密 Cookie 等私密文本
     *
     * @param plaintext 明文
     * @param secretSeed 密钥种子（如管理员口令哈希）
     * @return Base64 编码的密文（包含 IV 与 Tag）
     */
    public static String encryptAesGcm(String plaintext, String secretSeed) {
        if (plaintext == null || plaintext.isEmpty()) return "";
        try {
            byte[] keyBytes = deriveKey(secretSeed);
            javax.crypto.spec.SecretKeySpec keySpec = new javax.crypto.spec.SecretKeySpec(keyBytes, "AES");
            byte[] iv = new byte[GCM_IV_LENGTH];
            new java.security.SecureRandom().nextBytes(iv);
            javax.crypto.spec.GCMParameterSpec spec = new javax.crypto.spec.GCMParameterSpec(GCM_TAG_LENGTH, iv);

            javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance(AES_ALGO);
            cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, keySpec, spec);
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocate(iv.length + ciphertext.length);
            buffer.put(iv);
            buffer.put(ciphertext);
            return java.util.Base64.getEncoder().encodeToString(buffer.array());
        } catch (Exception e) {
            throw new RuntimeException("AES-GCM 加密失败: " + e.getMessage(), e);
        }
    }

    /**
     * 使用 AES-256-GCM 解密密文
     *
     * @param ciphertextBase64 Base64 编码密文
     * @param secretSeed 密钥种子
     * @return 解密后的明文
     */
    public static String decryptAesGcm(String ciphertextBase64, String secretSeed) {
        if (ciphertextBase64 == null || ciphertextBase64.isEmpty()) return "";
        try {
            byte[] keyBytes = deriveKey(secretSeed);
            javax.crypto.spec.SecretKeySpec keySpec = new javax.crypto.spec.SecretKeySpec(keyBytes, "AES");
            byte[] raw = java.util.Base64.getDecoder().decode(ciphertextBase64);
            if (raw.length <= GCM_IV_LENGTH) {
                throw new IllegalArgumentException("密文长度非法");
            }
            java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(raw);
            byte[] iv = new byte[GCM_IV_LENGTH];
            buffer.get(iv);
            byte[] ciphertext = new byte[buffer.remaining()];
            buffer.get(ciphertext);

            javax.crypto.spec.GCMParameterSpec spec = new javax.crypto.spec.GCMParameterSpec(GCM_TAG_LENGTH, iv);
            javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance(AES_ALGO);
            cipher.init(javax.crypto.Cipher.DECRYPT_MODE, keySpec, spec);
            byte[] decrypted = cipher.doFinal(ciphertext);
            return new String(decrypted, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("AES-GCM 解密失败: " + e.getMessage(), e);
        }
    }

    private static byte[] deriveKey(String seed) throws NoSuchAlgorithmException {
        String base = (seed == null || seed.trim().isEmpty()) ? "biliweb_default_device_salt_2026" : seed.trim();
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        return md.digest(("bili_cookie_key_seed:" + base).getBytes(StandardCharsets.UTF_8));
    }
}
