package com.tylab.biliweb.api;

import com.tylab.biliweb.model.StreamInfo;

import java.util.ArrayList;
import java.util.List;

/** playurl 接口解析结果：DASH 音视频流 */
public class PlayUrlResult {
    private final List<StreamInfo> videoStreams = new ArrayList<>();
    private final List<StreamInfo> audioStreams = new ArrayList<>();

    public List<StreamInfo> getVideoStreams() { return videoStreams; }
    public List<StreamInfo> getAudioStreams() { return audioStreams; }
}
