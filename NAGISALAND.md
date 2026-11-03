# VeneraX-nagi

Nagisaland 用的 VeneraX 离线优化版（私有，不发布 Release）。基于 Kyosee/VeneraX v2.3.6。

## 相对上游的改动
- `lib/network/app_dio_io.dart`：rhttp 客户端按设置缓存复用（原来每个请求都新建客户端，每张图都要重新 DNS + TCP + TLS 握手，用不上 keep-alive / HTTP/2）。
- `lib/network/images.dart`：漫画页 / 封面命中磁盘缓存后直接返回（原来会在后台再下载一遍并重写缓存）；下载时用 BytesBuilder 拼接数据，不再用 `List<int>` 逐字节存。
- `.github/workflows/nagi_offline.yml`：推到 main 自动编译 Android arm64，APK 在该次运行的 Artifacts（`VeneraX-nagi-arm64`）。上游的其它工作流已删掉，省 Actions 额度。

## 用法
编译出的 `VeneraX-<版本>-nagi-arm64-v8a.apk` 放进 `F:\GALGAME\_工具与杂项\Nagisaland安卓端\venerax\`，运行 `build.py` / `更新漫画.bat`，会优先合并带 `-nagi-` 的版本。

## 跟上游更新
- `.nagi-base` 记录当前基于的上游版本。
- Actions →「Nagisaland offline build」→ Run workflow（勾 sync）：自动把 Kyosee/VeneraX 最新 Release 合进来（只重放本仓库相对 `.nagi-base` 的改动，`.github` 保持本仓库的），提交到 main，编译，APK + `nagi-info.json` 推到 apk 分支。上游改到了优化涉及的代码时会报「合并冲突」，需要手动合并。
- 本地 `Nagisaland安卓端\漫画更新面板.bat` 有按钮可以触发这一步、下载 APK、打包 Nagisaland。

---

# suite 分支：Nagisaland 手机版多分区（漫画 + 番剧 + H）

只自己用，不分发。底栏：游戏库｜小站｜漫画｜番剧｜影视｜H｜解压｜模拟器，常驻、点一下切换。

| 分区 | 来源 | 协议 | 位置 |
| --- | --- | --- | --- |
| 漫画 | VeneraX 2.3.6（本仓库） | GPL-3.0 | `lib/`、`android/app` 的 `MainActivity` |
| 番剧 | Kazumi 2.4.2（Predidit/Kazumi） | GPL-3.0 | `packages/kazumi`，`android/app` 的 `kazumi/KazumiActivity` |
| H | Han1meViewer（Mlr111/Han1meViewer @3d9050e） | Apache-2.0 | `android/han`、`android/yenaly_libs`（`android/han/LICENSE-Han1meViewer`） |

## 架构
- 每个分区一个 Activity，都在同一个任务里常驻，`REORDER_TO_FRONT | NO_ANIMATION` 切换，状态保留。
- `android/app/src/main/kotlin/com/github/kyosee/venera/suite/SuiteNav.kt`
  - `SuiteNav`：`go` / `attach` / `interceptFinish`。切到游戏库 / 小站 / 解压 / 模拟器时，把 Nagisaland 主界面提到前面，
    并在它的 WebView 里点 `#nav button[data-tab]`（按按钮文字匹配，不依赖 data-tab 的值）。
  - `SuiteNavBar`：原生底栏（配色照 Nagisaland 网页），沉浸 / 键盘 / 横屏 / 画中画时隐藏；
    底栏可见时，内容区向上让出位置，传给内容的 insets 去掉了底部导航栏（Flutter 不会再多留一截空白）。
  - `SuiteSwitchActivity`：Theme.NoDisplay 的中转界面，给 Nagisaland 主界面用，Nagisaland 的 smali 不用改。认这几种写法：
    组件名 `io.github.kyosee.venera.suite.GoComic` / `GoAnime` / `GoH`；
    extra `section`（或 `target` / `tab`）= `comic` / `anime` / `h`；链接 `nagisuite://comic` 等。
  - `SuiteNavInitializer`：androidx.startup，进程一启动就跟踪各 Activity。
  - H 模块通过反射调用 `SuiteNav`（`com.yenaly.han1meviewer.suite.SuiteHook`），proguard 里保留了。
- 分区主界面按返回键退出时（Flutter 的 `SystemNavigator.pop`、H 首页返回）整个任务退到后台，不销毁。
- 进程 Application 是 `com.yenaly.han1meviewer.HanimeApplication`（app 的 manifest 里指定）。

## 相对上游的关键修改
- **H 作为 library**：原 app 模块改成 `android/han`；settings.gradle 声明 library / parcelize / serialization 插件和
  KSP 2.2.21-2.0.5；`android/gradle/libs.versions.toml`（Room 2.7.2）；根 build.gradle 加 jitpack；
  app 模块 `implementation project(':han')`、desugaring、`dataBinding true`（作为库时 app 也必须开，否则闪退）、
  排除 kotlin-android-extensions-runtime。
- **对 Han1meViewer 的改动**：去 Firebase；不检查更新；不接管崩溃；DynamicColors 只作用于 `com.yenaly.*`；
  FileProvider 换成 `HFileProvider` + `hanime_file_paths`（authority `<包名>.hanime.fileprovider`，运行时按包名算）；
  `<application>` 上不再放主题等属性，主题写到每个 Activity；去桌面入口；MainActivity 改 singleTop；
  yenaly_libs 不再给整个 App 设 networkSecurityConfig。
- **H 解析（2026 年网页改版）**：`logic/Parser.kt`
  - `hanimeItemsVer3`：`div.video-item-container > div.horizontal-card > a.video-link`（img.main-thumb、div.duration、
    div.stats-container > div.stat-item[好评率, 播放量]、div.title）+ `div.subtitle`（a = 上传者，span.subtitle-time）。
    搜索、首页各分类、我的列表、相关视频都先试它，旧结构作后备。
  - 首页：按每一行的标题 /「更多」链接认分类（最新上市 / 最新上传 / 里番 / 中文字幕 / 他们在看 / 本日排行 / 本月排行）。
  - watch 页：相关视频原来用 `select(...)[0]`，新网页取不到 → `Index 0 out of bounds for length 0`（「播放报错 0」）。
    现在标题和播放地址之外的部分各自失败只记日志；播放地址依次试 `video#player > source`、`const source = '…'`、
    脚本里的 .mp4 / .m3u8；播放清单支持新结构（`playlist-hover-wrap[data-href]`）。
  - 视频链接正则放宽（镜像站、相对链接），`Jsoup.parse` 带站点地址，相对链接也能取到。
  - 播放器：允许 http↔https 跳转；`vdownload.hembed.com` 出错时自动换备用 CDN 重试一次。
  - 解析不出时把网页存到手机 `内部存储/Nagisaland/debug/h-<watch|search|home|mylist>-*.html`（每种留最近 5 个）。
- **崩溃记录**：整个 App 崩溃时堆栈写到 `内部存储/Nagisaland/crash/crash-*.txt`（`SuiteCrashLog`，HanimeApplication 里安装）。
- **番剧**
  - flutter_inappwebview 用官方 6.1.5（VeneraX 的 fork 6.2.0-beta.3 会遵守 `UserScript.forMainFrameOnly`，默认 true，
    脚本进不了 iframe → 大量源「视频解析超时」）；Android / 验证码的 UserScript 都显式写了 `forMainFrameOnly: false`。
  - audio_service 引擎 id：`KazumiEngine` 用 `AudioServicePlugin.getFlutterEngineId()`（"audio_service_engine"）；
    漫画 `MainActivity.onCreate` 在 super 之前调用 `KazumiEngine.reserve()` 放占位引擎（不跑 Dart、不注册插件），
    番剧引擎真正创建时替换并销毁它。否则换集时释放播放器失败卡死。
  - Bangumi 镜像接口要官方 CI 注入的 KAZUMI_APPID / KAZUMI_KEY；没有时自动走 Bangumi 官方接口
    （`services/network/bangumi_acceleration.dart`）。
  - 播放器：网络抖动类错误（例 `tcp: ffurl_read returned 0xffffff92` = -110 ETIMEDOUT）只记日志不弹窗，
    15 秒后进度没动且一直在缓冲 / 提前播完才提示；`network-timeout` 120 秒，ffmpeg 网络错误后自动重连
    （`reconnect_on_network_error` 等）。
  - 弹幕要弹弹play 开放平台的 AppId / AppSecret：本地在 `NagiBuild\dart_defines.txt` 填 DANDANAPI_APPID / DANDANAPI_KEY；
    CI 在仓库 Secrets 里加同名变量。

## CI
`.github/workflows/suite_build.yml`：推到 suite（或手动运行）→ 编 arm64-v8a，APK 在 Artifacts（Nagisaland-suite-arm64），
suite 分支的构建还推到 `apk-suite` 分支。临时密钥签名，正式签名在用户电脑上用 merge_venerax.py 合并时做。

## 2026-11-03 统一成一个应用（unify-app 分支）
以前：CI 编 suite APK（漫画 + 番剧 + H）→ 电脑上 merge_venerax.py 反编译，拼进 Nagisaland 主界面的 smali → 签名。
现在：**一个工程直接编出完整的 `com.nagisaland.mobile`**，电脑上只剩「加模拟器 + 正式签名」。
- Nagisaland 主界面（原来只有 smali，Java 源码丢了）改写成 Kotlin：`android/app/src/main/kotlin/com/nagisaland/mobile/`
  - `MainActivity`（全屏 WebView）、`GameClient`（虚拟域名 → 游戏文件夹 / assets）、`Bridge`（`window.NagisaNative`，接口名不变）、
    `Ops`（文件 / 7-Zip / 网络 / 安装）、`Files`（`<包名>.files` 文件共享）、`HttpJob` / `DeleteJob`、`Ocr.kt`（识屏翻译，`window.Ocr`）
  - 启动器网页 `assets/launcher/`、注入游戏的 `assets/bridge-*.js` 原样复用；7-Zip 在 `jniLibs/*/lib7zz.so`
- merge_venerax.py 做的事都在源码里：`applicationId com.nagisaland.mobile`（authority 都用 `${applicationId}`）；桌面只有 Nagisaland 一个图标
  （VeneraX 换图标的别名没有桌面入口）；VeneraX 不再认 http/https 链接；各分区同一任务（去掉 taskAffinity，Kazumi singleTop）；
  主界面 onPause 不调用全局 `pauseTimers()`；`comic/anime/h.json` 指向 `SuiteSwitchActivity`。
- 版本号：安卓的 versionName / versionCode 在 `android/nagisaland.properties`；VeneraX 自己的版本号写死在 `lib/foundation/app.dart`
  的 `veneraXVersion`（漫画源 minAppVersion 检查用），以前靠 merge_venerax.py 改 package_info_plus。
- `targetSdk` 固定 34（和以前合并版一样），`minSdk` 至少 24。
- 行为改动：游戏库根页面按返回 → 整个 App 退到后台（以前 finishAndRemoveTask 会把各分区一起关掉）。
- 统一主题：`packages/kazumi/lib/nagi_theme.dart`（背景 #0f0f12，文字 #fafaf9 / #a1a1aa，主色 #e879a9，副色 #a78bfa），
  漫画（`lib/main.dart`）和番剧（`app_widget.dart`）固定深色并套用；H 在 `android/han/src/main/res/values/themes.xml`，并关掉壁纸动态取色。
  开关：`kNagiUnifiedTheme`、`NAGI_UNIFIED_THEME`。底栏仍是 `SuiteNavBar`。主界面网页的 `--bg / --text / --sub` 也改成同样的值。
- 出正式包：Actions 产物 `Nagisaland-v<版本>-…-arm64-v8a.apk` 放进 Nagisaland-Mobile 的 `ci\`，双击「出正式包.bat」（`sign_release.py`）：
  加 `emulators\` 里的模拟器、生成 `emulators.json`、用 `nagisaland-mobile.keystore` 签名 → `dist\Nagisaland_v<版本>.apk`。
- 上游同步：见 `doc/上游同步.md`。

## 2026-11-03 影视分区（Emby / Jellyfin，参考 Hills）
- Dart：`packages/kazumi/lib/nagi_video/`（放在 kazumi 包里，直接用它的 media_kit / dio / cached_network_image，不加新依赖）
  - `emby_api.dart`：登录（`/System/Info/Public` 自动认 Emby / Jellyfin → `/Users/AuthenticateByName`，密码不保存，只存令牌）、
    媒体库、继续观看、下一集、最新、条目分页 / 搜索、季 / 集、播放地址（`PlaybackInfo` → `/Videos/{id}/stream?static=true` 直接播原文件，
    不转码；外挂字幕）、播放进度回报（`/Sessions/Playing[/Progress|/Stopped]`）、标记已看。
  - `video_app.dart`：入口 `nagiVideoMain`；顶部三个分页「服务器｜聚合视界｜设置」（底部已有 Nagisaland 分区栏）。
    聚合视界 = 所有服务器的继续观看 / 下一集 / 最近添加合在一起 + 跨服务器搜索。
  - `browse_pages.dart`（服务器首页、媒体库网格、搜索）、`detail_page.dart`（电影 / 剧，选季选集，长按一集标记已看 / 未看）、
    `player_page.dart`（media_kit，配置照番剧；断点续播、每 10 秒回报进度、字幕 / 音轨 / 倍速、自动下一集、横屏）。
  - `video_store.dart`：服务器和设置存在 `getApplicationSupportDirectory()/nagi_video.json`（不用番剧的 Hive：两个引擎同时开同一个库会冲突）；
    图片缓存也单独一个库 `nagiVideoImages`。
- 原生：`android/app/.../video/VideoActivity.kt`，独立 Flutter 引擎（缓存 id `nagi_video_engine`，入口 `nagiVideoMain`），创建前
  `KazumiEngine.reserve()`（audio_service 的坑，同漫画）。`SuiteNav` 加 `video`，底栏「影视」在番剧和 H 之间；主界面网页加按钮和 `video.json`。
- 飞牛影视：接口没公开，确认后在 `emby_api.dart` 的 `ServerKind.fnos` 接上（现在添加时会提示未接入）。

## 本地编译（不用 CI）
在 VeneraX-nagi（或 NagiBuild）目录：`flutter pub get`，然后
`flutter build apk --release --target-platform android-arm64 --dart-define-from-file=dart_defines.txt`（没有 dart_defines.txt 就去掉这个参数）。
`android/key.properties` 指向任意密钥都行（CI 用临时密钥）；产物 `build/app/outputs/apk/release/Nagisaland-<版本>-arm64-v8a.apk`。
再双击 Nagisaland-Mobile 的「出正式包.bat」：它会自动在 `..\NagiBuild\build\app\outputs\…` 和 `ci\` 里找最新的包，加模拟器、用正式密钥签名。

## 用户电脑上的部分（不在仓库里；统一版之前的旧流程）
- merge_venerax.py：取 suite APK；provider authority 改成 Nagisaland 包名（现在有 `.fileprovider`、`.androidx-startup`、
  `.hanime.fileprovider` 三个）；Nagisaland 主界面改 singleTop（Kazumi 在源码里已经是 singleTop）；
  删掉 Nagisaland 主界面 onPause 里的 `WebView.pauseTimers()`（它是全局的，会冻结番剧解析用的 WebView）；
  写 comic / anime / h.json，指向上面 `SuiteSwitchActivity` 认的入口。
- 包名 `com.nagisaland.mobile`、`nagisaland-mobile.keystore` 永远不变；密钥不进仓库。

## 2026-11-03 合并：本地 v0.17.2 + 云端 suite
两份代码都从 4093c22 分出来，各自做了 v0.15–v0.17.2。合并原则：**用户真机验证过的本地写法优先**，云端独有的修复合进来。
- 取本地（`local-v0.17.2` 分支）：`SuiteNav.kt`（底栏图标样式、`go/attach/interceptFinish`，根页面返回回到「游戏库」，
  `SuiteSwitchActivity` 认 extra `to`）、app 的 manifest / build.gradle / MainActivity / KazumiActivity / KazumiEngine、
  H 模块的 manifest、build.gradle.kts、`FILE_PROVIDER_AUTHORITY = <包名>.fileProvider`、SuiteHook、SuiteCrashLog、
  Firebase 去除方式、`libs.versions.toml`、番剧的 forMainFrameOnly / Bangumi 镜像回退写法。
  上面「用户电脑上的部分」里 merge_venerax.py 的处理（Kazumi singleTask→singleTop 等）仍然由电脑上的脚本做。
- 取云端：H 的 watch 页修复（标题 / 播放地址之外各部分单独失败、播放地址多重兜底、相关视频新结构）、
  播放器备用 CDN 与 http↔https、`videoUrlRegex` 放宽、`HanimeResolution` 保留未知分辨率、`SuiteDebug` 网页存档、
  番剧播放器网络错误不弹窗、`pubspec.lock`、关上游更新检查、CI。
- `Parser.kt`：以云端版为底，`hanimeItemVer3` 的字段取法（播放量取最后一个 stat-item 的 ownText、时间去掉「•」、
  标题兜底 `title` 属性）和搜索整页找新卡片用本地版；首页先按位置取各行（本地写法），一行都取不到时再按行标题认分类（云端写法）。
