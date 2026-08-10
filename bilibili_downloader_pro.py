import os
import re
import sys
import json
import time
import glob
import math
import queue
import shutil
import threading
import subprocess
import tkinter as tk
from tkinter import ttk, messagebox, filedialog, scrolledtext
import requests

# B站API通用请求头
HEADERS = {
    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/115.0.0.0 Safari/537.36",
    "Referer": "https://www.bilibili.com"
}

# B站画质代码对照表 (用于友好的错误提示)
QN_MAP = {
    127: "8K 超高清", 126: "杜比视界", 125: "HDR 真彩", 120: "4K 超清",
    116: "1080P 60帧", 112: "1080P 高码率", 80: "1080P 高清", 74: "720P 60帧",
    64: "720P 高清", 32: "480P 清晰", 16: "360P 流畅"
}

# 同时并行的下载任务数
MAX_CONCURRENT = 1  # 同时下载的最大任务数; 1=单任务独占带宽, 速度更稳

# 多线程分片下载: 单个流总大小达到阈值时才分片并发下载绕过单连接限速。
# 注意: 小文件(如音频, 通常 5~30MB 中多数 <20MB)走单连接——
# 小文件分片会因多连接触发 CDN 并发限速/握手开销反而更慢, 主流工具同样只对大文件分片
PARALLEL_MIN_SIZE = 20 * 1024 * 1024     # 流大小达到该值才启用分片 (20MB)
PARALLEL_PARTS_MAX = 4                    # 分片下载的 worker 线程数 (并发连接数)
PARALLEL_PART_SIZE = 2 * 1024 * 1024      # 每片目标大小 (2MB, 细粒度便于负载均衡)
PARALLEL_MAX_PARTS = 32                   # 分片数上限
PART_RETRY_MAX = 3                        # 每个分片最大尝试次数 (含首次), 失败自动重试

# 任务级自动重试: 网络类错误(所有节点均失败/超时/断流)时, 保留已下载部分自动重试
TASK_AUTO_RETRY_MAX = 2                  # 网络错误自动重试次数
TASK_AUTO_RETRY_DELAY = 5                # 每次重试前的等待秒数

# 任务状态
TASK_WAITING = "排队中"
TASK_DOWNLOADING = "下载中"
TASK_PAUSED = "已暂停"
TASK_MERGING = "合并中"
TASK_TRANSCODING = "转码中"
TASK_COMPLETED = "已完成"
TASK_FAILED = "失败"
TASK_CANCELLED = "已取消"
TASK_SKIPPED = "已跳过"  # 内部状态：权限不足直接移除，仅用于工作线程传递

ACTIVE_STATES = (TASK_WAITING, TASK_DOWNLOADING, TASK_PAUSED, TASK_MERGING, TASK_TRANSCODING)
TERMINAL_STATES = (TASK_COMPLETED, TASK_FAILED, TASK_CANCELLED, TASK_SKIPPED)


class DownloadCancelledError(Exception):
    """任务被用户取消时抛出的内部异常"""
    pass


class QualityPermissionError(Exception):
    """画质权限不足（无Cookie/非大会员/被降级）导致的失败，任务将被直接移除"""
    pass


_NETWORK_ERROR_HINTS = (
    "timeout", "timed out", "connect", "connection", "socket",
    "所有下载节点均失败", "连接失败", "节点", "proxy",
)


def is_network_error(e):
    """判断异常是否为网络类错误 (连接失败/超时/断流/节点不可达等), 用于任务自动重试"""
    if isinstance(e, requests.exceptions.RequestException):
        return True
    msg = str(e).lower()
    return any(h in msg for h in _NETWORK_ERROR_HINTS)


class SlowNodeError(Exception):
    """当前节点下载速度过慢, 需要切换到备用节点"""
    pass


class RangeUnsupportedError(Exception):
    """服务器不支持 Range 分片下载"""
    pass


# 慢速节点检测阈值: 平均速度连续 SLOW_SPEED_SECONDS 秒低于 SLOW_SPEED_THRESHOLD 时切换备用节点
SLOW_SPEED_THRESHOLD = 30 * 1024   # 30 KB/s
SLOW_SPEED_SECONDS = 15


_SHORT_LINK_CACHE = {}


def _match_key(text):
    """从文本中正则匹配视频标识 (不含短链跳转逻辑)"""
    if not text:
        return None
    m = re.search(r'(?<![0-9A-Za-z])BV[0-9A-Za-z]{10}', text, re.IGNORECASE)
    if m:
        return m.group(0)
    m = re.search(r'(?<![0-9A-Za-z])av(\d+)', text, re.IGNORECASE)
    if m:
        return f"av{m.group(1)}"
    m = re.search(r'(?<![0-9A-Za-z])ep(\d+)', text, re.IGNORECASE)
    if m:
        return f"ep{m.group(1)}"
    m = re.search(r'(?<![0-9A-Za-z])ss(\d+)', text, re.IGNORECASE)
    if m:
        return f"ss{m.group(1)}"
    m = re.search(r'(?<![0-9A-Za-z])md(\d+)', text, re.IGNORECASE)
    if m:
        return f"md{m.group(1)}"
    m = re.search(r'bangumi\.bilibili\.com/anime/(\d+)', text, re.IGNORECASE)
    if m:
        return f"ss{m.group(1)}"
    return None


def detect_link_type(text):
    """识别链接中的视频标识，返回统一的关键字：BVxxxx / av123 / ep123 / ss123 / md123。
    兼容大小写、URL 参数、全角字符与首尾空白；b23.tv / bilibili.tv 短链会跟随跳转解析；
    旧式番剧链接 bangumi.bilibili.com/anime/{id} 归一化为 ss{id}。无法识别返回 None。
    注意: 短链跳转涉及网络请求, 应在线程中调用。"""
    if not text:
        return None
    # 归一化: 去首尾空白, 全角空格转半角, 全角字母/数字转半角
    text = text.strip().replace('\u3000', ' ')
    text = ''.join(chr(ord(c) - 0xFEE0) if '\uFF01' <= c <= '\uFF5E' else c for c in text)

    # b23.tv / bilibili.tv 短链: 跟随跳转取最终 URL (或页面 HTML) 再识别
    m = re.search(r'https?://(?:b23\.tv|bilibili\.tv)/[^\s，,]+', text, re.IGNORECASE)
    if m:
        short_url = m.group(0)
        if short_url in _SHORT_LINK_CACHE:
            return _SHORT_LINK_CACHE[short_url]
        try:
            resp = requests.get(short_url, allow_redirects=True, headers=HEADERS, timeout=10)
            resolved = (resp.url or "").strip()
            if resolved and not re.search(r'https?://(?:b23\.tv|bilibili\.tv)/', resolved, re.IGNORECASE):
                key = _match_key(resolved) or _match_key(resp.text)
            else:
                key = _match_key(resp.text)
            _SHORT_LINK_CACHE[short_url] = key
            return key
        except Exception:
            pass
    return _match_key(text)


def _key_kind(key):
    """根据统一关键字判断来源类型"""
    if not key:
        return None
    if key[:2].upper() == "BV" or key[:2].lower() == "av":
        return "video"
    return "bangumi"


def is_permission_error(code, msg):
    """判断播放流接口返回的错误是否为 登录/大会员/权限 类错误"""
    if code in (-10403, -404):
        return True
    keywords = ("大会员", "会员", "权限", "无权限", "登录", "未登录", "付费")
    return any(k in msg for k in keywords)


def format_duration(seconds):
    """将时长格式化为 时:分:秒。
    番剧接口可能返回毫秒（如 12:30.203 -> 750203），值 >= 86400 时按毫秒处理。"""
    value = int(seconds or 0)
    if value >= 86400:
        value //= 1000
    hours, rem = divmod(value, 3600)
    minutes, secs = divmod(rem, 60)
    return f"{hours}:{minutes:02d}:{secs:02d}"


def fetch_playurl(bvid, cid, qn, cookie=""):
    """请求播放流并校验画质，返回 {video_url, audio_url, actual_qn}。
    权限不足/画质被降级时抛 QualityPermissionError，其他错误抛 Exception。"""
    url = "https://api.bilibili.com/x/player/playurl"
    params = {
        "bvid": bvid,
        "cid": cid,
        "qn": qn,
        "fnval": 4048 | 1024 | 512 | 256,  # 请求 8K 和杜比的所有可能组合
        "fourk": 1
    }
    headers = HEADERS.copy()
    if cookie:
        headers["Cookie"] = cookie

    resp = requests.get(url, params=params, headers=headers, timeout=10).json()
    if resp["code"] != 0:
        msg = resp.get("message", "")
        if resp["code"] == -404:
            # B站对需大会员/登录内容在无权限访问时返回 -404 "啥都木有"
            raise QualityPermissionError("该内容需登录或大会员观看，请填写 Cookie 后重试。")
        if is_permission_error(resp["code"], msg):
            raise QualityPermissionError(f"画质权限不足: {msg or '需要登录或大会员'}")
        raise Exception(msg or "无法获取视频流")

    dash_data = resp.get("data", {}).get("dash")
    if not dash_data:
        raise Exception("无法获取 DASH 音视频分离流格式。")

    v_list = dash_data.get("video", [])
    target_v = next((v for v in v_list if v["id"] == qn), None)
    if not target_v:
        target_v = max(v_list, key=lambda x: x["id"]) if v_list else None
    if not target_v:
        raise Exception("未获取到视频流。")
    actual_qn = target_v["id"]

    # 拦截 B站 的“静默画质降级”
    if actual_qn < qn:
        exp_name = QN_MAP.get(qn, f"画质代码 {qn}")
        act_name = QN_MAP.get(actual_qn, f"画质代码 {actual_qn}")
        raise QualityPermissionError(
            f"期望画质【{exp_name}】被降级为【{act_name}】\n"
            f"可能原因：Cookie 过期、账号非大会员或该集需要登录观看。"
        )

    def _candidate_urls(stream):
        """收集主 URL 与备用 URL (backupUrl), 过滤空串/非http项并去重保序, 用于节点故障时切换"""
        urls = []
        raw = [stream.get("baseUrl") or ""] + list(stream.get("backupUrl") or [])
        for u in raw:
            u = (u or "").strip()
            if u.startswith("http") and u not in urls:
                urls.append(u)
        return urls

    a_list = dash_data.get("audio", [])
    if not a_list:
        a_list = dash_data.get("flac", {}).get("audio", [])
    target_a = max(a_list, key=lambda x: x["id"]) if a_list else None

    return {
        "video_url": target_v["baseUrl"],
        "audio_url": target_a["baseUrl"] if target_a else None,
        "video_urls": _candidate_urls(target_v),
        "audio_urls": _candidate_urls(target_a) if target_a else [],
        "actual_qn": actual_qn,
    }


class DownloadTask:
    """单个分P/单集番剧的下载任务，负责流下载(支持暂停/续传)、ffmpeg合并与清理。"""

    def __init__(self, app, bvid, title, page_num, cid, part_title, total_pages, save_dir, qn, cookie,
                 quality_name="", source_kind="video", mode="video", audio_format="m4a"):
        self.app = app
        self.bvid = bvid
        self.title = title
        self.page_num = page_num
        self.cid = cid
        self.part_title = part_title or f"第{page_num}集"
        self.total_pages = total_pages
        self.save_dir = save_dir
        self.qn = qn
        self.cookie = cookie
        self.quality_name = quality_name
        self.source_kind = source_kind
        self.mode = mode            # "video" 或 "audio"
        self.audio_format = audio_format  # 仅音频模式: "m4a" 或 "mp3"
        self.checked = False        # 任务管理列表的勾选状态

        self.state = TASK_WAITING
        self.progress = 0.0
        self.speed_str = "0 KB/s"
        self._last_progress_bytes = 0
        self._last_progress_time = time.time()
        self.error = ""
        self.iid = None
        self.final_output = None

        self._cancel_event = threading.Event()
        self._resume_event = threading.Event()
        self._pause_request = False
        self._paused = False
        self._state_lock = threading.Lock()
        self._thread = None
        self._ffmpeg_proc = None
        self._row_update_pending = False

        self.video_written = 0
        self.audio_written = 0
        self.video_total = 0
        self.audio_total = 0
        self.video_url = None
        self.audio_url = None
        self.temp_video = None
        self.temp_audio = None
        self.temp_transcode = None

    # ---------- 展示信息 ----------
    def display_name(self):
        if self.total_pages > 1:
            if self.source_kind == "bangumi":
                return f"第{self.page_num}集 {self.part_title}"
            return f"P{self.page_num:02d} {self.part_title}"
        return self.title

    def progress_text(self):
        if self.state == TASK_COMPLETED:
            return "100%"
        return f"{self.progress:.1f}%"

    def is_active(self):
        return self.state in ACTIVE_STATES

    def is_terminal(self):
        return self.state in TERMINAL_STATES

    def _set_state(self, state):
        with self._state_lock:
            self.state = state
        self._schedule_row_update()

    def _schedule_row_update(self):
        with self._state_lock:
            if self._row_update_pending:
                return
            self._row_update_pending = True
        self.app.root.after(200, self._flush_row_update)

    def _flush_row_update(self):
        with self._state_lock:
            self._row_update_pending = False
        self.app.update_task_row(self)

    def log(self, text):
        self.app.log(text)

    # ---------- 任务控制 ----------
    def start(self):
        if self.state != TASK_WAITING:
            return
        self._thread = threading.Thread(target=self.run, daemon=True)
        self._thread.start()

    def request_pause(self):
        if self.state == TASK_WAITING:
            # 尚未启动的任务直接标记暂停，调度器不会再启动它
            self._set_state(TASK_PAUSED)
            self.app.start_pending_downloads()
        elif self.state == TASK_DOWNLOADING:
            # 正在下载的任务，worker 会在下一个数据块循环中处理
            self._pause_request = True
        # 合并阶段不允许暂停

    def request_resume(self):
        if self.state != TASK_PAUSED:
            return
        if self._thread is None:
            # 排队时暂停的任务，恢复后重新进入调度
            self._set_state(TASK_WAITING)
            self.app.start_pending_downloads()
        else:
            self._paused = False
            self._pause_request = False
            self._resume_event.set()

    def cancel(self):
        """请求取消：唤醒线程并终止 ffmpeg；文件清理由 worker 完成。"""
        self._cancel_event.set()
        self._resume_event.set()
        if self._ffmpeg_proc is not None and self._ffmpeg_proc.poll() is None:
            try:
                self._ffmpeg_proc.terminate()
            except Exception:
                pass
        if self._thread is None and self.state in (TASK_WAITING, TASK_PAUSED):
            self._set_state(TASK_CANCELLED)

    def cleanup_temp_files(self):
        for p in (self.temp_video, self.temp_audio, self.temp_transcode):
            if p and os.path.exists(p):
                try:
                    os.remove(p)
                except Exception:
                    pass
            if p:
                # 清理分片下载残留的 .partN 临时文件
                for pp in glob.glob(p + ".part*"):
                    try:
                        os.remove(pp)
                    except Exception:
                        pass

    # ---------- 下载工作线程 ----------
    def run(self):
        attempts = 0
        while True:
            attempts += 1
            try:
                self._set_state(TASK_DOWNLOADING)
                if attempts > 1:
                    self.log(f"🔄 任务自动重试 ({attempts - 1}/{TASK_AUTO_RETRY_MAX}): {self.display_name()}")
                else:
                    self.log(f"🚀 开始下载任务: {self.display_name()}")
                self._prepare_streams()

                if self.mode == "audio":
                    # 仅音频模式: 下载音频流; M4A 直接改名保存, MP3 用 ffmpeg 转码
                    if not self.audio_url:
                        raise Exception("未获取到音频流。")
                    self.log(f"⬇️ 下载音频流: {self.display_name()}")
                    self._download_stream(self.audio_urls, self.temp_audio, "audio")
                    if self._cancel_event.is_set():
                        raise DownloadCancelledError()
                    if self.audio_format == "mp3":
                        self._set_state(TASK_TRANSCODING)
                        self.app.start_pending_downloads()  # 转码不占下载槽, 立即让下一任务补位下载
                        self.speed_str = "转码中"
                        self._schedule_row_update()
                        self.log(f"🔄 正在转码为 MP3: {self.display_name()}")
                        self._transcode_to_mp3()
                        if self._cancel_event.is_set():
                            raise DownloadCancelledError()
                    else:
                        os.replace(self.temp_audio, self.final_output)
                    self.cleanup_temp_files()
                    self.progress = 100.0
                    self.speed_str = "完成"
                    self._set_state(TASK_COMPLETED)
                    self.log(f"🎉 任务完成: {self.final_output}")
                else:
                    if self.video_url:
                        self.log(f"⬇️ 下载画面流: {self.display_name()}")
                        self._download_stream(self.video_urls, self.temp_video, "video")
                    if self.audio_url and not self._cancel_event.is_set():
                        self.log(f"⬇️ 下载音频流: {self.display_name()}")
                        self._download_stream(self.audio_urls, self.temp_audio, "audio")

                    if self._cancel_event.is_set():
                        raise DownloadCancelledError()

                    self._set_state(TASK_MERGING)
                    self.app.start_pending_downloads()  # 合并不占下载槽, 立即让下一任务补位下载
                    self.progress = 100.0
                    self.speed_str = "合并中"
                    self._schedule_row_update()
                    self.log(f"🔄 正在合并音视频: {self.display_name()}")
                    self._merge_files()

                    self.cleanup_temp_files()
                    self.progress = 100.0
                    self.speed_str = "完成"
                    self._set_state(TASK_COMPLETED)
                    self.log(f"🎉 任务完成: {self.final_output}")
                break  # 下载成功
            except QualityPermissionError as e:
                self.error = str(e)
                self.cleanup_temp_files()
                self._set_state(TASK_SKIPPED)
                self.log(f"⏭️ 画质权限不足，任务将移除: {self.display_name()} - {e}")
                break
            except DownloadCancelledError:
                self.cleanup_temp_files()
                self._set_state(TASK_CANCELLED)
                self.log(f"🗑️ 任务已取消: {self.display_name()}")
                break
            except Exception as e:
                if attempts <= TASK_AUTO_RETRY_MAX and is_network_error(e):
                    # 网络类错误(所有节点均失败/超时/断流等): 保留已下载部分, 等待后自动重试(断点续传)
                    self.error = str(e)
                    self.log(f"🔄 网络异常({type(e).__name__}), {TASK_AUTO_RETRY_DELAY}s 后自动重试 "
                             f"({attempts}/{TASK_AUTO_RETRY_MAX + 1})...")
                    wait_start = time.time()
                    while time.time() - wait_start < TASK_AUTO_RETRY_DELAY:
                        if self._cancel_event.is_set():
                            self.cleanup_temp_files()
                            self._set_state(TASK_CANCELLED)
                            self.log(f"🗑️ 任务已取消: {self.display_name()}")
                            self.app.on_task_finished(self)
                            return
                        time.sleep(0.5)
                    continue
                self.error = str(e)
                self.cleanup_temp_files()
                self._set_state(TASK_FAILED)
                self.log(f"❌ 任务失败 [{self.display_name()}]: {e}")
                break
        self.app.on_task_finished(self)

    def _prepare_streams(self):
        info = fetch_playurl(self.bvid, self.cid, self.qn, self.cookie)
        self.video_url = info["video_url"]
        self.audio_url = info["audio_url"]
        self.video_urls = info.get("video_urls") or [self.video_url]
        self.audio_urls = info.get("audio_urls") or ([self.audio_url] if self.audio_url else [])
        actual_qn = info["actual_qn"]

        safe_title = re.sub(r'[\\/:*?"<>|]', '_', self.title)
        if self.total_pages > 1:
            if self.source_kind == "bangumi":
                base = f"第{self.page_num:02d}集_{safe_title}"
            else:
                base = f"P{self.page_num:02d}_{safe_title}"
        else:
            base = safe_title

        if self.mode == "audio":
            out_ext = ".mp3" if self.audio_format == "mp3" else ".m4a"
            temp_video = None
        else:
            out_ext = ".mkv" if actual_qn >= 120 else ".mp4"
            temp_video = os.path.join(self.save_dir, f"{base}_v.m4s")
        self.final_output = os.path.join(self.save_dir, f"{base}{out_ext}")
        self.temp_video = temp_video
        self.temp_audio = os.path.join(self.save_dir, f"{base}_a.m4s") if self.audio_url else None
        # 转码临时文件: mp3 先转写到临时文件, 成功后原子改名, 取消/失败时只清理临时文件, 不影响他人产物
        self.temp_transcode = os.path.join(self.save_dir, f"{base}_t.mp3") if (
            self.mode == "audio" and self.audio_format == "mp3") else None
        # 注意: 不在此处清理临时文件, 以便任务级自动重试时基于已有部分断点续传;
        # 取消/失败/任务级重试耗尽时的清理由 run() 的异常分支负责

    def _download_file(self, urls, path, which, start=0, end=None, update_progress=True):
        """下载单个流(或其中一段 Range [start, end))到 path。
        支持暂停/恢复(Range续传)、取消，以及节点连接失败/超时/HTTP错误/中途断流时自动切换备用节点续传。
        update_progress=False 用于分片线程: 不更新共享进度统计, 仅写文件。"""
        if isinstance(urls, str):
            urls = [urls]
        offset = 0
        if os.path.exists(path):
            offset = os.path.getsize(path)
        if update_progress:
            if which == "video":
                self.video_written = offset
            else:
                self.audio_written = offset
        if end is not None and start + offset >= end:
            return  # 分片已完整

        last_speed_time = time.time()
        last_speed_bytes = (self.video_written if update_progress else start + offset) + \
                           (self.audio_written if update_progress else 0)
        last_error = None
        restart_count = 0
        slow_start_time = None
        conn_errors = (requests.exceptions.ConnectTimeout, requests.exceptions.ConnectionError,
                       requests.exceptions.ChunkedEncodingError, requests.exceptions.ReadTimeout,
                       requests.exceptions.SSLError, requests.exceptions.ContentDecodingError)

        while True:
            if self._cancel_event.is_set():
                raise DownloadCancelledError()

            # 暂停处理：等待 恢复/取消 (轮询标志, 多分片线程共用无 wait/clear 竞态)
            if self._pause_request or self._paused:
                self._paused = True
                self._set_state(TASK_PAUSED)
                self.speed_str = "已暂停"
                self._schedule_row_update()
                while self._pause_request or self._paused:
                    if self._cancel_event.is_set():
                        raise DownloadCancelledError()
                    time.sleep(0.2)
                self._set_state(TASK_DOWNLOADING)
                continue

            # 依次尝试候选节点 (主 URL + 备用 URL); 每个节点的 Range 头在循环内按当前 offset 构造
            paused_now = False
            restart_now = False
            for cand_index, cand in enumerate(urls):
                if self._cancel_event.is_set():
                    raise DownloadCancelledError()
                if self._pause_request or self._paused:
                    paused_now = True
                    break
                # 每个候选节点都按当前 offset 重新构造 Range 头,
                # 避免节点切换(断流/慢速/内容不符)后继续用旧 Range 导致重复写入
                headers = HEADERS.copy()
                if end is not None:
                    req_from = start + offset
                    req_to = end - 1
                    if req_from > req_to:
                        return  # 分片已完整
                    headers["Range"] = f"bytes={req_from}-{req_to}"
                elif offset > 0:
                    headers["Range"] = f"bytes={offset}-"
                try:
                    r = requests.get(cand, headers=headers, stream=True, timeout=30)
                except conn_errors as e:
                    last_error = e
                    self.log(f"⚠️ 节点 {self._host_of(cand)} 连接失败, 尝试备用节点...")
                    continue

                if r.status_code == 416 and offset > 0:
                    # 断点已超出文件总大小: 校验 Content-Range 确认资源完整后才视为已完成
                    total = self._parse_range_total(r.headers.get("content-range"))
                    r.close()
                    if end is not None:
                        return  # 分片模式: 请求范围超出资源, 视为该分片已完整
                    if total == offset:
                        if which == "video":
                            self.video_written = offset
                            self.video_total = offset
                        else:
                            self.audio_written = offset
                            self.audio_total = offset
                        return
                    last_error = Exception("节点返回 416 但资源大小不一致")
                    self.log(f"⚠️ 节点 {self._host_of(cand)} 返回异常 416, 尝试备用节点...")
                    continue
                if r.status_code >= 400:
                    # HTTP 错误(403/404/500 等): 切到备用节点, 不从头重下
                    last_error = Exception(f"HTTP {r.status_code}")
                    self.log(f"⚠️ 节点 {self._host_of(cand)} 返回 HTTP {r.status_code}, 尝试备用节点...")
                    r.close()
                    continue
                if r.status_code == 200:
                    if end is not None:
                        # 分片模式: 服务器忽略 Range 返回完整内容, 无法分片, 交由上层降级
                        r.close()
                        raise RangeUnsupportedError("服务器不支持 Range 分片下载")
                    if offset > 0:
                        # 服务器忽略 Range 直接返回完整内容: 不支持断点续传, 从头下载
                        r.close()
                        offset = 0
                        if update_progress:
                            if which == "video":
                                self.video_written = 0
                            else:
                                self.audio_written = 0
                        restart_now = True
                        break

                if update_progress:
                    content_length = int(r.headers.get("content-length", 0))
                    if which == "video":
                        self.video_total = offset + content_length
                    else:
                        self.audio_total = offset + content_length

                written = offset
                mode = "ab" if offset > 0 else "wb"
                try:
                    with open(path, mode) as f:
                        for chunk in r.iter_content(chunk_size=65536):
                            if not chunk:
                                continue
                            if self._cancel_event.is_set():
                                raise DownloadCancelledError()
                            f.write(chunk)
                            written += len(chunk)
                            if update_progress:
                                if which == "video":
                                    self.video_written = written
                                else:
                                    self.audio_written = written

                            now = time.time()
                            if update_progress and now - last_speed_time >= 0.5:
                                total_bytes = self.video_written + self.audio_written
                                total_all = self.video_total + self.audio_total
                                speed_bps = (total_bytes - last_speed_bytes) / max(now - last_speed_time, 1e-6)
                                self.speed_str = self._fmt_speed(speed_bps)
                                if total_all > 0:
                                    self.progress = min(100.0, total_bytes / total_all * 100.0)
                                self._schedule_row_update()
                                last_speed_time = now
                                last_speed_bytes = total_bytes

                                # 慢速节点检测: 仅当还有备用节点时, 平均速度持续过低则切换
                                if cand_index < len(urls) - 1:
                                    if speed_bps < SLOW_SPEED_THRESHOLD:
                                        if slow_start_time is None:
                                            slow_start_time = now
                                        elif now - slow_start_time >= SLOW_SPEED_SECONDS:
                                            raise SlowNodeError()
                                    else:
                                        slow_start_time = None

                            if self._pause_request or self._paused:
                                f.flush()
                                offset = written
                                break
                except DownloadCancelledError:
                    raise
                except SlowNodeError:
                    # 当前节点速度持续过低: 保留已写字节, 切换到备用节点续传
                    last_error = SlowNodeError()
                    self.log(f"⚠️ 节点 {self._host_of(cand)} 下载速度过慢, 切换备用节点续传...")
                    offset = written
                    slow_start_time = None
                    continue
                except conn_errors as e:
                    # 下载中途断流: 保留已写字节, 切换到下一节点续传
                    last_error = e
                    self.log(f"⚠️ 节点 {self._host_of(cand)} 下载中断, 尝试备用节点续传...")
                    offset = written
                    continue
                finally:
                    r.close()

                if self._pause_request or self._paused:
                    paused_now = True
                    break
                if end is not None and written != end - start:
                    # 分片模式: 响应内容与期望长度不符(CDN 返回错误数据), 切到下一节点重下
                    last_error = Exception(f"分片内容长度不符 ({written}/{end - start})")
                    self.log(f"⚠️ 节点 {self._host_of(cand)} 返回内容与期望不符, 尝试备用节点...")
                    offset = written
                    continue
                if end is None:
                    # 整流模式: 若响应带 Content-Range 总大小, 校验下载是否完整
                    full_size = self._parse_range_total(r.headers.get("content-range"))
                    if full_size > 0 and written != full_size:
                        last_error = Exception(f"响应内容不完整 ({written}/{full_size})")
                        self.log(f"⚠️ 节点 {self._host_of(cand)} 返回内容不完整, 尝试备用节点...")
                        offset = written
                        continue
                return  # 该流下载完成

            if restart_now:
                restart_count += 1
                if restart_count > 3:
                    raise Exception("下载节点均不支持断点续传, 无法完成下载。")
                continue  # 从头重新遍历候选节点
            if paused_now:
                continue  # 回到 while 顶部处理暂停

            # 所有候选节点均失败
            if last_error:
                raise Exception(f"所有下载节点均失败: {last_error}")
            raise Exception("所有下载节点均连接失败，请检查网络后重试。")

    def _update_progress_ui(self):
        """汇总视频/音频流已写字节, 更新任务进度与速度 (供分片下载轮询使用)"""
        total_bytes = self.video_written + self.audio_written
        total_all = self.video_total + self.audio_total
        now = time.time()
        speed_bps = (total_bytes - self._last_progress_bytes) / max(now - self._last_progress_time, 1e-6)
        self.speed_str = self._fmt_speed(speed_bps)
        if total_all > 0:
            self.progress = min(100.0, total_bytes / total_all * 100.0)
        self._schedule_row_update()
        self._last_progress_bytes = total_bytes
        self._last_progress_time = now

    def _probe_size(self, urls):
        """探测流总大小: 对候选节点发 Range: bytes=0-0 请求, 解析 Content-Range 的总大小; 失败返回 0"""
        for cand in urls:
            try:
                headers = HEADERS.copy()
                headers["Range"] = "bytes=0-0"
                r = requests.get(cand, headers=headers, stream=True, timeout=5)
                if r.status_code == 206:
                    total = self._parse_range_total(r.headers.get("content-range"))
                    r.close()
                    if total > 0:
                        return total
                elif r.status_code == 200:
                    # 服务器忽略 Range 返回完整内容: 不支持分片
                    r.close()
                    return 0
                else:
                    r.close()
            except Exception:
                continue
        return 0

    def _download_stream(self, urls, path, which):
        """下载单个流: 总大小达到阈值且支持 Range 时启用多线程分片下载, 否则走单连接"""
        if isinstance(urls, str):
            urls = [urls]
        total = self._probe_size(urls)
        if total >= PARALLEL_MIN_SIZE:
            self._download_file_parallel(urls, path, which, total)
        else:
            self._download_file(urls, path, which)

    def _download_file_parallel(self, urls, path, which, total):
        """多线程分片下载(队列模型, 对齐市面成熟下载器):
        切成细粒度分片放入任务队列, worker 线程池动态领取分片下载(负载均衡);
        每个分片下载失败自动重试(PART_RETRY_MAX 次), 重试耗尽才失败;
        全部完成后校验每片大小并顺序拼接。"""
        parts = max(1, min(PARALLEL_MAX_PARTS, math.ceil(total / PARALLEL_PART_SIZE)))
        if parts <= 1:
            self._download_file(urls, path, which)
            return
        self.log(f"⚡ 启用多线程分片下载: {parts} 片 / {PARALLEL_PARTS_MAX} 连接")
        part_size = math.ceil(total / parts)
        ranges = [(i * part_size, min((i + 1) * part_size, total)) for i in range(parts)]
        part_paths = [f"{path}.part{i}" for i in range(parts)]

        # 清理上次残留的分片文件
        for pp in part_paths:
            if os.path.exists(pp):
                try:
                    os.remove(pp)
                except Exception:
                    pass

        # 分片任务队列 + worker 线程池 (动态领取分片)
        task_q = queue.Queue()
        for i in range(parts):
            task_q.put(i)
        results = {}
        lock = threading.Lock()
        stop_flag = threading.Event()

        def worker():
            while not stop_flag.is_set():
                try:
                    i = task_q.get_nowait()
                except queue.Empty:
                    return
                try:
                    self._download_part_with_retry(urls, part_paths[i], which, *ranges[i])
                    with lock:
                        results[i] = None
                except DownloadCancelledError:
                    with lock:
                        results[i] = DownloadCancelledError()
                    stop_flag.set()
                    return
                except Exception as ex:
                    with lock:
                        results[i] = ex
                    stop_flag.set()  # 某分片重试耗尽, 通知其他 worker 停止领取新分片
                    return

        threads = [threading.Thread(target=worker, daemon=True) for _ in range(PARALLEL_PARTS_MAX)]
        for t in threads:
            t.start()

        # 重置进度统计基准, 避免首帧速度被任务创建时刻拉低
        self._last_progress_bytes = 0
        self._last_progress_time = time.time()

        # 轮询汇总分片进度 (分片线程不更新共享进度; 暂停期间不刷新速度)
        while any(t.is_alive() for t in threads):
            if self._cancel_event.is_set():
                stop_flag.set()
                break
            if self._pause_request or self._paused:
                time.sleep(0.3)
                continue
            done = sum(os.path.getsize(p) if os.path.exists(p) else 0 for p in part_paths)
            if which == "video":
                self.video_written = done
                self.video_total = total
            else:
                self.audio_written = done
                self.audio_total = total
            self._update_progress_ui()
            time.sleep(0.3)

        for t in threads:
            t.join()

        # 汇总线程结果: 取消优先, 其余取首个异常
        cancelled = False
        err = None
        for i in range(parts):
            res = results.get(i)
            if isinstance(res, DownloadCancelledError):
                cancelled = True
            elif isinstance(res, Exception):
                err = err or res
        if cancelled:
            raise DownloadCancelledError()
        if err:
            # 服务器不支持 Range: 删除分片文件并降级为单连接全量下载
            if isinstance(err, RangeUnsupportedError):
                self.log("⚠️ 服务器不支持 Range 分片, 降级为单连接下载")
                self.cleanup_temp_files()
                self._download_file(urls, path, which)
                return
            raise err

        # 校验每个分片完整后再拼接
        for i in range(parts):
            got = os.path.getsize(part_paths[i]) if os.path.exists(part_paths[i]) else 0
            if got != (ranges[i][1] - ranges[i][0]):
                raise Exception(f"分片 {i} 下载不完整 ({got}/{ranges[i][1] - ranges[i][0]})，请重试")
        self._merge_parts(path, part_paths)

        # 成功后校准最终进度
        if which == "video":
            self.video_written = total
            self.video_total = total
        else:
            self.audio_written = total
            self.audio_total = total
        self.progress = 100.0
        self._schedule_row_update()

    def _download_part_with_retry(self, urls, part_path, which, start, end):
        """下载单个分片 [start, end), 失败自动重试(最多 PART_RETRY_MAX 次)。
        每次重试轮换节点优先级(避开持续返回错误数据的节点);
        重试前清空分片文件(避免残留坏数据续传污染);
        Range 不支持(RangeUnsupportedError)与取消不重试。"""
        expect = end - start
        n = len(urls)
        last_err = None
        for attempt in range(1, PART_RETRY_MAX + 1):
            if self._cancel_event.is_set():
                raise DownloadCancelledError()
            # 轮换节点顺序: 每次重试换一个不同的主节点
            shift = (attempt - 1) % n
            rotated = urls[shift:] + urls[:shift]
            try:
                self._download_file(rotated, part_path, which, start, end, False)
            except DownloadCancelledError:
                raise
            except RangeUnsupportedError:
                raise  # 服务器不支持 Range, 重试无意义
            except Exception as e:
                last_err = e
                if attempt < PART_RETRY_MAX:
                    self.log(f"🔁 分片 {os.path.basename(part_path)} 第 {attempt} 次失败({type(e).__name__}), 自动重试...")
                    try:
                        os.remove(part_path)  # 清空残留(可能已被污染), 从头下载
                    except Exception:
                        pass
                continue
            # 校验分片完整性
            got = os.path.getsize(part_path) if os.path.exists(part_path) else 0
            if got == expect:
                return
            last_err = Exception(f"分片校验失败 ({got}/{expect})")
            if attempt < PART_RETRY_MAX:
                self.log(f"🔁 分片 {os.path.basename(part_path)} 校验失败, 清空后重试...")
                try:
                    os.remove(part_path)
                except Exception:
                    pass
                continue
        raise last_err or Exception("分片下载失败")

    def _merge_parts(self, path, part_paths):
        """按顺序拼接分片文件到目标文件, 完成后删除分片文件"""
        with open(path, "wb") as out:
            for pp in part_paths:
                with open(pp, "rb") as f:
                    shutil.copyfileobj(f, out, 1024 * 1024)
        for pp in part_paths:
            try:
                os.remove(pp)
            except Exception:
                pass

    @staticmethod
    def _parse_range_total(content_range):
        """解析 Content-Range 响应头中的资源总大小 (bytes */N), 解析失败返回 -1"""
        try:
            if content_range and "/" in content_range:
                return int(content_range.rsplit("/", 1)[1])
        except Exception:
            pass
        return -1

    @staticmethod
    def _host_of(url):
        """提取 URL 的主机名用于日志提示"""
        try:
            return url.split("/")[2]
        except Exception:
            return url[:48]

    @staticmethod
    def _fmt_speed(bps):
        if bps > 1024 * 1024:
            return f"{bps / (1024 * 1024):.1f} MB/s"
        return f"{bps / 1024:.1f} KB/s"

    def _transcode_to_mp3(self):
        """用 ffmpeg 将下载的音频流 (m4a容器) 转码为 mp3, 支持取消; 先写临时文件, 成功后原子改名"""
        ffmpeg_bin = self.app.get_ffmpeg_path()
        cmd = [ffmpeg_bin, "-y", "-i", self.temp_audio, "-vn", "-c:a", "libmp3lame",
               "-q:a", "2", self.temp_transcode]
        try:
            creationflags = subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0
            self._ffmpeg_proc = subprocess.Popen(
                cmd, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, creationflags=creationflags
            )
            while self._ffmpeg_proc.poll() is None:
                if self._cancel_event.is_set():
                    try:
                        self._ffmpeg_proc.terminate()
                    except Exception:
                        pass
                    self._ffmpeg_proc.wait()
                    raise DownloadCancelledError()
                time.sleep(0.2)
            if self._ffmpeg_proc.returncode != 0:
                raise Exception("FFmpeg 转码失败，请检查磁盘空间或文件权限。")
        finally:
            self._ffmpeg_proc = None
        os.replace(self.temp_transcode, self.final_output)

    def _merge_files(self):
        ffmpeg_bin = self.app.get_ffmpeg_path()
        cmd = [ffmpeg_bin, "-y", "-i", self.temp_video]
        if self.temp_audio and os.path.exists(self.temp_audio):
            cmd.extend(["-i", self.temp_audio, "-c:v", "copy", "-c:a", "copy", self.final_output])
        else:
            cmd.extend(["-c:v", "copy", self.final_output])

        try:
            creationflags = subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0
            self._ffmpeg_proc = subprocess.Popen(
                cmd, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, creationflags=creationflags
            )
            while self._ffmpeg_proc.poll() is None:
                if self._cancel_event.is_set():
                    try:
                        self._ffmpeg_proc.terminate()
                    except Exception:
                        pass
                    self._ffmpeg_proc.wait()
                    raise DownloadCancelledError()
                time.sleep(0.2)
            if self._ffmpeg_proc.returncode != 0:
                raise Exception("FFmpeg 合并失败，请检查磁盘空间或文件权限。")
        finally:
            self._ffmpeg_proc = None


class BilibiliDownloaderApp:
    def __init__(self):
        self.root = tk.Tk()
        self.root.title("Bilibili 视频下载器 Pro - TYLAB")
        self._set_initial_geometry()
        self.root.minsize(1120, 680)
        self.root.configure(bg="#f8f9fa")
        self._set_app_icon()

        # 初始化路径
        self.default_path = os.path.join(os.path.expanduser("~"), "Desktop")
        self.config_file = os.path.join(os.getenv("APPDATA", ""), "BilibiliDownloader", "config.json")
        if not os.path.exists(os.path.dirname(self.config_file)):
            os.makedirs(os.path.dirname(self.config_file), exist_ok=True)

        # 核心变量
        self.current_bvid = None          # 当前分析结果的归一化链接标识 (bv/av/ep/ss/md)
        self.current_input_key = None     # 与 current_bvid 同义，用于防错位比较
        self.current_title = ""
        self.current_pages = []

        # 记录上一次成功分析时的输入状态，防错位
        self.last_analyzed_url = ""
        self.last_analyzed_cookie = ""
        self.quality_qn_list = []         # 画质下拉选项对应的 qn 列表 (与选项一一对应)
        self.quality_names = {}           # qn -> 画质描述
        self.episode_checked = {}

        # 下载任务管理
        self.tasks = []
        self._task_iid_seq = 0          # 任务行 iid 自增序列, 避免删除后重复添加冲突
        self._download_clicked = False
        self._completion_popup_shown = False
        self._skipped_pending = []
        self._skipped_popup_scheduled = False

        self.setup_ui()
        self.load_config()
        self.root.protocol("WM_DELETE_WINDOW", self.on_close)
        self.root.mainloop()

    def _set_app_icon(self):
        """设置窗口/任务栏图标（exe 文件图标由打包配置指定）"""
        try:
            base = getattr(sys, '_MEIPASS', os.getcwd())
            icon_path = os.path.join(base, 'TYLAB_icon_9.ico')
            if os.path.exists(icon_path):
                self.root.iconbitmap(icon_path)
        except Exception:
            pass

    def _set_initial_geometry(self):
        """窗口尺寸取屏幕工作区的 80% 并居中显示"""
        try:
            if os.name == 'nt':
                import ctypes

                class RECT(ctypes.Structure):
                    _fields_ = [
                        ("left", ctypes.c_long),
                        ("top", ctypes.c_long),
                        ("right", ctypes.c_long),
                        ("bottom", ctypes.c_long),
                    ]

                rect = RECT()
                if ctypes.windll.user32.SystemParametersInfoW(0x0030, 0, ctypes.byref(rect), 0):
                    w = rect.right - rect.left
                    h = rect.bottom - rect.top
                    if w > 0 and h > 0:
                        win_w = int(w * 0.8)
                        win_h = int(h * 0.8)
                        x = rect.left + (w - win_w) // 2
                        y = rect.top + (h - win_h) // 2
                        self.root.geometry(f"{win_w}x{win_h}+{x}+{y}")
                        return
        except Exception:
            pass
        # 回退：使用屏幕尺寸的 80% 并居中
        sw = self.root.winfo_screenwidth()
        sh = self.root.winfo_screenheight()
        win_w = int(sw * 0.8)
        win_h = int(sh * 0.8)
        x = max((sw - win_w) // 2, 0)
        y = max((sh - win_h) // 2, 0)
        self.root.geometry(f"{win_w}x{win_h}+{x}+{y}")

    def setup_ui(self):
        # 配置 ttk 样式
        style = ttk.Style()
        style.theme_use('clam')
        style.configure("TProgressbar", thickness=18, background="#00aeec", troughcolor="#e9ecef")
        style.configure("Episode.Treeview", font=("Microsoft YaHei", 11), rowheight=30)
        style.configure("Episode.Treeview.Heading", font=("Microsoft YaHei", 11, "bold"))
        style.configure("Task.Treeview", font=("Microsoft YaHei", 11), rowheight=30)
        style.configure("Task.Treeview.Heading", font=("Microsoft YaHei", 11, "bold"))

        font_title = ("Microsoft YaHei", 16, "bold")
        font_main = ("Microsoft YaHei", 10)

        # 底部署名栏
        frame_footer = tk.Frame(self.root, bg="#eef3f7")
        frame_footer.pack(side=tk.BOTTOM, fill=tk.X)
        tk.Label(frame_footer, text="TYLAB · 仅供学习交流，请尊重原作者版权",
                 bg="#eef3f7", fg="#00aeec", font=("Microsoft YaHei", 9, "bold")).pack(pady=3)

        self.notebook = ttk.Notebook(self.root)
        self.notebook.pack(fill=tk.BOTH, expand=True, padx=10, pady=10)

        # ================= 标签页1：视频下载 =================
        page_download = tk.Frame(self.notebook, bg="#f8f9fa")
        self.notebook.add(page_download, text=" 视频下载 ")

        # 下载按钮固定在页面底部，窗口缩小时始终可见
        self.btn_download = tk.Button(page_download, text="立即开始下载", bg="#ff6699", fg="white",
                                      font=("Microsoft YaHei", 12, "bold"), relief="flat", cursor="hand2",
                                      state=tk.DISABLED, command=self.start_download_thread)
        self.btn_download.pack(side=tk.BOTTOM, pady=10, ipadx=50, ipady=8)

        tk.Label(page_download, text="Bilibili 视频下载", font=font_title, fg="#00aeec", bg="#f8f9fa").pack(pady=(12, 8))

        # 链接输入区
        frame_input = tk.Frame(page_download, bg="#f8f9fa")
        frame_input.pack(fill=tk.X, padx=20, pady=3)
        tk.Label(frame_input, text="视频链接或BV号:",
                 font=font_main, bg="#f8f9fa").pack(anchor=tk.W)

        frame_entry_btn = tk.Frame(frame_input, bg="#f8f9fa")
        frame_entry_btn.pack(fill=tk.X, pady=(4, 0))
        self.entry_bvid = tk.Entry(frame_entry_btn, font=("Arial", 11), relief="flat",
                                   highlightthickness=1, highlightbackground="#ced4da")
        self.entry_bvid.pack(side=tk.LEFT, fill=tk.X, expand=True, ipady=6, padx=(0, 10))
        self.btn_analyze = tk.Button(frame_entry_btn, text="🔍 分析视频", bg="#00aeec", fg="white",
                                     font=font_main, relief="flat", cursor="hand2", command=self.start_analyze_thread)
        self.btn_analyze.pack(side=tk.RIGHT, ipadx=10, ipady=3)
        tk.Button(frame_entry_btn, text="📋 粘贴", bg="#6c757d", fg="white", font=font_main,
                  relief="flat", cursor="hand2",
                  command=lambda: self._paste_to(self.entry_bvid)
                  ).pack(side=tk.RIGHT, padx=(0, 10), ipadx=10, ipady=3)
        tk.Button(frame_entry_btn, text="🗑 清空", bg="#6c757d", fg="white", font=font_main,
                  relief="flat", cursor="hand2",
                  command=lambda: self._clear_entry(self.entry_bvid)
                  ).pack(side=tk.RIGHT, padx=(0, 10), ipadx=10, ipady=3)

        # Cookie区
        frame_cookie = tk.Frame(page_download, bg="#f8f9fa")
        frame_cookie.pack(fill=tk.X, padx=20, pady=8)
        tk.Label(frame_cookie, text="Cookie (选填, 获取720p及以上画质需填写):", font=font_main, bg="#f8f9fa").pack(anchor=tk.W)
        frame_cookie_btn = tk.Frame(frame_cookie, bg="#f8f9fa")
        frame_cookie_btn.pack(fill=tk.X, pady=(4, 0))
        self.entry_cookie = tk.Entry(frame_cookie_btn, font=("Arial", 11), relief="flat",
                                     highlightthickness=1, highlightbackground="#ced4da")
        self.entry_cookie.pack(side=tk.LEFT, fill=tk.X, expand=True, ipady=6, padx=(0, 10))
        tk.Button(frame_cookie_btn, text="📋 粘贴", bg="#6c757d", fg="white", font=font_main,
                  relief="flat", cursor="hand2",
                  command=lambda: self._paste_to(self.entry_cookie)
                  ).pack(side=tk.RIGHT, ipadx=10, ipady=3)
        tk.Button(frame_cookie_btn, text="🗑 清空", bg="#6c757d", fg="white", font=font_main,
                  relief="flat", cursor="hand2",
                  command=lambda: self._clear_entry(self.entry_cookie)
                  ).pack(side=tk.RIGHT, padx=(0, 10), ipadx=10, ipady=3)
        self.entry_cookie.bind("<FocusOut>", lambda e: self.save_config())

        # 同时监听 URL 和 Cookie 的实时变化
        self.entry_bvid.bind("<KeyRelease>", self.on_input_changed)
        self.entry_cookie.bind("<KeyRelease>", self.on_input_changed)
        # 输入框右键粘贴
        self._bind_paste_menu(self.entry_bvid)
        self._bind_paste_menu(self.entry_cookie)

        # 路径区
        frame_path = tk.Frame(page_download, bg="#f8f9fa")
        frame_path.pack(fill=tk.X, padx=20, pady=8)
        tk.Label(frame_path, text="保存路径:", font=font_main, bg="#f8f9fa").pack(anchor=tk.W)

        frame_path_inner = tk.Frame(frame_path, bg="#f8f9fa")
        frame_path_inner.pack(fill=tk.X, pady=(4, 0))
        self.entry_path = tk.Entry(frame_path_inner, font=("Arial", 11), relief="flat",
                                   highlightthickness=1, highlightbackground="#ced4da")
        self.entry_path.pack(side=tk.LEFT, fill=tk.X, expand=True, ipady=6, padx=(0, 10))
        self.entry_path.insert(0, self.default_path)
        tk.Button(frame_path_inner, text="打开该目录", bg="#17a2b8", fg="white", font=font_main,
                  relief="flat", cursor="hand2", command=self._open_save_path
                  ).pack(side=tk.RIGHT, padx=(0, 10), ipadx=10, ipady=3)
        tk.Button(frame_path_inner, text="更改路径", bg="#6c757d", fg="white", font=font_main,
                  relief="flat", cursor="hand2", command=self.select_path).pack(side=tk.RIGHT, ipadx=10, ipady=3)

        # 使用说明区
        frame_tip = tk.LabelFrame(page_download, text=" 使用说明 ", font=font_main, bg="#f8f9fa", fg="#6c757d")
        frame_tip.pack(fill=tk.X, padx=20, pady=(5, 3))
        tips = "支持分P视频与番剧 (ep/ss/md) 链接；勾选集数批量下载；音频模式可选 M4A/MP3。仅供学习交流。"
        tk.Label(frame_tip, text=tips, font=("Microsoft YaHei", 9), bg="#f8f9fa", fg="#555",
                 justify=tk.LEFT).pack(anchor=tk.W, padx=10, pady=5)

        # 下载模式选择区 (视频/仅音频 + 画质动态选择) — 本区域字体统一加大一号
        font_mode = ("Microsoft YaHei", 11)   # 模式行/标签字号
        font_mode_ctl = ("Microsoft YaHei", 10)  # 下拉框/提示字号
        self.download_mode = tk.IntVar()
        self.download_mode.set(0)  # 0=下载视频, 1=仅下载音频
        frame_mode = tk.LabelFrame(page_download, text=" 下载模式 ", font=font_mode, bg="#f8f9fa", fg="#00aeec")
        frame_mode.pack(fill=tk.X, padx=20, pady=5)

        # 第一行: 视频/音频模式 + 音频格式
        frame_mode_row1 = tk.Frame(frame_mode, bg="#f8f9fa")
        frame_mode_row1.pack(fill=tk.X)
        tk.Radiobutton(frame_mode_row1, text="下载视频 (音视频合并)", variable=self.download_mode, value=0,
                       font=font_mode, bg="#f8f9fa",
                       command=self._on_mode_change).pack(side=tk.LEFT, padx=(10, 30), pady=6)
        tk.Radiobutton(frame_mode_row1, text="仅下载音频", variable=self.download_mode, value=1,
                       font=font_mode, bg="#f8f9fa",
                       command=self._on_mode_change).pack(side=tk.LEFT, pady=6)
        tk.Label(frame_mode_row1, text="音频格式:", font=font_mode, bg="#f8f9fa").pack(side=tk.LEFT, padx=(24, 4))
        self.cmb_audio_format = ttk.Combobox(frame_mode_row1, state="disabled", width=50,
                                             font=font_mode_ctl)
        self.cmb_audio_format["values"] = (
            "M4A - 原样保存音频流, 无需转码、速度最快、音质无损",
            "MP3 - 兼容性最好、体积小, 需 ffmpeg 转码、略耗时",
        )
        self.cmb_audio_format.current(0)
        self.cmb_audio_format.pack(side=tk.LEFT, pady=6)

        # 第二行: 画质选择 (动态, 未分析前禁用) + 提示
        frame_mode_row2 = tk.Frame(frame_mode, bg="#f8f9fa")
        frame_mode_row2.pack(fill=tk.X, pady=(0, 6))
        tk.Label(frame_mode_row2, text="画质:", font=font_mode, bg="#f8f9fa").pack(side=tk.LEFT, padx=(10, 4))
        self.cmb_quality = ttk.Combobox(frame_mode_row2, state="disabled", width=40,
                                        font=font_mode_ctl)
        self.cmb_quality.pack(side=tk.LEFT, pady=2)
        self.lbl_quality_hint = tk.Label(frame_mode_row2, text="等待输入链接并分析...",
                                         font=font_mode_ctl, bg="#f8f9fa", fg="#888")
        self.lbl_quality_hint.pack(side=tk.LEFT, padx=(10, 0), pady=2)

        # 选集列表区
        self.frame_episodes = tk.LabelFrame(page_download, text=" 选集列表 (可多选, 默认勾选第一集) ",
                                            font=font_main, bg="#f8f9fa", fg="#00aeec")
        self.frame_episodes.pack(fill=tk.BOTH, expand=True, padx=20, pady=5)

        # 当前解析出的视频标题（红色醒目显示，区别于蓝色边框标题）
        self.lbl_episode_title = tk.Label(self.frame_episodes, text="", font=("Microsoft YaHei", 11, "bold"),
                                          bg="#f8f9fa", fg="#e63946", anchor=tk.W)
        self.lbl_episode_title.pack(fill=tk.X, padx=10, pady=(8, 0))

        frame_ep_btns = tk.Frame(self.frame_episodes, bg="#f8f9fa")
        frame_ep_btns.pack(fill=tk.X, padx=8, pady=(6, 0))
        tk.Button(frame_ep_btns, text="全选", bg="#6c757d", fg="white", font=font_main, relief="flat",
                  cursor="hand2", command=self.select_all_episodes).pack(side=tk.LEFT, ipadx=8, ipady=2)
        tk.Button(frame_ep_btns, text="清空", bg="#6c757d", fg="white", font=font_main, relief="flat",
                  cursor="hand2", command=self.clear_episode_selection).pack(side=tk.LEFT, padx=(8, 0), ipadx=8, ipady=2)
        self.lbl_episode_count = tk.Label(frame_ep_btns, text="", font=font_main, bg="#f8f9fa", fg="#888")
        self.lbl_episode_count.pack(side=tk.RIGHT)

        frame_ep_tree = tk.Frame(self.frame_episodes, bg="#f8f9fa")
        frame_ep_tree.pack(fill=tk.BOTH, expand=True, padx=8, pady=6)
        columns = ("sel", "page", "title", "dur")
        self.episode_tree = ttk.Treeview(frame_ep_tree, columns=columns, show="headings", height=14,
                                         style="Episode.Treeview")
        self.episode_tree.heading("sel", text="选择")
        self.episode_tree.heading("page", text="集数")
        self.episode_tree.heading("title", text="标题")
        self.episode_tree.heading("dur", text="时长")
        self.episode_tree.column("sel", width=60, anchor=tk.CENTER, stretch=False)
        self.episode_tree.column("page", width=80, anchor=tk.CENTER, stretch=False)
        self.episode_tree.column("title", width=500, anchor=tk.W)
        self.episode_tree.column("dur", width=70, anchor=tk.CENTER, stretch=False)
        ep_scroll = ttk.Scrollbar(frame_ep_tree, orient=tk.VERTICAL, command=self.episode_tree.yview)
        self.episode_tree.configure(yscrollcommand=ep_scroll.set)
        self.episode_tree.pack(side=tk.LEFT, fill=tk.BOTH, expand=True)
        ep_scroll.pack(side=tk.RIGHT, fill=tk.Y)
        self.episode_tree.bind("<Button-1>", self.on_episode_click)

        # ================= 标签页2：任务管理 =================
        self.page_tasks = tk.Frame(self.notebook, bg="#f8f9fa")
        self.notebook.add(self.page_tasks, text=" 任务管理 ")

        tk.Label(self.page_tasks, text=f"下载任务列表 (最多 {MAX_CONCURRENT} 个任务并行, 其余排队)",
                 font=font_main, bg="#f8f9fa", fg="#555").pack(anchor=tk.W, padx=15, pady=(12, 4))

        frame_task_tree = tk.Frame(self.page_tasks, bg="#f8f9fa")
        frame_task_tree.pack(fill=tk.BOTH, expand=True, padx=15, pady=(4, 6))
        task_columns = ("sel", "no", "name", "quality", "status", "progress", "speed")
        self.task_tree = ttk.Treeview(frame_task_tree, columns=task_columns, show="headings", height=16,
                                      style="Task.Treeview")
        self.task_tree.heading("sel", text="选择")
        self.task_tree.heading("no", text="序号")
        self.task_tree.heading("name", text="任务名称")
        self.task_tree.heading("quality", text="画质")
        self.task_tree.heading("status", text="状态")
        self.task_tree.heading("progress", text="进度")
        self.task_tree.heading("speed", text="速度")
        self.task_tree.column("sel", width=60, anchor=tk.CENTER, stretch=False)
        self.task_tree.column("no", width=55, anchor=tk.CENTER, stretch=False)
        self.task_tree.column("name", width=380, anchor=tk.W)
        self.task_tree.column("quality", width=110, anchor=tk.CENTER)
        self.task_tree.column("status", width=85, anchor=tk.CENTER)
        self.task_tree.column("progress", width=80, anchor=tk.CENTER)
        self.task_tree.column("speed", width=95, anchor=tk.CENTER)

        # 任务行状态颜色
        self.task_tree.tag_configure(TASK_WAITING, foreground="#6c757d")
        self.task_tree.tag_configure(TASK_DOWNLOADING, foreground="#00aeec")
        self.task_tree.tag_configure(TASK_PAUSED, foreground="#ff9900")
        self.task_tree.tag_configure(TASK_MERGING, foreground="#9966ff")
        self.task_tree.tag_configure(TASK_TRANSCODING, foreground="#9966ff")
        self.task_tree.tag_configure(TASK_COMPLETED, foreground="#28a745")
        self.task_tree.tag_configure(TASK_FAILED, foreground="#dc3545")
        self.task_tree.tag_configure(TASK_CANCELLED, foreground="#adb5bd")
        self.task_tree.tag_configure(TASK_SKIPPED, foreground="#adb5bd")

        task_scroll = ttk.Scrollbar(frame_task_tree, orient=tk.VERTICAL, command=self.task_tree.yview)
        self.task_tree.configure(yscrollcommand=task_scroll.set)
        self.task_tree.pack(side=tk.LEFT, fill=tk.BOTH, expand=True)
        task_scroll.pack(side=tk.RIGHT, fill=tk.Y)
        self.task_tree.bind("<<TreeviewSelect>>", self.on_task_select)
        self.task_tree.bind("<Button-1>", self.on_task_check_click)
        self.task_tree.bind("<Button-3>", self.show_task_menu)
        self.task_tree.bind("<Double-1>", self.on_task_double_click)

        frame_task_btns = tk.Frame(self.page_tasks, bg="#f8f9fa")
        frame_task_btns.pack(fill=tk.X, padx=15, pady=(0, 6))
        # 全选按钮 (最左侧)
        self.btn_select_all = tk.Button(frame_task_btns, text="全选", bg="#00aeec",
                                        width=11, height=1, fg="white", activeforeground="white",
                                        disabledforeground="white",
                                        font=("Microsoft YaHei", 10, "bold"), relief="flat", cursor="hand2",
                                        activebackground="#0093cf",
                                        command=self.toggle_select_all)
        self.btn_select_all.pack(side=tk.LEFT, ipady=2)
        self.btn_pause_resume = tk.Button(frame_task_btns, text="暂停/继续", bg="#ff8c00",
                                          width=11, height=1, fg="white", activeforeground="white",
                                          disabledforeground="white",
                                          font=("Microsoft YaHei", 10, "bold"), relief="flat", cursor="hand2",
                                          state=tk.DISABLED, activebackground="#e67e00",
                                          command=self.toggle_pause_resume)
        self.btn_pause_resume.pack(side=tk.LEFT, padx=(10, 0), ipady=2)
        self.btn_delete_task = tk.Button(frame_task_btns, text="删除任务", bg="#e63946",
                                         width=11, height=1, fg="white", activeforeground="white",
                                         disabledforeground="white",
                                         font=("Microsoft YaHei", 10, "bold"), relief="flat", cursor="hand2",
                                         state=tk.DISABLED, activebackground="#c92a35",
                                         command=self.delete_selected_task)
        self.btn_delete_task.pack(side=tk.LEFT, padx=(10, 0), ipady=2)
        self.btn_clear_done = tk.Button(frame_task_btns, text="清空已完成", bg="#17a2b8",
                                        width=11, height=1, fg="white", activeforeground="white",
                                        disabledforeground="white",
                                        font=("Microsoft YaHei", 10, "bold"), relief="flat", cursor="hand2",
                                        state=tk.DISABLED, activebackground="#138496",
                                        command=self.clear_completed_tasks)
        self.btn_clear_done.pack(side=tk.LEFT, padx=(10, 0), ipady=2)
        self.btn_pause_all = tk.Button(frame_task_btns, text="全部暂停", bg="#ff6699",
                                       width=11, height=1, fg="white", activeforeground="white",
                                       disabledforeground="white",
                                       font=("Microsoft YaHei", 10, "bold"), relief="flat", cursor="hand2",
                                       state=tk.DISABLED, activebackground="#e05588",
                                       command=self.pause_all_tasks)
        self.btn_pause_all.pack(side=tk.LEFT, padx=(10, 0), ipady=2)
        self.btn_resume_all = tk.Button(frame_task_btns, text="全部继续", bg="#2ecc71",
                                        width=11, height=1, fg="white", activeforeground="white",
                                        disabledforeground="white",
                                        font=("Microsoft YaHei", 10, "bold"), relief="flat", cursor="hand2",
                                        state=tk.DISABLED, activebackground="#27ae60",
                                        command=self.resume_all_tasks)
        self.btn_resume_all.pack(side=tk.LEFT, padx=(10, 0), ipady=2)

        tk.Label(self.page_tasks, text="提示：右键已完成任务可打开文件/定位文件夹；右键失败任务可重新下载；双击已完成任务直接打开文件。",
                 font=("Microsoft YaHei", 9), bg="#f8f9fa", fg="#666").pack(anchor=tk.W, padx=15)
        self.lbl_task_summary = tk.Label(self.page_tasks, text="共 0 个任务",
                                         font=("Microsoft YaHei", 10, "bold"), bg="#f8f9fa", fg="#333")
        self.lbl_task_summary.pack(anchor=tk.W, padx=15, pady=(0, 12))

    # ---------- 事件监听 ----------
    def on_input_changed(self, event):
        """当输入框的 URL 或 Cookie 发生变化时，使上一次的分析结果失效"""
        current_url = self.entry_bvid.get().strip()
        current_cookie = self.entry_cookie.get().strip()

        if self.current_bvid is not None:
            if current_url != self.last_analyzed_url or current_cookie != self.last_analyzed_cookie:
                self.current_bvid = None
                self.current_input_key = None
                self.current_title = ""
                self.current_pages = []
                self.btn_download.config(state=tk.DISABLED, text="立即开始下载")

                # 清空画质下拉
                self.cmb_quality.set("")
                self.cmb_quality["values"] = ()
                self.cmb_quality.config(state="disabled")
                self.quality_qn_list = []
                self.quality_names.clear()

                # 清空选集列表
                for item in self.episode_tree.get_children():
                    self.episode_tree.delete(item)
                self.episode_checked.clear()
                self.lbl_episode_count.config(text="")
                self.lbl_episode_title.config(text="")

                # 提示用户
                self.lbl_quality_hint.config(text="⚠️ 链接或 Cookie 已发生变更，请重新【分析视频】", fg="#ff4500")
                self.update_status("等待重新分析...")

    # ---------- 分析与选集 ----------
    def start_analyze_thread(self):
        url = self.entry_bvid.get().strip()
        if not url:
            messagebox.showwarning("提示", "请输入视频链接、BV号或番剧链接！")
            return
        # 仅做初步格式预检, 完整识别(含短链跳转)放到后台线程执行, 避免阻塞界面
        if not re.search(r'(BV[0-9A-Za-z]{10}|av\d+|ep\d+|ss\d+|md\d+|bangumi\.bilibili\.com/anime/\d+|b23\.tv|bilibili\.tv)',
                         url, re.IGNORECASE):
            messagebox.showerror("格式错误", "未识别到合法的 B 站视频或番剧链接！\n\n"
                                            "支持：BV号/av号、bangumi/play/ep或ss链接、bangumi/media/md链接、b23.tv/bilibili.tv 短链")
            return

        self.log("==================================================")
        self.log("🔍 开始分析...")
        self.btn_analyze.config(state=tk.DISABLED, text="分析中...")
        self.btn_download.config(state=tk.DISABLED)

        threading.Thread(target=self.run_analyze_task, args=(url,), daemon=True).start()

    def run_analyze_task(self, key):
        try:
            key = detect_link_type(key)
            if not key:
                self.root.after(0, lambda: messagebox.showerror(
                    "格式错误",
                    "未识别到合法的 B 站视频或番剧链接！\n\n"
                    "支持：BV号/av号、bangumi/play/ep或ss链接、bangumi/media/md链接、b23.tv/bilibili.tv 短链"))
                return
            self.log(f"🔍 识别结果: {key}")
            cookie = self.entry_cookie.get().strip()
            if _key_kind(key) == "video":
                title, pages = self.get_video_info(key, cookie)
                source_label = "分P视频"
            else:
                season_id = int(key[2:]) if key[:2] == "ss" else None
                ep_id = int(key[2:]) if key[:2] == "ep" else None
                media_id = int(key[2:]) if key[:2] == "md" else None
                title, pages = self.get_bangumi_season_info(cookie=cookie, ep_id=ep_id,
                                                            season_id=season_id, media_id=media_id)
                source_label = "番剧/电影"

            self.log(f"📄 视频标题: {title}")
            self.log(f"📋 共 {len(pages)} 集")

            formats = self.get_video_formats(pages[0]["bvid"], pages[0]["cid"], cookie)
            hint = None
            if not formats:
                # 画质列表获取失败(常见: 大会员内容未填 Cookie, playurl 返回 -404)时不中断分析,
                # 降级为常见默认画质, 下载时 precheck 会再次校验真实权限
                formats = [(qn, QN_MAP.get(qn, f"画质代码 {qn}")) for qn in (80, 64, 32, 16)]
                hint = "⚠️ 未能获取画质列表，该内容可能需要登录/大会员，请填写 Cookie 后重新分析。"

            # 记录成功的状态
            self.current_bvid = key
            self.current_input_key = key
            self.current_title = title
            self.current_pages = pages

            self.root.after(0, self.update_analysis_ui, title, pages, formats, source_label, hint)
        except Exception as e:
            self.root.after(0, self.log, f"❌ 分析失败: {str(e)}")
            self.root.after(0, messagebox.showerror, "分析失败", str(e))
            self.current_bvid = None
            self.current_input_key = None
        finally:
            self.root.after(0, lambda: self.btn_analyze.config(state=tk.NORMAL, text="🔍 分析视频"))

    def get_video_info(self, key, cookie=""):
        """获取普通视频 (BV/av) 的标题与分P列表"""
        url = "https://api.bilibili.com/x/web-interface/view"
        headers = HEADERS.copy()
        if cookie:
            headers["Cookie"] = cookie
        params = {"bvid": key}
        if key[:2].lower() == "av":
            params = {"aid": int(key[2:])}

        resp = requests.get(url, params=params, headers=headers, timeout=10).json()
        code = resp.get("code", -1)
        if code != 0 and key[:2].upper() == "BV" and code == -400:
            # 部分情况下接口对 BV 大小写敏感，用另一种大小写重试一次
            alt_key = key.lower() if key == key.upper() else key.upper()
            resp2 = requests.get(url, params={"bvid": alt_key}, headers=headers, timeout=10).json()
            if resp2.get("code") == 0:
                resp = resp2
                code = 0

        if code != 0:
            msg = resp.get("message", "获取视频详情失败")
            raise Exception(
                "未能获取视频信息，请检查链接是否正确、视频是否已删除/失效，"
                "或该视频是否需登录后观看。\n"
                f"(接口返回: code={code}, {msg})"
            )
        if not isinstance(resp.get("data"), dict):
            raise Exception("接口返回数据异常，请稍后重试或检查链接。")

        data = resp["data"]
        bvid = data.get("bvid", key)
        pages = []
        for p in data.get("pages", []):
            pages.append({
                "bvid": bvid,
                "cid": p.get("cid"),
                "page": p.get("page", len(pages) + 1),
                "part": p.get("part", f"第{len(pages) + 1}集"),
                "duration": p.get("duration", 0),
                "kind": "video",
            })
        if not pages:
            pages = [{
                "bvid": bvid,
                "cid": data.get("cid"),
                "page": 1,
                "part": data.get("title", "视频"),
                "duration": data.get("duration", 0),
                "kind": "video",
            }]
        return data.get("title", "未命名视频"), pages

    def get_bangumi_season_info(self, cookie="", ep_id=None, season_id=None, media_id=None):
        """获取番剧/电影整季信息：md 先解析出 season_id，再通过 season 接口取正片列表"""
        headers = HEADERS.copy()
        if cookie:
            headers["Cookie"] = cookie

        if media_id is not None:
            resp = requests.get("https://api.bilibili.com/pgc/review/user",
                                params={"media_id": media_id}, headers=headers, timeout=10).json()
            if resp.get("code") != 0:
                raise Exception(resp.get("message", "无法解析该 md 链接的番剧信息。"))
            media = resp.get("result", {}).get("media") or {}
            season_id = media.get("season_id")
            if not season_id:
                raise Exception("该 md 链接未找到对应的番剧季信息。")

        params = {}
        if season_id is not None:
            params["season_id"] = season_id
        elif ep_id is not None:
            params["ep_id"] = ep_id
        else:
            raise Exception("缺少番剧 season_id/ep_id 参数。")

        resp = requests.get("https://api.bilibili.com/pgc/view/web/season",
                            params=params, headers=headers, timeout=10).json()
        if resp.get("code") != 0:
            raise Exception(resp.get("message", "获取番剧信息失败，请检查链接或 Cookie。"))

        result = resp.get("result") or {}
        title = result.get("title") or result.get("season_title") or "未命名番剧"
        episodes = result.get("episodes") or []
        if not episodes:
            raise Exception("该番剧暂无可下载的正片集数。")

        pages = []
        for idx, ep in enumerate(episodes, start=1):
            bvid = ep.get("bvid")
            cid = ep.get("cid")
            if not bvid or not cid:
                continue
            ep_title = str(ep.get("title") or "").strip()
            long_title = str(ep.get("long_title") or "").strip()
            if ep_title and long_title:
                part = f"{ep_title} {long_title}".strip()
            else:
                part = ep_title or long_title or f"第{idx}集"
            pages.append({
                "bvid": bvid,
                "cid": cid,
                "page": idx,
                "part": part,
                "duration": ep.get("duration") or 0,
                "kind": "bangumi",
                "ep_id": ep.get("id"),
            })

        if not pages:
            raise Exception("正片数据缺少 bvid/cid，无法下载。")
        return title, pages

    def get_video_formats(self, bvid, cid, cookie=""):
        """获取可用画质列表 [(qn, desc), ...]; 接口失败(如大会员内容未登录返回 -404)时返回 None"""
        url = "https://api.bilibili.com/x/player/playurl"
        params = {"bvid": bvid, "cid": cid, "qn": 127, "fnval": 4048, "fourk": 1}
        headers = HEADERS.copy()
        if cookie:
            headers["Cookie"] = cookie
        try:
            resp = requests.get(url, params=params, headers=headers, timeout=10).json()
        except Exception:
            return None
        if resp.get("code") == 0:
            data = resp.get("data") or {}
            q_list = data.get("accept_quality", [])
            d_list = data.get("accept_description", [])
            if q_list and d_list:
                return list(zip(q_list, d_list))
        return None

    def update_analysis_ui(self, title, pages, formats, source_label, hint=None):
        # 记录本次成功解析时，输入框的内容（防错位核心基准）
        self.last_analyzed_url = self.entry_bvid.get().strip()
        self.last_analyzed_cookie = self.entry_cookie.get().strip()

        self.update_quality_ui(formats, hint=hint)
        self.update_episode_ui(title, pages, source_label)
        self.log(f"✅ 分析完成，共 {len(pages)} 集，{len(formats)} 种画质可用。" + (f"；{hint}" if hint else ""))
        self.save_config()

    def update_quality_ui(self, formats, hint=None):
        self.quality_qn_list = []
        self.quality_names.clear()

        for qn, desc in formats:
            self.quality_names[qn] = desc
            self.quality_qn_list.append(qn)

        # 填充画质下拉框: 每项带权限提示 (需大会员/需登录/免登录)
        self.cmb_quality["values"] = [
            f"{self.quality_names.get(qn, f'画质代码 {qn}')} {self._req_text(qn)}"
            for qn in self.quality_qn_list
        ]
        if self.quality_qn_list:
            self.cmb_quality.current(0)

        # 视频模式启用画质下拉, 音频模式禁用
        self.cmb_quality.config(state="readonly" if self.download_mode.get() == 0 else "disabled")

        if hint:
            self.lbl_quality_hint.config(text=hint, fg="#ff9900")
        else:
            self.lbl_quality_hint.config(
                text="根据视频支持情况动态获取; 需登录/大会员的画质需填写 Cookie", fg="#888")

    def update_episode_ui(self, title, pages, source_label):
        for item in self.episode_tree.get_children():
            self.episode_tree.delete(item)
        self.episode_checked.clear()

        for idx, p in enumerate(pages):
            iid = f"ep{idx}"
            dur_str = format_duration(p.get("duration", 0))
            checked = "☑" if idx == 0 else "☐"
            if p.get("kind") == "bangumi":
                page_label = f"第{p['page']}集"
            else:
                page_label = f"P{p['page']}"
            self.episode_tree.insert("", tk.END, iid=iid, values=(checked, page_label, p["part"], dur_str))
            self.episode_checked[iid] = (idx == 0)

        self.frame_episodes.config(text=" 选集列表 (可多选, 默认勾选第一集) ")
        self.lbl_episode_title.config(text=f"视频标题: {title} （{source_label}）")
        self.lbl_episode_count.config(text=f"共 {len(pages)} 集 | 已选 {sum(1 for v in self.episode_checked.values() if v)} 集")
        self.update_download_btn_text()

    def on_episode_click(self, event):
        iid = self.episode_tree.identify_row(event.y)
        if not iid:
            return
        # 只有点击第一列「选择」的勾选框区域才切换勾选状态
        if self.episode_tree.identify_column(event.x) != "#1":
            return
        self.episode_checked[iid] = not self.episode_checked.get(iid, False)
        self.episode_tree.set(iid, "sel", "☑" if self.episode_checked[iid] else "☐")
        self.lbl_episode_count.config(text=f"共 {len(self.current_pages)} 集 | 已选 {sum(1 for v in self.episode_checked.values() if v)} 集")
        self.update_download_btn_text()

    def select_all_episodes(self):
        for iid in self.episode_tree.get_children():
            self.episode_checked[iid] = True
            self.episode_tree.set(iid, "sel", "☑")
        self.lbl_episode_count.config(text=f"共 {len(self.current_pages)} 集 | 已选 {len(self.episode_checked)} 集")
        self.update_download_btn_text()

    def clear_episode_selection(self):
        for iid in self.episode_tree.get_children():
            self.episode_checked[iid] = False
            self.episode_tree.set(iid, "sel", "☐")
        self.lbl_episode_count.config(text=f"共 {len(self.current_pages)} 集 | 已选 0 集")
        self.update_download_btn_text()

    def _on_mode_change(self):
        """切换下载模式时启用/禁用音频格式与画质下拉框"""
        if self.download_mode.get() == 1:
            self.cmb_audio_format.config(state="readonly")
            self.cmb_quality.config(state="disabled")
        else:
            self.cmb_audio_format.config(state="disabled")
            self.cmb_quality.config(state="readonly" if self.quality_qn_list else "disabled")

    def _selected_audio_format(self):
        """读取下拉框选中的音频格式: 'm4a' / 'mp3'"""
        return "mp3" if self.cmb_audio_format.current() == 1 else "m4a"

    def _req_text(self, qn):
        """画质档位的权限提示"""
        if qn >= 112 or qn == 74:
            return "(需大会员)"
        if qn >= 64:
            return "(需登录)"
        return "(免登录)"

    def _selected_quality_qn(self):
        """读取画质下拉框当前选中的 qn"""
        idx = self.cmb_quality.current()
        if 0 <= idx < len(self.quality_qn_list):
            return self.quality_qn_list[idx]
        return 80

    def update_download_btn_text(self):
        n = sum(1 for v in self.episode_checked.values() if v)
        if n and self.current_bvid is not None:
            self.btn_download.config(state=tk.NORMAL, text=f"立即下载所选 ({n} 集)")
        else:
            self.btn_download.config(state=tk.DISABLED, text="请先分析并勾选集数")

    # ---------- 下载任务调度 ----------
    def start_download_thread(self):
        """下载入口：防止重复点击导致重复创建任务"""
        if self._download_clicked:
            return
        self._download_clicked = True
        self.btn_download.config(state=tk.DISABLED)
        try:
            self._do_start_download()
        finally:
            self._download_clicked = False
            self.update_download_btn_text()

    def _do_start_download(self):
        current_input_url = self.entry_bvid.get().strip()
        key = detect_link_type(current_input_url)

        if not self.current_bvid or key != self.current_input_key:
            messagebox.showwarning("提示", "当前链接与上次分析的视频不一致，请重新点击【分析视频】！")
            return

        path = self.entry_path.get().strip()
        if not os.path.exists(path):
            messagebox.showwarning("提示", "保存路径不存在，请重新选择！")
            return

        selected = [p for idx, p in enumerate(self.current_pages) if self.episode_checked.get(f"ep{idx}", False)]
        if not selected:
            messagebox.showwarning("提示", "请至少勾选一集！")
            return

        qn = self._selected_quality_qn()
        cookie = self.entry_cookie.get().strip()
        mode = "audio" if self.download_mode.get() == 1 else "video"
        audio_format = self._selected_audio_format()

        if mode == "audio":
            # 仅音频模式: 使用免登录基础画质取流, 不受视频画质权限影响, 也无需画质预检
            qn = 16
            quality_name = f"仅音频 ({audio_format.upper()})"
        else:
            # 画质预检：权限不足/画质降级时直接弹窗拦截，不进入确认与任务流程
            if not self.precheck_download(selected[0]["bvid"], selected[0]["cid"], qn, cookie):
                return

            quality_name = self.quality_names.get(qn) or QN_MAP.get(qn, f"画质代码 {qn}")

        self.show_download_confirm(selected, quality_name, path, mode=mode, audio_format=audio_format)

    def precheck_download(self, bvid, cid, qn, cookie):
        """下载前预检画质权限。返回 True 表示可下载，False 表示已弹窗说明并中止。"""
        try:
            fetch_playurl(bvid, cid, qn, cookie)
            return True
        except QualityPermissionError as e:
            messagebox.showwarning(
                "无法按所选画质下载",
                f"{e}\n\n已取消本次下载。\n请检查 Cookie 或降低画质后重试。"
            )
            self.log(f"⏭️ 下载预检失败（权限）: {e}")
            return False
        except Exception as e:
            messagebox.showerror(
                "下载预检失败",
                f"无法获取视频流：{e}\n\n已取消本次下载，请稍后重试。"
            )
            self.log(f"❌ 下载预检失败: {e}")
            return False

    def show_download_confirm(self, selected, quality_name, path, mode="video", audio_format="m4a"):
        """弹出确认窗口展示所选集数与画质；确认后创建任务并跳转任务管理页"""
        qn = self._selected_quality_qn()
        if mode == "audio":
            qn = 16
        cookie = self.entry_cookie.get().strip()

        dlg = tk.Toplevel(self.root)
        dlg.title("确认下载")
        dlg.configure(bg="#f8f9fa")
        dlg.transient(self.root)
        dlg.grab_set()
        dlg.resizable(False, False)
        dlg.update_idletasks()
        x = self.root.winfo_rootx() + max((self.root.winfo_width() - 600) // 2, 0)
        y = self.root.winfo_rooty() + max((self.root.winfo_height() - 520) // 2, 0)
        dlg.geometry(f"600x520+{x}+{y}")

        font_main = ("Microsoft YaHei", 10)
        pad = 16

        tk.Label(dlg, text="请确认以下下载信息：", font=("Microsoft YaHei", 12, "bold"),
                 bg="#f8f9fa", fg="#00aeec").pack(anchor=tk.W, padx=pad, pady=(14, 6))
        info = f"视频: {self.current_title}\n{'类型' if mode == 'audio' else '画质'}: {quality_name}\n保存路径: {path}"
        tk.Label(dlg, text=info, font=font_main, bg="#f8f9fa", fg="#333",
                 justify=tk.LEFT, anchor=tk.W).pack(anchor=tk.W, padx=pad)

        tk.Label(dlg, text=f"已选 {len(selected)} 集：", font=font_main, bg="#f8f9fa", fg="#333"
                 ).pack(anchor=tk.W, padx=pad, pady=(10, 4))

        txt = scrolledtext.ScrolledText(dlg, height=12, font=("Microsoft YaHei", 9), state="disabled",
                                        relief="flat", highlightthickness=1, highlightbackground="#ced4da")
        txt.pack(fill=tk.BOTH, expand=True, padx=pad, pady=(0, 10))
        txt.configure(state="normal")
        for p in selected:
            if p.get("kind") == "bangumi":
                label = f"第{p['page']}集  {p['part']}"
            else:
                label = f"P{p['page']}  {p['part']}"
            txt.insert(tk.END, label + "\n")
        txt.configure(state="disabled")

        frame_btns = tk.Frame(dlg, bg="#f8f9fa")
        frame_btns.pack(fill=tk.X, padx=pad, pady=(0, 14))
        tk.Button(frame_btns, text="确认下载", bg="#00aeec", fg="white", font=font_main, relief="flat",
                  cursor="hand2", command=lambda: (dlg.destroy(), self._confirmed_download(selected, qn, cookie, quality_name, path, mode, audio_format))
                  ).pack(side=tk.RIGHT, ipadx=20, ipady=4)
        tk.Button(frame_btns, text="取消", bg="#6c757d", fg="white", font=font_main, relief="flat",
                  cursor="hand2", command=dlg.destroy).pack(side=tk.RIGHT, padx=(0, 10), ipadx=20, ipady=4)

        dlg.wait_window()

    def _confirmed_download(self, selected, qn, cookie, quality_name, path, mode="video", audio_format="m4a"):
        # 任务特征: 视频模式按画质 qn 区分, 音频模式按音频格式区分,
        # 使同一视频的 视频/音频/不同画质/不同音频格式 可并存, 仅完全相同特征视为重复
        existing = {(t.bvid, t.cid, t.mode, t.qn if t.mode == "video" else t.audio_format) for t in self.tasks}
        selected = [p for p in selected
                    if (p["bvid"], p["cid"], mode, qn if mode == "video" else audio_format) not in existing]
        if not selected:
            messagebox.showinfo("提示", "所选内容均已存在于下载列表，无需重复添加。")
            return
        self._completion_popup_shown = False
        for p in selected:
            task = DownloadTask(
                self, p["bvid"], self.current_title,
                p["page"], p["cid"], p["part"], len(self.current_pages),
                path, qn, cookie,
                quality_name=quality_name, source_kind=p.get("kind", "video"),
                mode=mode, audio_format=audio_format
            )
            self.tasks.append(task)
            self.add_task_row(task)

        self.log(f"📥 已添加 {len(selected)} 个下载任务，开始调度...")
        self.update_overview()
        self.start_pending_downloads()
        self.notebook.select(self.page_tasks)

    def add_task_row(self, task):
        self._task_iid_seq += 1
        iid = f"task{self._task_iid_seq}"
        task.iid = iid
        self.task_tree.insert("", tk.END, iid=iid,
                              values=("☐", len(self.tasks), task.display_name(), task.quality_name, task.state,
                                      task.progress_text(), task.speed_str),
                              tags=(task.state,))
        self.btn_clear_done.config(state=tk.NORMAL if any(t.is_terminal() for t in self.tasks) else tk.DISABLED)
        self.update_task_summary()

    def start_pending_downloads(self):
        # 并发槽位只计真正占用网络下载的任务; 转码/合并不占下载名额, 保证下载始终满负荷进行
        active = sum(1 for t in self.tasks if t.state == TASK_DOWNLOADING)
        slots = MAX_CONCURRENT - active
        for t in self.tasks:
            if slots <= 0:
                break
            if t.state == TASK_WAITING:
                t.start()
                slots -= 1

    def update_task_row(self, task):
        if task.iid and self.task_tree.exists(task.iid):
            try:
                no = self.tasks.index(task) + 1
            except ValueError:
                no = ""
            check = "☑" if task.checked else "☐"
            self.task_tree.item(task.iid,
                                values=(check, no, task.display_name(), task.quality_name, task.state,
                                        task.progress_text(), task.speed_str),
                                tags=(task.state,))
        self.update_overview()
        self.update_task_summary()

    def _renumber_task_rows(self):
        """删除任务后按当前列表顺序重新编号"""
        for i, iid in enumerate(self.task_tree.get_children(), start=1):
            vals = list(self.task_tree.item(iid, "values"))
            if vals:
                vals[1] = i
                self.task_tree.item(iid, values=tuple(vals))

    def update_task_summary(self):
        total = len(self.tasks)
        active = sum(1 for t in self.tasks if t.is_active())
        done = sum(1 for t in self.tasks if t.state == TASK_COMPLETED)
        failed = sum(1 for t in self.tasks if t.state == TASK_FAILED)
        cancelled = sum(1 for t in self.tasks if t.state == TASK_CANCELLED)
        self.lbl_task_summary.config(
            text=f"共 {total} 个任务 | 进行中 {active} | 已完成 {done} | 失败 {failed} | 已取消 {cancelled}"
        )
        self.btn_clear_done.config(state=tk.NORMAL if done or failed or cancelled else tk.DISABLED)
        self.btn_pause_all.config(
            state=tk.NORMAL if any(t.state in (TASK_WAITING, TASK_DOWNLOADING) for t in self.tasks) else tk.DISABLED
        )
        self.btn_resume_all.config(
            state=tk.NORMAL if any(t.state == TASK_PAUSED for t in self.tasks) else tk.DISABLED
        )
        checked = [t for t in self.tasks if t.checked]
        self.btn_pause_resume.config(
            state=tk.NORMAL if any(t.state in (TASK_PAUSED, TASK_WAITING, TASK_DOWNLOADING) for t in checked) else tk.DISABLED
        )
        self.btn_delete_task.config(state=tk.NORMAL if any(t.checked for t in self.tasks) else tk.DISABLED)
        self.update_select_all_btn()

    def update_overview(self):
        """进度条与状态栏已移除，保留方法以兼容调用处"""
        pass

    def on_task_finished(self, task):
        self.root.after(0, self._handle_task_finished, task)
        self.root.after(0, self.start_pending_downloads)

    def _handle_task_finished(self, task):
        if task.state == TASK_SKIPPED:
            # 权限不足：直接移除任务，并弹窗解释原因
            self._skipped_pending.append(f"• {task.display_name()}: {task.error}")
            self._remove_task(task)
            if not self._skipped_popup_scheduled:
                self._skipped_popup_scheduled = True
                self.root.after(500, self._flush_skipped_popup)
            return
        if task.state == TASK_FAILED and task.error:
            messagebox.showerror("下载失败", f"{task.display_name()}\n\n{task.error}")
        self.update_task_row(task)
        self.check_completion_popup()

    def _flush_skipped_popup(self):
        """汇总权限不足的任务并一次性弹窗说明原因，避免多个任务弹出多个窗口"""
        self._skipped_popup_scheduled = False
        if not self._skipped_pending:
            return
        messages = self._skipped_pending
        self._skipped_pending = []
        messagebox.showwarning(
            "部分任务未下载",
            "以下任务因画质权限不足未能开始下载：\n"
            "（可能原因：未填写 Cookie、Cookie 已过期，或该内容需要大会员）\n\n"
            + "\n".join(messages)
        )

    def check_completion_popup(self):
        if self._completion_popup_shown:
            return
        if self.tasks and all(t.is_terminal() for t in self.tasks):
            done = sum(1 for t in self.tasks if t.state == TASK_COMPLETED)
            if done:
                self._completion_popup_shown = True
                messagebox.showinfo("下载完成", f"全部任务已结束：成功 {done} 个。\n已在【任务管理】页保留结果，可右键打开文件。")

    # ---------- 任务管理页操作 ----------
    def _task_by_iid(self, iid):
        for t in self.tasks:
            if t.iid == iid:
                return t
        return None

    def _remove_task(self, task):
        if task.iid and self.task_tree.exists(task.iid):
            self.task_tree.delete(task.iid)
        if task in self.tasks:
            self.tasks.remove(task)
        self._renumber_task_rows()
        self.update_overview()
        self.update_task_summary()
        self.on_task_select()

    def on_task_select(self, event=None):
        """行高亮仅用于展示; 暂停/继续与删除按钮状态由 update_task_summary 按勾选状态统一管理"""
        pass

    def toggle_pause_resume(self):
        """暂停/继续: 对已勾选的任务, 暂停的继续, 等待/下载中的暂停"""
        checked = [t for t in self.tasks if t.checked]
        if not checked:
            return
        for t in checked:
            if t.state == TASK_PAUSED:
                t.request_resume()
            elif t.state in (TASK_WAITING, TASK_DOWNLOADING):
                t.request_pause()
        self.update_task_summary()

    def pause_all_tasks(self):
        for t in self.tasks:
            if t.state in (TASK_WAITING, TASK_DOWNLOADING):
                t.request_pause()
        self.update_task_summary()

    def resume_all_tasks(self):
        for t in self.tasks:
            if t.state == TASK_PAUSED:
                t.request_resume()
        self.update_task_summary()

    def on_task_check_click(self, event):
        """点击任务列表第一列「选择」勾选框时切换勾选状态"""
        iid = self.task_tree.identify_row(event.y)
        if not iid:
            return
        if self.task_tree.identify_column(event.x) != "#1":
            return
        task = self._task_by_iid(iid)
        if not task:
            return
        task.checked = not task.checked
        self.task_tree.set(iid, "sel", "☑" if task.checked else "☐")
        self.update_task_summary()

    def toggle_select_all(self):
        """全选/取消全选: 若存在未勾选任务则全部勾选, 否则全部取消"""
        if not self.tasks:
            return
        all_checked = all(t.checked for t in self.tasks)
        for t in self.tasks:
            t.checked = not all_checked
            if t.iid and self.task_tree.exists(t.iid):
                self.task_tree.set(t.iid, "sel", "☑" if t.checked else "☐")
        self.update_task_summary()

    def update_select_all_btn(self):
        """根据勾选状态同步全选按钮文字"""
        if not self.tasks:
            self.btn_select_all.config(text="全选")
            return
        if all(t.checked for t in self.tasks):
            self.btn_select_all.config(text="取消全选")
        else:
            self.btn_select_all.config(text="全选")

    def delete_selected_task(self):
        tasks = [t for t in self.tasks if t.checked]
        if not tasks:
            messagebox.showinfo("提示", "请先在列表中勾选要删除的任务。")
            return
        active = [t for t in tasks if t.is_active()]
        if active:
            if not messagebox.askyesno(
                "取消下载",
                f"确定要取消 {len(active)} 个进行中的任务并删除吗？\n未完成的部分文件将被清理。",
                icon="warning"
            ):
                return
        for t in tasks:
            t.cancel()
        for t in tasks:
            self._remove_task(t)
        self.start_pending_downloads()

    def clear_completed_tasks(self):
        to_remove = [t for t in self.tasks if t.is_terminal()]
        for t in to_remove:
            self._remove_task(t)
        self.start_pending_downloads()

    # ---------- 右键菜单与打开文件 ----------
    def show_task_menu(self, event):
        iid = self.task_tree.identify_row(event.y)
        if not iid:
            return
        self.task_tree.selection_set(iid)
        task = self._task_by_iid(iid)
        if not task:
            return
        # 右键即视为选择该任务: 清空其他勾选, 仅勾选当前任务, 删除/暂停只作用于它
        for t in self.tasks:
            t.checked = (t is task)
            if t.iid and self.task_tree.exists(t.iid):
                self.task_tree.set(t.iid, "sel", "☑" if t.checked else "☐")
        self.update_task_summary()

        menu = tk.Menu(self.root, tearoff=0)
        if task.state == TASK_COMPLETED and task.final_output and os.path.exists(task.final_output):
            menu.add_command(label="打开文件", command=lambda: self.open_task_file(task))
            menu.add_command(label="打开文件所在文件夹", command=lambda: self.open_task_folder(task))
        elif task.state in (TASK_FAILED, TASK_CANCELLED):
            menu.add_command(label="重新下载", command=lambda: self.redownload_task(task))
        elif task.state == TASK_PAUSED:
            menu.add_command(label="继续下载", command=task.request_resume)
        elif task.state in (TASK_WAITING, TASK_DOWNLOADING):
            menu.add_command(label="暂停下载", command=task.request_pause)

        menu.add_separator()
        menu.add_command(label="删除任务", command=self.delete_selected_task)
        try:
            menu.tk_popup(event.x_root, event.y_root)
        finally:
            menu.grab_release()

    def on_task_double_click(self, event):
        iid = self.task_tree.identify_row(event.y)
        if not iid:
            return
        task = self._task_by_iid(iid)
        if task and task.state == TASK_COMPLETED and task.final_output and os.path.exists(task.final_output):
            self.open_task_file(task)

    def open_task_file(self, task):
        try:
            os.startfile(task.final_output)
        except Exception as e:
            self.log(f"❌ 无法打开文件: {e}")

    def open_task_folder(self, task):
        folder = os.path.dirname(task.final_output)
        try:
            # 使用命令行字符串形式传给 explorer，避免参数被二次引号包裹导致定位失败
            subprocess.Popen(f'explorer /select,"{os.path.normpath(task.final_output)}"')
        except Exception:
            try:
                os.startfile(folder)
            except Exception as e:
                self.log(f"❌ 无法打开文件夹: {e}")

    def redownload_task(self, task):
        new_task = DownloadTask(
            self, task.bvid, task.title, task.page_num, task.cid, task.part_title, task.total_pages,
            task.save_dir, task.qn, task.cookie,
            quality_name=task.quality_name, source_kind=task.source_kind,
            mode=task.mode, audio_format=task.audio_format
        )
        self.tasks.append(new_task)
        self.add_task_row(new_task)
        self.log(f"🔄 已重新加入下载: {new_task.display_name()}")
        self.start_pending_downloads()

    # ---------- 窗口关闭 ----------
    def on_close(self):
        active = [t for t in self.tasks if t.is_active()]
        if active:
            if not messagebox.askyesno(
                "退出确认",
                f"有 {len(active)} 个下载任务正在进行，取消全部任务并退出吗？\n未完成的部分文件将被清理。",
                icon="warning"
            ):
                return
            for t in active:
                t.cancel()
            self.root.after(800, self.root.destroy)
        else:
            self.root.destroy()

    # ---------- 工具方法 ----------
    def get_ffmpeg_path(self):
        if hasattr(sys, '_MEIPASS'):
            path = os.path.join(sys._MEIPASS, 'ffmpeg.exe')
            if os.path.exists(path):
                return path
        local_path = os.path.join(os.getcwd(), 'ffmpeg.exe')
        if os.path.exists(local_path):
            return local_path
        return 'ffmpeg'

    def _paste_to(self, entry):
        """从剪贴板粘贴文本到输入框光标处, 并触发输入监听"""
        try:
            text = self.root.clipboard_get()
        except Exception:
            messagebox.showwarning("提示", "剪贴板中没有可粘贴的内容。")
            return
        entry.insert(tk.INSERT, text)
        self.on_input_changed(None)
        if entry is self.entry_cookie:
            self.save_config()

    def _clear_entry(self, entry):
        """清空输入框内容, 并触发输入监听"""
        entry.delete(0, tk.END)
        self.on_input_changed(None)
        if entry is self.entry_cookie:
            self.save_config()

    def _bind_paste_menu(self, entry):
        """为输入框绑定右键粘贴菜单"""
        menu = tk.Menu(self.root, tearoff=0)
        menu.add_command(label="粘贴", command=lambda: self._paste_to(entry))

        def show_menu(e):
            try:
                menu.tk_popup(e.x_root, e.y_root)
            finally:
                menu.grab_release()

        entry.bind("<Button-3>", show_menu)

    def _open_save_path(self):
        """用系统文件管理器打开当前保存路径"""
        path = self.entry_path.get().strip()
        if not os.path.isdir(path):
            messagebox.showwarning("提示", "保存路径不存在，请先选择有效的文件夹。")
            return
        try:
            os.startfile(path)
        except Exception as e:
            messagebox.showerror("错误", f"无法打开文件夹: {e}")

    def select_path(self):
        selected = filedialog.askdirectory(initialdir=self.entry_path.get())
        if selected:
            self.entry_path.delete(0, tk.END)
            self.entry_path.insert(0, selected)
            self.save_config()

    def log(self, text):
        # 控制台已移除，保留方法以兼容各处调用
        pass

    def update_status(self, text):
        # 状态栏已移除，保留方法以兼容各处调用
        pass

    def load_config(self):
        if os.path.exists(self.config_file):
            try:
                with open(self.config_file, 'r', encoding='utf-8') as f:
                    conf = json.load(f)
                    if conf.get("cookie"):
                        self.entry_cookie.insert(0, conf["cookie"])
                    if conf.get("path") and os.path.exists(conf["path"]):
                        self.entry_path.delete(0, tk.END)
                        self.entry_path.insert(0, conf["path"])
                    self.log("📁 历史配置已加载。")
            except Exception:
                pass

    def save_config(self):
        conf = {
            "cookie": self.entry_cookie.get().strip(),
            "path": self.entry_path.get().strip()
        }
        try:
            with open(self.config_file, 'w', encoding='utf-8') as f:
                json.dump(conf, f)
        except Exception:
            pass


if __name__ == "__main__":
    BilibiliDownloaderApp()
