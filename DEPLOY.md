# 部署指南：Cloudflare Tunnel 免费公网访问

把本机运行的 blibliTYWeb 通过 Cloudflare Tunnel 暴露成公网 HTTPS 地址，任何设备（手机/朋友电脑）输入网址即可访问，**无需服务器、无需备案、无需公网 IP**。

```
手机/朋友电脑 ──> https://你的域名 ──> Cloudflare 网络 ──> cloudflared（你电脑）──> localhost:8080 (Spring Boot)
```

## 前提

- 一台常开机的电脑（本机即可，Windows 环境已配好 ffmpeg）
- 一个域名（见步骤 2，最便宜约 10~30 元/年）
- cloudflared 已安装（本机已装好 v2026.7.3）

---

## 步骤 1：注册 Cloudflare 账号

访问 https://dash.cloudflare.com/sign-up 注册（邮箱 + 密码）。

## 步骤 2：买域名（推荐直接在 Cloudflare 买）

1. 登录 Cloudflare 后，左侧菜单 **Domain Registration → Register Domain**
2. 搜索一个便宜的域名（`.xyz` / `.top` / `.online` 等首年约 10~30 元）
3. 直接购买——**在 Cloudflare 买的域名自动就是 Cloudflare 托管**，省去"转移域名"这一步
4. 支持支付宝/银行卡付款

> 也可以去 Namecheap / 阿里云买更便宜的，但买完需要把域名接入 Cloudflare（DNS 托管转移，多一步）。在 Cloudflare 直接买最省事。

## 步骤 3：创建 Tunnel（网页操作，约 2 分钟）

1. 打开 https://one.dash.cloudflare.com （Cloudflare Zero Trust 面板）
2. 左侧 **Networks → Tunnels → Create a tunnel**
3. 类型选 **Cloudflared**，起个名字如 `biliweb`，点 Save tunnel
4. 网页会给出**安装命令**，其中包含你的 tunnel token（形如 `cloudflared service install eyJ...`）
5. **先不要运行**，记下这个 token（后面步骤 5 用）

## 步骤 4：配置公网主机名（Public Hostname）

创建 tunnel 后网页会引导你添加 Public Hostname：

- **Subdomain**：填一个不常见的子域名，如 `bili`（注意：小圈子自用建议别用太好记的名字，减少被陌生人发现/扫描的概率）
- **Domain**：选你买的域名
- **Service Type**：`HTTP`
- **URL**：`localhost:8080`
- 保存

## 步骤 5：本机启动 tunnel

打开 PowerShell，运行：

```powershell
cloudflared tunnel run --token <第3步记下的token>
```

看到 `Registered tunnel connection` 且无报错，说明 tunnel 已连上。

> **开机自启（可选）**：`cloudflared service install <token>` 可注册成 Windows 服务，开机自动运行。

## 步骤 6：启动应用

另开一个 PowerShell（应用和 tunnel 两个进程都要跑）：

```powershell
cd C:\Users\Admin\Desktop\CodeWork\blibliTYWeb
mvn spring-boot:run
```

> 更稳的方式：先打成 jar 再后台运行，见下方"打包运行"。

## 步骤 7：验证

浏览器访问 `https://<你的子域名>.<你的域名>`，能打开下载器页面即部署成功。手机用流量访问试试（验证不依赖局域网）。

---

## 打包运行（推荐，替代 mvn spring-boot:run）

```powershell
cd C:\Users\Admin\Desktop\CodeWork\blibliTYWeb
mvn clean package -DskipTests
java -jar target/bili-web-0.1.0.jar
```

`target/` 下的 jar 包含全部代码 + 前端页面，可拷贝到任何装了 JDK8 的机器运行（Windows 需带 ffmpeg，Linux 需装 Linux 版 ffmpeg 并改 `application.yml`）。

## 日常维护

- **Cookie**：页面右上角"Cookie 设置"输入管理口令（`application.yml` 的 `app.cookie-admin-token`）即可更新，无需重启
- **Cookie 过期**：B 站 SESSDATA 约 1~6 个月过期，页面状态栏提示失效后重新配置
- **更新代码**：`git pull` 后重新 `mvn package` 并重启 jar
- **下载文件**：在 `downloads/` 目录，记得定期清理

## 安全提醒（重要）

- 你的域名是**公网可达**的，任何知道网址的人都能访问下载器
- 小圈子自用：**别把网址发到公开渠道**；子域名用不常见的名字
- **个人 B 站 Cookie 风险**：公网暴露 + 陌生访问会放大账号风险（见之前讨论）。若以后要开放给陌生人，务必先加访问保护（Cloudflare Access 免费版可加邮箱白名单，或项目里加访问口令），**不要用主账号 Cookie**
- `config/cookie.json` 和 `application.yml` 里的管理口令**不要提交到 git**（本仓库已 gitignore，提交前注意）

## 可选加分项：GitHub Actions 自动打包

本仓库已关联 GitHub（`TYLAB404/blibliTYWeb`）。可在仓库加一个 workflow：每次 push 自动 `mvn package` 产出 jar 附件，省去本地打包。需要时可再配。
