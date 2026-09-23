package com.tylab.biliweb.controller;

import com.tylab.biliweb.model.CookieStatus;
import com.tylab.biliweb.service.CookieAdminService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.Map;

/** Cookie 状态与管理接口 */
@RestController
@RequestMapping("/api")
public class CookieAdminController {

    private final CookieAdminService cookieAdminService;

    public CookieAdminController(CookieAdminService cookieAdminService) {
        this.cookieAdminService = cookieAdminService;
    }

    /** 公开查询当前 Cookie 状态（脱敏，免口令，带60秒内存防抖缓存，供顶部状态栏高频显示） */
    @GetMapping("/cookie/status")
    public CookieStatus publicStatus() {
        return cookieAdminService.getStatusCached();
    }

    /** 校验管理口令（点击【验证口令】按钮后调用） */
    @PostMapping("/admin/cookie/verify-token")
    public ResponseEntity<?> verifyToken(@RequestHeader(value = "X-Admin-Token", required = false) String token) {
        if (!authorized(token)) {
            return unauthorized();
        }
        return ResponseEntity.ok(Collections.singletonMap("valid", true));
    }

    /** 保存/更新 Cookie（校验有效性后生效，需管理口令） */
    @PostMapping("/admin/cookie")
    public ResponseEntity<?> saveCookie(@RequestHeader(value = "X-Admin-Token", required = false) String token,
                                        @RequestBody Map<String, String> body) {
        if (!authorized(token)) return unauthorized();
        try {
            CookieStatus status = cookieAdminService.saveCookie(body.get("cookie"));
            return ResponseEntity.ok(status);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Collections.singletonMap("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Collections.singletonMap("error", "保存失败: " + e.getMessage()));
        }
    }

    /** 管理员查询当前最新 Cookie 状态（实时探活，需管理口令） */
    @GetMapping("/admin/cookie/status")
    public ResponseEntity<?> adminStatus(@RequestHeader(value = "X-Admin-Token", required = false) String token) {
        if (!authorized(token)) return unauthorized();
        return ResponseEntity.ok(cookieAdminService.getStatus());
    }

    /** 管理口令是否已配置（无需口令即可查，用于前端提示） */
    @GetMapping("/admin/cookie/token-configured")
    public Map<String, Object> tokenConfigured() {
        return Collections.singletonMap("configured", cookieAdminService.isTokenConfigured());
    }

    /** 申请登录二维码（含 Base64 图片，需管理口令） */
    @PostMapping("/admin/login/qrcode/generate")
    public ResponseEntity<?> generateQrCode(@RequestHeader(value = "X-Admin-Token", required = false) String token) {
        if (!authorized(token)) return unauthorized();
        try {
            return ResponseEntity.ok(cookieAdminService.generateLoginQrCode());
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Collections.singletonMap("error", "申请二维码失败: " + e.getMessage()));
        }
    }

    /** 轮询登录二维码扫码状态（扫码成功自动加密保存，需管理口令） */
    @GetMapping("/admin/login/qrcode/poll")
    public ResponseEntity<?> pollQrCode(@RequestHeader(value = "X-Admin-Token", required = false) String token,
                                        @RequestParam("qrcodeKey") String qrcodeKey) {
        if (!authorized(token)) return unauthorized();
        try {
            return ResponseEntity.ok(cookieAdminService.pollLoginQrCode(qrcodeKey));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Collections.singletonMap("error", "轮询失败: " + e.getMessage()));
        }
    }

    private boolean authorized(String token) {
        return token != null && cookieAdminService.verifyToken(token);
    }

    private ResponseEntity<Map<String, Object>> unauthorized() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Collections.singletonMap("error", "管理口令错误或未配置"));
    }
}
