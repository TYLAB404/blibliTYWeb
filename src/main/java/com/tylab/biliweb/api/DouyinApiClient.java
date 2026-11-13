package com.tylab.biliweb.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tylab.biliweb.model.DouyinInfo;
import org.apache.http.Header;
import org.apache.http.HttpHeaders;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.client.LaxRedirectStrategy;
import org.apache.http.message.BasicHeader;
import org.apache.http.util.EntityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.PreDestroy;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 抖音 (Douyin) / TikTok 客户端：
 * 负责提取链接、解析作品信息（无水印视频、原声音频、图集高清列表）与流式代理下载。
 */
@Component
public class DouyinApiClient {

    private static final Logger log = LoggerFactory.getLogger(DouyinApiClient.class);

    private static final String PC_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";
    private static final String MOBILE_UA = "Mozilla/5.0 (iPhone; CPU iPhone OS 16_6 like Mac OS X) AppleWebKit/605.1.15 "
            + "(KHTML, like Gecko) Version/16.6 Mobile/15E148 Safari/604.1";

    private static final Pattern URL_PATTERN = Pattern.compile("https?://[^\\s，,]+", Pattern.CASE_INSENSITIVE);
    private static final Pattern DOUYIN_HOST_PATTERN = Pattern.compile("(?i)(?:v\\.douyin\\.com|douyin\\.com|iesdouyin\\.com|tiktok\\.com|vt\\.tiktok\\.com)");
    private static final Pattern AWEME_ID_PATTERN = Pattern.compile("/(?:video|note)/(\\d+)");
    private static final Pattern DIGITS_PATTERN = Pattern.compile("\\b(\\d{15,22})\\b");

    private final ObjectMapper mapper = new ObjectMapper();
    private final CloseableHttpClient httpClient;
    private final CloseableHttpClient noRedirectHttpClient;

    @Value("${app.douyin-api-url:}")
    private String customApiUrl;

    /** 缓存的访客凭证 ttwid */
    private volatile String cachedTtwid = "";
    private volatile long ttwidExpireTime = 0;

    public DouyinApiClient() {
        RequestConfig config = RequestConfig.custom()
                .setConnectTimeout(10_000)
                .setSocketTimeout(12_000)
                .setConnectionRequestTimeout(10_000)
                .build();

        org.apache.http.impl.conn.PoolingHttpClientConnectionManager cm =
                new org.apache.http.impl.conn.PoolingHttpClientConnectionManager();
        cm.setMaxTotal(50);
        cm.setDefaultMaxPerRoute(20);

        this.httpClient = HttpClients.custom()
                .setConnectionManager(cm)
                .setDefaultRequestConfig(config)
                .setDefaultHeaders(Collections.singletonList(new BasicHeader(HttpHeaders.USER_AGENT, PC_UA)))
                .setRedirectStrategy(new LaxRedirectStrategy())
                .build();

        // 专门用于手动追踪重定向（不自动跳转）的客户端
        RequestConfig noRedirectConfig = RequestConfig.custom()
                .setConnectTimeout(8_000)
                .setSocketTimeout(8_000)
                .setRedirectsEnabled(false)
                .build();

        this.noRedirectHttpClient = HttpClients.custom()
                .setDefaultRequestConfig(noRedirectConfig)
                .build();
    }

    @PreDestroy
    public void close() throws IOException {
        httpClient.close();
        noRedirectHttpClient.close();
    }

    /**
     * 判断文本中是否包含抖音或 TikTok 链接
     */
    public boolean isDouyinLink(String input) {
        if (input == null || input.trim().isEmpty()) {
            return false;
        }
        return DOUYIN_HOST_PATTERN.matcher(input).find();
    }

    /**
     * 解析抖音/TikTok 内容（支持视频、图集）
     */
    public DouyinInfo parse(String input) {
        if (input == null || input.trim().isEmpty()) {
            throw new IllegalArgumentException("请输入抖音或 TikTok 分享链接");
        }

        String rawUrl = extractUrl(input);
        if (rawUrl == null) {
            throw new IllegalArgumentException("未在输入内容中找到有效的网络链接");
        }

        log.info("开始解析抖音内容，原始链接: {}", rawUrl);

        // 1. 跟随重定向，解析真实目标 URL 与 aweme_id
        String resolvedUrl = followRedirects(rawUrl);
        String awemeId = extractAwemeId(resolvedUrl);
        if (awemeId == null) {
            awemeId = extractAwemeId(rawUrl);
        }

        log.info("重定向解析结果: 最终URL = {}, aweme_id = {}", resolvedUrl, awemeId);

        // 2. 多重引擎回退解析
        DouyinInfo info = null;

        // 引擎 A: 如果配置了自定义解析中继地址，优先使用
        if (customApiUrl != null && !customApiUrl.trim().isEmpty()) {
            try {
                info = parseWithCustomApi(customApiUrl.trim(), rawUrl);
                if (info != null) return info;
            } catch (Exception e) {
                log.warn("自定义解析接口调用失败: {}", e.getMessage());
            }
        }

        // 引擎 B: 官方接口直连解析（携带 ttwid 凭证与模拟请求）
        if (awemeId != null) {
            try {
                info = parseFromOfficialApi(awemeId, resolvedUrl);
                if (info != null && (info.getVideoUrl() != null || !info.getImages().isEmpty())) {
                    log.info("官方端点解析成功: {}", info.getTitle());
                    return info;
                }
            } catch (Exception e) {
                log.warn("官方直接解析失败，尝试备用公共引擎: {}", e.getMessage());
            }
        }

        // 引擎 C: 高可用公共免密引擎 Fallback
        try {
            info = parseFromPublicFallback(rawUrl, awemeId);
            if (info != null) {
                log.info("公共备用解析引擎成功: {}", info.getTitle());
                return info;
            }
        } catch (Exception e) {
            log.warn("公共备用解析引擎也失败: {}", e.getMessage());
        }

        if (info == null) {
            throw new RuntimeException("解析抖音内容失败：未能获取到媒体资源，可能是链接已失效、内容设置为私密或触发了平台风控。");
        }

        return info;
    }

    /**
     * 官方接口解析
     */
    private DouyinInfo parseFromOfficialApi(String awemeId, String referer) throws IOException {
        String ttwid = getOrFetchTtwid();
        String apiUrl = "https://www.iesdouyin.com/aweme/v1/web/aweme/detail/?aweme_id=" + awemeId
                + "&device_platform=webapp&aid=6383&channel=channel_pc_web&pc_client_type=1&version_code=190500&version_name=19.5.0&cookie_enabled=true";

        HttpGet request = new HttpGet(apiUrl);
        request.setHeader(HttpHeaders.USER_AGENT, PC_UA);
        request.setHeader(HttpHeaders.REFERER, referer != null ? referer : "https://www.douyin.com/");
        if (ttwid != null && !ttwid.isEmpty()) {
            request.setHeader("Cookie", "ttwid=" + ttwid + ";");
        }

        try (CloseableHttpResponse resp = httpClient.execute(request)) {
            int status = resp.getStatusLine().getStatusCode();
            if (status == 200 && resp.getEntity() != null) {
                String body = EntityUtils.toString(resp.getEntity(), StandardCharsets.UTF_8);
                JsonNode root = mapper.readTree(body);
                JsonNode detail = root.path("aweme_detail");
                if (!detail.isMissingNode() && !detail.isNull() && detail.has("desc")) {
                    return buildFromAwemeDetail(detail, awemeId);
                }
            }
        }
        return null;
    }

    /**
     * 从官方 aweme_detail 结构构造 DouyinInfo
     */
    private DouyinInfo buildFromAwemeDetail(JsonNode detail, String awemeId) {
        DouyinInfo info = new DouyinInfo();
        info.setAwemeId(awemeId != null ? awemeId : detail.path("aweme_id").asText(""));
        info.setTitle(detail.path("desc").asText("").trim());
        info.setCreateTime(detail.path("create_time").asLong(System.currentTimeMillis() / 1000));

        // 作者信息
        JsonNode author = detail.path("author");
        info.setAuthorName(author.path("nickname").asText("抖音用户"));
        JsonNode avatarUrls = author.path("avatar_thumb").path("url_list");
        if (avatarUrls.isArray() && avatarUrls.size() > 0) {
            info.setAuthorAvatar(avatarUrls.get(0).asText(""));
        }

        // 判断是否为图集
        JsonNode images = detail.path("images");
        if (images.isArray() && images.size() > 0) {
            info.setType("image");
            List<String> imgList = new ArrayList<>();
            for (JsonNode imgNode : images) {
                JsonNode uList = imgNode.path("url_list");
                if (uList.isArray() && uList.size() > 0) {
                    imgList.add(uList.get(0).asText(""));
                }
            }
            info.setImages(imgList);
            if (!imgList.isEmpty()) {
                info.setCover(imgList.get(0));
            }
        } else {
            // 普通视频
            info.setType("video");
            JsonNode video = detail.path("video");
            info.setVideoWidth(video.path("width").asInt(1080));
            info.setVideoHeight(video.path("height").asInt(1920));
            long dur = video.path("duration").asLong(0);
            info.setDuration(dur > 1000 ? dur / 1000 : dur);

            // 封面
            JsonNode coverList = video.path("cover").path("url_list");
            if (coverList.isArray() && coverList.size() > 0) {
                info.setCover(coverList.get(0).asText(""));
            }

            // 无水印视频地址
            JsonNode playAddr = video.path("play_addr").path("url_list");
            if (playAddr.isArray() && playAddr.size() > 0) {
                String rawPlayUrl = playAddr.get(0).asText("");
                // 将 playwm 替换为 play 去除水印
                String noWatermarkUrl = rawPlayUrl.replace("playwm", "play");
                info.setVideoUrl(noWatermarkUrl);
            }
        }

        // 音频信息
        JsonNode music = detail.path("music");
        if (!music.isMissingNode() && !music.isNull()) {
            info.setMusicTitle(music.path("title").asText("原声音频"));
            JsonNode musicUrls = music.path("play_url").path("url_list");
            if (musicUrls.isArray() && musicUrls.size() > 0) {
                info.setMusicUrl(musicUrls.get(0).asText(""));
            }
        }

        return info;
    }

    /**
     * 高可用公共中继解析备选方案（多源冗余兜底）
     */
    private DouyinInfo parseFromPublicFallback(String targetUrl, String awemeId) {
        String encodedUrl;
        try {
            encodedUrl = URLEncoder.encode(targetUrl, StandardCharsets.UTF_8.name());
        } catch (Exception e) {
            encodedUrl = targetUrl;
        }

        // 尝试备用公共解析接口
        String[] fallbackApis = new String[] {
                "https://api.pearktrue.cn/api/video/douyin.php?url=" + encodedUrl,
                "https://api.oioweb.cn/api/common/video?url=" + encodedUrl,
                "https://api.tikwm.com/api/?url=" + encodedUrl
        };

        for (String api : fallbackApis) {
            try {
                HttpGet req = new HttpGet(api);
                req.setHeader(HttpHeaders.USER_AGENT, PC_UA);
                try (CloseableHttpResponse resp = httpClient.execute(req)) {
                    if (resp.getStatusLine().getStatusCode() == 200 && resp.getEntity() != null) {
                        String body = EntityUtils.toString(resp.getEntity(), StandardCharsets.UTF_8);
                        JsonNode root = mapper.readTree(body);

                        // 格式 1: pearktrue / 标准格式 (code: 200, data: { title, url, cover, music })
                        if (root.path("code").asInt(-1) == 200 && root.has("data")) {
                            JsonNode data = root.path("data");
                            DouyinInfo item = new DouyinInfo();
                            item.setAwemeId(awemeId != null ? awemeId : "douyin");
                            item.setTitle(data.path("title").asText("抖音短视频").trim());
                            item.setCover(data.path("cover").asText(""));
                            item.setAuthorName(data.path("author").asText("抖音作者"));
                            item.setAuthorAvatar(data.path("avatar").asText(""));

                            // 检查图集
                            JsonNode images = data.path("images");
                            if (images.isArray() && images.size() > 0) {
                                item.setType("image");
                                List<String> imgList = new ArrayList<>();
                                for (JsonNode img : images) {
                                    imgList.add(img.asText(""));
                                }
                                item.setImages(imgList);
                                if (!imgList.isEmpty()) item.setCover(imgList.get(0));
                            } else {
                                item.setType("video");
                                String vUrl = data.path("url").asText("");
                                if (vUrl.isEmpty()) {
                                    vUrl = data.path("video").asText("");
                                }
                                item.setVideoUrl(vUrl);
                            }

                            item.setMusicUrl(data.path("music").asText(""));
                            item.setMusicTitle("原声音频");
                            return item;
                        }

                        // 格式 2: tikwm.com (code: 0, data: { play, wmplay, title, cover, music, images })
                        if (root.path("code").asInt(-1) == 0 && root.has("data")) {
                            JsonNode data = root.path("data");
                            DouyinInfo item = new DouyinInfo();
                            item.setAwemeId(data.path("id").asText(awemeId != null ? awemeId : "douyin"));
                            item.setTitle(data.path("title").asText("抖音视频").trim());
                            item.setCover(data.path("cover").asText(""));
                            item.setAuthorName(data.path("author").path("nickname").asText("抖音用户"));
                            item.setAuthorAvatar(data.path("author").path("avatar").asText(""));

                            JsonNode images = data.path("images");
                            if (images.isArray() && images.size() > 0) {
                                item.setType("image");
                                List<String> imgList = new ArrayList<>();
                                for (JsonNode img : images) {
                                    imgList.add(img.asText(""));
                                }
                                item.setImages(imgList);
                            } else {
                                item.setType("video");
                                String playUrl = data.path("play").asText("");
                                if (playUrl.startsWith("/")) {
                                    playUrl = "https://www.tikwm.com" + playUrl;
                                }
                                item.setVideoUrl(playUrl);
                            }

                            String musicUrl = data.path("music").asText("");
                            if (musicUrl.startsWith("/")) {
                                musicUrl = "https://www.tikwm.com" + musicUrl;
                            }
                            item.setMusicUrl(musicUrl);
                            item.setMusicTitle(data.path("music_info").path("title").asText("背景音乐"));
                            return item;
                        }
                    }
                }
            } catch (Exception e) {
                // 忽略单个 fallback 错误，继续下一个
                log.debug("Fallback api {} failed: {}", api, e.getMessage());
            }
        }
        return null;
    }

    /**
     * 自定义中继接口调用（支持标准 REST JSON 请求）
     */
    private DouyinInfo parseWithCustomApi(String apiUrl, String shareUrl) throws IOException {
        HttpPost post = new HttpPost(apiUrl);
        post.setHeader(HttpHeaders.CONTENT_TYPE, "application/json");
        post.setEntity(new StringEntity("{\"url\":\"" + shareUrl + "\"}", StandardCharsets.UTF_8));
        try (CloseableHttpResponse resp = httpClient.execute(post)) {
            if (resp.getStatusLine().getStatusCode() == 200 && resp.getEntity() != null) {
                String body = EntityUtils.toString(resp.getEntity(), StandardCharsets.UTF_8);
                JsonNode root = mapper.readTree(body);
                if (root.path("code").asInt(-1) == 200 && root.has("data")) {
                    JsonNode data = root.path("data");
                    DouyinInfo info = new DouyinInfo();
                    info.setTitle(data.path("title").asText(""));
                    info.setCover(data.path("cover").asText(""));
                    info.setVideoUrl(data.path("url").asText(""));
                    info.setMusicUrl(data.path("music").asText(""));
                    return info;
                }
            }
        }
        return null;
    }

    /**
     * 自动向字节跳动注册或刷新获取 ttwid 设备凭证
     */
    private synchronized String getOrFetchTtwid() {
        long now = System.currentTimeMillis();
        if (cachedTtwid != null && !cachedTtwid.isEmpty() && now < ttwidExpireTime) {
            return cachedTtwid;
        }

        try {
            HttpPost post = new HttpPost("https://ttwid.bytedance.com/ttwid/union/register/");
            post.setHeader(HttpHeaders.CONTENT_TYPE, "application/json");
            post.setHeader(HttpHeaders.USER_AGENT, PC_UA);

            String payload = "{\"region\":\"cn\",\"aid\":1768,\"needFid\":\"false\",\"service\":\"www.ixigua.com\","
                    + "\"migrate_info\":{\"ticket\":\"\",\"source\":\"node\"},\"cbUrlProtocol\":\"https\",\"union\":\"true\"}";
            post.setEntity(new StringEntity(payload, StandardCharsets.UTF_8));

            try (CloseableHttpResponse resp = httpClient.execute(post)) {
                Header[] setCookies = resp.getHeaders("Set-Cookie");
                if (setCookies != null) {
                    for (Header h : setCookies) {
                        String val = h.getValue();
                        Matcher m = Pattern.compile("ttwid=([^;]+)").matcher(val);
                        if (m.find()) {
                            cachedTtwid = m.group(1);
                            ttwidExpireTime = now + (24 * 3600 * 1000); // 缓存 24 小时
                            log.info("成功获取并缓存抖音 ttwid: {}", cachedTtwid.substring(0, Math.min(20, cachedTtwid.length())) + "...");
                            return cachedTtwid;
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("获取 ttwid 失败: {}", e.getMessage());
        }
        return cachedTtwid;
    }

    /**
     * 打开用于媒体代理下载的流响应
     */
    public CloseableHttpResponse openStream(String mediaUrl, String range) throws IOException {
        HttpGet request = new HttpGet(mediaUrl);
        if (range != null && !range.isEmpty()) {
            request.setHeader(HttpHeaders.RANGE, range);
        }
        request.setHeader(HttpHeaders.USER_AGENT, MOBILE_UA);
        request.setHeader(HttpHeaders.REFERER, "https://www.douyin.com/");
        return httpClient.execute(request);
    }

    /**
     * 从字符串中提取 HTTP/HTTPS URL
     */
    public String extractUrl(String text) {
        if (text == null) return null;
        Matcher m = URL_PATTERN.matcher(text.trim());
        if (m.find()) {
            String url = m.group();
            // 清理末尾多余标点
            url = url.replaceAll("[)\\]>\"'，,。；;\\s]+$", "");
            return url;
        }
        return null;
    }

    /**
     * 连续跟随 HTTP 301/302 重定向，获取最终落地 URL
     */
    public String followRedirects(String url) {
        String current = url;
        for (int i = 0; i < 6; i++) {
            HttpGet request = new HttpGet(current);
            request.setHeader(HttpHeaders.USER_AGENT, MOBILE_UA);
            try (CloseableHttpResponse resp = noRedirectHttpClient.execute(request)) {
                int code = resp.getStatusLine().getStatusCode();
                if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                    Header loc = resp.getFirstHeader("Location");
                    if (loc != null && loc.getValue() != null && !loc.getValue().isEmpty()) {
                        String next = loc.getValue().trim();
                        if (!next.startsWith("http")) {
                            next = URI.create(current).resolve(next).toString();
                        }
                        current = next;
                        continue;
                    }
                }
                break;
            } catch (Exception e) {
                log.warn("跟随重定向异常 ({}): {}", current, e.getMessage());
                break;
            }
        }
        return current;
    }

    /**
     * 从 URL 中提取 aweme_id（纯数字 ID）
     */
    public String extractAwemeId(String url) {
        if (url == null) return null;
        Matcher m = AWEME_ID_PATTERN.matcher(url);
        if (m.find()) {
            return m.group(1);
        }
        Matcher dm = DIGITS_PATTERN.matcher(url);
        if (dm.find()) {
            return dm.group(1);
        }
        return null;
    }
}
