# Bili Web 下载器 (bili-web)

轻量、优雅的 Bilibili 视频/音频在线解析与多画质下载 Web 工具。支持单 P 解析、分 P 批量下载、4K/8K 自动回退、音频转 MP3、Cookie 账号管理与自动化定时清理。

---

## 🛠️ 前置运行环境依赖 (Prerequisites)

换到任意新电脑或服务器上开发运行前，请确认具备以下环境：

1. **Java 开发环境**：`JDK 17` 或更高版本
2. **构建管理工具**：`Maven 3.6+`
3. **音视频处理内核**：**`FFmpeg`**（核心依赖）
   * **Windows 本地开发**：从 [FFmpeg 官方](https://ffmpeg.org/download.html) 或 [Gyan.dev](https://www.gyan.dev/ffmpeg/builds/) 下载 `ffmpeg.exe`，直接放入项目根目录下的 **`tools/`** 文件夹中即可（或加入 Windows 环境变量 `PATH`）；
   * **Linux 云服务器**：直接终端执行 `sudo apt update && sudo apt install -y ffmpeg`；
   * **macOS 开发**：终端执行 `brew install ffmpeg`。

---

## 🚀 本地开发与启动 (Local Quick Start)

### 1. 克隆代码
```bash
git clone https://github.com/TYLAB404/blibliTYWeb.git
cd blibliTYWeb
```

### 2. 准备 FFmpeg
* 若在 Windows 上，确保 `tools/ffmpeg.exe` 存在；
* 若在系统全局已配置 `ffmpeg`，程序会自动识别。

### 3. 本地编译并启动
```bash
# 方式一：直接通过 Maven 启动开发服务器
mvn spring-boot:run

# 方式二：打包成独立 Jar 运行
mvn clean package -DskipTests
java -jar target/bili-web-0.1.0.jar
```
启动成功后，浏览器打开：👉 `http://localhost:8080`

---

## ⚙️ 核心配置说明 (`src/main/resources/application.yml`)

| 配置项 | 默认值 | 说明 |
| :--- | :--- | :--- |
| `server.port` | `8080` | Web 服务监听端口 |
| `app.download-dir` | `./downloads` | 视频临时下载与合成缓存目录 |
| `app.auto-clean-hours` | `24` | 自动清理超过此小时数的历史下载文件 |
| `app.ffmpeg-path` | `""` | 可选自定义 ffmpeg 路径（留空则自动扫描系统 PATH 与 tools 目录） |
| `app.cookie-admin-token` | 哈希值 | 管理后台修改 B 站 Cookie 时的身份认证令牌 |

---

## 🚢 自动化持续交付 (CI/CD)

本项目内置配置了企业级 **GitHub Actions 手动一键部署流水线**（`.github/workflows/deploy.yml`）：

1. 本地完成开发后，直接提交代码并推送到 GitHub：
   ```bash
   git add .
   git commit -m "feat: 新功能描述"
   git push
   ```
2. 在 GitHub 仓库页面进入 **Actions** 标签，点击 **【手动一键部署到云服务器】** ➡️ **【Run workflow】**。
3. GitHub 自动使用纯净云端沙箱拉取依赖、编译打包，并通过加密通道推送至服务器以 PID 文件隔离机制安全平滑重启。

---

## 📂 项目工程目录说明

```text
blibliTYWeb/
├── .github/workflows/deploy.yml  # GitHub Actions 自动化部署流水线配置
├── src/
│   ├── main/
│   │   ├── java/com/tylab/biliweb/
│   │   │   ├── api/             # B 站开放接口交互客户端 (BiliApiClient)
│   │   │   ├── controller/      # Web 接口与后台控制器
│   │   │   ├── model/           # 数据实体与下载任务模型
│   │   │   ├── service/         # 下载核心引擎与自动清理调度服务
│   │   │   └── util/            # FFmpeg 工具链与安全加密工具
│   │   └── resources/
│   │       ├── application.yml  # 项目配置文件
│   │       └── static/index.html # 现代化自适应 Web 前端单页应用
├── tools/                       # 本地第三方辅助程序目录 (存放 Windows ffmpeg.exe)
├── pom.xml                      # Maven 依赖管理与项目构建清单
└── README.md                    # 本项目技术规格与运行说明书
```
