package com.tylab.biliweb.controller;

import com.tylab.biliweb.api.DouyinApiClient;
import org.apache.http.Header;
import org.apache.http.HttpHeaders;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 抖音下载与媒体流代理：
 * 提供防盗链视频/音频代理下载、图集一键打包 ZIP 下载。
 */
@RestController
@RequestMapping("/api/douyin")
public class DouyinController {

    private static final Logger log = LoggerFactory.getLogger(DouyinController.class);
    private final DouyinApiClient douyinApiClient;

    public DouyinController(DouyinApiClient douyinApiClient) {
        this.douyinApiClient = douyinApiClient;
    }

    /**
     * 抖音视频/音频/图片防盗链流式代理下载：
     * 彻底解决直接在浏览器点击触发 403 Forbidden 或变成浏览器直接播放的问题。
     */
    @GetMapping("/download")
    public void download(@RequestParam String url,
                         @RequestParam(required = false, defaultValue = "douyin_media") String title,
                         @RequestParam(required = false, defaultValue = "video") String type,
                         HttpServletRequest req,
                         HttpServletResponse resp) {
        if (url == null || url.trim().isEmpty()) {
            resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            return;
        }

        String ext = ".mp4";
        String contentType = "video/mp4";
        if ("audio".equalsIgnoreCase(type)) {
            ext = ".mp3";
            contentType = "audio/mpeg";
        } else if ("image".equalsIgnoreCase(type)) {
            ext = ".jpg";
            contentType = "image/jpeg";
        }

        // 清理文件名非法字符
        String safeName = title.replaceAll("[\\\\/:*?\"<>|\\r\\n]+", "_").trim();
        if (safeName.isEmpty()) safeName = "douyin_media";
        if (!safeName.toLowerCase().endsWith(ext)) {
            safeName += ext;
        }

        String encodedFilename;
        try {
            encodedFilename = URLEncoder.encode(safeName, StandardCharsets.UTF_8.name()).replace("+", "%20");
        } catch (Exception e) {
            encodedFilename = "douyin_media" + ext;
        }

        String range = req.getHeader(HttpHeaders.RANGE);
        try (CloseableHttpResponse upstreamResp = douyinApiClient.openStream(url, range)) {
            int status = upstreamResp.getStatusLine().getStatusCode();
            resp.setStatus(status);

            // 转发 Content-Type 和 Content-Length
            Header cType = upstreamResp.getFirstHeader(HttpHeaders.CONTENT_TYPE);
            resp.setContentType(cType != null ? cType.getValue() : contentType);

            Header cLen = upstreamResp.getFirstHeader(HttpHeaders.CONTENT_LENGTH);
            if (cLen != null) {
                resp.setHeader(HttpHeaders.CONTENT_LENGTH, cLen.getValue());
            }

            Header cRange = upstreamResp.getFirstHeader(HttpHeaders.CONTENT_RANGE);
            if (cRange != null) {
                resp.setHeader(HttpHeaders.CONTENT_RANGE, cRange.getValue());
            }
            resp.setHeader(HttpHeaders.ACCEPT_RANGES, "bytes");

            // 强制触发浏览器保存文件弹窗
            resp.setHeader(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encodedFilename);

            if (upstreamResp.getEntity() != null) {
                try (InputStream in = upstreamResp.getEntity().getContent();
                     OutputStream out = resp.getOutputStream()) {
                    byte[] buffer = new byte[16384];
                    int bytesRead;
                    while ((bytesRead = in.read(buffer)) != -1) {
                        out.write(buffer, 0, bytesRead);
                    }
                    out.flush();
                }
            }
        } catch (Exception e) {
            log.error("代理下载抖音媒体流异常: {}", e.getMessage());
            if (!resp.isCommitted()) {
                resp.setStatus(HttpServletResponse.SC_BAD_GATEWAY);
            }
        }
    }

    /**
     * 图集一键打包 ZIP 实时压缩流下载：
     * 无需落地服务器磁盘，边下边压边推流给前端。
     */
    @PostMapping("/download-zip")
    public void downloadZip(@RequestBody Map<String, Object> body,
                            HttpServletResponse resp) {
        String title = (String) body.get("title");
        @SuppressWarnings("unchecked")
        List<String> images = (List<String>) body.get("images");

        if (images == null || images.isEmpty()) {
            resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            return;
        }

        String safeTitle = (title != null && !title.trim().isEmpty())
                ? title.replaceAll("[\\\\/:*?\"<>|\\r\\n]+", "_").trim()
                : "douyin_images";
        String zipName = safeTitle + "-图集.zip";

        String encodedName;
        try {
            encodedName = URLEncoder.encode(zipName, StandardCharsets.UTF_8.name()).replace("+", "%20");
        } catch (Exception e) {
            encodedName = "douyin_images.zip";
        }

        resp.setContentType("application/zip");
        resp.setHeader(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encodedName);

        try (ZipOutputStream zos = new ZipOutputStream(resp.getOutputStream())) {
            byte[] buffer = new byte[8192];
            for (int i = 0; i < images.size(); i++) {
                String imgUrl = images.get(i);
                if (imgUrl == null || imgUrl.trim().isEmpty()) continue;

                String entryName = String.format("%s_%02d.jpg", safeTitle, i + 1);
                ZipEntry entry = new ZipEntry(entryName);
                zos.putNextEntry(entry);

                try (CloseableHttpResponse imgResp = douyinApiClient.openStream(imgUrl, null)) {
                    if (imgResp.getStatusLine().getStatusCode() == 200 && imgResp.getEntity() != null) {
                        try (InputStream in = imgResp.getEntity().getContent()) {
                            int read;
                            while ((read = in.read(buffer)) != -1) {
                                zos.write(buffer, 0, read);
                            }
                        }
                    }
                } catch (Exception ex) {
                    log.warn("打包图片 {} 失败: {}", imgUrl, ex.getMessage());
                }

                zos.closeEntry();
            }
            zos.finish();
        } catch (Exception e) {
            log.error("生成图集 ZIP 异常: {}", e.getMessage());
        }
    }
}
