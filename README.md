# 电视直播（Android TV · WebView）

用系统 WebView 打开直播网站的 Android TV 应用（无第三方内核依赖）。
底层一个全屏 WebView 常驻播放，打开应用即播第一个频道；按 OK / 点屏呼出沉浸式频道面板换台，
面板打开时视频继续播放。全程不针对任何具体网站写选择器或做定制劫持。

## 界面与交互

- **播放层**：全屏 WebView（`activity_main.xml` 的 `webView`），横屏、沉浸式、屏蔽网页自身的按键和触摸
- **操作面板**（`panel_overlay.xml`，按 OK / 点屏呼出，tvOS 视觉语言）：一屏三列，键位与最初设计一致
  - **左 · 频道信息**（`panel_info.xml`，纯展示）：在播/预览状态、清晰度、频道名、在播节目、时段与剩余时间、进度线、下一档、系统时间日期
  - **中 · 频道列表**（行视图代码直搭，不 XML inflate）：组头显示「第几组 / 共几组 · 多少个」，两侧箭头供触屏换组；在播台带声波角标（`BadgeIconView`）
  - **右 · 手机配置**：手机配置的二维码（本地 zxing 生成，配置地址不出内网）、`<电视IP>:8723` 与 4 位配对码、换码按钮
  - 网站登录不在电视上列条目：手机配置页里点条目的「打开」，电视直接开该站登录页（`LoginActivity`），登录态存在电视的 Cookie 里
- **按键与触屏**：频道列表里左右换组、上下选台、OK 切台并收起面板；Back 收起面板。触屏下点组头箭头换组、点频道行直接切台（首次点击即播放）、点「换一组」重发配对码
- **焦点与播放解耦**：光标停在哪个台就预览哪个台的信息，切台不影响浏览位置；只记上次浏览的分组（`SharedPreferences(panel_focus)` 存 `group`），组内落点由该组在播频道决定，不记行号
- 面板打开时底层视频继续播放，整块背景压暗让画面透出来；同时对视频层下发高斯模糊（API 31+ `RenderEffect`，部分内核的视频走独立 Surface，实际可能不生效）

## 播放链路

1. **换台重建实例**（`recreateWebView()`）：复用旧实例会残留上一个站点的文档，导致新页面起不了播、铺不满屏
2. **全黑过渡遮罩**：打开页面前盖住正在加载的网页，等视频真正出首帧（`requestVideoFrameCallback`）才 700ms 渐隐；15 秒还没首帧就收幕并跳错误页
3. **自动全屏**（`APPLY_FS_JS`，`videoWatch` 每 800ms 轮询）：挑页面上面积最大、且确实在放画面的 `video`（含同源 iframe）——
   src 是 `blob:` / m3u8/flv/mp4 这类媒体地址，或者已经解出帧（`videoWidth > 0`，播放器把 MediaStream 挂到
   `srcObject` 上时 src 是空的），用 CSS 铺满视口——不依赖浏览器全屏 API、不需要用户手势
4. **播放重试**（`tryPlay`）：有些播放器第一次 `play()` 不生效，没播起来就隔 100ms 再 `play()`，最多 5 次
5. **看门狗**（`fsWatch`）：播放器重写行内样式后重新落实全屏，并跟随切换到正在播的 `video`
6. 网页自己请求真全屏（`onShowCustomView`）时，全屏视图插到面板之下——视频全屏状态下仍能正常呼出面板

## 网络与资源策略

- **不加载样式类资源**：`BLOCK_STYLE_RESOURCES` 拦掉网页的 CSS / 图片 / 字体 / manifest（按 `Sec-Fetch-Dest` 判断，老内核按扩展名兜底），视频与 JS 一律放行
- **埋点整域拦截**：`TRACKING_HOSTS`（GA、hm.baidu.com、umeng、cnzz、sensorsdata…）返回空响应，播放器不感知
- **静态脚本磁盘缓存**（`SERVE_FROM_SCRIPT_CACHE`）：脚本请求未命中缓存就当场下载并留存，下次秒回；按域名分组，单文件上限 6MB、总预算 80MB，文件名带内容哈希/版本号的按不可变资源缓存 7 天；回灌时补齐 CORS 响应头，否则 `crossorigin` 脚本会被内核判加载失败
- **错误页**：本地 [error.html](app/src/main/assets/error.html) 只居中显示一句提示，没有按钮。两种进入方式：主文档加载失败（`onReceivedError`），或点台后 15 秒内没等到视频首帧（`PLAY_TIMEOUT_MS`，计时从 `openChannel` 起、中途重定向不续表）—— 后者先 `stopLoading()` 掐掉起不来的网页再跳页，带 `?msg=该频道暂时无法播放`
- PC 桌面 UA（`TV_USER_AGENT`，网站才会下发电脑版而不是 App 版）、`mediaPlaybackRequiresUserGesture=false`、允许混合内容、放行第三方 Cookie

## 数据：频道与登录网址

出厂不带任何频道，两个文件都不存在时列表为空，面板引导去配置页添加。
全部编辑都在手机端配置页完成，电视端只读展示（应用内没有频道编辑器）。

落盘在 `/sdcard/Android/data/com.tvlive.web/files/`（卸载即清除，调试时也能直接 `adb push`）：

- `channels.txt` —— DIYP txt 格式：`分组名,#genre#` + 每行 `频道名称,频道地址[,节目别名[,清晰度]]`
  （第三列默认取频道名，只用来对节目单，电视上不再显示台标；清晰度默认 HD）
- `logins.txt` —— 每行 `名称,地址`，只在手机配置页里管理，电视面板不再列条目

```bash
adb push channels.txt /sdcard/Android/data/com.tvlive.web/files/channels.txt
```

## 手机配置（`ConfigServer` + `assets/config.html`）

电视端起一个内置 HTTP 服务（端口 8723），配置网页由电视自己下发，手机扫码就是电视上那份页面，
不需要额外的服务器、也不需要装配置 App。

- **入口**：面板右列的二维码（本地 zxing 生成，配置地址不出内网）+ 明文 `<电视IP>:8723` + 4 位配对码
- **门禁**：写接口必须带 `X-Pin` 请求头（或 `?pin=`）且与屏幕上显示的配对码一致；配对码可在电视上「换一组」
- **读**：`GET /` 配置页，`GET /api/state` 回当前分组、频道、登录网址、在播状态和 `remoteReady`（登录页在不在前台）
- **写**（`POST /api`，`{"op":..}`）：`ch.add` / `ch.upd` / `ch.del` / `ch.import` / `ch.clear` /
  `group.add` / `group.rename` / `group.del` / `login.add` / `login.upd` / `login.del` / `pin.regenerate`，
  另有 `play`、`login.open` 让手机直接遥控电视
- **遥控通道**：`GET /remote?pin=`（同 8723 端口）升级成 WebSocket，逐条 JSON 指令与下表一致；
  握手前先验配对码，登录页开着才收，登录页不在前台时推 `{"t":"ready","v":false}` 让页面提示
- 数据变更后 `ChannelStore.onChanged` 回主线程重画面板，手机改完电视立刻可见

## 节目单（EPG）

- 依次尝试多个 XMLTV 源（`EpgManager.SOURCES`：epg.zsdc.eu.org、epg.erw.cc、live.fanmingming.com、epg.112114.xyz 等），支持 gzip
- 每天 00:25 `AlarmManager` 定时拉取，`EpgReceiver` 在开机后重新排闹钟；解析结果落盘
- 保留窗口：已播 36 小时 ~ 未播 26 小时；按频道名匹配，匹配不上再拿第三列的节目别名试一次

## 手机遥控登录（配置网页的「电视遥控」页）

有些直播网页要登录才能看，但电视上没法打字。手机在配置页的登录网址上点「打开」
（即向电视发 `login.open`），电视打开一个可交互的登录 WebView（`LoginActivity`），手机当鼠标，
把操作同步到电视上完成登录。打开登录页前电视先 `stopPlayback()` 停掉当前直播（掐请求 + 暂停页面里的
`video/audio`），退出登录页后重播刚才那个台，登录写进共享 Cookie 的登录态这才生效；
重播不在 `onResume` 里立刻做 —— 那一刻登录页的窗口常常还没撤干净，页面在这半秒里加载，
站点自己的播放器容易停在「有画面但没播」的状态，所以挂到窗口真正拿到焦点（`onWindowFocusChanged`）才起播。

入口只有一个：**配置网页的「电视遥控」页**（从「网站登录」顶栏「＋」左边图标进，或遥控页里直接开网址），
走 8723 的 `/remote` WebSocket，不用装 App。

- **手机 = 鼠标模型**：手机只发**相对位移 + 行为**（不带屏幕坐标），电视自己维护一个光标点（`CursorView`）。
  这样不受手机屏幕比例和网页布局影响
- **鼠标注入**：电视发 `SOURCE_MOUSE` + `TOOL_TYPE_MOUSE` 的 `MotionEvent`，用 `webView.dispatchTouchEvent()` 打进网页，
  内核按鼠标源自己合成可信的鼠标事件链 —— 移动=`ACTION_HOVER_MOVE`（页面 `:hover`、`mousemove` 照常），
  单击=`ACTION_BUTTON_PRESS`/`RELEASE`（`mousedown`→`mouseup`→`click`），
  拖拽=按住左键的 `ACTION_MOVE`（`mousemove` 带 `buttons=1`，能选字），长按就是按住不放再挪动。
  设备本身支持触摸也不用再开一条路：手指直接点屏幕走的是系统自己的输入管线，一样是可信事件。
  两处内核不给的地方：双击的 `click_count` 只认真外设，第二对按键之后往光标下的元素补发一次
  `dblclick`（`isTrusted=false`，监听器照常）；`ACTION_SCROLL` 更会把 webview 内核直接撞崩（SIGTRAP），
  所以滚轮不发原生事件，改成给光标下的滚动容器发 `wheel` + 改 `scrollTop`
- **文字注入**：网页底部一个输入框 + 一个「替换输入框」按钮，点替换把内容整体写进电视上聚焦的输入框
  （输入框留空点替换就是清空那个框）。
  优先走合成 IME（捕获页面 `InputConnection` 后 `commitText`），等同真实输入法；桥没建立时退回 JS
  （native value setter + `input` 事件，兼容 React/Vue 受控输入框）
- **传输**：`ConfigServer` 的 `/remote` WebSocket，按 rAF（≈16ms）合并发送，并用
  `pointerrawupdate` / `getCoalescedEvents` 把两帧之间的中间点补齐。
  这条路有个绕不开的限制：标签页切后台或锁屏，浏览器就冻结 JS、连接断掉（回前台自动重连），
  而普通 http 页面拿不到系统的屏幕常亮，所以登录时得自己保持亮屏
- **触控板手势**（网页的 `#pad`）：轻点=单击、二连点=双击、双指滑动=滚轮，
  单指滑动看「单指滚动」开关——关掉是鼠标移动（配合长按=按住拖拽），打开是滚轮（此时长按不起拖拽）。
  开关存在手机本地，刷新页面还在。快捷键是 Tab / 返回 / 刷新 / 关闭登录页
- **遥控页的排布**：连接状态（一个点 + 两三个字）挂在顶栏，「单指滚动」贴在「网页操作」标题右边，
  缩放读数与 `－ / 1× / ＋`、输入框与「替换」各占一行，省下的竖向空间全给触控板（`56vh`，最少 300px）
- **页面缩放与关闭**：网页的「缩放」行和电视上登录页左下角的 `－ / 1× / ＋` 共用同一套档位——手机只发方向，
  电视算步进（一档 ±0.1，30%~400%），做法是往 `body` 打 CSS `zoom`；
  布局视口注入成 WebView 自己的宽（`<meta name="viewport" content="width=屏幕 dp 宽">`），
  页面按屏幕宽排版，zoom 上去就是桌面浏览器整页放大的效果（px / vw / fixed 一起乘倍数）；
  档位由电视回推，两边的读数不会各算各的。同一排还有个「关闭」，点了等价于按返回键退出登录页；
  手机遥控页上也有「关闭登录页」，除了收掉电视那一页，手机自己退回「网站登录」

协议（一条消息一个 JSON）：

| 消息 | 含义 |
| --- | --- |
| `{"t":"m","dx":..,"dy":..}` | 相对位移（单位 dp） |
| `{"t":"c"}` / `{"t":"dc"}` | 单击 / 双击 |
| `{"t":"ds"}` … `{"t":"de"}` | 拖拽开始 / 结束（期间用 `m` 移动） |
| `{"t":"s","dx":..,"dy":..}` | 滚轮 |
| `{"t":"txt","s":"..","r":true}` | 文字，`r` 是否整体替换 |
| `{"t":"k","k":"enter\|tab\|del\|esc\|back\|refresh\|close"}` | 按键，`close` 直接收掉登录页 |
| `{"t":"nav","u":"https://.."}` | 登录页跳转地址 |
| `{"t":"z","d":-1\|0\|1}` | 缩放：减一档 / 回 1× / 加一档 |
| `{"t":"z","v":100}` | 电视回推的当前档位（百分比），连上和每次改动都推 |

### 没有实体电视，怎么测

手机浏览器连的是电视的局域网地址，模拟器当「电视」时它在 NAT 里（10.0.2.15），
手机直连不到；`adb forward` 又只绑电脑 127.0.0.1，所以要在电脑上起一个中继把端口开到局域网，
手机填 `电脑局域网IP:8724`。真电视则手机和电视同一局域网、直接填电视 IP 即可。

```bash
adb forward tcp:8723 tcp:8723
# 中继：把电脑 0.0.0.0:8724 转发到 adb forward 的 127.0.0.1:8723（进模拟器）
node -e 'const n=require("net");n.createServer(u=>{const d=n.connect(8723,"127.0.0.1");u.pipe(d);d.pipe(u);u.on("error",()=>d.destroy());d.on("error",()=>u.destroy())}).listen(8724,"0.0.0.0")'
```

## 构建

```bash
# JDK 17 + Android SDK（compileSdk/targetSdk 35；电视端 minSdk 26；路径写在 local.properties）
./gradlew assembleDebug
# 电视端：app/build/outputs/apk/debug/app-debug.apk
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
  MainActivity.kt        播放 + 面板（三列）+ 网络拦截
  ChannelStore.kt        频道与登录网址的唯一数据源：落盘、解析、增删改、配对码
  Models.kt              Channel / Group / LoginEntry
  ConfigServer.kt        内置 HTTP 配置服务（8723）：下发配置页 + PIN 门禁的读写接口
  PairingView.kt         独立的配对浮层（二维码 + 地址 + 配对码）
  Qr.kt                  zxing 本地生成二维码 Bitmap
  Net.kt                 取本机局域网地址
  LoginActivity.kt       登录页：可交互 WebView + 网页遥控光标 + 触摸/文字注入
  RemoteWebView.kt       捕获页面 InputConnection，供合成 IME 提交文字
  CursorView.kt          悬浮光标点（手机=鼠标，位置即点击落点）
  BadgeIconView.kt       在播频道的声波角标动画
  EpgManager.kt          XMLTV 下载、解析、匹配、定时更新
  EpgReceiver.kt         开机重排节目单闹钟
  TvApp.kt               Application

app/src/main/res/layout/
  panel_overlay.xml      面板：左 频道信息 / 中 频道列表 / 右 手机配置二维码
  panel_info.xml         左列频道信息（状态、清晰度、频道名、EPG、剩余时间、下一档、时间；纯展示不参与焦点）
```
