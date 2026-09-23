package com.tylab.biliweb.controller;

import com.tylab.biliweb.api.BiliApiClient;
import com.tylab.biliweb.model.FormatOption;
import com.tylab.biliweb.model.VideoInfo;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 解析：链接 → 视频信息 → 画质列表 */
@RestController
@RequestMapping("/api")
public class ParseController {

    private final BiliApiClient apiClient;

    public ParseController(BiliApiClient apiClient) {
        this.apiClient = apiClient;
    }

    /** 解析 B 站链接/文本，返回视频或番剧信息与分P 列表 */
    @PostMapping("/parse")
    public VideoInfo parse(@RequestBody Map<String, String> body) {
        String input = body.get("url");
        if (input == null || input.trim().isEmpty()) {
            throw new IllegalArgumentException("请输入视频或番剧链接");
        }
        BiliApiClient.LinkResult link = apiClient.resolveLink(input);
        if (link == null) {
            throw new IllegalArgumentException("无法识别链接中的视频或番剧编号（支持 BV/av 号、番剧 ss/ep 号、完整链接、Markdown 与 b23.tv 短链）");
        }
        if (link.seasonId != null) {
            return apiClient.getVideoInfoBySeasonId(link.seasonId);
        }
        if (link.epId != null) {
            return apiClient.getVideoInfoByEpId(link.epId);
        }
        if (link.aid != null) {
            return apiClient.getVideoInfoByAid(link.aid);
        }
        return apiClient.getVideoInfo(link.bvid);
    }

    /** 获取某分P 的可用画质列表 */
    @GetMapping("/formats")
    public List<FormatOption> formats(@RequestParam String bvid, @RequestParam long cid) {
        return apiClient.getFormats(bvid, cid);
    }
}
