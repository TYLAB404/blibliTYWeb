package com.tylab.biliweb.controller;

import com.tylab.biliweb.api.BiliApiClient;
import com.tylab.biliweb.api.DouyinApiClient;
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

/** 解析：链接 → 视频/图集信息（支持 B 站与抖音/TikTok 智能识别） */
@RestController
@RequestMapping("/api")
public class ParseController {

    private final BiliApiClient apiClient;
    private final DouyinApiClient douyinApiClient;

    public ParseController(BiliApiClient apiClient, DouyinApiClient douyinApiClient) {
        this.apiClient = apiClient;
        this.douyinApiClient = douyinApiClient;
    }

    /** 智能解析：自动识别 B 站与抖音/TikTok 链接 */
    @PostMapping("/parse")
    public Object parse(@RequestBody Map<String, String> body) {
        String input = body.get("url");
        if (input == null || input.trim().isEmpty()) {
            throw new IllegalArgumentException("请输入视频或分享链接");
        }

        // 1. 判断是否为抖音 / TikTok 链接
        if (douyinApiClient.isDouyinLink(input)) {
            return douyinApiClient.parse(input);
        }

        // 2. 否则按 B 站链接解析
        BiliApiClient.LinkResult link = apiClient.resolveLink(input);
        if (link == null) {
            throw new IllegalArgumentException("无法识别链接内容。支持 B 站视频/番剧（BV/av号、ss/ep号、b23.tv 短链）或 抖音/TikTok 分享链接（含口令文本）");
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

    /** 获取某分P 的可用画质列表（B 站专用） */
    @GetMapping("/formats")
    public List<FormatOption> formats(@RequestParam String bvid, @RequestParam long cid) {
        return apiClient.getFormats(bvid, cid);
    }
}
