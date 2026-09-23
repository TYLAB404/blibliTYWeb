package com.tylab.biliweb.controller;

import com.tylab.biliweb.model.FriendLink;
import com.tylab.biliweb.service.CookieAdminService;
import com.tylab.biliweb.service.FriendLinkService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/** 友情链接接口 */
@RestController
@RequestMapping("/api")
public class FriendLinkController {

    private final FriendLinkService friendLinkService;
    private final CookieAdminService cookieAdminService;

    public FriendLinkController(FriendLinkService friendLinkService, CookieAdminService cookieAdminService) {
        this.friendLinkService = friendLinkService;
        this.cookieAdminService = cookieAdminService;
    }

    /** 获取所有友情链接（公开，无需口令） */
    @GetMapping("/friends")
    public List<FriendLink> list() {
        return friendLinkService.list();
    }

    /** 新增友情链接（需管理口令） */
    @PostMapping("/admin/friends")
    public ResponseEntity<?> add(@RequestHeader(value = "X-Admin-Token", required = false) String token,
                                 @RequestBody Map<String, String> body) {
        if (!authorized(token)) {
            return unauthorized();
        }
        try {
            String name = body != null ? body.get("name") : null;
            String url = body != null ? body.get("url") : null;
            String description = body != null ? body.get("description") : null;
            FriendLink link = friendLinkService.add(name, url, description);
            return ResponseEntity.ok(link);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Collections.singletonMap("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Collections.singletonMap("error", "添加失败: " + e.getMessage()));
        }
    }

    /** 删除友情链接（需管理口令） */
    @DeleteMapping("/admin/friends/{id}")
    public ResponseEntity<?> delete(@RequestHeader(value = "X-Admin-Token", required = false) String token,
                                    @PathVariable("id") String id) {
        if (!authorized(token)) {
            return unauthorized();
        }
        try {
            boolean ok = friendLinkService.delete(id);
            if (!ok) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Collections.singletonMap("error", "友链不存在或已被删除"));
            }
            return ResponseEntity.ok(Collections.singletonMap("success", true));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Collections.singletonMap("error", "删除失败: " + e.getMessage()));
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
