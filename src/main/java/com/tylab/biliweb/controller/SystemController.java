package com.tylab.biliweb.controller;

import com.tylab.biliweb.config.AccessAuthInterceptor;
import com.tylab.biliweb.service.DownloadService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.Map;

/** 系统状态与安全认证接口 */
@RestController
@RequestMapping("/api/system")
public class SystemController {

    private final AccessAuthInterceptor accessAuthInterceptor;
    private final DownloadService downloadService;

    public SystemController(AccessAuthInterceptor accessAuthInterceptor, DownloadService downloadService) {
        this.accessAuthInterceptor = accessAuthInterceptor;
        this.downloadService = downloadService;
    }

    /** 访问口令是否已开启 */
    @GetMapping("/access-info")
    public Map<String, Object> accessInfo() {
        return Collections.singletonMap("authRequired", accessAuthInterceptor.isAuthRequired());
    }

    /** 校验访问口令 */
    @PostMapping("/verify-token")
    public Map<String, Object> verifyToken(@RequestBody Map<String, String> body) {
        String token = body != null ? body.get("token") : null;
        boolean valid = accessAuthInterceptor.verify(token);
        return Collections.singletonMap("valid", valid);
    }

    /** 磁盘存储状态 */
    @GetMapping("/storage")
    public Map<String, Object> storage() {
        return downloadService.getStorageInfo();
    }
}
