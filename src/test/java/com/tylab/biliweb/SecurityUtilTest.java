package com.tylab.biliweb;

import com.tylab.biliweb.util.SecurityUtil;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class SecurityUtilTest {

    @Test
    public void testHashedPassword() {
        String tokenConfig = "sha256:biliweb_salt:89654fb6011cb34a00172019bcbf97f0b2188ab53aa845d90d6ab773f3daa9f2";
        // 正确密码
        assertTrue(SecurityUtil.verifyToken("12138", tokenConfig));
        // 错误密码
        assertFalse(SecurityUtil.verifyToken("123456", tokenConfig));
        assertFalse(SecurityUtil.verifyToken("", tokenConfig));
        assertFalse(SecurityUtil.verifyToken(null, tokenConfig));
    }

    @Test
    public void testAesGcmEncryptDecrypt() {
        String seed = "sha256:biliweb_salt:89654fb6011cb34a00172019bcbf97f0b2188ab53aa845d90d6ab773f3daa9f2";
        String sampleCookie = "SESSDATA=fake_sessdata_123456; bili_jct=fake_jct_abcdef; DedeUserID=12345;";

        // 加密
        String encrypted = SecurityUtil.encryptAesGcm(sampleCookie, seed);
        assertNotNull(encrypted);
        assertFalse(encrypted.isEmpty());
        assertFalse(encrypted.contains("SESSDATA")); // 绝无明文

        // 解密
        String decrypted = SecurityUtil.decryptAesGcm(encrypted, seed);
        assertEquals(sampleCookie, decrypted);

        // 错误密钥解密失败
        assertThrows(RuntimeException.class, () -> {
            SecurityUtil.decryptAesGcm(encrypted, "wrong_seed");
        });
    }
}
