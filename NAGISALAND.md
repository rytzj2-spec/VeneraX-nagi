# VeneraX-nagi

## 独立开发分支：安卓陪伴（未发布）

`feature/android-companion` 从 `5c2beec` 建立，新增「更多 → 陪伴」与独立 Python 服务。
定位／轨迹、状态、消息、提醒和授权锁屏等实现与局限见 [陪伴功能开发与验收](doc/陪伴功能开发与验收.md)。
不改变包名、签名、v0.23.4 版本号，不部署或替换原有屏幕共享服务。
仅新增模块类型编译和服务端测试已做；完整 APK、部署与两台真机验收尚未完成，不能当作正式版本使用。

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

## v0.19 – v0.22 验证状态（2026-11-04 整理）

「真机」= 用户装到手机上用过并反馈；「CI」= GitHub Actions 编译出 APK（只说明能编译）；「本机测试」= 云端容器里跑过（不是手机）。

| 版本 / versionCode | 提交 | 内容 | 状态 |
| --- | --- | --- | --- |
| v0.19.1 / 51 | 5d86d6b | 分区栏自定义、横屏竖排栏、引擎组 + 预热、Impeller 开关、perf 日志、番剧历史合并、H 验证 | 真机：分区栏 / perf 日志能用；**切分区闪退**（原因见 v0.19.3） |
| v0.19.2 / 52 | 8948e65 + b03dc2b | 引擎改回独立（后来证明不是闪退原因）、原生崩溃记录 `SuiteExitInfo`；本地修的首帧监听闪退 b03dc2b | 真机：**闪退修好**，引擎组 / 预热 / Impeller 全开也不闪退；崩溃记录生成了 12 份 native 记录 |
| v0.19.3 / 53 | 6b314e2、bd0dcdf | 面板开关自己画（照 H 的 Material 样子） | 随 v0.19.4 装过，没有单独反馈 |
| v0.19.4 / 54 | ae21112、60c3fc0 | 预热 / 引擎组默认开、弹幕失败原因、番剧下载多连接分块 + 自动重试、底栏左滑打开设置 | 真机装过（本地编译）；反馈了边缘返回跳游戏库（v0.20.1 修）；**下载 / 弹幕提示没有单独反馈** |
| v0.20.0 / 55 | e27a503 | 一键配置（导出 / 导入 / 分区开关 / 首次必须导入） | CI（随 d2c0d05）；**没上真机** |
| v0.20.1 / 56 | 52de86c | 返回手势：左边缘打开设置、分区首页返回行为、去掉番剧「再按一次退出」 | CI（随 d2c0d05）；**没上真机** |
| v0.21.0 / 57 | d2c0d05 | 配置链接自动更新、局域网屏幕共享（MJPEG）、影视 / H 一起看（SyncPlay） | CI 通过；**没上真机** |
| v0.22.0 / 58 | d6e3dab、2570f6e、fc32e76 | 屏幕共享经服务器（WebRTC）+ 服务器牵线服务；飞牛影视 | 真机：经服务器能看（经中转），**码率低 / 卡 / 延迟高；点全屏共享就结束**（v0.23.0 修）；服务器已部署（TURN=cloudflare） |
| v0.23.0 / 59 | 见下面 | 共享稳定性（录屏 resize、牵线重连、ICE restart、结束原因、share.txt）、画质（起始码率、自动档、统计）、「更多」「一起看」独立页面 | 见下面 v0.23.0 一节 |

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
- 飞牛影视：v0.22.0 按社区整理的接口接上了（`fnos_api.dart`，见 v0.22.0）。

## 2026-11-04 真机反馈修复
- 番剧自动选源（`packages/kazumi/lib/services/player/auto_source_service.dart`）：所有源同时测「能搜到 → 能拿到播放列表 → 有要看的那一集」，
  按耗时排。详情页「开始观看」= 自动选最快的直接播（提示里可点「手动选源」，长按按钮也是手动选源，都不行自动打开选源面板）。
  不在后台解析最终视频地址（每个源要开 WebView，太重），解析失败时在播放页手动换源。
- 番剧历史：原来的源不可用时自动换一个有这一集的源（`history_playback_service.dart`）；播放页恢复进度改成看「这部番在所有源里最近一次」
  （`HistoryController.latestFor`），换源后接着同一集、同一位置。
- H：hanime1 的 Cloudflare 验证页（403 + Just a moment，不定时出现）自动静默重试 3 次再报「网站被加固」（`NetworkRepo.cfRetry`）。
- 影视登录：用户名必填；失败时显示服务器返回的内容；Emby 用 `Emby …` 授权头 + 地址参数里的客户端信息、不发 Authorization 头；
  自动试 `/emby` 前缀；添加服务器改成「类型 / http·https / 地址 / 端口 → 测试连接 → 用户名密码」两步。

## 2026-11-04 v0.19.1
- 分区栏自定义（`android/app/.../suite/`）：`SuiteNavConfig`（SharedPreferences `suite_nav`：8 个分区的顺序 + 哪些在底栏，3~6 个）
  是唯一配置，主界面网页底栏（`Bridge.navConfig/openMore/editNav`，`window.__nagiNavChanged`）、原生 `SuiteNavBar`、横屏左侧竖排栏三处共用，改了马上生效。
  「更多」固定在最后：点开是格子面板（点 = 打开，长按 = 放到底栏），底部「编辑底栏」（拖动排序、每个分区开关「底栏」）和「流畅度设置」；
  长按底栏的「更多」也直接进编辑。横屏：原生分区栏变成左侧竖排，网页用 `@media (orientation: landscape)` 同样竖排。没做边缘划出的侧边栏。
- 流畅度：漫画 / 番剧 / 影视的引擎都从 `SuiteEngines` 的同一个 `FlutterEngineGroup` 建并常驻；主界面空闲时后台预热番剧、影视（漫画第一次打开时建）。
  Impeller：manifest 里的写死关闭去掉了，改由 `SuiteStartup`（androidx.startup，进程一启动）调 `FlutterLoader.ensureInitializationComplete` 传
  `--enable-impeller=…`；「流畅度设置」里可以打开（完全退出再开生效）。切换耗时写到 `内部存储/Nagisaland/debug/perf.txt`（`SuitePerf`）：
  从点击到 onResume、到画面出来（Flutter 等首帧；表面没重建时等下一帧），并记引擎是预热好的还是要新建、各引擎新建用了多少毫秒。
- 番剧历史按番合并（Bangumi id，没有按标题）：保存进度时把别的源的记录并进来只留一条；第一次加载时迁移旧数据；恢复进度取所有源里更新时间最新的那条。
- H：「去验证」（`suite/CfVerifyActivity`）和登录页「Cookie 登录」共用 `suite/NagiHAuth`（Cloudflare Cookie + UA 覆盖，HCookieJar / UserAgentInterceptor 都用）；
  Google 登录按钮隐藏（WebView 里 Google OAuth 不可能成功）；去掉每次切到 H 自动读剪贴板。

## 2026-11-04 v0.19.2（versionCode 52）：修 v0.19.1 切分区闪退（对原因的判断有误，真正原因见 v0.19.3；崩溃记录部分有效）
- 现象：v0.19.1 点影视 / 番剧 / 漫画随机闪退，crash/ 里没有文件（原生层）。perf.txt 里同一个分区的引擎在一次切换后马上又「新建」
  = 进程崩了被拉起；几乎都在第二个 Flutter 分区刚显示出来 1~2 秒后。
- 判断：`FlutterEngineGroup` 里后建的引擎是从第一个 spawn 出来的，和它共用 UI / 渲染 / IO 线程和 GPU 资源上下文（v0.19.0 是三个独立引擎）。
  没有原生 trace，不能 100% 确定，所以按「修不准就回退」处理：
  - `SuiteEngines.create` 默认回到 v0.19.0 的独立 `FlutterEngine`；漫画界面 `provideFlutterEngine` 默认返回 null（界面自己建、自己销毁，和 v0.19.0 一样）。
  - 引擎组、后台预热改成「流畅度设置」里的实验开关，**默认关**（换了键名 `engine_group_v2` / `prewarm_v2`，v0.19.1 时默认开的值不带过来）；
    引擎组是否启用在进程里第一次用到时定下来，进程内不变。
  - 引擎组 / 预热 / 占位引擎 / Flutter 初始化全部 try/catch：出错退回普通建引擎，异常（带堆栈）写进 `debug/perf.txt`（不管「记录切换耗时」开没开）；
    不在主线程建引擎也会记一条。
  - 其它 v0.19.1 改动（分区栏自定义、横屏竖排栏、Impeller 开关、perf 日志、番剧历史合并、H 验证）都保留。
- 原生崩溃记录 `suite/SuiteExitInfo.kt`：每次启动（`SuiteStartup`，后台线程）用 `ActivityManager.getHistoricalProcessExitReasons`（Android 11+）
  读系统记下的上次退出原因，写 `内部存储/Nagisaland/crash/exit-<时间>-<native|anr|crash|lowmem|signal…>.txt`（原因、description、退出码、
  前后台、内存；ANR 附 trace 原文；原生崩溃附 tombstone 原文 `.pb` + 从里面抽出的可读字符串），perf.txt 里也记一行。
  按时间戳只记新的；用户强停 / App 更新 / 正常退出不记。第一次运行会把系统还留着的历史（最多 16 条，含 v0.19.1 的崩溃）都写出来。

## 2026-11-04 v0.19.3（versionCode 53）
- **闪退的真正原因**（b03dc2b，本地修的）：真机 12 份原生崩溃全是 `platform_view_android_jni_impl.cc` 的 `CheckException`。
  `SuitePerf.onResumed` 在 `FlutterUiDisplayListener.onFlutterUiDisplayed` 回调里直接 `removeOnFirstFrameRenderedListener`，
  FlutterView 正在遍历监听器集合 → `ConcurrentModificationException` 从 JNI 回调里抛出 → 引擎 abort。改成 post 到下一轮再移除，并全部 try/catch。
  **铁律**：引擎经 JNI 调用的 Java/Kotlin 回调（FlutterUiDisplayListener、MethodChannel handler、PlatformView 等）里不要改正在遍历的集合，也不要让异常抛出去。
  上面 v0.19.2 对引擎组的判断是错的：修好后引擎组、预热、Impeller 全开真机也不闪退。默认值仍是关（开关在「流畅度设置」）。
- 「流畅度设置」「编辑底栏」的开关在主界面几乎看不见：面板是原生 Dialog，系统 `Switch` 跟界面主题走，主界面是 `Theme.Black`（很老的系统主题），
  只剩一个淡点；H 是 Material 主题所以正常。`SuitePanels.nagiSwitch` 自己画轨道和滑块（`Pill` / `Dot`），样子照 H 里的 Material 开关：细轨道 + 比轨道粗的圆滑块，
  开 = 紫色滑块 #A78BFA + 暗紫轨道，关 = 浅灰滑块 + 深灰轨道，各处一致。

## 2026-11-04 v0.19.4（versionCode 54）
- 「流畅度设置」的后台预热、共享引擎组改回**默认开**（键名还是 `prewarm_v2` / `engine_group_v2`，已经手动设过的保持原样）。
- 弹幕「选择番剧」只写「搜索失败」看不出原因：
  - 弹弹play 接口不带凭据一定 403（响应头 `X-Error-Message: Missing Authentication Headers`）。凭据只能编译时 `--dart-define` 传入
    （`DANDANAPI_APPID` / `DANDANAPI_KEY`，本地放 `dart_defines.txt`，**没有这个文件时编译命令里的参数要去掉，弹幕就没法用**）。
  - `DanmakuClient`：没凭据直接抛「没有弹弹play 凭据…」；服务器拒绝时带状态码和 `X-Error-Message`（401/403 提示检查凭据和手机时间）。
  - `danmaku_source_sheet.dart`：搜索 / 分集失败时标题下面显示具体原因（`_errorDetail`）。
- 番剧下载（`packages/kazumi/lib/services/download/download_manager.dart`）：
  - 单文件 mp4（「0/1 分片」）以前只有一条连接、断了就失败，「分片并发数」对它没用。现在先发 `Range: bytes=0-0` 探测，
    服务器回 206 就按 4 MB 分块，用「分片并发数」条连接同时下（最多 16），写进预分配的 `video.mp4.tmp`，下完的块号记在 `video.mp4.tmp.chunks`；
    暂停 / 断开后只补没下完的块（以前单连接下了一半的 .tmp 前面完整的块直接算下完）。每块断了从断点接着请求，最多重试 6 次。
    服务器不支持分段时还是原来的单连接。
  - 整集自动重试：网络原因失败（Dio 错误、`HttpException: Connection closed…`、超时、m3u8 分片失败）时 3 / 10 / 30 / 60 / 120 秒后自动重来，
    这一轮有进展就重新计数；等待时显示「连接断开，N 秒后自动重试」。马上要重试的失败不通知出去（否则下载服务以为没活了会停掉前台服务）。
- 左滑分区栏 = 打开当前分区的设置（只认分区栏上的手势，不和分区里本来的左右滑、系统返回手势冲突）：
  - 原生 `SuiteNavBar` 左滑 → `SuiteNav.openSettings`：漫画 / 番剧 / 影视通过 `nagisaland/suite` 通道发 `openSettings` 给 Dart
    （引擎在各 Activity 的 `configureFlutterEngine` 里 `SuiteNav.registerEngine` 登记）；H 打开 `SettingsActivity`。
  - Dart 端 `packages/kazumi/lib/nagi_suite_channel.dart`：漫画推 `SettingsPage` 到根导航，番剧 `pushNamed('/settings/')`，影视回首页切「设置」分页。
  - 主界面网页底栏左滑：游戏库 → 游戏目录设置；小站 / 解压 / 模拟器 → 流畅度设置（`Bridge.suiteSettings`）。

## 2026-11-04 v0.20.0（versionCode 55）：一键配置
- 「更多 → 一键配置」：导出 / 导入 `.nagi`（zip）。原生实现（`suite/SuiteConfigPack.kt` 打包解包、`SuiteConfigUi.kt` 界面、`SuiteProfile.kt` 分区开关），
  各分区的数据都在 App 私有目录 filesDir（= Flutter 的 getApplicationSupportDirectory），直接读写文件：
  - 漫画：`comic_source/*.js` + `.data`（不带账号时只留 `settings`，账号 / token / cookies / `_device…` 都去掉）、`appdata.json` 的 settings
    （去掉应用锁密码、WebDAV、图片翻译 API Key、deviceId、代理、本机路径等，见 `COMIC_SETTING_SKIP`）；
    可选 `cookie.db`（登录）、`local_favorite.db` + `read_later.db`（收藏）、`history.db`（历史）。
    导入写 appdata.json 时必须带 `searchHistory`（缺了漫画会把整个文件当坏的删掉）。
  - 番剧：`plugins/v2/plugins.json`（规则，按 name 合并）。番剧自己的设置在 Hive 里，这一版没导出。
  - 影视：`nagi_video.json`（不带账号时清空 username / userId / token / deviceId；导入时同地址已登录的保留本机登录）。
    影视界面：没登录的服务器显示「未登录，点一下登录」，点了直接进登录页；聚合视界 / 搜索跳过没登录的。
  - 个人数据默认都不带，勾选时红字提醒。
  - 分区开关：导出时选对方能用哪些分区（gal = 游戏库 / 小站 / 解压 / 模拟器，一个总开关 + 4 个小开关；漫画 / 番剧 / 影视 / H）。
    `SuiteProfile.enabled` → `SuiteNavConfig.available/order/inBar` 过滤（底栏最少个数 = min(3, 能用的个数)），主界面网页 `navConfig().enabled` 藏按钮、
    默认页换成第一个能用的；`SuiteNav.go` 拒绝关掉的分区；gal 全关时主界面一打开就进第一个分区，分区根页面返回 = 退到后台；预热跳过关掉的。
- 必须先导入：全新安装（`SuiteStartup` 一开始判断 filesDir 里没有任何分区数据）→ 任何界面都先转到 `SuiteOnboardActivity`；
  升级上来的老用户当作自己用（`owner`），全部分区可用。导入页也接「用 Nagisaland 打开」（VIEW，octet-stream / zip）。
- App 已经在用时导入：写完文件后 `SuiteRestartActivity`（单独进程 `:nagi_restart`）杀掉主进程再打开。
- 导出文件在 `内部存储/Nagisaland/config/<配置名>-<时间>.nagi`，可直接「分享」（FileProvider）。

## 2026-11-04 v0.20.1（versionCode 56）：返回手势
- 真机（v0.19.4）：分区首页从边缘往里划（全面屏手势 = 返回）直接跳回游戏库（`interceptFinish` 写死 `go("lib")`）；
  番剧首页还会先弹 Kazumi 自带的「再按一次退出应用」。
- `suite/SuiteBack.kt`：漫画 / 番剧 / 影视 / H 首页 / 主界面在 `onActivityCreated` 时挂一个 `PRIORITY_OVERLAY` 的返回回调
  （Android 14+ 用 `OnBackAnimationCallback` 记 `BackEvent.swipeEdge`），记下边缘后照常交给界面自己处理返回
  （ComponentActivity → `onBackPressedDispatcher.onBackPressed()`；FlutterActivity / 主界面 → `onBackPressed()`）。
  界面退到根要关掉自己时才进 `SuiteNav.interceptFinish`（主界面是 `exitToBackground`）：1.5 秒内是左边缘 + 开关开 → 打开这个分区的设置；
  否则按「分区首页返回时」：退到后台（默认）/ 回到游戏库 / 什么也不做。二级页面的返回不走到这里，左右都照常退一页。
  这几个界面的 manifest 加了 `enableOnBackInvokedCallback="true"`（H 只加在首页 MainActivity）。Android 13 及以下拿不到方向，两边都按返回行为处理。
  没用 setSystemGestureExclusionRects。
- 番剧 `pages/menu/menu.dart`：去掉「再按一次退出」，根页面直接 `SystemNavigator.pop()` 交给原生。
- 「流畅度设置 → 手势」：左边缘右滑打开设置（默认开）、底栏左滑打开设置（v0.19.4 的做法，默认关）、分区首页返回时（三选一）。
  主界面网页的「更多」打开的也是这个原生面板。

## 2026-11-04 v0.21.0（versionCode 57）：配置链接、一起看、屏幕共享
- 配置链接（`suite/SuiteConfigSync.kt`）：导出时可填 updateUrl（写进 manifest，另带 `revision` = 导出时间）；导入后订阅
  （配置里没写就订阅下载它的那个链接）。主界面 onCreate 时 6 小时最多查一次（ETag / Last-Modified，再比 revision），
  新的下载到 `filesDir/config_pending.nagi`，下次进程启动时 `SuiteStartup` → `applyPending` 在任何分区启动前套用（不用重启）。
  「一键配置」菜单：从配置链接导入、立即检查更新（有更新可选马上重启）、取消自动更新；导入页加「输入配置链接」。
  链接要放在一直在线的地方（GitHub / Gitee raw、NAS、网盘直链），手机当服务器不行。
- 「更多 → 一起看」（`suite/SuiteTogetherUi.kt`）：
  - 屏幕共享（`suite/SuiteScreenShare.kt`，手机自己当服务器）：MediaProjection → VirtualDisplay（最长边 1280）→ ImageReader →
    JPEG（约 12 帧/秒，没人看不编码）→ MJPEG；声音 Android 10+ 用 AudioPlaybackCapture（录手机正在播放的声音，不录麦克风，
    需要 RECORD_AUDIO）→ 48k 立体声 PCM。小 HTTP 服务（端口 8686，被占就随机）：`/<6 位口令>/` 网页、`v.mjpg`、`a.pcm`、`info`；
    网页用 <img> 显示 MJPEG，声音 fetch 读 PCM、Web Audio 排队播放（延迟 0.1~0.6 秒）。前台服务类型 mediaProjection，通知里能停。
    地址列 Wi-Fi / 热点、Tailscale（100.64/10）、公网 IPv6。不在同一个网络要组网工具。横竖屏切换时重建画面大小。
  - 看别人的屏幕：粘贴链接 → 浏览器打开。
- 同步播放（SyncPlay，公共服务器 syncplay.pl:8996，画面各自播）：
  - 影视：`nagi_video/video_together.dart`，复用番剧的 `SyncplayClient`；播放页顶栏「一起看」；房间一直保持（换集 / 退出播放页不断开）；
    file 名 `nagivideo|<服务器地址>|<条目 id>|<标题>`，对方换了视频、自己也登录着同一个服务器时自动跟着换。
    本地暂停 / 播放从 `player.stream.playing`、拖进度从 position 跳变（>2.5 秒）判断；对方的状态套用后 1.5 秒内不回发。
    设置存在 `nagi_video.json` 的 settings（syncRoom / syncName / syncServer）。提示用 `videoMessengerKey`。
  - H：Kotlin 版客户端 `han/.../suite/NagiSyncPlay.kt`（协议照 Kazumi：STARTTLS、Hello、joined + ready、State 心跳 + RTT、
    ignoringOnTheFly），`NagiTogetherH.kt` 接 Jzvd（onStatePlaying / onStatePause / onProgress 跳变）；
    播放器顶栏 `tv_together`「一起看」；file 名 `nagih|<videoCode>|<标题>`，对方换视频自动打开同一个 VideoActivity。

## 2026-11-04 v0.22.0（versionCode 58）：屏幕共享经服务器（WebRTC）、飞牛影视
- 屏幕共享「经服务器」（`suite/SuiteRtcShare.kt`，依赖 `io.getstream:stream-webrtc-android:1.3.10` + `okhttp:4.12.0`）：
  手机是 WebRTC 房主，每个观看者一条 PeerConnection；`ScreenCapturerAndroid`（最长边 1280、30 帧）→ 硬件 H.264（`DefaultVideoEncoderFactory`，
  发送端 `setCodecPreferences` 把 H.264 排第一，观看页也排第一）；码率上限按清晰度（3 / 2 / 1 Mbps），`MAINTAIN_RESOLUTION`。
  声音：WebRTC 只能从录音来，`JavaAudioDeviceModule` 会开麦克风，`setAudioRecordDataCallback` 里每 10ms 整段换成
  `AudioPlaybackCapture`（复用 `ScreenCapturerAndroid.getMediaProjection()`，Android 14 一次授权只能用一次）录到的播放声音，没有就静音；
  前台服务类型 `mediaProjection|microphone`（没录音权限时只要 mediaProjection）。牵线：OkHttp WebSocket → `<服务器>/share/ws`。
  「共享我的屏幕」先选「经服务器 / 局域网直连」；服务器地址**不写死**（仓库是公开的），第一次填，之后记住，一键配置导出时带上（manifest `shareServer`）。
- 服务器（`server/nagi_share/`，部署见 `doc/屏幕共享部署.md`）：`nagi_share.py`（websockets 12 单进程，127.0.0.1:8090）只牵线 + 下发 ICE：
  STUN + TURN 临时凭据（Cloudflare Realtime TURN 用 Key ID / Token 换；自建 coturn 用 use-auth-secret 的 HMAC 凭据），`config.json` 切换，只在服务器上。
  `web/index.html` 观看页（H.264 优先、立体声 Opus、显示「直连 / 经中转」、弹幕聊天）。`deploy_nagi_share.py`（paramiko）：服务器信息读本地
  `deploy_local.json`（不进 Git），先备份线上 Nginx / 配置，systemd 服务（独立用户、内存上限 80M），在小站 Nginx 的 `location /` 前加 `/share/`
  （标记包起来，`nginx -t` 不过自动还原），可选装 coturn（限速限并发、禁止转发到内网、每次启动从 GCP 元数据刷新外部 IP）。
- 飞牛影视（`nagi_video/fnos_api.dart`）：飞牛没有公开文档，按社区逆向整理的协议写（fntv-electron、Kodi 插件 plugin.video.fnmedia、
  JellyPlex-Watched 的 TrimMedia 适配；没有照抄代码）：`/v/api/v1/...`，`Authx` 签名（MD5(apiKey_路径_nonce_毫秒_MD5(body)_apiSecret)，POST 的 body
  里也带 nonce），登录 `/login` 拿 token（`Authorization`）；媒体库 `mediadb/list`、条目 `item/list`（分页）、季 / 集、详情、
  `play/info` → `/media/range/<media_guid>`（播放器带请求头，`PlaybackSource.headers`），进度 `play/record`，标记已看 `item/watched`；
  图片 `/sys/img<路径>?w=` 也要签名（`EmbyApi.imageHeadersFor`）。数据都转成 Emby 的字段，界面不分两套；`EmbyApi` 每个方法开头按 `ServerKind.fnos` 转过去。
  没整理到的：继续观看 / 下一集（返回空）、搜索（取 500 条按标题过滤）、标为未看；FN ID 远程访问（只能局域网 / 直连地址）；网盘 / 原盘文件。
- 状态：CI 编译（见 Actions）；服务器端在云端容器里本机测试过：牵线全流程（开房间 / 密码 / offer-answer / 聊天 / 房主离开），
  以及 Nginx（同一段配置）+ 观看页 + 一个用画布模拟手机的房主，在无头 Chromium 里端到端看到画面、显示「直连」、弹幕双向。
  **没上真机，没部署到线上服务器，飞牛没有真机器测过。**

## 2026-11-04 v0.23.0（versionCode 59）：共享稳定性、画质、「更多」「一起看」页面
- 真机（v0.22.0，跨网络、经中转）：码率低、糊、卡、延迟高；点了一下「全屏」共享就结束。
- **点全屏就结束的原因**：转屏后调了库自带 `ScreenCapturerAndroid.changeCaptureFormat`，它会 `VirtualDisplay.release()` 再
  `createVirtualDisplay()`；Android 14 一次录屏授权只许建一个 VirtualDisplay，第二次被拒 → 录屏权限被收回 → 共享结束。
  改用自己的 `NagiScreenCapturer`（`SuiteRtcShare.kt`）：一次授权只建一个 VirtualDisplay，改尺寸用 `resize()` + `SurfaceTextureHelper.setTextureSize()`。
- 牵线：服务器（`nagi_share.py`）开房间 / 进房间时发重连钥匙（房主 `key`、观看者 `peer + key`）；掉线保留 `GRACE`（60 秒，config 可改），
  重连带钥匙回到原位（房主拿到当前观看者名单，补建断开期间进来的人的连接）；房主掉线时观看者收到 `host-away` / `host-back`；
  `bye` 带原因关房间（观看页显示「共享结束：原因」）；超时关房间也带原因。手机端 OkHttp 退避重连（1/2/4/8/15/30 秒），断开期间要发的消息排队；
  一次都没连上过（地址错）试 4 次就结束并说原因。手机和观看页每 25 秒 `ping`。
- WebRTC：ICE 断开 5 秒 / 失败 → `restartIce()` + 带 IceRestart 的新 offer（最多 3 次）。只在：用户停止、`MediaProjection.Callback.onStop`（系统收回）、
  启动失败时结束（`end(reason)` 唯一出口），原因记 `SuiteRtcShare.lastEnd`（「一起看」页显示）。事件写 `内部存储/Nagisaland/debug/share.txt`。
- 画质：`pc.setBitrate(最低, 起始, 最高)`（不再从 300k 慢慢涨）；清晰度 自动（起 2.5M，最高 8M，录屏 1280）/ 清晰（4M / 6M，1920）/ 标准（2.5M / 3.5M）/ 省流（1.2M / 1.5M，960）；
  「网不好时」流畅优先（`MAINTAIN_FRAMERATE`，默认）/ 清晰优先 / 平衡；录屏尺寸取 16 的倍数（硬件编码器）。开始时把编码器支持的格式写进 share.txt，
  统计里有实际编码（H264 / VP8）和编码器实现名（看硬件编码有没有生效；没有 H.264 时自然用 VP8）。
  中转：服务器 `turn: both` 时两个都给，手机「中转」选 自动 / Cloudflare / 自建（房间里的观看者跟房主一样）；部署 `--turn both`。
- 观看页：牵线断了只重连不拆画面；接收端 `jitterBufferTarget = 40ms`（老浏览器 `playoutDelayHint`）；右上角「统计」每秒：视频 / 声音码率、
  分辨率帧率、丢包、抖动、编码、缓冲、往返、直连 / 经中转 + UDP / TCP / TLS。
- 「更多」改成独立页面 `SuiteMoreActivity`（`SuitePages.kt`）：功能（一起看〔显示共享状态〕、一键配置）→ 其他分区格子（点开、长按放底栏）→ 设置（编辑底栏、流畅度设置，这一轮还是弹窗）；
  底部分区栏（「更多」亮），从这里切走后自己关掉。底栏「更多」、主界面网页「更多」都开这个页面（`SuitePanels.showMore` 删了）。
- 「一起看」独立页面 `SuiteTogetherActivity`：没在共享 = 服务器地址 / 密码 / 清晰度 / 网不好时 / 中转 + 「经服务器共享」「局域网直连」；
  共享中 = 状态卡片（房间、在看人数、连接类型、码率〔受限原因〕、画面、编码、牵线状态、链接点一下复制，每秒刷新）+ 分享 / 停止；看别人的屏幕；同步播放说明。
  `SuiteShareActivity` 只剩「要权限 → 启动服务」；两种共享的通知都打开这个页面。`SuiteTogetherUi.kt` 删了。
- 状态：服务器在容器里测过（续连 / 保留 / bye 原因 / 超时 / both）；观看页 + Nginx + 画布房主在无头 Chromium 里端到端测过
  （统计有数据、H264、观看者牵线断开自动重连画面不断、房主断开重连画面不断、弹幕、结束原因）。**手机端没上真机。**
- 服务器端有改动（`nagi_share.py`、`web/index.html`、`deploy_nagi_share.py`）：要重新跑部署。

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
