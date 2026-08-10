package com.tylab.biliweb.util;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** ffmpeg 查找与执行 */
@Component
public class FfmpegUtil {

    private final String configuredPath;

    public FfmpegUtil(@Value("${app.ffmpeg-path:}") String configuredPath) {
        this.configuredPath = configuredPath == null ? "" : configuredPath.trim();
    }

    /** 返回可用的 ffmpeg 可执行文件路径；找不到返回 null */
    public String resolvePath() {
        if (!configuredPath.isEmpty()) {
            File f = new File(configuredPath);
            if (f.exists()) return f.getAbsolutePath();
        }
        for (String candidate : new String[]{"ffmpeg", "ffmpeg.exe"}) {
            try {
                Process p = new ProcessBuilder("where", candidate).redirectErrorStream(true).start();
                String line = readFirstLine(p);
                int exit = p.waitFor();
                if (exit == 0 && line != null && !line.trim().isEmpty()) {
                    return line.trim();
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    public boolean isAvailable() {
        return resolvePath() != null;
    }

    /**
     * 合并音视频流（不重新编码）。
     * @return 合并输出文件
     */
    public File merge(String videoPath, String audioPath, String outputPath) throws IOException, InterruptedException {
        String ffmpeg = resolvePath();
        if (ffmpeg == null) {
            throw new IOException("未找到 ffmpeg，无法合并音视频");
        }
        List<String> cmd = new ArrayList<>();
        cmd.add(ffmpeg);
        cmd.add("-y");
        cmd.add("-i");
        cmd.add(videoPath);
        cmd.add("-i");
        cmd.add(audioPath);
        cmd.add("-c:v");
        cmd.add("copy");
        cmd.add("-c:a");
        cmd.add("copy");
        cmd.add(outputPath);
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        // 消费输出，防止缓冲区阻塞
        try (java.io.BufferedReader br = new java.io.BufferedReader(
                new java.io.InputStreamReader(p.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                // 静默，仅调试时打印
                System.out.println("[ffmpeg] " + line);
            }
        }
        int exit = p.waitFor();
        if (exit != 0) {
            throw new IOException("ffmpeg 合并失败，退出码 " + exit);
        }
        return new File(outputPath);
    }

    private String readFirstLine(Process p) throws IOException {
        try (java.io.BufferedReader br = new java.io.BufferedReader(
                new java.io.InputStreamReader(p.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
            return br.readLine();
        }
    }
}
