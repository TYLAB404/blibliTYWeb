package com.tylab.biliweb.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tylab.biliweb.model.FriendLink;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/** 友情链接服务：持久化保存在服务器本地 config/friends.json */
@Service
public class FriendLinkService {

    private static final Logger log = LoggerFactory.getLogger(FriendLinkService.class);

    private final ObjectMapper mapper = new ObjectMapper();
    private final List<FriendLink> friendLinks = new CopyOnWriteArrayList<>();

    @Value("${app.friends-file-path:config/friends.json}")
    private String friendsFilePath;

    @PostConstruct
    public void init() {
        loadFromFile();
    }

    /** 读取所有友情链接 */
    public List<FriendLink> list() {
        return Collections.unmodifiableList(new ArrayList<>(friendLinks));
    }

    /** 添加友情链接 */
    public synchronized FriendLink add(String name, String url, String description) throws IOException {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("网站名称不能为空");
        }
        if (url == null || url.trim().isEmpty()) {
            throw new IllegalArgumentException("网站链接不能为空");
        }
        String trimmedUrl = url.trim();
        if (!trimmedUrl.startsWith("http://") && !trimmedUrl.startsWith("https://")) {
            trimmedUrl = "https://" + trimmedUrl;
        }

        FriendLink link = new FriendLink(
                UUID.randomUUID().toString().replace("-", "").substring(0, 12),
                name.trim(),
                trimmedUrl,
                description == null ? "" : description.trim(),
                System.currentTimeMillis()
        );

        friendLinks.add(link);
        saveToFile();
        log.info("已新增友情链接: {} -> {}", link.getName(), link.getUrl());
        return link;
    }

    /** 根据 ID 删除友情链接 */
    public synchronized boolean delete(String id) throws IOException {
        if (id == null || id.trim().isEmpty()) {
            return false;
        }
        boolean removed = friendLinks.removeIf(link -> id.trim().equals(link.getId()));
        if (removed) {
            saveToFile();
            log.info("已删除友情链接 ID: {}", id);
        }
        return removed;
    }

    private void loadFromFile() {
        File file = new File(friendsFilePath);
        if (!file.exists()) {
            return;
        }
        try {
            List<FriendLink> loaded = mapper.readValue(file, new TypeReference<List<FriendLink>>() {});
            if (loaded != null) {
                friendLinks.clear();
                friendLinks.addAll(loaded);
                log.info("已加载 {} 条友情链接", friendLinks.size());
            }
        } catch (Exception e) {
            log.warn("读取友情链接配置文件失败 ({}): {}", friendsFilePath, e.getMessage());
        }
    }

    private void saveToFile() throws IOException {
        File file = new File(friendsFilePath);
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        mapper.writerWithDefaultPrettyPrinter().writeValue(file, new ArrayList<>(friendLinks));
    }
}
