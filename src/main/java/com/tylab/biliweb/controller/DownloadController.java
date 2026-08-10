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
import java.util.List;
import java.util.Map;

/** 下载任务：创建、查询、取消、取文件 */
@RestController
@RequestMapping("/api")
public class DownloadController {

    private final DownloadService downloadService;

    public DownloadController(DownloadService downloadService) {
        this.downloadService = downloadService;
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
        if (bvid == null || cid == null || quality == null) {
            throw new IllegalArgumentException("参数不完整: bvid/cid/quality 必填");
        }
        if (videoTitle == null) videoTitle = bvid;
        if (pageTitle == null || pageTitle.isEmpty()) pageTitle = videoTitle;
        if (pagePart == null || pagePart.isEmpty()) pagePart = "P1";
        return downloadService.createTask(bvid, cid.longValue(), quality.intValue(),
                videoTitle, pageTitle, pagePart, audioOnly);
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

    /** 下载已完成的任务文件 */
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
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(new FileSystemResource(f));
    }
}
