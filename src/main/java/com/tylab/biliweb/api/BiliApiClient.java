package com.tylab.biliweb.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tylab.biliweb.model.FormatOption;
import com.tylab.biliweb.model.StreamInfo;
import com.tylab.biliweb.model.VideoInfo;
import com.tylab.biliweb.model.VideoPage;
import org.apache.http.Header;
import org.apache.http.HttpHeaders;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.client.LaxRedirectStrategy;
import org.apache.http.message.BasicHeader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.PreDestroy;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** B 站 API 客户端：视频信息、播放地址、画质列表 */
@Component
public class BiliApiClient {

    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
    private static final String REFERER = "https://www.bilibili.com";
    /** fnval 组合：4048(8K)|1024(杜比)|512(杜比视界)|256(4K)，仅返回 DASH 流 */
    private static final int FNVAL = 4048 | 1024 | 512 | 256;

    private final ObjectMapper mapper = new ObjectMapper();
    private volatile String cookie;
    private final CloseableHttpClient httpClient;

    public BiliApiClient(@Value("${app.bilibili-cookie:}") String cookie) {
        this.cookie = cookie == null ? "" : cookie.trim();
        RequestConfig config = RequestConfig.custom()
                .setConnectTimeout(10_000)
                .setSocketTimeout(10_000)
                .setConnectionRequestTimeout(10_000)
                .build();
        List<Header> headers = new ArrayList<>();
        headers.add(new BasicHeader(HttpHeaders.USER_AGENT, UA));
        headers.add(new BasicHeader(HttpHeaders.REFERER, REFERER));
        org.apache.http.impl.conn.PoolingHttpClientConnectionManager cm =
                new org.apache.http.impl.conn.PoolingHttpClientConnectionManager();
        cm.setMaxTotal(50);
        cm.setDefaultMaxPerRoute(20);

        this.httpClient = HttpClients.custom()
                .setConnectionManager(cm)
                .setDefaultRequestConfig(config)
                .setDefaultHeaders(headers)
                .setRedirectStrategy(new LaxRedirectStrategy())
                .build();
    }

    @PreDestroy
    public void close() throws IOException {
        httpClient.close();
    }

    /** 运行时更新 Cookie（页面管理入口调用） */
    public synchronized void setCookie(String cookie) {
        this.cookie = cookie == null ? "" : cookie.trim();
    }

    public String getCookie() {
        return cookie;
    }

    /**
     * 检测当前 Cookie 的登录状态（B 站 nav 接口）。
     * 返回 null 表示网络失败；否则返回 {isLogin, uname, vipStatus, vipType}。
     */
    public LoginStatus checkLogin() {
        return checkLoginWith(cookie);
    }

    /** 用指定 Cookie 检测登录状态，不改变当前 Cookie */
    public LoginStatus checkLoginWith(String testCookie) {
        HttpGet request = new HttpGet("https://api.bilibili.com/x/web-interface/nav");
        if (testCookie != null && !testCookie.isEmpty()) {
            request.setHeader("Cookie", testCookie);
        }
        try (CloseableHttpResponse resp = httpClient.execute(request)) {
            if (resp.getStatusLine().getStatusCode() != 200) {
                return null;
            }
            JsonNode root = mapper.readTree(resp.getEntity().getContent());
            if (root.path("code").asInt(-1) != 0) {
                return new LoginStatus(false, "", 0, 0);
            }
            JsonNode data = root.path("data");
            return new LoginStatus(
                    data.path("isLogin").asBoolean(false),
                    data.path("uname").asText(""),
                    data.path("vipStatus").asInt(0),
                    data.path("vipType").asInt(0));
        } catch (IOException e) {
            return null;
        }
    }

    /** 登录状态结果 */
    public static class LoginStatus {
        public final boolean isLogin;
        public final String uname;
        public final int vipStatus; // 0=非会员 1=大会员
        public final int vipType;

        public LoginStatus(boolean isLogin, String uname, int vipStatus, int vipType) {
            this.isLogin = isLogin;
            this.uname = uname;
            this.vipStatus = vipStatus;
            this.vipType = vipType;
        }
    }

    /** 获取视频信息（标题、封面、分P 列表） */
    public VideoInfo getVideoInfo(String bvid) {
        JsonNode data = getJson("https://api.bilibili.com/x/web-interface/view?bvid=" + urlEncode(bvid), true);
        return buildVideoInfo(data, bvid);
    }

    /** 通过 av 号获取视频信息 */
    public VideoInfo getVideoInfoByAid(long aid) {
        JsonNode data = getJson("https://api.bilibili.com/x/web-interface/view?aid=" + aid, true);
        return buildVideoInfo(data, data.path("bvid").asText(""));
    }

    private VideoInfo buildVideoInfo(JsonNode data, String bvid) {
        VideoInfo info = new VideoInfo();
        info.setBvid(bvid.isEmpty() ? data.path("bvid").asText("") : bvid);
        info.setAid(data.path("aid").asLong());
        info.setTitle(data.path("title").asText());
        info.setCover(data.path("pic").asText(""));
        List<VideoPage> pages = new ArrayList<>();
        for (JsonNode p : data.path("pages")) {
            VideoPage page = new VideoPage();
            page.setCid(p.path("cid").asLong());
            page.setPage(p.path("page").asInt());
            page.setPart(p.path("part").asText());
            page.setDuration(p.path("duration").asLong());
            page.setBvid(info.getBvid());
            pages.add(page);
        }
        info.setPages(pages);
        return info;
    }

    /** 通过 season_id 获取番剧/影视信息 */
    public VideoInfo getVideoInfoBySeasonId(long seasonId) {
        String url = "https://api.bilibili.com/pgc/view/web/season?season_id=" + seasonId;
        JsonNode result = getJson(url, true);
        return buildBangumiInfo(result, null);
    }

    /** 通过 ep_id 获取番剧/影视信息，并记录默认选中的分集 */
    public VideoInfo getVideoInfoByEpId(long epId) {
        String url = "https://api.bilibili.com/pgc/view/web/season?ep_id=" + epId;
        JsonNode result = getJson(url, true);
        return buildBangumiInfo(result, epId);
    }

    private VideoInfo buildBangumiInfo(JsonNode result, Long targetEpId) {
        if (result == null || result.isMissingNode() || result.isNull()) {
            throw new BiliApiException("获取番剧信息失败，未找到该番剧数据");
        }
        VideoInfo info = new VideoInfo();
        info.setTitle(result.path("title").asText(""));
        info.setCover(result.path("cover").asText(""));

        List<VideoPage> pages = new ArrayList<>();
        JsonNode episodes = result.path("episodes");
        if ((!episodes.isArray() || episodes.isEmpty()) && result.has("section")) {
            for (JsonNode sec : result.path("section")) {
                JsonNode secEps = sec.path("episodes");
                if (secEps.isArray() && secEps.size() > 0) {
                    episodes = secEps;
                    break;
                }
            }
        }

        int targetIdx = 0;
        int idx = 0;
        String firstBvid = "";
        long firstAid = 0L;

        for (JsonNode ep : episodes) {
            VideoPage page = new VideoPage();
            page.setCid(ep.path("cid").asLong());
            page.setPage(idx + 1);
            String epBvid = ep.path("bvid").asText("");
            long epAid = ep.path("aid").asLong();
            page.setBvid(epBvid);

            if (firstBvid.isEmpty() && !epBvid.isEmpty()) {
                firstBvid = epBvid;
                firstAid = epAid;
            }

            // 分集标题优先使用 show_title（例如 "第1话 告白"），若无则拼接 title + long_title
            String showTitle = ep.path("show_title").asText("");
            if (showTitle.isEmpty()) {
                String t = ep.path("title").asText("");
                String lt = ep.path("long_title").asText("");
                showTitle = lt.isEmpty() ? t : (t.isEmpty() ? lt : (t + " " + lt));
            }
            if (showTitle.isEmpty()) {
                showTitle = "P" + (idx + 1);
            }
            page.setPart(showTitle);

            long durMs = ep.path("duration").asLong(0L);
            page.setDuration(durMs > 1000 ? durMs / 1000 : durMs);

            long curEpId = ep.path("ep_id").asLong(0L);
            if (targetEpId != null && curEpId == targetEpId) {
                targetIdx = idx;
                info.setBvid(epBvid);
                info.setAid(epAid);
            }
            pages.add(page);
            idx++;
        }

        if (info.getBvid() == null || info.getBvid().isEmpty()) {
            info.setBvid(firstBvid);
            info.setAid(firstAid);
        }
        info.setPages(pages);
        info.setDefaultPageIndex(targetIdx);
        return info;
    }

    /** 获取某分P 的播放流（DASH） */
    public PlayUrlResult getPlayUrl(String bvid, long cid, int qn) {
        String url = "https://api.bilibili.com/x/player/playurl?bvid=" + urlEncode(bvid)
                + "&cid=" + cid + "&qn=" + qn + "&fnval=" + FNVAL + "&fourk=1";
        JsonNode data = getJson(url, true);
        return parsePlayUrl(data);
    }

    /** 获取某分P 的可用画质列表 */
    public List<FormatOption> getFormats(String bvid, long cid) {
        String url = "https://api.bilibili.com/x/player/playurl?bvid=" + urlEncode(bvid)
                + "&cid=" + cid + "&qn=127&fnval=" + FNVAL + "&fourk=1";
        JsonNode data = getJson(url, true);
        // 实际可用的画质：DASH 流中真实存在的视频流 id（未登录/无大会员时 B 站只下发低画质流）
        java.util.Set<Integer> actual = new java.util.LinkedHashSet<>();
        for (JsonNode v : data.path("dash").path("video")) {
            actual.add(v.path("id").asInt());
        }
        // 画质描述：accept_quality + accept_description 配对
        java.util.Map<Integer, String> descMap = new java.util.LinkedHashMap<>();
        JsonNode qualities = data.path("accept_quality");
        JsonNode descs = data.path("accept_description");
        for (int i = 0; i < qualities.size(); i++) {
            int q = qualities.get(i).asInt();
            String d = i < descs.size() ? descs.get(i).asText() : String.valueOf(q);
            descMap.put(q, d);
        }
        // 输出：只在 accept_quality 中保留实际可下的画质，保持从高到低顺序
        List<FormatOption> formats = new ArrayList<>();
        for (java.util.Map.Entry<Integer, String> e : descMap.entrySet()) {
            if (actual.contains(e.getKey())) {
                formats.add(new FormatOption(e.getKey(), e.getValue(), true));
            }
        }
        return formats;
    }

    /** 解析 playurl 返回的 DASH 数据 */
    public PlayUrlResult parsePlayUrl(JsonNode data) {
        PlayUrlResult result = new PlayUrlResult();
        JsonNode dash = data.path("dash");
        for (JsonNode v : dash.path("video")) {
            StreamInfo s = new StreamInfo();
            s.setId(v.path("id").asInt());
            s.setBaseUrl(v.path("baseUrl").asText(""));
            s.setBackupUrls(collectUrls(v.path("backupUrl")));
            s.setBandwidth(v.path("bandwidth").asLong());
            s.setCodecs(v.path("codecs").asText(""));
            s.setMimeType(v.path("mimeType").asText(""));
            result.getVideoStreams().add(s);
        }
        for (JsonNode a : dash.path("audio")) {
            StreamInfo s = new StreamInfo();
            s.setId(a.path("id").asInt());
            s.setBaseUrl(a.path("baseUrl").asText(""));
            s.setBackupUrls(collectUrls(a.path("backupUrl")));
            s.setBandwidth(a.path("bandwidth").asLong());
            s.setCodecs(a.path("codecs").asText(""));
            s.setMimeType(a.path("mimeType").asText(""));
            result.getAudioStreams().add(s);
        }
        // flac 无损音频兜底
        if (result.getAudioStreams().isEmpty() && dash.has("flac")) {
            JsonNode flac = dash.path("flac").path("audio");
            if (!flac.isMissingNode()) {
                StreamInfo s = new StreamInfo();
                s.setId(flac.path("id").asInt());
                s.setBaseUrl(flac.path("baseUrl").asText(""));
                s.setBackupUrls(collectUrls(flac.path("backupUrl")));
                s.setBandwidth(flac.path("bandwidth").asLong());
                s.setCodecs(flac.path("codecs").asText(""));
                s.setMimeType(flac.path("mimeType").asText(""));
                result.getAudioStreams().add(s);
            }
        }
        return result;
    }

    private List<String> collectUrls(JsonNode arr) {
        List<String> urls = new ArrayList<>();
        for (JsonNode u : arr) {
            String s = u.asText();
            if (!s.isEmpty()) urls.add(s);
        }
        return urls;
    }

    /** GET 一个 JSON API，返回 data 节点；code != 0 抛异常 */
    private JsonNode getJson(String url, boolean withCookie) {
        HttpGet request = new HttpGet(url);
        if (withCookie && !cookie.isEmpty()) {
            request.setHeader("Cookie", cookie);
        }
        try (CloseableHttpResponse resp = httpClient.execute(request)) {
            int status = resp.getStatusLine().getStatusCode();
            if (status != 200) {
                throw new BiliApiException(status, "HTTP " + status + " 请求失败: " + url);
            }
            JsonNode root = mapper.readTree(resp.getEntity().getContent());
            int code = root.path("code").asInt(-1);
            if (code != 0) {
                String msg = root.path("message").asText("未知错误");
                if (isPermissionError(code, msg)) {
                    throw new QualityPermissionException(code, msg);
                }
                throw new BiliApiException(code, msg);
            }
            if (root.has("result")) {
                return root.path("result");
            }
            return root.path("data");
        } catch (IOException e) {
            throw new BiliApiException("网络请求失败: " + e.getMessage());
        }
    }

    private boolean isPermissionError(int code, String message) {
        if (code == -10403 || code == -404) return true;
        String m = message.toLowerCase();
        return m.contains("大会员") || m.contains("会员") || m.contains("权限")
                || m.contains("登录") || m.contains("付费") || m.contains("login");
    }

    /** 打开流媒体响应（支持 Range），供流式下载 */
    public CloseableHttpResponse openStream(String url, String range) throws IOException {
        HttpGet request = new HttpGet(url);
        if (range != null) {
            request.setHeader(HttpHeaders.RANGE, range);
        }
        if (!cookie.isEmpty()) {
            request.setHeader("Cookie", cookie);
        }
        return httpClient.execute(request);
    }

    /** 探测资源总大小（字节），失败返回 -1 */
    public long probeSize(String url) {
        RequestConfig cfg = RequestConfig.custom()
                .setConnectTimeout(5_000)
                .setSocketTimeout(5_000)
                .build();
        HttpGet request = new HttpGet(url);
        request.setConfig(cfg);
        request.setHeader(HttpHeaders.RANGE, "bytes=0-0");
        try (CloseableHttpResponse resp = httpClient.execute(request)) {
            Header contentRange = resp.getFirstHeader(HttpHeaders.CONTENT_RANGE);
            if (contentRange != null) {
                String value = contentRange.getValue();
                int slash = value.lastIndexOf('/');
                if (slash >= 0) {
                    return Long.parseLong(value.substring(slash + 1).trim());
                }
            }
            Header contentLength = resp.getFirstHeader(HttpHeaders.CONTENT_LENGTH);
            if (contentLength != null) {
                return Long.parseLong(contentLength.getValue());
            }
            return -1;
        } catch (IOException | NumberFormatException e) {
            return -1;
        }
    }

    /** 从 B 站链接/文本中提取 BV 号、av 号、番剧 ss/ep 号（含 b23.tv 短链跟随跳转与 Markdown 清洗） */
    public LinkResult resolveLink(String input) {
        if (input == null) return null;
        String text = input.trim();
        // 手机端分享格式 / Markdown 格式：提取其中的 URL 并清洗末尾多余标点
        java.util.regex.Matcher urlMatcher = java.util.regex.Pattern
                .compile("https?://[^\\s，,]+", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(text);
        if (urlMatcher.find()) {
            String url = urlMatcher.group();
            // 清理末尾因 Markdown 格式带来的右括号、右方括号等，如 [title](https://.../ss6312)
            url = url.replaceAll("[)\\]>\"'\\s]+$", "");
            // b23.tv / bilibili.tv 短链：跟随跳转取最终 URL
            if (url.matches("(?i)https?://(?:b23\\.tv|bilibili\\.tv)/.*")) {
                String resolved = followRedirect(url);
                if (resolved != null) {
                    LinkResult r = parseLink(resolved);
                    if (r != null) return r;
                }
            }
            LinkResult r = parseLink(url);
            if (r != null) return r;
        }
        return parseLink(text);
    }

    /** 跟随短链跳转，返回最终 URL（失败返回 null） */
    public String followRedirect(String url) {
        // 用不自动跟随重定向的请求，手动拿 Location
        HttpGet request = new HttpGet(url);
        RequestConfig cfg = RequestConfig.custom()
                .setRedirectsEnabled(false)
                .build();
        request.setConfig(cfg);
        try (CloseableHttpResponse resp = httpClient.execute(request)) {
            int status = resp.getStatusLine().getStatusCode();
            if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                Header location = resp.getFirstHeader("Location");
                if (location != null && location.getValue() != null) {
                    String loc = location.getValue();
                    return loc.startsWith("http") ? loc : url;
                }
            }
            return null;
        } catch (IOException e) {
            return null;
        }
    }

    /** 从文本或 URL 中提取视频编号（BV、av、番剧 ss、番剧 ep） */
    public static LinkResult parseLink(String input) {
        if (input == null) return null;
        String s = input.trim();
        // 1. 匹配 BV 号 (BV1xx...)
        java.util.regex.Matcher bv = java.util.regex.Pattern.compile("BV[0-9A-Za-z]{10}").matcher(s);
        if (bv.find()) {
            return new LinkResult(bv.group(), null);
        }
        // 2. 匹配番剧 ep 号 (ep123456, /play/ep123456, ep_id=123456)
        java.util.regex.Matcher ep = java.util.regex.Pattern.compile("(?i)(?:/play/ep|/ep|ep_id=|\\bep)(\\d+)").matcher(s);
        if (ep.find()) {
            return new LinkResult(null, null, null, Long.parseLong(ep.group(1)));
        }
        // 3. 匹配番剧 ss 号 (ss3542, ss6312, /play/ss3542, season_id=3542)
        java.util.regex.Matcher ss = java.util.regex.Pattern.compile("(?i)(?:/play/ss|/ss|season_id=|\\bss)(\\d+)").matcher(s);
        if (ss.find()) {
            return new LinkResult(null, null, Long.parseLong(ss.group(1)), null);
        }
        // 4. 匹配 av / aid 号 (av123456, aid=123456)
        java.util.regex.Matcher av = java.util.regex.Pattern.compile("(?i)(?:/video/av|aid=|\\bav)(\\d+)").matcher(s);
        if (av.find()) {
            return new LinkResult(null, Long.parseLong(av.group(1)));
        }
        return null;
    }

    private static String urlEncode(String s) {
        try {
            return java.net.URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    /** 申请登录二维码 */
    public QrCodeResult generateQrCode() {
        HttpGet request = new HttpGet("https://passport.bilibili.com/x/passport-login/web/qrcode/generate");
        try (CloseableHttpResponse resp = httpClient.execute(request)) {
            if (resp.getStatusLine().getStatusCode() != 200) {
                throw new BiliApiException("申请二维码失败: HTTP " + resp.getStatusLine().getStatusCode());
            }
            JsonNode root = mapper.readTree(resp.getEntity().getContent());
            if (root.path("code").asInt(-1) != 0) {
                throw new BiliApiException("申请二维码失败: " + root.path("message").asText());
            }
            JsonNode data = root.path("data");
            return new QrCodeResult(data.path("url").asText(""), data.path("qrcode_key").asText(""));
        } catch (IOException e) {
            throw new BiliApiException("申请二维码网络失败: " + e.getMessage());
        }
    }

    /** 轮询扫码状态（成功时捕获 Set-Cookie 响应头） */
    public QrPollResult pollQrCode(String qrcodeKey) {
        String url = "https://passport.bilibili.com/x/passport-login/web/qrcode/poll?qrcode_key=" + urlEncode(qrcodeKey);
        HttpGet request = new HttpGet(url);
        try (CloseableHttpResponse resp = httpClient.execute(request)) {
            int status = resp.getStatusLine().getStatusCode();
            if (status != 200) {
                throw new BiliApiException("轮询二维码失败: HTTP " + status);
            }
            JsonNode root = mapper.readTree(resp.getEntity().getContent());
            JsonNode data = root.path("data");
            int subCode = data.path("code").asInt(-1);
            String message = data.path("message").asText("");
            String cookie = "";
            if (subCode == 0) {
                Header[] setCookies = resp.getHeaders("Set-Cookie");
                StringBuilder sb = new StringBuilder();
                if (setCookies != null) {
                    for (Header h : setCookies) {
                        String val = h.getValue();
                        if (val != null) {
                            int semi = val.indexOf(';');
                            String pair = (semi >= 0 ? val.substring(0, semi) : val).trim();
                            if (!pair.isEmpty()) {
                                if (sb.length() > 0) sb.append("; ");
                                sb.append(pair);
                            }
                        }
                    }
                }
                cookie = sb.toString();
            }
            return new QrPollResult(subCode, message, cookie);
        } catch (IOException e) {
            throw new BiliApiException("轮询二维码网络异常: " + e.getMessage());
        }
    }

    public static class QrCodeResult {
        public final String url;
        public final String qrcodeKey;
        public QrCodeResult(String url, String qrcodeKey) {
            this.url = url;
            this.qrcodeKey = qrcodeKey;
        }
    }

    public static class QrPollResult {
        public final int code; // 0=成功, 86101=未扫码, 86090=已扫码未确认, 86038=已过期
        public final String message;
        public final String cookie;
        public QrPollResult(int code, String message, String cookie) {
            this.code = code;
            this.message = message;
            this.cookie = cookie;
        }
    }

    /** 链接解析结果（支持普通视频 BV/av 与番剧影视 ss/ep） */
    public static class LinkResult {
        public final String bvid;
        public final Long aid;
        public final Long seasonId;
        public final Long epId;

        public LinkResult(String bvid, Long aid) {
            this(bvid, aid, null, null);
        }

        public LinkResult(String bvid, Long aid, Long seasonId, Long epId) {
            this.bvid = bvid;
            this.aid = aid;
            this.seasonId = seasonId;
            this.epId = epId;
        }
    }
}
