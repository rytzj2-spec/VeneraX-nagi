# Nagisaland 漫画来源库

App「漫画 → 来源库 → 添加」填：

- `https://cdn.jsdelivr.net/gh/rytzj2-spec/VeneraX-nagi@unify-app/comic_sources/index.json`（国内一般能打开；jsDelivr 有最多约 12 小时缓存）
- 或 `https://raw.githubusercontent.com/rytzj2-spec/VeneraX-nagi/unify-app/comic_sources/index.json`

更新某个源：替换对应 .js，把 `index.json` 里的 `version` 改成新版本号（要和 .js 里的 `version` 一致），提交即可。
只放 .js 脚本，**不要放 .data**（里面有登录状态 / 账号）。
注：`51漫画`（manga51-3.js）和 `如漫画`（rumanhua.js）的 key 相同（都是 `9S8$vJnU2ANeSRoF`），同时只能装一个。
