# 电视直播（Android TV · WebView）

用 WebView 打开直播网站的 Android TV 应用（腾讯 X5 内核，下载失败自动回退系统 WebView）。
首页是**原生 Apple TV 风格 UI**（大标题 + 多行频道网格 + 焦点放大动画），
方向键选频道，OK 在同一个 WebView 里全屏播放直播网页，返回键一次直接回首页。

## 功能

- **原生首页**：黑色背景、大号焦点标题、多行频道网格（按容器实测宽度自动算列数，锁屏/分屏启动也不会错列）；
  聚焦卡片放大 1.1 倍并显示白色描边（`activity_main.xml` / `item_channel.xml`）
- **频道自定义（应用内完成，不用碰文件）**：首页“编辑频道”卡片 → 批量粘贴（每行一个 `频道名称,频道地址`，支持 `#` 注释）
  + 每条频道单独“删除”按钮 + 保存 / 取消；频道存在应用外部存储 `files/channels.txt`，首次运行自动写入默认频道
- **进入即全黑**：打开频道立刻盖全黑遮罩（不露出正在加载的网页），等视频真正呈现首帧
  （`requestVideoFrameCallback`）才 700ms 渐隐；40 秒仍无画面自动收起
- **自动全屏**：页面里面积最大且带可播放 src（`blob:` 或 m3u8/flv/mp4 等地址，含同源 iframe）的 `video` 用 CSS 铺满视口
  （不依赖浏览器全屏 API、不需要用户手势），播放器重写行内样式会被看门狗重新落实；每轮都会调用 `video.play()`，防止有的页面不自动播放
- **通用逻辑**：不针对任何具体网站写选择器/劫持，只认“最大可播放 video、blob 地址、首帧呈现”等通用规则，换任意直播网站都能用
- **屏蔽页面操作**：进入直播网页后方向键/OK/Tab/触摸全部被吃掉，只看视频（音量键等系统键不受影响）；回首页自动恢复
- **按键行为**：直播页按返回 = 退出全屏并直接回首页；首页再按返回退出应用；编辑页按返回 = 取消并关闭
- **错误页**：网络失败跳本地 [error.html](app/src/main/assets/error.html)（重试 / 回到首页，后者通过 `tvhome://` 协议通知原生首页关闭直播页）
- 电视 UA、自动播放（`mediaPlaybackRequiresUserGesture=false`）、混合内容、第三方 Cookie、横屏全屏沉浸式
- 不加载网页的 CSS / 图片 / 字体等样式类资源（`MainActivity.kt` 里的 `BLOCK_STYLE_RESOURCES` 开关）

## 构建

```bash
# 需要 JDK 17 和 Android SDK（已在 local.properties 指向 sdk.dir）
./gradlew assembleDebug          # 或用 Android Studio 直接打开本目录
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

## 安装到电视 / 手机

```bash
adb connect <电视IP>:5555     # 无线调试时
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## 自定义

- 频道：应用内“编辑频道”卡片（格式 `频道名称,频道地址`，一行一个）；内置默认频道在 [app/src/main/assets/channels.txt](app/src/main/assets/channels.txt)
- 改 UA：`MainActivity.kt` 的 `TV_USER_AGENT`
- 允许加载网页样式资源：`MainActivity.kt` 的 `BLOCK_STYLE_RESOURCES = false`
- 直播页左下角的 video 调试浮层：`MainActivity.kt` 的 `DEBUG_SHOW_VIDEO_INFO`（只在直播页显示）
