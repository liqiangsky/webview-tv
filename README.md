# InWeb（Android TV · WebView）

用系统 WebView 播放直播网站的 Android TV 应用，不需要额外内核。打开就是上一个在看的频道，
按 OK 呼出频道面板换台，频道和登录网址都在手机上配置。

## 构建与安装

```bash
# 需要 JDK 17 + Android SDK（compileSdk/targetSdk 35，minSdk 26），SDK 路径写在 local.properties
./gradlew assembleDebug

adb connect <电视IP>:5555      # 无线调试时才需要
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## 遥控器

| 按键 | 作用 |
| --- | --- |
| OK | 呼出/收起频道面板；播放出错时按 OK 呼出列表换台 |
| 上 / 下 | 在频道列表里选台 |
| 左 / 右 | 换分组 |
| 返回 | 收起面板；面板已关时连按两次退出应用 |

切台后会出现黑底过渡屏（台名 + 「正在连接…」），画面出来自动消失；
15 秒还没出画面会显示「该频道暂时无法播放」，按 OK 换台即可。

触屏电视也一样：点屏幕呼出面板，点频道行直接切台，点组头两边的箭头换分组。

## 手机配置

电视面板右列有二维码、`<电视IP>:8723` 和 4 位配对码。手机连同一个 WiFi，扫码打开配置页，输入配对码后可以：

- 增删改频道和分组，或整段导入 / 清空
- 管理「登录网址」：电视上没法打字，点条目右边的「打开」，电视会打开那个网页，
  再用手机当鼠标完成登录（配置页的「电视遥控」页）
- 换配对码
- 直接点频道名让电视切台

改动保存后电视上的面板会立刻更新。

## 频道数据

存在电视的 `/sdcard/Android/data/com.inweb.tv/files/`（`applicationId` 改名前的旧版本在 `com.tvlive.web` 下，换 ID 装上新版后两者互不相干，需要时把文件搬过去），卸载应用会清掉。也可以在电脑上直接推文件：

- `channels.txt`：DIYP txt 格式，`分组名,#genre#` 开头，每行 `频道名称,频道地址[,节目别名[,清晰度]]`
- `logins.txt`：每行 `名称,地址`，只在手机配置页里用

```bash
adb push channels.txt /sdcard/Android/data/com.inweb.tv/files/channels.txt
```

应用本身不带任何频道，两个文件都不存在时列表为空，面板会提示去配置页添加。

## 节目单

频道名能对上公开的 XMLTV 节目单时，面板左列会显示当前节目、剩余时间和下一档，每天自动更新一次；
对不上的频道只显示一句占位文案，不影响播放。

## 排查

播放异常时可以在 `MainActivity.kt` 的 companion object 里打开 `DEBUG_SHOW_VIDEO_INFO`，
直播页左下角会列出页面上每个视频的地址和播放状态；其余开关（UA、资源拦截、脚本缓存等）也在同一处，
每个常量上都有注释。

日志统一用 `TvWeb` 标签：

```bash
adb logcat -s TvWeb
```
