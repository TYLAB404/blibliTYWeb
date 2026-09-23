package com.tylab.biliweb;

import com.tylab.biliweb.model.FriendLink;
import com.tylab.biliweb.service.FriendLinkService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class FriendLinkServiceTest {

    private FriendLinkService service;
    private File tempFile;

    @BeforeEach
    public void setup() throws IOException {
        service = new FriendLinkService();
        tempFile = File.createTempFile("test_friends", ".json");
        tempFile.deleteOnExit();
        ReflectionTestUtils.setField(service, "friendsFilePath", tempFile.getAbsolutePath());
        service.init();
    }

    @AfterEach
    public void teardown() {
        if (tempFile != null && tempFile.exists()) {
            tempFile.delete();
        }
    }

    @Test
    public void testAddAndList() throws IOException {
        FriendLink link = service.add("哔哩哔哩", "https://www.bilibili.com", "弹幕视频网");
        assertNotNull(link);
        assertNotNull(link.getId());
        assertEquals("哔哩哔哩", link.getName());
        assertEquals("https://www.bilibili.com", link.getUrl());

        List<FriendLink> list = service.list();
        assertEquals(1, list.size());
        assertEquals("哔哩哔哩", list.get(0).getName());
    }

    @Test
    public void testDelete() throws IOException {
        FriendLink link = service.add("测试", "http://example.com", "测试描述");
        assertEquals(1, service.list().size());

        boolean deleted = service.delete(link.getId());
        assertTrue(deleted);
        assertEquals(0, service.list().size());
    }

    @Test
    public void testUrlFormatting() throws IOException {
        FriendLink link = service.add("无协议前缀测试", "example.com", "");
        assertTrue(link.getUrl().startsWith("https://"));
    }
}
