# DyDown

自用的抖音下载器（Android，无 GMS 依赖，纯侧载）。

支持四件事：**单条视频（无水印）· 图集 + BGM · 主页批量 · 直播录制**。

---

## 一、核心设计：不逆向签名

抖音 Web 接口需要 `a_bogus` 签名，算法经常变。这个 App 的做法是**不自己算签名**，
而是复用抖音页面自己的请求环境：

```
用户登录（WebView）
      ↓ cookie 落在 CookieManager
隐藏 WebView 打开 douyin.com
      ↓ 页面里已有 ttwid / 签名器 / 请求环境
① 注入 JS 挂钩 fetch / XMLHttpRequest，捕获页面自己发出的接口响应
② 或在页面上下文里 fetch 目标接口
      ↓ 我们只负责把响应搬回来
```

代价是每次请求多 1–2 秒，换来的是**签名改版基本无感**。真正需要跟版的地方只剩
JSON 字段层级，而 `JsonScan` 用递归找 key，对层级变化有容错。

### 取址通道（按代价从低到高自动降级）

| 通道 | 实现 | 覆盖 | 前提 |
|---|---|---|---|
| A1 分享页 SSR | `iesdouyin.com/share/{video,note}/{id}/` 里的 `_ROUTER_DATA` | 单条视频 / 图集 | cookie（登录后更稳） |
| A2 老接口 | `web/api/v2/aweme/iteminfo` | 单条 | 无（已不稳） |
| B 页面接口 | 抓包 `/aweme/v1/web/...`，或页面上下文 fetch | 详情 / 主页翻页 / 直播流 | 登录态 |
| C yt-dlp | 内置 python + yt-dlp `--dump-single-json` | 单条兜底 | 无 |

几个容易踩的坑，代码里都已处理：

- 分享短链播完会**跳到推荐页**，需沿跳转链回溯出原始 `/video/{id}` 或 `/note/{id}`；
- 图集里的「实况照片」（`aweme_type=68`）真视频在 `images[].video.play_addr`，
  顶层 `video.play_addr` 可能是 BGM 的 MP3；
- 主页抓包会混入 `/aweme/favorite/`（喜欢列表）等**其他作者**的作品，
  因此只认 `/aweme/post/` 响应并按 `author.sec_uid` 二次过滤；
- 直播短链会重定位到 `webcast reflow`，19 位 `room_id` 不是 `web_rid`；
  流地址从 `live.douyin.com/{webRid}` 的 SSR 里取，除还原 `\u0026` 外还必须把
  HTML 实体 `&amp;` 还原成 `&`，否则 `wsSecret` / `wsTime` 鉴权参数只被当成
  `keeptime` 的一部分，CDN 直接返回 403（实测还原后可持续拉流）；
  录制中途流被掐断时仍会落盘已录到的部分，不再整段丢弃。

无水印处理：`/playwm/` → `/play/`，并把 `ratio=720p` 改写为 `1080p`。

---

## 二、目录

```
app/src/main/java/com/dydown/app/
├── core/            Model、JSON 容错扫描、OkHttp + WebView cookie 复用
├── parse/
│   ├── LinkResolver      分享文案 → 作品ID/用户ID/直播间号（含跳转链跟踪）
│   ├── AwemeParser       作品 JSON → MediaMeta（snake/camel 都兼容）
│   ├── SharePageParser   通道 A
│   ├── PageBridge        隐藏 WebView：注入抓包钩子 + 页面内 fetch
│   ├── RouterDataParser  SSR / 抓包响应 → 作品列表（按接口来源与作者过滤）
│   ├── DouyinApi         通道 B：详情 / 主页翻页
│   ├── LivePageParser    直播页 SSR → 房间信息 + 原画 FLV/HLS 流
│   └── ParseRepository   降级总调度
├── ytdlp/YtDlp           通道 C + 应用内更新 yt-dlp
├── download/
│   ├── Downloader        Range 断点续传
│   ├── MediaStoreWriter  写相册（API29+ 用 IS_PENDING，免存储权限）
│   ├── DownloadEngine    视频 / 图集（含 BGM、实况照片）
│   ├── LiveRecorder      直播流按字节落盘
│   └── Ffmpeg            FLV→MP4 无损转封装（尽力而为）
├── service/         DownloadService（前台服务队列）、LiveRecordService
├── data/            Prefs（保存目录等）、Room 任务表 + 独立 history 表、封面缓存
└── ui/              Compose：设置 / 解析 / 批量 / 录播 / 任务 五个标签页
                     （任务页内再分「队列 / 历史」）
```

**为什么用前台服务而不是 WorkManager**：批量下大文件会撞 10 分钟后台任务上限，
前台服务没有这个限制，进度通知本来也要做。

---

## 三、编译

环境：JDK 17（**不要用 24**，AGP 不认）、Gradle 8.10.2（wrapper 已固定）、Android SDK 35。

```bash
# 首次需要 local.properties 指向 SDK（该文件已 gitignore）
echo "sdk.dir=/path/to/Android/Sdk" > local.properties
./gradlew assembleDebug
# 产物 app/build/outputs/apk/debug/app-debug.apk
```

arm64 单架构 debug APK 约 **42MB**（python 11MB + ffmpeg 14MB + Compose）。
上 release 建议开混淆并保留 `com.yausername.**`（见 proguard-rules.pro）。

---

## 四、首次使用

1. 装 APK → 打开 App → 底部导航最左「设置」→「打开抖音网页登录」→ 扫码。
   **这一步不能跳**：不登录的话分享页经常返回空 `item_list`。
   网页登录态只在该 WebView 自己的 `.douyin.com` cookie 中，与手机上的抖音 App 不互通。
2. 「解析」页粘分享文案（短链或长链均可）→ 选清晰度 → 下载。
3. 「批量」页粘主页链接 → 自动滚动加载全部作品 → 全选/勾选 → 下载选中。
   列表会显示 `@作者` 便于核对。
4. 「录播」页粘直播短链或 `live.douyin.com/xxx` → 开始录制（通知栏可停止）。

保存位置在「设置」里可分别配置：

- 视频 / 直播：`Movies/DyDown`（可改，直播落其 `live` 子目录）
- 图集：`Pictures/DyDown`（可改，每个作品一个 `gallery_<id>` 子目录）

### 历史记录

「任务」页顶部可切到「历史」，记录四类事件：`PARSE`（解析成功/失败）、
`DOWNLOAD`（含成品 Uri、大小、结束时间）、`BATCH`（主页与条数）、`LIVE`（时长与大小）。
支持按类型筛选、按标题/作者搜索、打开成品、用**原始分享链接重新解析**、清空记录。
历史存本地 Room 的独立 `history` 表，**不上传**；清空历史只删数据库记录，不删相册文件。
批量下载默认静默跳过已成功的作品，可在「设置」关掉该开关。

设计上有两点值得注意：

- 数据库从 v1 升到 v2 时用显式 `Migration(1,2)`（只建新表 + 回填旧任务），
  不用 `fallbackToDestructiveMigration`，否则覆盖安装会清空原有任务；
- 列表查询用**列投影 + 行数上限**（tasks 800 / history 2000），不 `SELECT *` 全表。
  Android 的 `CursorWindow` 只有 2MB，累计上千行且每行带图集 URL JSON 时，
  Room 遍历到窗口外会抛 `IllegalStateException` 并被 WebView 的异常处理器杀进程。
  下载执行读的是单独的单行查询，不受投影影响。

---

## 五、已知限制（务必先读）

- **需要你自己的登录态**：项目不提供任何账号、Cookie 或绕过登录的能力；
  抖音 Web 端风控和接口结构会变，失效时需要按上面的思路自行跟版。
- **主页批量靠 WebView 滚动 + 抓包**：账号作品特别多时耗时较长；
  API 翻页被风控会自动降级到滚动加载，列表以 `has_more=0` 判定到底。
- **签名挂钩是尽力而为**：`PageBridge.script()` 试的是
  `window.byted_acrawler.frontierSign`；若页面换名，接口会返回空 →
  通道 B 失败。补新的取签名入口即可，不用动 Kotlin。
- **直播录制**默认落 `.flv`（ExoPlayer 能放，相册不认）。App 会尝试用内置
  ffmpeg 转 mp4，但 youtubedl-android 0.14.0 没暴露 execute API，这里是
  「找二进制 + ProcessBuilder」的兜底，失败就保留 flv。要 mp4 可以电脑上转：
  `ffmpeg -i in.flv -c copy -movflags +faststart out.mp4`
- **yt-dlp 更新**走 GitHub，国内网络可能需要代理，失败不影响主链路。
- 内置库只发布到 **0.14.0**（JitPack 上没有更新版本），升级靠应用内更新。

已在 Android 真机上完成单条视频、图集/实况照片、主页批量、直播解析与 UI 的验证；
不同机型、系统版本和抖音版本仍可能有差异，建议第一次只测一条视频。

---

## 六、合规

仅供个人学习与技术研究，用于离线保存自己有权保存的内容。
去水印后二次发布、搬运、商用属于侵权；自动化抓取也违反平台用户协议。
请勿分发此 APK 到应用商店，勿用于批量抓取他人内容。
仓库不含任何账号、Cookie、密钥或签名绕过材料。
