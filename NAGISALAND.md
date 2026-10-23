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
