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
}
