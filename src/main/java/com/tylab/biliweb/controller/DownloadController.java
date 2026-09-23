package com.tylab.biliweb.controller;

import com.tylab.biliweb.model.DownloadTask;
import com.tylab.biliweb.model.TaskStatus;
import com.tylab.biliweb.service.DownloadService;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.File;
import java.net.URLEncoder;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.util.EntityUtils;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Map;

/** 下载任务：创建、查询、取消、取文件 */
@RestController
@RequestMapping("/api")
public class DownloadController {

    private final DownloadService downloadService;
    private final com.tylab.biliweb.api.BiliApiClient apiClient;

    public DownloadController(DownloadService downloadService, com.tylab.biliweb.api.BiliApiClient apiClient) {
        this.downloadService = downloadService;
        this.apiClient = apiClient;
    }

    /** 创建下载任务 */
    @PostMapping("/download")
    public DownloadTask download(@RequestBody Map<String, Object> body) {
        String bvid = (String) body.get("bvid");
        Number cid = (Number) body.get("cid");
        Number quality = (Number) body.get("quality");
        String videoTitle = (String) body.get("videoTitle");
        String pageTitle = (String) body.get("pageTitle");
        String pagePart = (String) body.get("pagePart");
        Boolean audioOnly = body.get("audioOnly") == null ? Boolean.FALSE : (Boolean) body.get("audioOnly");
        Boolean autoFallback = body.get("autoFallback") == null ? Boolean.TRUE : (Boolean) body.get("autoFallback");
        if (bvid == null || cid == null || quality == null) {
            throw new IllegalArgumentException("参数不完整: bvid/cid/quality 必填");
        }
        if (videoTitle == null) videoTitle = bvid;
        if (pageTitle == null || pageTitle.isEmpty()) pageTitle = videoTitle;
        if (pagePart == null || pagePart.isEmpty()) pagePart = "P1";
        return downloadService.createTask(bvid, cid.longValue(), quality.intValue(),
                videoTitle, pageTitle, pagePart, audioOnly, autoFallback);
    }

    /** 批量创建下载任务 */
    @PostMapping("/batch-download")
    public List<DownloadTask> batchDownload(@RequestBody Map<String, Object> body) {
        String bvid = (String) body.get("bvid");
        String videoTitle = (String) body.get("videoTitle");
        Number quality = (Number) body.get("quality");
        Boolean audioOnly = body.get("audioOnly") == null ? Boolean.FALSE : (Boolean) body.get("audioOnly");
        Boolean autoFallback = body.get("autoFallback") == null ? Boolean.TRUE : (Boolean) body.get("autoFallback");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> pages = (List<Map<String, Object>>) body.get("pages");
        if (bvid == null || pages == null || pages.isEmpty() || quality == null) {
            throw new IllegalArgumentException("参数不完整: bvid/quality/pages 必填");
        }
        List<DownloadTask> created = new java.util.ArrayList<>();
        for (Map<String, Object> p : pages) {
            Number cid = (Number) p.get("cid");
            if (cid == null) continue;
            String itemBvid = (String) p.get("bvid");
            if (itemBvid == null || itemBvid.isEmpty()) itemBvid = bvid;
            String part = (String) p.get("part");
            Number pageNum = (Number) p.get("page");
            String pagePart = pageNum != null ? "P" + pageNum : "P1";
            String pageTitle = part != null && !part.isEmpty() ? part : pagePart;
            try {
                DownloadTask t = downloadService.createTask(itemBvid, cid.longValue(), quality.intValue(),
                        videoTitle, pageTitle, pagePart, audioOnly, autoFallback);
                created.add(t);
            } catch (Exception e) {
                // 若单个任务添加失败（如已存在），记录日志并继续其它分P
            }
        }
        return created;
    }

    /** 所有任务 */
    @GetMapping("/tasks")
    public List<DownloadTask> tasks() {
        return downloadService.listTasks();
    }

    /** 单个任务 */
    @GetMapping("/task/{id}")
    public DownloadTask task(@PathVariable String id) {
        DownloadTask task = downloadService.getTask(id);
        if (task == null) {
            throw new IllegalArgumentException("任务不存在: " + id);
        }
        return task;
    }

    /** 取消任务 */
    @PostMapping("/task/{id}/cancel")
    public Map<String, Object> cancel(@PathVariable String id) {
        boolean ok = downloadService.cancelTask(id);
        return java.util.Collections.singletonMap("ok", ok);
    }

    /** 删除任务（可选同时删除磁盘文件） */
    @org.springframework.web.bind.annotation.DeleteMapping("/task/{id}")
    public Map<String, Object> deleteTask(@PathVariable String id,
                                          @org.springframework.web.bind.annotation.RequestParam(defaultValue = "false") boolean deleteFile) {
        boolean ok = downloadService.deleteTask(id, deleteFile);
        return java.util.Collections.singletonMap("ok", ok);
    }

    /** 清理已完成/失败/已取消的任务 */
    @PostMapping("/tasks/clear")
    public Map<String, Object> clearTasks(@org.springframework.web.bind.annotation.RequestParam(defaultValue = "false") boolean deleteFiles) {
        int count = downloadService.clearFinishedTasks(deleteFiles);
        return java.util.Collections.singletonMap("clearedCount", count);
    }

    /** 下载已完成的任务文件（支持 Content-Length 与 Accept-Ranges） */
    @GetMapping("/task/{id}/file")
    public ResponseEntity<Resource> file(@PathVariable String id) {
        DownloadTask task = downloadService.getTask(id);
        if (task == null || task.getStatus() != TaskStatus.COMPLETED || task.getOutputFile() == null) {
            return ResponseEntity.notFound().build();
        }
        File f = new File(task.getOutputFile());
        if (!f.exists()) {
            return ResponseEntity.notFound().build();
        }
        String filename;
        try {
            filename = URLEncoder.encode(f.getName(), "UTF-8").replace("+", "%20");
        } catch (java.io.UnsupportedEncodingException e) {
            filename = f.getName();
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + filename)
                .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                .contentLength(f.length())
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(new FileSystemResource(f));
    }

    /** B 站封面图片防盗链代理（支持跨域、带 B 站 Referer，双重保险） */
    @GetMapping("/proxy/image")
    public ResponseEntity<byte[]> proxyImage(@org.springframework.web.bind.annotation.RequestParam String url) {
        if (url == null || url.trim().isEmpty()) {
            return ResponseEntity.badRequest().build();
        }
        try {
            java.net.URI uri = new java.net.URI(url);
            String host = uri.getHost();
            if (host == null || (!host.endsWith(".hdslb.com") && !host.endsWith(".bilibili.com"))) {
                return ResponseEntity.badRequest().build();
            }
            try (CloseableHttpResponse resp = apiClient.openStream(url, null)) {
                int status = resp.getStatusLine().getStatusCode();
                if (status != 200 || resp.getEntity() == null) {
                    return ResponseEntity.status(status).build();
                }
                byte[] bytes = EntityUtils.toByteArray(resp.getEntity());
                String contentType = resp.getEntity().getContentType() != null
                        ? resp.getEntity().getContentType().getValue()
                        : "image/jpeg";
                return ResponseEntity.ok()
                        .header(HttpHeaders.CONTENT_TYPE, contentType)
                        .header(HttpHeaders.CACHE_CONTROL, "public, max-age=86400")
                        .body(bytes);
            }
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).build();
        }
    }
}
