package com.tylab.biliweb.controller;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 根路径与前端首页控制器：
 * 显式接管 "/" 与 "/index.html"，直接流式输出静态 HTML，
 * 彻底规避 Spring Boot 内部 ViewResolver / WelcomePageHandlerMapping 的模式匹配与类加载异常。
 */
@Controller
public class ViewController {

    @GetMapping(value = {"/", "/index.html"}, produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<Resource> index() {
        Resource resource = new ClassPathResource("static/index.html");
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_HTML)
                .body(resource);
    }
}
