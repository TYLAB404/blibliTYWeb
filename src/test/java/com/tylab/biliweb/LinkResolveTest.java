package com.tylab.biliweb;

import com.tylab.biliweb.api.BiliApiClient;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class LinkResolveTest {

    @Test
    public void testBangumiSeasonLink() {
        String url1 = "https://www.bilibili.com/bangumi/play/ss3542?from_spmid=666.23.0.0";
        BiliApiClient.LinkResult r1 = BiliApiClient.parseLink(url1);
        assertNotNull(r1);
        assertEquals(3542L, r1.seasonId);
        assertNull(r1.bvid);
        assertNull(r1.epId);

        String textMarkdown = "[徒然喜欢你-番剧-全集-高清正版在线观看-bilibili-哔哩哔哩](https://www.bilibili.com/bangumi/play/ss6312?spm_id_from=333.337.0.0)";
        BiliApiClient client = new BiliApiClient("");
        BiliApiClient.LinkResult r2 = client.resolveLink(textMarkdown);
        assertNotNull(r2);
        assertEquals(6312L, r2.seasonId);
        assertNull(r2.bvid);
    }

    @Test
    public void testBangumiEpisodeLink() {
        String epUrl = "https://www.bilibili.com/bangumi/play/ep85833?theme=movie";
        BiliApiClient.LinkResult r = BiliApiClient.parseLink(epUrl);
        assertNotNull(r);
        assertEquals(85833L, r.epId);
        assertNull(r.seasonId);
    }

    @Test
    public void testStandardVideoLink() {
        String bv = "https://www.bilibili.com/video/BV1GJ411x7h7";
        BiliApiClient.LinkResult r = BiliApiClient.parseLink(bv);
        assertNotNull(r);
        assertEquals("BV1GJ411x7h7", r.bvid);
    }
}
