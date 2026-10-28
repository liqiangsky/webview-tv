# 电视直播（Android TV · WebView）

用系统 WebView 打开直播网站的 Android TV 应用（无第三方内核依赖）。
底层一个全屏 WebView 常驻播放，打开应用即播第一个频道；按 OK / 点屏呼出沉浸式频道面板换台，
面板打开时视频继续播放。全程不针对任何具体网站写选择器或做定制劫持。

## 界面与交互

- **播放层**：全屏 WebView（`activity_main.xml` 的 `webView`），横屏、沉浸式、屏蔽网页自身的按键和触摸
- **频道面板**（`panel_overlay.xml`，按 OK / 上下键 / 点屏呼出）：
  - 顶部 Coverflow 分组栏：居中放大、两侧压暗、自动吸附（`CoverflowScrollView` + `item_group_panel.xml`）
  - 组内频道列表：在播频道带声波角标（`BadgeIconView` + `item_panel_channel.xml`）
  - 左侧信息栏（`panel_info.xml`）：台标（文字/图片自动切换）、在播·预览状态、清晰度、频道介绍、节目单在播/下档 + 进度条、系统时间
- **按键**：面板内左右换组、上下选台、OK 切台并保持面板打开（可连续换台）；Back 顺序为 频道列表 → 分组栏 → 收起面板
- **焦点与播放解耦**：光标停在哪个台就预览哪个台的信息，切台不影响浏览位置；分组位置记在 `SharedPreferences(panel_focus)`
- 面板打开时底层视频实时高斯模糊（API 31+ `RenderEffect`，低版本跳过）

## 播放链路

1. **换台重建实例**（`recreateWebView()`）：复用旧实例会残留上一个站点的文档，导致新页面起不了播、铺不满屏
2. **全黑过渡遮罩**：打开页面前盖住正在加载的网页，等视频真正出首帧（`requestVideoFrameCallback`）才 700ms 渐隐；40 秒兜底强制收起
3. **自动全屏**（`APPLY_FS_JS`，`videoWatch` 每 800ms 轮询）：挑页面上面积最大且 src 可播（`blob:` 或 m3u8/flv/mp4，含同源 iframe）的 `video`，用 CSS 铺满视口——不依赖浏览器全屏 API、不需要用户手势
4. **播放重试**（`tryPlay`）：有些播放器第一次 `play()` 不生效，没播起来就隔 100ms 再 `play()`，最多 5 次
5. **看门狗**（`fsWatch`）：播放器重写行内样式后重新落实全屏，并跟随切换到正在播的 `video`
6. 网页自己请求真全屏（`onShowCustomView`）时，全屏视图插到面板之下——视频全屏状态下仍能正常呼出面板

## 网络与资源策略

- **不加载样式类资源**：`BLOCK_STYLE_RESOURCES` 拦掉网页的 CSS / 图片 / 字体 / manifest（按 `Sec-Fetch-Dest` 判断，老内核按扩展名兜底），视频与 JS 一律放行
- **埋点整域拦截**：`TRACKING_HOSTS`（GA、hm.baidu.com、umeng、cnzz、sensorsdata…）返回空响应，播放器不感知
- **静态脚本磁盘缓存**（`SERVE_FROM_SCRIPT_CACHE`）：脚本请求未命中缓存就当场下载并留存，下次秒回；按域名分组，单文件上限 6MB、总预算 80MB，文件名带内容哈希/版本号的按不可变资源缓存 7 天；回灌时补齐 CORS 响应头，否则 `crossorigin` 脚本会被内核判加载失败
- **错误页**：主文档加载失败跳本地 [error.html](app/src/main/assets/error.html)（重试 / 回到首页，后者用 `tvhome://` 协议通知原生层）
- PC 桌面 UA（`TV_USER_AGENT`，网站才会下发电脑版而不是 App 版）、`mediaPlaybackRequiresUserGesture=false`、允许混合内容、放行第三方 Cookie

## 频道表

格式（一行一个台，`#` 开头为注释）：

```
央视频道,#genre#
CCTV1,https://tv.cctv.com/live/cctv1/
CCTV13(央视新闻),https://m-live.cctvnews.cctv.com/live/landscape.html?liveRoomNumber=xxx,CCTV13,HD
```

即 `频道名称,频道地址,台标名称（默认取频道名称）,清晰度（默认 HD）`。

读取优先级：**外部文件 > 内置默认**。外部路径为
`/sdcard/Android/data/com.tvlive.web/files/channels.txt`，改完直接推：

```bash
adb push channels.txt /sdcard/Android/data/com.tvlive.web/files/channels.txt
```

内置默认频道在 [app/src/main/assets/channels.txt](app/src/main/assets/channels.txt)。
应用内没有频道编辑器（V2 起移除），频道就按上面的方式维护。

## 节目单（EPG）

- 依次尝试多个 XMLTV 源（`EpgManager.SOURCES`：epg.zsdc.eu.org、epg.erw.cc、live.fanmingming.com、epg.112114.xyz 等），支持 gzip
- 每天 00:25 `AlarmManager` 定时拉取，`EpgReceiver` 在开机后重新排闹钟；解析结果落盘
- 保留窗口：已播 36 小时 ~ 未播 26 小时；按频道名匹配，匹配不上再拿台标名试一次

## 构建

```bash
# JDK 17 + Android SDK（compileSdk/targetSdk 35，minSdk 26，路径写在 local.properties）
./gradlew assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

## 安装

```bash
adb connect <电视IP>:5555          # 无线调试时
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## 常用开关（都在 `MainActivity.kt` 的 companion object 里）

| 常量 | 作用 |
| --- | --- |
| `TV_USER_AGENT` | 网页 UA（默认 PC Chrome 桌面版） |
| `BLOCK_STYLE_RESOURCES` | 是否拦截网页 CSS / 图片 / 字体 |
| `TRACKING_HOSTS` | 统计/广告域名黑名单 |
| `SERVE_FROM_SCRIPT_CACHE` | 脚本本地缓存总开关（`CACHE_BUDGET`、`CACHE_DEFAULT_TTL` 配套） |
| `DEBUG_SHOW_VIDEO_INFO` | 直播页左下角显示每个 `video` 的 src 与播放状态（排查用） |

## 源码结构

```
app/src/main/java/com/tvlive/web/
  MainActivity.kt        播放 + 面板 + 网络拦截 + 频道解析（单 Activity）
  CoverflowScrollView.kt 分组栏：居中吸附、缩放与压暗
  BadgeIconView.kt       在播频道的声波角标动画
  LogoLoader.kt          台标下载与内存缓存
  EpgManager.kt          XMLTV 下载、解析、匹配、定时更新
  EpgReceiver.kt         开机重排节目单闹钟
  TvApp.kt               Application
```
