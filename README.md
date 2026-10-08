# Immich TV（Android TV 相册浏览器 · 共享链接版）

用 **Kotlin + Jetpack Compose for TV** 从零实现，通过 Immich 的**公开共享链接**在电视上看照片和视频——不需要账号 / API key，家里人用手机扫码即可完成连接。

> **个人自用项目**：最初为满足作者自家「在电视上看家庭照片/视频」的需求而写——家人手机扫二维码即可连上电视，浏览 Immich 里的共享相册，功能够用就好。现已以 **MIT 许可**开源分享，欢迎按需使用、修改和分发（见 [LICENSE](LICENSE)）。

## 技术栈

| 层   | 选型                                                                                                 |
| ---- | ---------------------------------------------------------------------------------------------------- |
| UI   | Jetpack Compose for TV（`androidx.tv:tv-material:1.1.0` + `tv-foundation:1.0.0`）                |
| 图片 | Coil 3（磁盘缓存按可用空间自适应 64MB~1GB + 内存 40%，同主机并发抬到 10）                            |
| 视频 | Media3 ExoPlayer +`PlayerView`（等比缩放、byte-range 边下边播、磁盘缓存 128MB~2GB + 下一视频预读） |
| HTTP | Retrofit + OkHttp + kotlinx.serialization                                                            |
| 连接 | 电视内置极简 HTTP 服务器（`ServerSocket` 手写）+ 二维码，手机扫码回传共享链接                      |
| 诊断 | 崩溃写盘 + 内置服务器`GET /debug` 可读上次崩溃栈（电视上看不到 logcat）                            |

## 环境要求

- Android Studio **Otter 2025.2.1+**
- JDK 17
- Android SDK 36（compileSdk / targetSdk 36），minSdk 24（Android 7+）
- TV 模拟器：Android Studio 设备管理 → "TV" 类别（如 ADT-3 / Google TV 镜像）；真机用 Google TV / Android TV 盒子，开启"开发者模式 + 无线调试"侧载

## 快速开始

1. 用 Android Studio **Open** 打开本项目根目录（首次打开会自动同步 Gradle；若提示缺 wrapper，运行 `gradle wrapper --gradle-version 8.14.3`）
2. 选择 TV 模拟器/真机，点 Run
3. 在 Immich 网页端给家人相册创建一个**共享链接**（相册 → 分享 → 复制链接，形如 `https://服务器/share/xxxx`）
4. App 首屏会显示一个二维码（内容是电视内置服务器的地址）：
   - **主路径（扫码）**：家人手机扫二维码 → 手机浏览器打开页面 → 粘贴共享链接 → 点「发送到电视」，电视自动切到照片页并记住这条链接；
   - **兜底**：电视上用遥控器直接输入链接，或从「历史共享链接」里点「使用」。
5. 手机和电视需要在**同一个 Wi-Fi**（局域网直连）。

> `AndroidManifest.xml` 已含 `android:usesCleartextTraffic="true"`（局域网 HTTP 需要）。
> 生产建议给 Immich 套 HTTPS 反代（Caddy/Traefik）后去掉该开关。

## 功能特性（当前已实现）

- **按天时间线**：照片按拍摄日期倒序分组（吸顶日期头），左右键横向翻当天照片，上下键跨天滚动；
- **右侧年份栏**：常驻快速跳转，顶部实时显示当前所在年月，点某一年一键跳到那一年；
- **流式加载**：先出最新一个月的照片（界面立即可用），更早的月份在后台限并发继续补，顶部显示「已加载 x / y 个月…」；
- **加载失败可重试**：整体失败或部分月份失败都会给出原因 + 「重试」按钮；
- **全屏幻灯片**：每 5 秒自动翻页、到底回卷，「确定」暂停/继续；先显缩略图、后台预热原图（弱网不空白）；
- **智能预取**（弱网 = 延迟大、带宽足，用并发换延迟）：
  - 缩略图：从当前页起**预取 10 张**，快速连翻不空白；
  - 高清图：**当前页先单独拉**（无缝升级最先到位），窗口内其余**限并发 5 并行**，且窗口按实测网络质量自适应——**延迟低预取 10 张，延迟高预取 3 张**（用原图加载耗时的滚动均线估计 RTT）；
- **图文混排浏览**：图片与视频在一条时间线里无缝翻页，翻到视频就地起播、播完自动往下走一个资源；
- **视频播放**：ExoPlayer，等比缩放留黑边、支持拖进度（byte-range）、底部控制条（上一个/播放暂停/下一个）；
- **视频预读 + 磁盘缓存**：播放走 CacheDataSource 流式落盘（128MB~2GB，按可用空间自适应），切回去直接从磁盘起播；**当前视频播放时后台预读「下一个视频」的前 8MB**，切过去秒开；
- **预览期间禁止休眠**：看幻灯片/视频时画面不熄灭，退出恢复系统策略；
- **返回定位**：预览/播放返回后，列表自动滚回那张照片所在日期与列，不重新拉取相册；
- **自动更新**：启动时静默检查 GitHub Release 最新版（版本号高于当前则提示），一键下载（带进度）+ 调系统安装器；发布物直接是 APK
- **连接页**：扫码二维码 + 历史共享链接（最多 20 条，带相册名）+ 电视上手动输入；
- **崩溃诊断**：崩溃栈写文件，手机浏览器打开 `http://电视IP:端口/debug` 即可查看。

## Immich API 对接（Immich v3 · 公开共享接口）

| 用途           | 端点                                                   | 说明                                             |
| -------------- | ------------------------------------------------------ | ------------------------------------------------ |
| 共享链接信息   | `GET /api/shared-links/me?key=xxx`                   | 返回相册名、共享类型（ALBUM/INDIVIDUAL）、有效期 |
| 相册月份桶列表 | `GET /api/timeline/buckets?albumId=&key=`            | 一次请求拿到全部月份，作为流式加载的进度分母     |
| 某月照片       | `GET /api/timeline/bucket?albumId=&timeBucket=&key=` | v3 是**列式** JSON，客户端按天分组         |
| 缩略图         | `GET /api/assets/{id}/thumbnail?key=&size=thumbnail` | 网格瓦片用                                       |
| 大图           | `GET /api/assets/{id}/thumbnail?key=&size=preview`   | 全屏用；size 参数大小写敏感，必须小写            |
| 视频           | `GET /api/assets/{id}/video/playback?key=`           | 支持 byte-range 拖进度                           |

**为什么不走账号登录**：家庭场景共享链接最省事——无需在电视上输账号，权限天然受限（只读），而且 `?key=` 可以直接写在图片/视频 URL 里，Coil 和 ExoPlayer 都不用额外配鉴权 header。

**已知限制**：共享链接没有 `asset.download` 权限，所以「原图」接口（`/assets/{id}/original`）一律 400。实测 `size=preview` 是共享链接能拿到的最大分辨率（1080p 级），足够覆盖电视屏幕，全屏看图就用它（代码里 `originalUrl()` 实际返回 preview 档）。

## 目录结构

```
app/src/main/java/com/zch/immich/tv/
├── MainActivity.kt          # 全屏 + KeyBus 按键拦截入口
├── api/
│   ├── ImmichApi.kt         # DTO + Retrofit 接口（shared-links / timeline）
│   └── ImmichClient.kt      # 共享链接解析 / Retrofit / 媒体 URL 帮助方法
├── data/
│   └── SettingsStore.kt     # 当前链接 + 历史（最多 20 条）持久化
├── diagnose/
│   └── CrashLogger.kt       # 崩溃写盘 + 链式异常展开
├── net/
│   └── ShareLinkServer.kt   # 内置 HTTP 服务器：扫码页 / POST /link / GET /debug
└── ui/
    ├── App.kt               # 状态导航（Connect / Home）+ 预览覆盖层路由
    ├── ConnectScreen.kt     # 二维码 + 历史 + 手动输入
    ├── HomeScreen.kt        # 按天时间线 + 年份栏 + 流式加载
    ├── ViewerScreen.kt      # 全屏查看路由：图片幻灯片 / 视频分流
    ├── PlayerScreen.kt      # ExoPlayer 播放 + 控制条
    ├── ImageSupport.kt      # Coil 配置（复用 OkHttp、缓存策略）
    ├── KeyBus.kt            # 全屏页按键统一接管（走 Activity.dispatchKeyEvent）
    ├── TouchSupport.kt      # tvTap / TvButton / TvStatusButton（触屏+遥控器双通道）
    └── QrCode.kt            # 二维码生成（zxing）
```

## 实现要点

- **按键为什么走 `Activity.dispatchKeyEvent`（KeyBus）**：Compose 的 `onKeyEvent` 只在节点真正有焦点时才触发，而 `HorizontalPager` 内部的 ScrollableNode 会把焦点拦走，遥控器会"失灵"。改成页面级统一接管：谁在显示谁注册，销毁即摘除，未注册时按键走系统默认。
- **触屏和遥控器双通道**：`tv-material3` 的 Button/Card 不处理触摸；`TouchSupport.tvTap` 用 `pointerInput + detectTapGestures` 只补触摸、不碰焦点也不碰按键，两套输入互不干扰。
- **流式加载的定位稳定性**：时间桶按「最新在前」顺序冲刷，新数据永远追加在时间线末尾，已显示的天在扁平列表里的下标不变——所以预览页返回时的定位不会因为后台还在加载而错位。
- **弱网并发/缓存策略**（延迟大、带宽足的场景用「并发换延迟」）：
  - 图片：缩略图预取 10 张并行；高清图「当前页先单独拉 + 其余限并发 5」，窗口按实测网络质量自适应（延迟低 10 张 / 延迟高 3 张）；OkHttp 同主机并发抬到 10，磁盘缓存按可用空间自适应（64MB~1GB）；
  - 视频：播放走 `CacheDataSource`（进程级 `SimpleCache`，128MB~2GB 自适应）边播边落盘；当前视频播放时后台预读下一个视频的前 8MB，切换秒起播。
- **解析全部丢到 IO 线程**：几千张照片的相册有二十几个时间桶、几 MB 列式 JSON，主线程解析会被判"无响应"。
- **错误兜底**：加载阶段的任何 Throwable（含 OOM/AssertionError）都落到 UI 上而不是直接崩掉；崩溃再兜底到 CrashLogger + `/debug` 页面。

## 测试

`./gradlew :app:testDebugUnitTest`，共 22 个用例：

- `ShareLinkServerTest`（10）：内置服务器路由 / 表单解析 / 404 / `/api/state` / `/debug`；
- `ParseShareLinkTest`（8）：共享链接解析边界（带逗号、缺协议、带 query、`/api/share/` 前缀等）；
- `TimelineBucketAssetsDtoTest`（4）：列式 JSON 反序列化回归（曾经因 `ratio` 类型声明错误导致整相册加载失败）。

## 自动构建与一键发布（GitHub Actions）

推代码到 `main` 后，GitHub 会自动编译并生成正式签名的 release APK 作为 Actions artifact（`build-apk.yml`）；PR 只构建 debug 包做基本校验。

**发新版用「一键发布」**（Actions 页面 → 一键发布 → Run workflow → 填版本号，如 `1.1.0`）：
- 自动用 `-PversionName=1.1.0` 构建正式签名 release APK（versionCode 由版本号推导：`major*10000 + minor*100 + patch`，保证每次发版递增，自动更新才能安装）；
- 自动打 `v1.1.0` 标签并创建 GitHub Release，**直接把 APK 挂上去**（不再产生 zip）；
- 发布成功后电视上的自动更新会检测到新版本。

也可以走传统方式：`git tag v1.1.0 && git push`（`release.yml` 的 push tags 分支会完成同样的构建+发布）。

> 已有的旧发布物（`1.0.0` 只有 `immich-tv-apk.zip`）自动更新也能处理：下载后自动解出里面的 APK 再安装。

配置签名（一次性）：

1. 生成 keystore：

   ```bash
   keytool -genkey -v -keystore release.jks -alias immich-tv -keyalg RSA -keysize 2048 -validity 10000
   ```

2. 把 keystore 转成 base64，并到仓库 **Settings → Secrets and variables → Actions** 添加 4 个 secret：

   | Secret | 值 |
   | ------ | -- |
   | `KEYSTORE_BASE64` | release.jks 的 base64（Linux/macOS：`base64 -w0 release.jks`；Windows PowerShell：`[Convert]::ToBase64String([IO.File]::ReadAllBytes("release.jks"))`） |
   | `KEYSTORE_PASSWORD` | keystore 口令（keytool 的 `-storepass`，唯一必需的口令） |
   | `KEY_ALIAS` | 上面 `-alias` 用的别名 |
   | `KEY_PASSWORD` | **可留空**。JDK 9+ 默认 PKCS12 keystore 只有一道口令（keytool 生成时不会再单独询问 keypass），留空自动等同 `KEYSTORE_PASSWORD` |

   > 旧版 JKS 格式才有「storepass / keypass」两道口令；现代 JDK 生成的 keystore 一道就够。签名时报 `Keystore was tampered with, or password was incorrect` 基本是 `KEYSTORE_PASSWORD` 或 base64 内容有问题。

3. **密钥只放 Secrets、别提交进仓库**；本地没配环境变量时 `assembleRelease` 会报缺签名信息（预期行为），日常开发用 `assembleDebug` 即可。

## 路线图 / 已知缺口

**已完成**：扫码连接、按天时间线 + 年份跳转、流式加载 + 重试、全屏幻灯片、图文混排视频播放、崩溃诊断、返回定位、自动更新。

**下一步候选**（按价值排序）：

- [ ] 过期共享链接预警：临近/已过期时在首页明确提示
- [ ] 设置页：幻灯片间隔、清晰度档位、清缓存、诊断信息 UI
- [ ] 网络状态监听（权限已声明，尚未使用）与离线提示
- [ ] EncryptedSharedPreferences 加密存储历史链接
- [ ] 内置接收服务器加简单鉴权（目前局域网内任何设备都能 POST 换相册）

## 许可证

[MIT](LICENSE) © 2026 zch
