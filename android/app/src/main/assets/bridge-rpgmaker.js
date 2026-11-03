// Galport Android bridge v3 —— 注入到 index.html，在引擎脚本之前加载。
// 让 PC 版 RPG Maker MV/MZ 在安卓 WebView 外壳里好用：
//   1. 锁定画面（不能被手指拖动/缩放）
//   2. 屏幕按键：菜单、确定、快进(按住)、自动、隐藏对话框、方向键、冲刺；右上角「返回」= 取消/返回上一层
//   3. 修改器：金钱 / 变量 / 开关 / 物品 / 角色 / 倍速 / 穿墙 / 不遇敌；按键大小、透明度可调
//   4. 兼容：PC 插件的 require('fs') 等（Node 兼容层）；MV 在手机上找 .m4a 没声音 → 改回 .ogg
(function () {
    "use strict";

    // >>> node-shim（和 bridge-generic.js / bridge-rpgmaker.js 共用，改一处两边都改）
    // ------------------------------------------------------------------
    // Node.js 兼容层：PC 版插件会直接 require('fs') / require('path') 等（手机上没有 → 报错卡死）
    // 注意 process 故意做成函数，这样 typeof process !== "object"，引擎仍然走网页模式存档
    // ------------------------------------------------------------------
    (function nodeShim() {
        if (typeof window.require === "function") return;
        var ROOT = "/game";
        var FP = "galport.fs:", DP = "galport.fsdir:";
        var mem = {}, dirs = {};
        dirs[ROOT] = true;

        // ---------- path ----------
        function normalize(p) {
            p = String(p == null ? "" : p).replace(/\\/g, "/");
            var abs = p.charAt(0) === "/", out = [];
            p.split("/").forEach(function (s) {
                if (!s || s === ".") return;
                if (s === "..") { if (out.length && out[out.length - 1] !== "..") out.pop(); else if (!abs) out.push(".."); return; }
                out.push(s);
            });
            var r = (abs ? "/" : "") + out.join("/");
            if (out.length && /\/$/.test(p)) r += "/";   // 和 Node 一样保留结尾的 /
            return r || (abs ? "/" : ".");
        }
        function resolve() {
            var p = "";
            for (var i = arguments.length - 1; i >= 0; i--) {
                var s = String(arguments[i] == null ? "" : arguments[i]).replace(/\\/g, "/");
                if (/^[a-zA-Z]:\//.test(s)) s = s.slice(2);
                if (!s) continue;
                p = p ? s + "/" + p : s;
                if (s.charAt(0) === "/") break;
            }
            if (p.charAt(0) !== "/") p = ROOT + (p ? "/" + p : "");
            return normalize(p).replace(/(.)\/$/, "$1");
        }
        function dirname(p) { p = normalize(p); var i = p.lastIndexOf("/"); return i < 0 ? "." : i === 0 ? "/" : p.slice(0, i); }
        function basename(p, ext) { p = normalize(p); var b = p.slice(p.lastIndexOf("/") + 1); if (ext && b.slice(-ext.length) === ext) b = b.slice(0, -ext.length); return b; }
        function extname(p) { var b = basename(p), i = b.lastIndexOf("."); return i <= 0 ? "" : b.slice(i); }
        var path = {
            sep: "/", delimiter: ":", normalize: normalize, resolve: resolve, dirname: dirname, basename: basename, extname: extname,
            join: function () { return normalize(Array.prototype.filter.call(arguments, function (s) { return s !== ""; }).join("/")); },
            isAbsolute: function (p) { return /^([a-zA-Z]:)?[\\/]/.test(String(p)); },
            relative: function (a, b) {
                a = resolve(a).split("/").filter(Boolean); b = resolve(b).split("/").filter(Boolean);
                var i = 0; while (i < a.length && i < b.length && a[i] === b[i]) i++;
                return a.slice(i).map(function () { return ".."; }).concat(b.slice(i)).join("/");
            },
            parse: function (p) { var b = basename(p), e = extname(p); return { root: "/", dir: dirname(p), base: b, ext: e, name: e ? b.slice(0, -e.length) : b }; },
            format: function (o) { return (o.dir ? o.dir + "/" : "") + (o.base || (o.name || "") + (o.ext || "")); },
            toNamespacedPath: function (p) { return p; }
        };
        path.posix = path; path.win32 = path;

        // ---------- Buffer（够插件存读 base64 / 文本用） ----------
        function utf8Enc(s) { return new TextEncoder().encode(s); }
        function utf8Dec(u) { return new TextDecoder().decode(u); }
        function Buf(x, enc) { return Buf.from(x, enc); }
        Buf.from = function (x, enc) {
            var u;
            if (typeof x === "string") {
                enc = String(enc || "utf8").toLowerCase();
                if (enc === "base64") { var s = atob(x.replace(/[^A-Za-z0-9+/=]/g, "")); u = new Uint8Array(s.length); for (var i = 0; i < s.length; i++) u[i] = s.charCodeAt(i); }
                else if (enc === "hex") { u = new Uint8Array(x.length >> 1); for (var j = 0; j < u.length; j++) u[j] = parseInt(x.substr(j * 2, 2), 16); }
                else if (enc === "latin1" || enc === "binary" || enc === "ascii") { u = new Uint8Array(x.length); for (var k = 0; k < x.length; k++) u[k] = x.charCodeAt(k) & 255; }
                else u = utf8Enc(x);
            } else if (x instanceof ArrayBuffer) u = new Uint8Array(x);
            else if (ArrayBuffer.isView(x)) u = new Uint8Array(x.buffer.slice(x.byteOffset, x.byteOffset + x.byteLength));
            else u = new Uint8Array(x || []);
            return wrap(u);
        };
        Buf.alloc = function (n, fill) { var u = new Uint8Array(n); if (fill) u.fill(typeof fill === "number" ? fill : 0); return wrap(u); };
        Buf.allocUnsafe = Buf.alloc;
        Buf.isBuffer = function (b) { return !!(b && b.__gpBuf); };
        Buf.byteLength = function (s, enc) { return typeof s === "string" ? Buf.from(s, enc).length : s.byteLength; };
        Buf.concat = function (list) {
            var n = 0; list.forEach(function (b) { n += b.length; });
            var u = new Uint8Array(n), o = 0; list.forEach(function (b) { u.set(b, o); o += b.length; });
            return wrap(u);
        };
        function wrap(u) {
            Object.defineProperty(u, "__gpBuf", { value: true });
            u.toString = function (enc) {
                enc = String(enc || "utf8").toLowerCase();
                if (enc === "base64") { var s = "", C = 0x8000; for (var i = 0; i < this.length; i += C) s += String.fromCharCode.apply(null, this.subarray(i, i + C)); return btoa(s); }
                if (enc === "hex") return Array.prototype.map.call(this, function (b) { return (b < 16 ? "0" : "") + b.toString(16); }).join("");
                if (enc === "latin1" || enc === "binary" || enc === "ascii") { var t = ""; for (var j = 0; j < this.length; j++) t += String.fromCharCode(this[j]); return t; }
                return utf8Dec(this);
            };
            u.toJSON = function () { return { type: "Buffer", data: Array.prototype.slice.call(this) }; };
            u.equals = function (b) { if (b.length !== this.length) return false; for (var i = 0; i < b.length; i++) if (b[i] !== this[i]) return false; return true; };
            return u;
        }
        if (typeof window.Buffer === "undefined") window.Buffer = Buf;

        // ---------- fs（虚拟文件，存在 App 本地存储里；写满了就只留在内存） ----------
        function key(p) { return resolve(p); }
        function enoent(p, op) { var e = new Error("ENOENT: no such file or directory, " + (op || "open") + " '" + p + "'"); e.code = "ENOENT"; e.errno = -2; e.path = p; return e; }
        function lsGet(k) { try { return localStorage.getItem(k); } catch (e) { return null; } }
        function lsSet(k, v) { try { localStorage.setItem(k, v); } catch (e) {} }
        function lsDel(k) { try { localStorage.removeItem(k); } catch (e) {} }
        function getFile(p) {
            var k = key(p);
            if (mem[k]) return mem[k];
            var s = lsGet(FP + k);
            if (s == null) return null;
            try { mem[k] = JSON.parse(s); } catch (e) { return null; }
            return mem[k];
        }
        function isDir(p) { var k = key(p); return !!dirs[k] || lsGet(DP + k) != null || children(k).length > 0; }
        function mkdirp(k) { while (k && k !== "/" && !dirs[k]) { dirs[k] = true; lsSet(DP + k, "1"); k = dirname(k); } }
        function putFile(p, data, enc) {
            var k = key(p), rec;
            if (typeof data === "string" && (!enc || /^utf-?8$/i.test(enc.encoding || enc))) rec = { t: "s", d: data };
            else rec = { t: "b", d: Buf.from(data, typeof enc === "string" ? enc : enc && enc.encoding).toString("base64") };
            rec.m = Date.now();
            mem[k] = rec;
            mkdirp(dirname(k));
            lsSet(FP + k, JSON.stringify(rec));
        }
        function readFile(p, enc) {
            var r = getFile(p);
            if (!r) throw enoent(key(p));
            enc = typeof enc === "string" ? enc : enc && enc.encoding;
            var b = r.t === "s" ? null : Buf.from(r.d, "base64");
            if (enc) return b ? b.toString(enc) : (/^utf-?8$/i.test(enc) ? r.d : Buf.from(r.d).toString(enc));
            return b || Buf.from(r.d);
        }
        function children(k) {
            var pre = k === "/" ? "/" : k + "/", out = {};
            function add(full) { if (full.indexOf(pre) === 0) { var rest = full.slice(pre.length), i = rest.indexOf("/"); out[i < 0 ? rest : rest.slice(0, i)] = i < 0 ? "f" : "d"; } }
            Object.keys(mem).forEach(add);
            Object.keys(dirs).forEach(function (d) { if (d.indexOf(pre) === 0) { var r = d.slice(pre.length); out[r.split("/")[0]] = "d"; } });
            try {
                for (var i = 0; i < localStorage.length; i++) {
                    var lk = localStorage.key(i);
                    if (lk.indexOf(FP) === 0) add(lk.slice(FP.length));
                    else if (lk.indexOf(DP) === 0) { var d = lk.slice(DP.length); if (d.indexOf(pre) === 0) out[d.slice(pre.length).split("/")[0]] = "d"; }
                }
            } catch (e) {}
            delete out[""];
            return Object.keys(out).map(function (n) { return { name: n, t: out[n] }; });
        }
        function stat(p) {
            var r = getFile(p);
            if (!r && !isDir(p)) throw enoent(key(p), "stat");
            var t = new Date(r ? r.m : Date.now());
            return { size: r ? (r.t === "s" ? r.d.length : Math.floor(r.d.length * 3 / 4)) : 0, mtime: t, ctime: t, birthtime: t, atime: t, mtimeMs: +t,
                isFile: function () { return !!r; }, isDirectory: function () { return !r; }, isSymbolicLink: function () { return false; } };
        }
        function unlink(p) { var k = key(p); if (!getFile(k)) throw enoent(k, "unlink"); delete mem[k]; lsDel(FP + k); }
        var fs = {
            existsSync: function (p) { try { return !!getFile(p) || isDir(p); } catch (e) { return false; } },
            readFileSync: readFile,
            writeFileSync: function (p, d, enc) { putFile(p, d, enc); },
            appendFileSync: function (p, d, enc) { var old = getFile(p) ? readFile(p, "utf8") : ""; putFile(p, old + (typeof d === "string" ? d : Buf.from(d).toString()), "utf8"); },
            mkdirSync: function (p) { mkdirp(key(p)); },
            readdirSync: function (p, o) {
                var k = key(p);
                if (!isDir(k)) throw enoent(k, "scandir");
                var list = children(k);
                if (o && o.withFileTypes) return list.map(function (c) { return { name: c.name, isFile: function () { return c.t === "f"; }, isDirectory: function () { return c.t === "d"; } }; });
                return list.map(function (c) { return c.name; });
            },
            statSync: stat, lstatSync: stat,
            unlinkSync: unlink, rmSync: function (p) { try { unlink(p); } catch (e) {} },
            rmdirSync: function (p) { var k = key(p); delete dirs[k]; lsDel(DP + k); },
            renameSync: function (a, b) { var r = getFile(a); if (!r) throw enoent(key(a), "rename"); unlink(a); var k = key(b); mem[k] = r; mkdirp(dirname(k)); lsSet(FP + k, JSON.stringify(r)); },
            copyFileSync: function (a, b) { putFile(b, readFile(a)); },
            accessSync: function (p) { if (!fs.existsSync(p)) throw enoent(key(p), "access"); },
            openSync: function () { return 3; }, closeSync: function () {}, fsyncSync: function () {},
            watch: function () { return { close: function () {} }; },
            constants: { F_OK: 0, R_OK: 4, W_OK: 2, X_OK: 1 }
        };
        // 异步版本：回调 / promise
        ["readFile", "writeFile", "appendFile", "mkdir", "readdir", "stat", "lstat", "unlink", "rename", "copyFile", "rm", "rmdir", "access"].forEach(function (n) {
            var sync = fs[n + "Sync"];
            fs[n] = function () {
                var a = Array.prototype.slice.call(arguments), cb = typeof a[a.length - 1] === "function" ? a.pop() : null, r, err = null;
                try { r = sync.apply(fs, a); } catch (e) { err = e; }
                if (cb) setTimeout(function () { cb(err, r); }, 0);
            };
        });
        fs.exists = function (p, cb) { var r = fs.existsSync(p); if (cb) setTimeout(function () { cb(r); }, 0); };
        fs.promises = {};
        Object.keys(fs).forEach(function (n) {
            if (/Sync$/.test(n)) {
                var sync = fs[n];
                fs.promises[n.slice(0, -4)] = function () { var a = arguments; return new Promise(function (ok, no) { try { ok(sync.apply(fs, a)); } catch (e) { no(e); } }); };
            }
        });

        // ---------- 其它模块：能用的给个像样的，剩下的给“什么都不做”的空对象 ----------
        function noop() {
            var f = function () { return p; };
            var p = new Proxy(f, {
                get: function (t, k) {
                    if (k === Symbol.toPrimitive) return function () { return ""; };
                    if (k === "then") return undefined;
                    if (k === "toString" || k === "valueOf") return function () { return ""; };
                    return p;
                },
                apply: function () { return p; }, construct: function () { return p; }, set: function () { return true; }
            });
            return p;
        }
        function openExternal(url) {
            try { if (window.NagisaNative && NagisaNative.openUrl) { NagisaNative.openUrl(String(url)); return; } } catch (e) {}
            location.href = String(url);
        }
        var exit = function () { location.href = "galport://exit"; };
        // nw.Window.get()：插件常拿它的 .window 挂按键监听、.on("close") 等 —— 给一个接到真实页面上的对象
        var winBase = { window: window, title: document.title, x: 0, y: 0, isFullscreen: true, isAlwaysOnTop: false,
            get width() { return window.innerWidth; }, get height() { return window.innerHeight; },
            on: function () { return win; }, once: function () { return win; }, removeListener: function () { return win; },
            removeAllListeners: function () { return win; }, close: exit, reload: function () { location.reload(); } };
        var win = new Proxy(winBase, { get: function (t, k) { return k in t ? t[k] : (typeof k === "symbol" ? undefined : noop()); }, set: function (t, k, v) { t[k] = v; return true; } });
        var nwgui = { Window: { get: function () { return win; }, open: function (u, o, cb) { if (typeof cb === "function") try { cb(noop()); } catch (e) {} return noop(); } }, Shell: { openExternal: openExternal, openItem: function () {}, showItemInFolder: function () {} },
            App: { quit: exit, closeAllWindows: exit, argv: [], dataPath: ROOT + "/userdata", manifest: {} }, Screen: noop(), Menu: noop(), MenuItem: noop(), Clipboard: noop() };
        var proc = function process() {};
        proc.platform = "linux"; proc.arch = "arm64"; proc.env = {}; proc.argv = []; proc.versions = {}; proc.version = "";
        proc.execPath = ROOT + "/Game"; proc.mainModule = { filename: ROOT + "/index.html" };
        proc.cwd = function () { return ROOT; }; proc.exit = exit; proc.on = function () { return proc; }; proc.once = proc.on; proc.off = proc.on;
        proc.nextTick = function (fn) { var a = Array.prototype.slice.call(arguments, 1); setTimeout(function () { fn.apply(null, a); }, 0); };
        proc.memoryUsage = function () { return { rss: 0, heapTotal: 0, heapUsed: 0 }; }; proc.hrtime = function () { var t = performance.now(); return [Math.floor(t / 1000), Math.floor(t % 1000 * 1e6)]; };
        if (typeof window.process === "undefined") window.process = proc;
        // 有的插件不检查就直接用全局 nw（如 DRS_BoostEngine）。给一个假的 nw 对象；
        // Utils.isNwjs() 只看 require/process，所以引擎仍按网页模式运行
        if (typeof window.nw === "undefined") window.nw = nwgui;
        var os = { platform: function () { return "linux"; }, type: function () { return "Linux"; }, homedir: function () { return ROOT; }, tmpdir: function () { return ROOT + "/tmp"; },
            EOL: "\n", arch: function () { return "arm64"; }, cpus: function () { return []; }, totalmem: function () { return 0; }, freemem: function () { return 0; }, hostname: function () { return "android"; } };
        var mods = { fs: fs, "fs/promises": fs.promises, path: path, "nw.gui": nwgui, os: os, buffer: { Buffer: Buf }, process: proc,
            util: { inspect: function (o) { try { return JSON.stringify(o); } catch (e) { return String(o); } }, format: function () { return Array.prototype.join.call(arguments, " "); },
                promisify: function (f) { return function () { var a = Array.prototype.slice.call(arguments); return new Promise(function (ok, no) { a.push(function (e, r) { e ? no(e) : ok(r); }); f.apply(null, a); }); }; } },
            events: function EventEmitter() {} };
        mods.events.EventEmitter = mods.events;
        mods.events.prototype.on = function () { return this; }; mods.events.prototype.emit = function () { return false; };
        mods.events.prototype.once = mods.events.prototype.on; mods.events.prototype.off = mods.events.prototype.on; mods.events.prototype.removeListener = mods.events.prototype.on;
        var req = function require(name) {
            name = String(name).replace(/^node:/, "");
            if (mods[name]) return mods[name];
            try { if (window.console) console.warn("[galport] require('" + name + "') 在手机上不可用，已用空模块代替"); } catch (e) {}
            return (mods[name] = noop());
        };
        req.resolve = function (n) { return n; };
        req.cache = {};
        req.main = proc.mainModule;
        window.require = req;

        // 插件用本地路径显示自己存的图片（截图相册等）：换成 data: 地址
        try {
            var srcDesc = Object.getOwnPropertyDescriptor(HTMLImageElement.prototype, "src");
            Object.defineProperty(HTMLImageElement.prototype, "src", {
                configurable: true, enumerable: srcDesc.enumerable, get: srcDesc.get,
                set: function (v) {
                    var s = String(v);
                    if (s.indexOf(ROOT + "/") === 0) {
                        var r = getFile(s) || getFile(decodeURIComponent(s));
                        if (r) v = "data:image/" + (/\.jpe?g$/i.test(s) ? "jpeg" : /\.webp$/i.test(s) ? "webp" : "png") + ";base64," +
                            (r.t === "b" ? r.d : Buf.from(r.d).toString("base64"));
                    }
                    srcDesc.set.call(this, v);
                }
            });
        } catch (e) {}
    })();

    // <<< node-shim

    // ------------------------------------------------------------------
    // 退出 & 返回键
    // ------------------------------------------------------------------
    function exitApp() { location.href = "galport://exit"; }
    window.close = exitApp;

    // ------------------------------------------------------------------
    // 按键模拟：按 keyCode 找引擎里的按键名（插件改过映射也能对上）
    // ------------------------------------------------------------------
    var KEY = { ok: 13, cancel: 27, shift: 16, ctrl: 17, tab: 9, left: 37, up: 38, right: 39, down: 40 };
    var pressAt = {};
    function keyName(code) {
        return (typeof Input !== "undefined" && Input.keyMapper && Input.keyMapper[code]) || null;
    }
    function keyDown(code) {
        var n = keyName(code);
        if (!n || !Input._currentState) return;
        Input._currentState[n] = true;
        pressAt[code] = Date.now();
    }
    function keyUp(code) {
        var n = keyName(code);
        if (!n || !Input._currentState) return;
        var held = Date.now() - (pressAt[code] || 0);
        setTimeout(function () { Input._currentState[n] = false; }, Math.max(0, 90 - held)); // 至少保持几帧
    }
    function tapKey(code) { keyDown(code); keyUp(code); }

    window.__galportBack = function () {
        if (panel && panel.style.display !== "none") { closePanel(); return; }
        tapKey(KEY.cancel);
    };

    // 右键 = 隐藏对话框（MessageWindowHidden 等插件用 TouchInput 的取消判定）
    function rightClick() {
        if (typeof TouchInput === "undefined" || !TouchInput._onCancel) return;
        var w = (typeof Graphics !== "undefined" && Graphics.width) || 816;
        var h = (typeof Graphics !== "undefined" && Graphics.height) || 624;
        TouchInput._onCancel(w / 2, h / 2);
    }

    // ------------------------------------------------------------------
    // 样式
    // ------------------------------------------------------------------
    var CSS = [
        "html,body{margin:0!important;padding:0;width:100%;height:100%;overflow:hidden!important;position:fixed;top:0;right:0;bottom:0;left:0;",
        "touch-action:none;overscroll-behavior:none;-webkit-user-select:none;user-select:none;-webkit-tap-highlight-color:transparent}",
        ":root{--gp-b:1;--gp-k:1;--gp-o:.72}",
        "#gp-bar{position:fixed;left:6px;top:50%;transform:translateY(-50%);z-index:100000;display:flex;flex-direction:column;gap:calc(min(7px,1vh)*var(--gp-b))}",
        ".gp-btn{width:calc(min(46px,9vh)*var(--gp-b));height:calc(min(46px,9vh)*var(--gp-b));border-radius:50%;border:1.5px solid rgba(255,255,255,.55);background:rgba(20,20,28,.45);",
        "color:#fff;font:600 calc(min(13px,3vh)*var(--gp-b))/1 sans-serif;display:flex;align-items:center;justify-content:center;opacity:var(--gp-o);",
        "touch-action:none;-webkit-user-select:none;user-select:none;padding:0;box-sizing:border-box}",
        ".gp-btn.on{background:rgba(255,120,160,.65);opacity:.95}",
        ".gp-btn.pressed{background:rgba(255,255,255,.35)}",
        "#gp-bar.min .gp-hide{display:none}",
        "#gp-back{position:fixed;right:max(10px,env(safe-area-inset-right));top:10px;z-index:100000;border-radius:calc(20px*var(--gp-b));",
        "width:calc(min(58px,11vh)*var(--gp-b));height:calc(min(40px,8vh)*var(--gp-b))}",
        "#gp-back.off{display:none}",
        "#gp-pad{position:fixed;left:calc(min(46px,9vh)*var(--gp-b) + 16px);bottom:14px;z-index:100000;width:calc(150px*var(--gp-k));height:calc(150px*var(--gp-k));display:none}",
        "#gp-pad .gp-btn{position:absolute;width:33.4%;height:33.4%;border-radius:calc(12px*var(--gp-k));font-size:calc(16px*var(--gp-k))}",
        "#gp-act{position:fixed;right:max(14px,env(safe-area-inset-right));bottom:18px;z-index:100000;display:none;gap:calc(12px*var(--gp-k));align-items:flex-end}",
        "#gp-act .gp-btn{width:calc(62px*var(--gp-k));height:calc(62px*var(--gp-k));font-size:calc(14px*var(--gp-k))}",
        "#gp-body input[type=range]{flex:1;min-width:120px}",
        "#gp-trnote{position:fixed;left:50%;top:12px;transform:translateX(-50%);z-index:100002;background:rgba(0,0,0,.7);color:#fff;padding:4px 12px;border-radius:12px;font-size:13px;display:none;pointer-events:none}",
        "#gp-panel{position:fixed;top:0;right:0;bottom:0;left:0;z-index:100001;background:rgba(10,10,16,.93);color:#eee;display:none;flex-direction:column;",
        "font:14px/1.4 sans-serif;touch-action:pan-y;-webkit-user-select:text;user-select:text}",
        "#gp-panel header{display:flex;gap:6px;padding:8px 10px;align-items:center;flex-wrap:wrap;border-bottom:1px solid #333}",
        "#gp-panel header b{margin-right:8px;color:#ff8fb1}",
        "#gp-panel .tab{padding:6px 12px;border-radius:14px;background:#262633;color:#ddd;border:0;font-size:14px}",
        "#gp-panel .tab.on{background:#ff6f9c;color:#fff}",
        "#gp-panel .x{margin-left:auto;background:#444;color:#fff;border:0;border-radius:14px;padding:6px 14px;font-size:14px}",
        "#gp-body{flex:1;overflow-y:auto;padding:8px 12px 40px;touch-action:pan-y;-webkit-overflow-scrolling:touch}",
        "#gp-body .row{display:flex;align-items:center;gap:8px;padding:6px 0;border-bottom:1px solid #22222c}",
        "#gp-body .row .n{flex:1;min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}",
        "#gp-body .id{color:#888;font-size:12px;width:42px;flex:none}",
        "#gp-body input[type=text],#gp-body input[type=number]{width:110px;background:#1b1b24;color:#fff;border:1px solid #444;",
        "border-radius:6px;padding:5px 7px;font-size:14px}",
        "#gp-body input.s{width:100%;box-sizing:border-box;margin:4px 0 8px}",
        "#gp-body button{background:#303042;color:#fff;border:0;border-radius:6px;padding:6px 10px;font-size:13px}",
        "#gp-body button.p{background:#ff6f9c}",
        "#gp-body .card{background:#171720;border-radius:10px;padding:10px;margin:8px 0}",
        "#gp-body .hint{color:#999;font-size:12px;margin:6px 0}",
        "#gp-body label{display:flex;align-items:center;gap:6px}",
        "#gp-toast{position:fixed;left:50%;bottom:22px;transform:translateX(-50%);z-index:100002;pointer-events:none;max-width:80vw;text-align:center;background:rgba(0,0,0,.8);color:#fff;",
        "padding:6px 14px;border-radius:14px;font:13px sans-serif;display:none}"
    ].join("");

    // 覆盖层上的触摸/按键不要传给游戏（引擎监听的是 document）
    function isolate(node) {
        ["touchstart", "touchmove", "touchend", "touchcancel", "mousedown", "mousemove", "mouseup",
         "pointerdown", "pointermove", "pointerup", "wheel", "keydown", "keyup", "contextmenu"].forEach(function (t) {
            node.addEventListener(t, function (e) { e.stopPropagation(); }, { passive: true });
        });
    }

    function el(tag, attrs, text) {
        var e = document.createElement(tag);
        for (var k in attrs || {}) {
            if (k === "class") e.className = attrs[k];
            else if (k === "style") e.style.cssText = attrs[k];
            else e.setAttribute(k, attrs[k]);
        }
        if (text != null) e.textContent = text;
        return e;
    }

    // 屏幕按钮：按下调 onDown，松开调 onUp
    function makeBtn(label, onDown, onUp, extraClass) {
        var b = el("button", { class: "gp-btn" + (extraClass ? " " + extraClass : "") }, label);
        var touching = false;
        function down(e) {
            if (e.type === "mousedown" && touching) return;
            if (e.type === "touchstart") touching = true;
            e.preventDefault(); b.classList.add("pressed"); if (onDown) onDown();
        }
        function up(e) {
            if (e.type === "mouseup" && touching) return;
            e.preventDefault(); b.classList.remove("pressed"); if (onUp) onUp();
        }
        b.addEventListener("touchstart", down, { passive: false });
        b.addEventListener("touchend", up, { passive: false });
        b.addEventListener("touchcancel", up, { passive: false });
        b.addEventListener("mousedown", down);   // 电脑上调试用
        b.addEventListener("mouseup", up);
        return b;
    }

    var toastTimer;
    function toast(msg) {
        var t = document.getElementById("gp-toast");
        t.textContent = msg; t.style.display = "block";
        clearTimeout(toastTimer);
        toastTimer = setTimeout(function () { t.style.display = "none"; }, 1400);
    }

    var store = {
        get: function (k, d) { try { var v = localStorage.getItem("galport." + k); return v == null ? d : JSON.parse(v); } catch (e) { return d; } },
        set: function (k, v) { try { localStorage.setItem("galport." + k, JSON.stringify(v)); } catch (e) {} }
    };

    // ------------------------------------------------------------------
    // 屏幕按键
    // ------------------------------------------------------------------
    var panel, body, padOn = false;

    function buildUi() {
        var st = el("style"); st.textContent = CSS; document.head.appendChild(st);

        var vp = document.querySelector('meta[name="viewport"]');
        if (!vp) { vp = el("meta", { name: "viewport" }); document.head.appendChild(vp); }
        vp.setAttribute("content", "width=device-width,initial-scale=1,maximum-scale=1,minimum-scale=1,user-scalable=no,viewport-fit=cover");

        document.body.appendChild(el("div", { id: "gp-toast" }));

        // 左侧工具条
        var bar = el("div", { id: "gp-bar" });
        bar.appendChild(makeBtn("⋯", function () {
            bar.classList.toggle("min");
            store.set("barMin", bar.classList.contains("min"));
        }));
        bar.appendChild(makeBtn("菜单", menuPress, null, "gp-hide"));
        bar.appendChild(makeBtn("确定", function () { keyDown(KEY.ok); }, function () { keyUp(KEY.ok); }, "gp-hide"));
        bar.appendChild(makeBtn("快进", function () { keyDown(KEY.ctrl); }, function () { keyUp(KEY.ctrl); }, "gp-hide"));
        var autoBtn = makeBtn("自动", function () { tapKey(KEY.tab); autoBtn.classList.toggle("on"); }, null, "gp-hide");
        bar.appendChild(autoBtn);
        bar.appendChild(makeBtn("隐藏", rightClick, null, "gp-hide"));
        var padBtn = makeBtn("方向", function () { setPad(!padOn); }, null, "gp-hide");
        bar.appendChild(padBtn);
        bar.appendChild(makeBtn("修改", openPanel, null, "gp-hide"));
        // Nagisaland 手机版里：退出到游戏库
        if (window.NagisaNative) bar.appendChild(makeBtn("退出", null, function () {
            if (confirm("退出到游戏库？没存档的进度会丢失。")) exitApp();
        }, "gp-hide"));
        if (store.get("barMin", false)) bar.classList.add("min");
        isolate(bar);
        document.body.appendChild(bar);

        // 右上角「返回」：返回上一层（= 电脑上的 Esc / 右键）。全屏时系统侧滑第一下只会呼出导航栏，所以单独做个按钮
        var back = makeBtn("返回", null, function () { window.__galportBack(); });
        back.id = "gp-back";
        if (!store.get("backBtn", true)) back.classList.add("off");
        isolate(back);
        document.body.appendChild(back);

        // 方向键 + 冲刺/确定
        var pad = el("div", { id: "gp-pad" });
        [["▲", KEY.up, "left:33.3%;top:0"], ["▼", KEY.down, "left:33.3%;top:66.6%"],
         ["◀", KEY.left, "left:0;top:33.3%"], ["▶", KEY.right, "left:66.6%;top:33.3%"]].forEach(function (d) {
            var b = makeBtn(d[0], function () { keyDown(d[1]); }, function () { keyUp(d[1]); });
            b.style.cssText += ";" + d[2];
            pad.appendChild(b);
        });
        isolate(pad);
        document.body.appendChild(pad);

        var act = el("div", { id: "gp-act" });
        act.appendChild(makeBtn("冲刺", function () { keyDown(KEY.shift); }, function () { keyUp(KEY.shift); }));
        act.appendChild(makeBtn("确定", function () { keyDown(KEY.ok); }, function () { keyUp(KEY.ok); }));
        isolate(act);
        document.body.appendChild(act);

        function setPad(on) {
            padOn = on;
            pad.style.display = on ? "block" : "none";
            act.style.display = on ? "flex" : "none";
            padBtn.classList.toggle("on", on);
            store.set("pad", on);
        }
        setPad(store.get("pad", false));
        applyLook();

        // 修改器面板
        panel = el("div", { id: "gp-panel" });
        var head = el("header");
        head.appendChild(el("b", null, "修改器"));
        TABS.forEach(function (t) {
            var b = el("button", { class: "tab", "data-t": t[0] }, t[1]);
            b.onclick = function () { showTab(t[0]); };
            head.appendChild(b);
        });
        var x = el("button", { class: "x" }, "关闭");
        x.onclick = closePanel;
        head.appendChild(x);
        panel.appendChild(head);
        body = el("div", { id: "gp-body" });
        panel.appendChild(body);
        isolate(panel);
        document.body.appendChild(panel);
    }

    // ------------------------------------------------------------------
    // 修改器
    // ------------------------------------------------------------------
    var TABS = [["save", "存档"], ["tr", "翻译"], ["gold", "金钱"], ["find", "找数值"], ["var", "变量"], ["sw", "开关"], ["item", "物品"], ["actor", "角色"], ["misc", "其它"], ["keys", "按键"]];

    // 找数值：先搜当前的数，在游戏里让它变一下，再搜新的数，几次就能锁定是哪个变量/物品
    var finder = { list: null, hist: [] };
    function allNumbers() {
        var out = [];
        out.push({ k: "gold", name: "金钱", get: function () { return $gameParty.gold(); }, set: function (v) { $gameParty.gainGold(v - $gameParty.gold()); } });
        var vn = $dataSystem.variables;
        for (var i = 1; i < vn.length; i++) (function (id) {
            if (typeof $gameVariables.value(id) !== "number") return;
            out.push({ k: "v" + id, name: "变量 #" + id + " " + (vn[id] || ""), get: function () { return $gameVariables.value(id); }, set: function (v) { $gameVariables.setValue(id, v); } });
        })(i);
        [["物品", $dataItems], ["武器", $dataWeapons], ["防具", $dataArmors]].forEach(function (kind) {
            (kind[1] || []).forEach(function (it) {
                if (!it || !it.name) return;
                out.push({ k: kind[0] + it.id, name: kind[0] + "：" + it.name, get: function () { return $gameParty.numItems(it); }, set: function (v) { $gameParty.gainItem(it, v - $gameParty.numItems(it)); } });
            });
        });
        $gameParty.allMembers().forEach(function (a) {
            out.push({ k: "lv" + a.actorId(), name: a.name() + " 等级", get: function () { return a.level; }, set: function (v) { a.changeLevel(Math.max(1, Math.min(a.maxLevel(), v)), false); } });
            out.push({ k: "hp" + a.actorId(), name: a.name() + " HP", get: function () { return a.hp; }, set: function (v) { a.setHp(v); } });
            out.push({ k: "mp" + a.actorId(), name: a.name() + " MP", get: function () { return a.mp; }, set: function (v) { a.setMp(v); } });
            out.push({ k: "exp" + a.actorId(), name: a.name() + " 经验", get: function () { return a.currentExp(); }, set: function (v) { a.changeExp(v, false); } });
        });
        return out;
    }
    function tabFind() {
        body.appendChild(el("div", { class: "hint" },
            "不知道好感度、次数之类的存在哪个变量里？输入它现在的数值点「搜索」；回游戏让它变一下（比如再对话一次），输入新数值点「再筛一次」，剩下几个就是它。"));
        var c = el("div", { class: "card" });
        var inp = input("", function () {}, "number");
        var go = el("button", { class: "p" }, finder.list ? "再筛一次" : "搜索");
        var reset = el("button", null, "重新开始");
        c.appendChild(row(null, "数值", [inp, go, reset]));
        body.appendChild(c);
        var res = el("div");
        body.appendChild(res);
        function show() {
            res.innerHTML = "";
            if (!finder.list) return;
            res.appendChild(el("div", { class: "hint" }, "第 " + finder.hist.length + " 次（搜过：" + finder.hist.join(" → ") + "），剩 " + finder.list.length + " 个" +
                (finder.list.length > 60 ? "，太多了，回游戏让它变一下再筛" : "")));
            finder.list.slice(0, 60).forEach(function (x) {
                var cur = x.get();
                res.appendChild(row(null, x.name, input(cur, function (nv) {
                    var n = Number(nv);
                    if (!isFinite(n)) return;
                    x.set(Math.floor(n)); toast(x.name + " = " + x.get());
                }, "number")));
            });
        }
        go.onclick = function () {
            var v = Number(inp.value);
            if (inp.value === "" || !isFinite(v)) { toast("先输入数值"); return; }
            var src = finder.list || allNumbers();
            finder.list = src.filter(function (x) { try { return x.get() === v; } catch (e) { return false; } });
            finder.hist.push(v);
            go.textContent = "再筛一次";
            inp.value = "";
            show();
        };
        reset.onclick = function () { finder.list = null; finder.hist = []; showTab("find"); };
        show();
    }

    // 按键外观：方向键大小 / 左侧按钮大小 / 透明度 / 右上角返回键
    var LOOK = { k: 1, b: 1, o: 0.72 };
    function applyLook() {
        var l = store.get("look", {});
        for (var n in LOOK) if (typeof l[n] === "number") LOOK[n] = l[n];
        var r = document.documentElement.style;
        r.setProperty("--gp-k", LOOK.k); r.setProperty("--gp-b", LOOK.b); r.setProperty("--gp-o", LOOK.o);
    }
    var curTab = "var";
    var cheat = { speed: 1, through: false, noEncounter: false };

    function inGame() {
        return typeof $gameParty !== "undefined" && $gameParty && typeof $dataSystem !== "undefined" && $dataSystem &&
            typeof $gameVariables !== "undefined" && $gameVariables && $gameParty.allMembers().length > 0;
    }
    function openPanel() {
        panel.style.display = "flex";
        showTab(curTab);
    }
    function closePanel() {
        panel.style.display = "none";
        if (document.activeElement && document.activeElement.blur) document.activeElement.blur();
    }
    function showTab(t) {
        curTab = t;
        Array.prototype.forEach.call(panel.querySelectorAll(".tab"), function (b) {
            b.classList.toggle("on", b.getAttribute("data-t") === t);
        });
        body.innerHTML = "";
        if (t !== "misc" && t !== "keys" && t !== "tr" && t !== "save" && !inGame()) {
            body.appendChild(el("div", { class: "hint" }, "请先开始游戏或读档，再打开修改器。"));
            return;
        }
        ({ save: tabSave, tr: tabTr, gold: tabGold, find: tabFind, "var": tabVar, sw: tabSw, item: tabItem, actor: tabActor, misc: tabMisc, keys: tabKeys })[t]();
        body.scrollTop = 0;
    }
    function num(v) {
        var s = String(v).trim();
        return s !== "" && isFinite(Number(s)) ? Number(s) : s;
    }
    function input(value, onSet, type) {
        var i = el("input", { type: type || "text", inputmode: type === "number" ? "numeric" : "text" });
        i.value = value;
        i.addEventListener("change", function () { onSet(i.value); });
        i.addEventListener("keydown", function (e) { if (e.keyCode === 13) i.blur(); });
        return i;
    }
    function row(id, name, ctrl) {
        var r = el("div", { class: "row" });
        if (id != null) r.appendChild(el("span", { class: "id" }, id));
        r.appendChild(el("span", { class: "n" }, name));
        (Array.isArray(ctrl) ? ctrl : [ctrl]).forEach(function (c) { if (c) r.appendChild(c); });
        return r;
    }
    function searchList(placeholder, render) {
        var s = el("input", { type: "text", class: "s", placeholder: placeholder });
        var list = el("div");
        body.appendChild(s);
        body.appendChild(list);
        function go() { list.innerHTML = ""; render(list, s.value.trim().toLowerCase()); }
        s.addEventListener("input", go);
        go();
    }
    function match(q, id, name) {
        return !q || String(id) === q || String(name).toLowerCase().indexOf(q) >= 0;
    }

    function tabGold() {
        var c = el("div", { class: "card" });
        c.appendChild(row(null, "当前金钱", input($gameParty.gold(), function (v) {
            var n = Math.max(0, Math.floor(Number(v) || 0));
            $gameParty.gainGold(n - $gameParty.gold());
            toast("金钱 = " + $gameParty.gold());
        }, "number")));
        [1000, 10000, 100000].forEach(function (n) {
            var b = el("button", null, "+" + n);
            b.onclick = function () { $gameParty.gainGold(n); showTab("gold"); toast("金钱 +" + n); };
            c.appendChild(b); c.appendChild(document.createTextNode(" "));
        });
        body.appendChild(c);
    }

    function tabVar() {
        body.appendChild(el("div", { class: "hint" },
            "好感度、属性、剧情进度一般都存在变量里。改之前建议先存个档。只列出有名字或值不为 0 的变量。"));
        searchList("搜索变量名或编号", function (list, q) {
            var names = $dataSystem.variables, n = 0;
            for (var i = 1; i < names.length && n < 400; i++) {
                var name = names[i] || "", v = $gameVariables.value(i);
                if (!name && !v) continue;
                if (!match(q, i, name)) continue;
                (function (id, val) {
                    var isObj = typeof val === "object" && val !== null;
                    var inp = input(isObj ? JSON.stringify(val) : val, function (nv) {
                        $gameVariables.setValue(id, num(nv));
                        toast("#" + id + " = " + $gameVariables.value(id));
                    });
                    if (isObj) inp.disabled = true;
                    list.appendChild(row("#" + id, name || "（无名）", inp));
                })(i, v);
                n++;
            }
            if (!n) list.appendChild(el("div", { class: "hint" }, "没有匹配的变量"));
        });
    }

    function tabSw() {
        searchList("搜索开关名或编号", function (list, q) {
            var names = $dataSystem.switches, n = 0;
            for (var i = 1; i < names.length && n < 400; i++) {
                var name = names[i] || "";
                if (!name && !$gameSwitches.value(i)) continue;
                if (!match(q, i, name)) continue;
                (function (id) {
                    var cb = el("input", { type: "checkbox" });
                    cb.checked = !!$gameSwitches.value(id);
                    cb.onchange = function () { $gameSwitches.setValue(id, cb.checked); toast("开关 #" + id + (cb.checked ? " 开" : " 关")); };
                    list.appendChild(row("#" + id, name || "（无名）", cb));
                })(i);
                n++;
            }
            if (!n) list.appendChild(el("div", { class: "hint" }, "没有匹配的开关"));
        });
    }

    function tabItem() {
        var kinds = [["物品", $dataItems], ["武器", $dataWeapons], ["防具", $dataArmors]];
        searchList("搜索物品名", function (list, q) {
            kinds.forEach(function (k) {
                var data = k[1] || [];
                var head = el("div", { class: "hint" }, k[0]);
                var any = false;
                list.appendChild(head);
                for (var i = 1; i < data.length; i++) {
                    var it = data[i];
                    if (!it || !it.name || !match(q, i, it.name)) continue;
                    any = true;
                    (function (item) {
                        var inp = input($gameParty.numItems(item), function (v) {
                            var n = Math.max(0, Math.floor(Number(v) || 0));
                            $gameParty.gainItem(item, n - $gameParty.numItems(item));
                            inp.value = $gameParty.numItems(item);
                            toast(item.name + " × " + inp.value);
                        }, "number");
                        inp.style.width = "70px";
                        var plus = el("button", null, "+1");
                        plus.onclick = function () { $gameParty.gainItem(item, 1); inp.value = $gameParty.numItems(item); };
                        list.appendChild(row(null, item.name, [inp, plus]));
                    })(it);
                }
                if (!any) list.removeChild(head);
            });
        });
    }

    function tabActor() {
        var members = $gameParty.allMembers();
        var pnames = ($dataSystem.terms && $dataSystem.terms.params) || [];
        members.forEach(function (a) {
            var c = el("div", { class: "card" });
            c.appendChild(el("b", null, a.name() + "  Lv." + a.level + "  HP " + a.hp + "/" + a.mhp + "  MP " + a.mp + "/" + a.mmp));
            var full = el("button", { class: "p", style: "margin-left:10px" }, "回满");
            full.onclick = function () { a.recoverAll(); showTab("actor"); toast(a.name() + " 已回满"); };
            c.appendChild(full);
            c.appendChild(row(null, "等级", input(a.level, function (v) {
                var lv = Math.max(1, Math.min(a.maxLevel(), Math.floor(Number(v) || 1)));
                a.changeLevel(lv, false); showTab("actor");
            }, "number")));
            for (var p = 0; p < 8; p++) {
                (function (pid) {
                    c.appendChild(row(null, pnames[pid] || ("参数" + pid), input(a.param(pid), function (v) {
                        a.addParam(pid, Math.floor(Number(v) || 0) - a.param(pid));
                        toast((pnames[pid] || "参数") + " = " + a.param(pid));
                    }, "number")));
                })(p);
            }
            body.appendChild(c);
        });
    }

    // ---- 菜单键：剧情 / 对话进行中游戏本身不让开菜单，按取消键只会跳过对话 → 改为打开「存档」页 ----
    function menuPress() {
        try {
            var sc = SceneManager._scene;
            if (typeof Scene_Map !== "undefined" && sc instanceof Scene_Map && typeof $gameMap !== "undefined" && $gameMap) {
                var busy = ($gameMessage && $gameMessage.isBusy()) || $gameMap.isEventRunning();
                var off = $gameSystem && $gameSystem.isMenuEnabled && !$gameSystem.isMenuEnabled();
                if (busy || off) {
                    curTab = "save"; openPanel();
                    toast(busy ? "剧情进行中游戏不让打开菜单（按了会跳过对话），在这里可以随时存档 / 读档" : "游戏现在禁用了菜单，在这里可以随时存档 / 读档");
                    return;
                }
            }
        } catch (e) {}
        tapKey(KEY.cancel);
    }
    // ---- 随时存档 / 读档（不用进游戏菜单；剧情中、禁用存档时也能存）----
    function slotInfo(id) {
        try {
            if (typeof DataManager.savefileInfo === "function") return DataManager.savefileInfo(id);   // MZ
            if (typeof DataManager.loadSavefileInfo === "function") return DataManager.loadSavefileInfo(id);   // MV
        } catch (e) {}
        return null;
    }
    function afterLoad() {
        try { SoundManager.playLoad(); } catch (e) {}
        try {
            if ($gameSystem.versionId() !== $dataSystem.versionId) { $gamePlayer.reserveTransfer($gameMap.mapId(), $gamePlayer.x, $gamePlayer.y); $gamePlayer.requestMapReload(); }
        } catch (e) {}
        SceneManager.goto(Scene_Map);
        try { $gameSystem.onAfterLoad(); } catch (e) {}
        closePanel();
        toast("读档完成");
    }
    function doSave(id) {
        try { $gameSystem.onBeforeSave(); } catch (e) {}
        var r;
        try { r = DataManager.saveGame(id); } catch (e) { toast("存档失败：" + e.message); return; }
        var ok = function () { try { SoundManager.playSave(); } catch (e) {} toast("已存到存档 " + id); showTab("save"); };
        if (r && typeof r.then === "function") r.then(ok, function (e) { toast("存档失败：" + (e && e.message || e)); });
        else if (r === false) toast("存档失败");
        else ok();
    }
    function doLoad(id) {
        var r;
        try { r = DataManager.loadGame(id); } catch (e) { toast("读档失败：" + e.message); return; }
        if (r && typeof r.then === "function") r.then(afterLoad, function (e) { toast("读档失败：" + (e && e.message || e)); });
        else if (r === false) toast("读档失败");
        else afterLoad();
    }
    function tabSave() {
        if (typeof DataManager === "undefined" || !DataManager.saveGame) { body.appendChild(el("div", { class: "hint" }, "游戏还没载入完。")); return; }
        var can = inGame();
        var busy = false;
        try { busy = can && (($gameMessage && $gameMessage.isBusy()) || ($gameMap && $gameMap.isEventRunning())); } catch (e) {}
        body.appendChild(el("div", { class: "hint" }, (can ? "随时存档：不用进游戏菜单，剧情中、游戏禁用存档时也能存。" : "先开始游戏才能存档；在标题画面可以直接读档。") +
            (busy ? "现在正在剧情 / 对话中：这时候存的档，读档后会从下一句接着走（当前这句不会再显示）。尽量在剧情结束、能走动的时候存。" : "") +
            "存档和游戏自己的存档位是同一套，游戏菜单里也能读。"));
        var max = 20;
        try { max = Math.min(40, DataManager.maxSavefiles()); } catch (e) {}
        var two = function (n) { return (n < 10 ? "0" : "") + n; };
        for (var id = 1; id <= max; id++) (function (id) {
            var info = slotInfo(id);
            var c = el("div", { class: "card", style: "display:flex;align-items:center;gap:8px;padding:8px 10px" });
            var t = "存档 " + id + "　";
            if (info) {
                var d = info.timestamp ? new Date(info.timestamp) : null;
                t += (info.title || "") + (info.playtime ? "　" + info.playtime : "") + (d ? "　" + (d.getMonth() + 1) + "/" + d.getDate() + " " + two(d.getHours()) + ":" + two(d.getMinutes()) : "");
            } else t += "（空）";
            c.appendChild(el("div", { style: "flex:1;min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap" }, t));
            if (can) {
                var sb = el("button", { class: "p" }, "存到这里");
                sb.onclick = function () { if (info && !confirm("覆盖存档 " + id + "？")) return; doSave(id); };
                c.appendChild(sb);
            }
            if (info) {
                var lb = el("button", null, "读档");
                lb.onclick = function () { if (can && !confirm("读取存档 " + id + "？当前没存的进度会丢失。")) return; doLoad(id); };
                c.appendChild(lb);
            }
            body.appendChild(c);
        })(id);
    }

    function tabMisc() {
        var c = el("div", { class: "card" });
        c.appendChild(el("div", null, "游戏倍速"));
        [1, 2, 3, 5].forEach(function (s) {
            var b = el("button", { class: cheat.speed === s ? "p" : "" }, s + "x");
            b.onclick = function () { cheat.speed = s; showTab("misc"); toast("倍速 " + s + "x"); };
            c.appendChild(b); c.appendChild(document.createTextNode(" "));
        });
        body.appendChild(c);
        [["through", "穿墙（地图上可以穿过障碍）"], ["noEncounter", "不遇敌（不触发随机战斗）"]].forEach(function (o) {
            var cb = el("input", { type: "checkbox" });
            cb.checked = cheat[o[0]];
            cb.onchange = function () { cheat[o[0]] = cb.checked; };
            var l = el("label", { class: "card" });
            l.appendChild(cb); l.appendChild(document.createTextNode(o[1]));
            body.appendChild(l);
        });
        var imp = el("div", { class: "card" });
        imp.appendChild(el("div", null, "存档"));
        imp.appendChild(el("div", { class: "hint" }, "导出时带进安装包的存档（或游戏文件夹里的 save）第一次打开会自动导入，只补手机上没有的存档位。想用电脑上的进度覆盖手机上的，点下面的按钮。"));
        if (saveFolder()) {
            var sc = el("input", { type: "checkbox" });
            sc.checked = store.get("saveSync", true);
            sc.onchange = function () { store.set("saveSync", sc.checked); toast(sc.checked ? "存档会同步到游戏文件夹" : "已关闭存档同步"); };
            var sl = el("label", { style: "margin:6px 0" });
            sl.appendChild(sc); sl.appendChild(document.createTextNode("存档时同步写到游戏文件夹的 save/（电脑版能直接读，卸载 App 也不丢）"));
            imp.appendChild(sl);
            var ex = el("button", null, "把手机上的存档全部导出到文件夹");
            ex.onclick = function () { exportAllSaves().then(function (n) { toast("已导出 " + n + " 个存档文件"); }, function (e) { toast("导出失败：" + e); }); };
            imp.appendChild(ex); imp.appendChild(document.createTextNode(" "));
        }
        var ow = el("button", null, "用电脑存档覆盖手机存档");
        ow.onclick = function () {
            if (!confirm("用电脑版存档覆盖手机上的同名存档？手机上对应存档位的进度会被替换。")) return;
            importPcSaves(true).then(function (r) {
                if (r.none) toast("这个游戏没有带电脑存档");
                else { toast("已导入 " + r.n + " 个存档，正在重新载入…"); setTimeout(function () { location.reload(); }, 1200); }
            }, function (e) { toast("导入失败：" + e); });
        };
        imp.appendChild(ow);
        body.appendChild(imp);
        body.appendChild(el("div", { class: "hint" }, "改完记得在游戏里存档。修改可能让剧情出问题，重要存档请先备份。"));
    }

    function tabKeys() {
        var c = el("div", { class: "card" });
        [["k", "方向键大小", 0.6, 2.5, 0.05], ["b", "左侧按钮 / 返回键大小", 0.6, 1.8, 0.05], ["o", "按钮不透明度", 0.2, 1, 0.02]].forEach(function (o) {
            var r = el("input", { type: "range", min: o[2], max: o[3], step: o[4] });
            r.value = LOOK[o[0]];
            var v = el("span", { style: "width:46px;text-align:right" }, Math.round(LOOK[o[0]] * 100) + "%");
            r.addEventListener("input", function () {
                LOOK[o[0]] = Number(r.value);
                v.textContent = Math.round(LOOK[o[0]] * 100) + "%";
                store.set("look", LOOK); applyLook();
            });
            c.appendChild(row(null, o[1], [r, v]));
        });
        var reset = el("button", null, "恢复默认");
        reset.onclick = function () { LOOK = { k: 1, b: 1, o: 0.72 }; store.set("look", LOOK); applyLook(); showTab("keys"); };
        c.appendChild(reset);
        body.appendChild(c);
        var cb = el("input", { type: "checkbox" });
        cb.checked = store.get("backBtn", true);
        cb.onchange = function () {
            store.set("backBtn", cb.checked);
            var b = document.getElementById("gp-back"); if (b) b.classList.toggle("off", !cb.checked);
        };
        var l = el("label", { class: "card" });
        l.appendChild(cb); l.appendChild(document.createTextNode("右上角显示「返回」键（返回上一层，同电脑上的 Esc）"));
        body.appendChild(l);
        body.appendChild(el("div", { class: "hint" }, "方向键在左侧按钮栏里点「方向」打开。关掉修改器时就能看到调整后的效果。"));
    }

    // ------------------------------------------------------------------
    // 导入电脑版存档
    //   来源：导出 APK 时带进来的 __galport_saves/（有 index.json）；
    //         或者（手机版 App 里）游戏文件夹自带的 save/（按 global 里记录的存档位去找）
    //   默认只补手机上没有的存档位，不动手机上已有的进度；同一份存档包只自动导入一次
    // ------------------------------------------------------------------
    function isMZ() { return typeof StorageManager !== "undefined" && typeof StorageManager.forageKey === "function" && typeof localforage !== "undefined"; }
    function saveExt() { return isMZ() ? ".rmmzsave" : ".rpgsave"; }
    function getBuf(url) { return fetch(url).then(function (r) { return r.ok ? r.arrayBuffer() : null; }).catch(function () { return null; }); }
    function utf8(buf) { return new TextDecoder("utf-8").decode(buf); }
    function hashStr(t) { var h = 0; for (var i = 0; i < t.length; i++) h = (h * 31 + t.charCodeAt(i)) | 0; return String(h); }
    // PC 存档文本 → 数组/对象（MZ：pako 压缩串；MV：LZString base64）
    function decodeSave(text) {
        try { return JSON.parse(isMZ() ? pako.inflate(text, { to: "string" }) : LZString.decompressFromBase64(text)); } catch (e) { return null; }
    }
    function encodeSave(obj) { var j = JSON.stringify(obj); return isMZ() ? pako.deflate(j, { to: "string", level: 1 }) : LZString.compressToBase64(j); }
    // 找到可导入的存档：[{ name: "file3", text }]，以及这份存档包的标记
    function findPcSaves() {
        var ext = saveExt();
        return fetch("__galport_saves/index.json").then(function (r) { return r.ok ? r.json() : null; }).catch(function () { return null; }).then(function (idx) {
            if (idx && idx.files) {
                return Promise.all(idx.files.map(function (n) {
                    return getBuf("__galport_saves/" + encodeURIComponent(n)).then(function (b) { return b && { name: n.replace(/\.[^.]+$/, ""), text: utf8(b) }; });
                })).then(function (list) { return { stamp: "apk:" + idx.stamp, list: list.filter(Boolean) }; });
            }
            return getBuf("save/global" + ext).then(function (g) {
                if (!g) return null;
                var gt = utf8(g), info = decodeSave(gt);
                if (!Array.isArray(info)) return null;
                var names = ["config"];
                info.forEach(function (x, id) { if (x) names.push("file" + id); });
                return Promise.all(names.map(function (n) {
                    return getBuf("save/" + n + ext).then(function (b) { return b && { name: n, text: utf8(b) }; });
                })).then(function (list) { return { stamp: "dir:" + hashStr(gt), list: [{ name: "global", text: gt }].concat(list.filter(Boolean)) }; });
            });
        });
    }
    // 存取：MZ 用 localforage（异步），MV 用 localStorage
    function mzKey(n) { return "rmmzsave." + $dataSystem.advanced.gameId + "." + n; }
    function mvKey(n) { return n === "config" ? "RPG Config" : n === "global" ? "RPG Global" : "RPG File" + String(n).replace(/^file/, ""); }
    function getSave(n) { return isMZ() ? localforage.getItem(mzKey(n)) : Promise.resolve(localStorage.getItem(mvKey(n))); }
    function setSave(n, text) { if (isMZ()) return localforage.setItem(mzKey(n), text); try { localStorage.setItem(mvKey(n), text); } catch (e) { return Promise.reject(e); } return Promise.resolve(); }
    // overwrite=true：电脑存档覆盖手机上的同名存档
    function importPcSaves(overwrite) {
        return findPcSaves().then(function (pc) {
            if (!pc || !pc.list.length) return { n: 0, none: true };
            if (!overwrite && store.get("saveImport") === pc.stamp) return { n: 0, skipped: true };
            var gl = null, added = [];
            return pc.list.reduce(function (p, s) {
                return p.then(function () {
                    if (s.name === "global") { gl = s; return; }
                    if (s.name.indexOf("file") !== 0 && s.name !== "config" && !isMZ()) return;   // MV 只认存档位和设置
                    return getSave(s.name).then(function (have) {
                        if (have && !overwrite) return;
                        return setSave(s.name, s.text).then(function () { if (/^file\d+$/.test(s.name)) added.push(Number(s.name.slice(4))); });
                    });
                });
            }, Promise.resolve()).then(function () {
                // 读档界面靠 global 里的列表显示：把导入的存档位的信息并进去
                if (!gl || !added.length) return;
                var pcInfo = decodeSave(gl.text);
                if (!Array.isArray(pcInfo)) return;
                return getSave("global").then(function (have) {
                    var info = (have && decodeSave(have)) || [];
                    if (!Array.isArray(info)) info = [];
                    added.forEach(function (id) { if (pcInfo[id]) info[id] = pcInfo[id]; });
                    return setSave("global", encodeSave(info));
                });
            }).then(function () {
                store.set("saveImport", pc.stamp);
                return { n: added.length };
            });
        });
    }
    // ------------------------------------------------------------------
    // 存档同步到游戏文件夹（Nagisaland 手机版里才有）：游戏每次存档，同时在 游戏文件夹/save/ 写一份
    // 和电脑版一样的 .rmmzsave / .rpgsave —— 卸载 App 存档也不会丢，拷回电脑就能接着玩
    // ------------------------------------------------------------------
    function saveFolder() {
        try {
            if (!window.NagisaNative || !NagisaNative.gameDir || !NagisaNative.writeB64) return null;
            var d = NagisaNative.gameDir(location.host);
            return d ? d.replace(/\/+$/, "") + "/save/" : null;
        } catch (e) { return null; }
    }
    function syncOn() { return store.get("saveSync", true) && !!saveFolder(); }
    function b64utf8(text) {
        var u = new TextEncoder().encode(text), s = "", C = 0x8000;
        for (var i = 0; i < u.length; i += C) s += String.fromCharCode.apply(null, u.subarray(i, i + C));
        return btoa(s);
    }
    function mirrorWrite(name, text) {
        var dir = saveFolder();
        if (!dir || text == null) return false;
        try { return NagisaNative.writeB64(dir + name + saveExt(), b64utf8(String(text))); } catch (e) { return false; }
    }
    function mirrorDelete(name) {
        var dir = saveFolder();
        if (!dir) return;
        try { NagisaNative.deleteFile(dir + name + saveExt()); } catch (e) {}
    }
    function mvName(id) { return id < 0 ? "config" : id === 0 ? "global" : "file" + id; }
    function hookSaveSync() {
        if (!saveFolder() || typeof StorageManager === "undefined") return;
        if (typeof StorageManager.saveZip === "function" && typeof StorageManager.forageKey === "function") {
            // MZ：saveZip(名字, 压缩串) → 手机数据库；顺便写一份文件
            var sz = StorageManager.saveZip, rm = StorageManager.remove;
            StorageManager.saveZip = function (name, zip) {
                var p = sz.apply(this, arguments);
                if (syncOn()) Promise.resolve(p).then(function () { mirrorWrite(name, zip); }, function () {});
                return p;
            };
            if (typeof rm === "function") StorageManager.remove = function (name) {
                var p = rm.apply(this, arguments);
                if (syncOn()) Promise.resolve(p).then(function () { mirrorDelete(name); }, function () {});
                return p;
            };
        } else if (typeof StorageManager.saveToWebStorage === "function") {
            // MV：saveToWebStorage(编号, json) → localStorage 里的 LZString 串，原样写成 .rpgsave
            var sw = StorageManager.saveToWebStorage, rw = StorageManager.removeWebStorage;
            StorageManager.saveToWebStorage = function (id) {
                var r = sw.apply(this, arguments);
                if (syncOn()) { try { mirrorWrite(mvName(id), localStorage.getItem(StorageManager.webStorageKey(id))); } catch (e) {} }
                return r;
            };
            if (typeof rw === "function") StorageManager.removeWebStorage = function (id) {
                var r = rw.apply(this, arguments);
                if (syncOn()) mirrorDelete(mvName(id));
                return r;
            };
        }
    }
    // 手机上现有的全部存档写到文件夹（旧版本里存的档）
    function exportAllSaves() {
        if (!saveFolder()) return Promise.reject("只有在 Nagisaland 手机版里才能导出");
        if (isMZ()) {
            var pre = "rmmzsave." + $dataSystem.advanced.gameId + ".";
            return localforage.keys().then(function (keys) {
                var ks = keys.filter(function (k) { return k.indexOf(pre) === 0; });
                return Promise.all(ks.map(function (k) { return localforage.getItem(k).then(function (z) { return mirrorWrite(k.slice(pre.length), z) ? 1 : 0; }); }));
            }).then(function (r) { return r.reduce(function (a, b) { return a + b; }, 0); });
        }
        var n = 0;
        for (var i = 0; i < localStorage.length; i++) {
            var k = localStorage.key(i), m = /^RPG (Config|Global|File(\d+))$/.exec(k);
            if (!m) continue;
            var name = m[1] === "Config" ? "config" : m[1] === "Global" ? "global" : "file" + m[2];
            if (mirrorWrite(name, localStorage.getItem(k))) n++;
        }
        return Promise.resolve(n);
    }

    // 在启动场景里、数据库读完之后导入（MZ 的存档键要用到 $dataSystem 里的 gameId）
    function hookSaveImport() {
        if (typeof Scene_Boot === "undefined" || typeof DataManager === "undefined" || !DataManager.isDatabaseLoaded) return;
        var orig = Scene_Boot.prototype.isReady, state = 0;
        Scene_Boot.prototype.isReady = function () {
            if (state !== 2) {
                if (state === 0 && DataManager.isDatabaseLoaded()) {
                    state = 1;
                    var t0 = Date.now();
                    var fin = function () { state = 2; };
                    importPcSaves(false).then(function (r) {
                        if (r && r.n) setTimeout(function () { toast("已导入电脑存档 " + r.n + " 个"); }, 1200);
                    }, function (e) { try { console.warn("[galport] 导入存档失败", e); } catch (e2) {} })
                        .then(function () { return isMZ() && StorageManager.updateForageKeys ? StorageManager.updateForageKeys() : 0; })
                        .then(fin, fin);
                    setTimeout(fin, 15000);   // 保险：导入卡住也不影响进游戏
                }
                if (state !== 2) return false;
            }
            return orig.apply(this, arguments);
        };
    }

    // ------------------------------------------------------------------
    // AI 翻译（Nagisaland 手机版里才有）：生肉游戏翻成简体中文
    // - 边玩边翻：对话 / 选项 / 说话人名字出现时翻译（等一下再显示）；菜单里的道具技能等数据库文字在后台一次翻完
    // - 整个游戏预翻译：把 data 里所有对话、选项、数据库文字一次翻完存起来，之后和汉化版一样流畅
    // - 翻译服务：免费机翻（微软）或兼容 OpenAI 格式的大模型 API（DeepSeek / 通义 / Kimi / OpenAI …，自己填 Key）
    // - 译文按原文缓存在 游戏文件夹/nagisaland_tr/zh.json，两种方式共用；设置（含 Key）存在 内部存储/Nagisaland/translate.json
    // ------------------------------------------------------------------
    var TR = { cfg: null, cache: null, dirty: false, saveTimer: 0, seq: 0, busy: 0, bingTok: null, bingAt: 0 };
    var TR_DEF = { provider: "free", base: "https://api.deepseek.com", key: "", model: "deepseek-chat", english: true, visionKey: "", conc: 16 };
    TR_DEF.model = "deepseek-flash";
    var TR_CONC = [4, 8, 16, 32, 64];   // 大模型预翻译同时发几批（可在设置里调）
    function trConc() { var n = +trCfg().conc; return n >= 1 && n <= 64 ? Math.round(n) : 16; }
    function N() { return window.NagisaNative || null; }
    function trAvailable() { var n = N(); return !!(n && n.gameDir && n.writeB64 && (n.httpStart || n.http) && n.gameDir(location.host)); }
    function trCfgPath() { try { return N().storageRoot().replace(/\/+$/, "") + "/Nagisaland/translate.json"; } catch (e) { return null; } }
    function trCachePath() { try { return N().gameDir(location.host).replace(/\/+$/, "") + "/nagisaland_tr/zh.json"; } catch (e) { return null; } }
    function trReadJson(p) { try { var t = p && N().readText(p); return t ? JSON.parse(t) : null; } catch (e) { return null; } }
    function trCfg() {
        if (!TR.cfg) { TR.cfg = {}; var c = trReadJson(trCfgPath()) || {}; for (var k in TR_DEF) TR.cfg[k] = c[k] != null ? c[k] : TR_DEF[k]; }
        return TR.cfg;
    }
    function trSaveCfg() { try { var all = trReadJson(trCfgPath()) || {}; for (var k in TR.cfg) all[k] = TR.cfg[k]; N().writeB64(trCfgPath(), b64utf8(JSON.stringify(all, null, 1))); } catch (e) {} }
    function trCache() { if (!TR.cache) TR.cache = trReadJson(trCachePath()) || {}; return TR.cache; }
    function trSaveCache(now) {
        TR.dirty = true;
        clearTimeout(TR.saveTimer);
        var go = function () { if (!TR.dirty) return; TR.dirty = false; try { N().writeB64(trCachePath(), b64utf8(JSON.stringify(TR.cache))); } catch (e) {} };
        if (now || Date.now() - (TR.lastSave || 0) > 10000) { TR.lastSave = Date.now(); go(); } else TR.saveTimer = setTimeout(function () { TR.lastSave = Date.now(); go(); }, 3000);
    }
    function trOn() { return trAvailable() && store.get("trOn", false); }
    function trLive() { return store.get("trLive", true); }

    // 控制符（\C[2]、\N[1]、\I[5]、\{ …）换成 ⟦n⟧，翻译完再换回来
    var TR_CODE = /\\[A-Za-z]+\[[^\]]*\]|\\[A-Za-z]+<[^>]*>|\\[{}\\$.|!><^]|\\[A-Za-z]+/g;
    function trNeeds(s) {
        if (!s || typeof s !== "string") return false;
        var t = s.replace(TR_CODE, "");
        if (/[぀-ヿㇰ-ㇿｦ-ﾟ]/.test(t)) return true;      // 有假名 → 日文
        if (/[一-鿿]/.test(t)) return false;                                 // 只有汉字 → 当作已经是中文
        return !!trCfg().english && /[A-Za-z]{2,}[^A-Za-z]+[A-Za-z]{2,}/.test(t);    // 两个以上英文单词 → 英文
    }
    function trProtect(s) { var codes = []; var t = s.replace(TR_CODE, function (m) { codes.push(m); return "⟦" + (codes.length - 1) + "⟧"; }); return { t: t, codes: codes }; }
    function trRestore(t, codes) {
        // 大模型偶尔会把 ⟦0⟧ 写成 [0] / 【0】，都认；译文里丢掉的控制符就算了（只影响颜色图标等）
        return String(t).replace(/[⟦\[【]\s*(\d+)\s*[⟧\]】]/g, function (m, i) { return codes[+i] == null ? m : codes[+i]; });
    }

    function trHttp(method, url, headers, body) {
        return new Promise(function (res) {
            var n = N();
            if (!n.httpStart) { try { res(JSON.parse(n.http(method, url, JSON.stringify(headers || {}), body == null ? null : body))); } catch (e) { res({ status: 0, error: String(e) }); } return; }
            var id = "tr" + (++TR.seq) + "_" + Date.now(), t0 = Date.now();
            n.httpStart(id, method, url, JSON.stringify(headers || {}), body == null ? null : body);
            (function poll() {
                var r = n.httpResult(id);
                if (r != null) { try { res(JSON.parse(r)); } catch (e) { res({ status: 0, error: "返回格式不对" }); } return; }
                if (Date.now() - t0 > 120000) { res({ status: 0, error: "超时" }); return; }
                setTimeout(poll, 80);
            })();
        });
    }
    function trFail(r) { return new Error(r.status ? "HTTP " + r.status + " " + String(r.body || "").slice(0, 120) : "连不上翻译服务（" + (r.error || "网络错误") + "）"); }

    // 免费：微软 Edge 翻译（国内一般能直接用），不行再试谷歌
    function trBing(list) {
        var tok = TR.bingTok && Date.now() - TR.bingAt < 8 * 60000 ? Promise.resolve(TR.bingTok)
            : trHttp("GET", "https://edge.microsoft.com/translate/auth", { "User-Agent": "Mozilla/5.0" }).then(function (r) {
                if (r.status !== 200 || !r.body) throw trFail(r);
                TR.bingTok = String(r.body).trim(); TR.bingAt = Date.now(); return TR.bingTok;
            });
        return tok.then(function (t) {
            return trHttp("POST", "https://api-edge.cognitive.microsofttranslator.com/translate?api-version=3.0&to=zh-Hans",
                { Authorization: "Bearer " + t, "Content-Type": "application/json" }, JSON.stringify(list.map(function (s) { return { Text: s }; })));
        }).then(function (r) {
            if (r.status !== 200) { TR.bingTok = null; throw trFail(r); }
            var d = JSON.parse(r.body);
            return d.map(function (x) { return x.translations[0].text; });
        });
    }
    function trGoogle(list) {
        return Promise.all(list.map(function (s) {
            return trHttp("GET", "https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=zh-CN&dt=t&q=" + encodeURIComponent(s), { "User-Agent": "Mozilla/5.0" }).then(function (r) {
                if (r.status !== 200) throw trFail(r);
                return JSON.parse(r.body)[0].map(function (x) { return x[0]; }).join("");
            });
        }));
    }
    var TR_SYS = "你是专业的游戏汉化译者。把用户给出的 JSON 字符串数组里的每一项（日文或英文的游戏文本：对话、选项、道具、技能、菜单等）翻译成自然流畅的简体中文，符合游戏里的说话语气，同一个人名在全文里译法保持一致。必须遵守：1. 原样保留 ⟦数字⟧ 占位符（它们是颜色、图标、角色名等控制符）和换行 \\n；2. 只输出一个 JSON 字符串数组，长度、顺序和输入完全一致；3. 不要添加解释、注释或代码块标记。";
    function trLLM(list) {
        var c = trCfg(), base = String(c.base || "").replace(/\/+$/, "");
        var o = { model: c.model, messages: [{ role: "system", content: TR_SYS }, { role: "user", content: JSON.stringify(list) }] };
        if (/deepseek\.com/i.test(base)) { if (/^deepseek-(chat|v4-flash)$/.test(o.model)) o.model = "deepseek-flash"; o.thinking = { type: "disabled" }; }   // 新 DeepSeek 默认先"思考"，翻译不需要，关掉快很多
        else o.temperature = 0.3;
        var body = JSON.stringify(o);
        return trHttp("POST", base + "/chat/completions", { Authorization: "Bearer " + c.key, "Content-Type": "application/json" }, body).then(function (r) {
            if (r.status !== 200) throw trFail(r);
            var txt = JSON.parse(r.body).choices[0].message.content || "";
            var a = txt.indexOf("["), b = txt.lastIndexOf("]");
            var arr = a >= 0 && b > a ? JSON.parse(txt.slice(a, b + 1)) : null;
            if (!arr || arr.length !== list.length) {
                if (list.length === 1) return [arr && arr[0] != null ? String(arr[0]) : txt.trim()];
                var h = Math.ceil(list.length / 2);   // 数量对不上：拆成两半重翻
                return trLLM(list.slice(0, h)).then(function (x) { return trLLM(list.slice(h)).then(function (y) { return x.concat(y); }); });
            }
            return arr.map(String);
        });
    }
    function trFree(list) { return trBing(list).catch(function (e) { return trGoogle(list).catch(function () { throw e; }); }); }
    // 内容审核拦截（阿里云百炼 data_inspection_failed / inappropriate content，DeepSeek Content Exists Risk 等）
    var TR_BLOCK = /inappropriate|data_inspection|DataInspection|Content Exists Risk|content_filter|content management policy|sensitive/i;
    // 被拦的一批：拆开重翻，把没问题的句子救回来；单句还被拦 → 改用免费机翻；再不行保留原文，不让整个预翻译停下
    function trLLMSafe(list) {
        return trLLM(list).catch(function (e) {
            if (!TR_BLOCK.test(e.message)) throw e;
            if (list.length > 1) {
                var h = Math.ceil(list.length / 2);
                return trLLMSafe(list.slice(0, h)).then(function (x) { return trLLMSafe(list.slice(h)).then(function (y) { return x.concat(y); }); });
            }
            TR.blocked = (TR.blocked || 0) + 1;
            return trFree(list).catch(function () { return [null]; });
        });
    }
    function trEngine(list) {
        var c = trCfg();
        if (c.provider === "llm" && c.key) return trLLMSafe(list);
        return trFree(list);
    }
    // 翻译一批原文 → 写进缓存；onProg(已完成, 总数)
    function trTranslate(srcs, onProg, stop) {
        var cache = trCache(), todo = [], seen = {};
        srcs.forEach(function (s) { if (trNeeds(s) && !(s in cache) && !seen[s]) { seen[s] = 1; todo.push(s); } });
        var llm = trCfg().provider === "llm" && trCfg().key;
        var chunks = [], cur = [], len = 0, maxN = llm ? 15 : 50, maxL = llm ? 900 : 4500;   // 大模型每批少一点：回得快、进度看得见
        todo.forEach(function (s) {
            if (cur.length && (cur.length >= maxN || len + s.length > maxL)) { chunks.push(cur); cur = []; len = 0; }
            cur.push(s); len += s.length;
        });
        if (cur.length) chunks.push(cur);
        var done = 0, idx = 0, err = null, retry = [], blocked0 = TR.blocked || 0;
        // 并发：大模型按设置（默认 16，最多 64）；免费机翻固定 4（微软接口并发高了会封）
        // 遇到限流 429：自动把并发减半（最少 2），这一批放回队列稍后重发，不会因为限流就停掉
        var maxLim = llm ? trConc() : 4, limit = maxLim, active = 0, lastCut = 0, okRun = 0;
        TR.busy++;
        function apply(ch, pr, out) {
            ch.forEach(function (s, i) { if (out[i] != null && String(out[i]).trim()) cache[s] = trRestore(out[i], pr[i].codes); });
            done += ch.length; trSaveCache(); if (onProg) onProg(done, todo.length, limit);
        }
        function next() {
            if (err || (stop && stop())) return null;
            if (retry.length) return retry.shift();
            if (idx < chunks.length) return { ch: chunks[idx++], tries: 0 };
            return null;
        }
        function worker() {
            if (active >= limit) return Promise.resolve();   // 并发被降低了：多出来的工人退出
            var job = next();
            if (!job) return Promise.resolve();
            active++;
            var ch = job.ch, pr = ch.map(trProtect);
            return trEngine(pr.map(function (p) { return p.t; })).then(function (out) {
                active--; apply(ch, pr, out);
                // 被限流降过的话：连续成功一阵子再慢慢加回去（+1），并补一个工人
                if (limit < maxLim && ++okRun >= 8 && Date.now() - lastCut > 3000) { okRun = 0; limit++; worker(); }
            }, function (e) {
                active--; job.tries++;
                var is429 = /HTTP 429|Throttl|rate.?limit/i.test(e.message);
                // 同一波里一起失败的请求只算一次（2 秒内只减半一次）
                if (is429) { okRun = 0; if (limit > 2 && Date.now() - lastCut > 2000) { lastCut = Date.now(); limit = Math.max(2, Math.floor(limit / 2)); if (onProg) onProg(done, todo.length, limit, true); } }
                if (job.tries > (is429 ? 5 : 2)) { err = e; return; }
                return new Promise(function (r) { setTimeout(r, is429 ? 3000 * job.tries : 1500); }).then(function () { retry.push(job); });
            }).then(worker);
        }
        if (onProg) onProg(0, todo.length, limit);
        var ws = []; for (var i = 0; i < limit; i++) ws.push(worker());
        // 被限流减过的工人：批次都做完前，有空位就补回（Promise.all 只等最初那批，所以用一个总的等待）
        var all = Promise.all(ws).then(function loop() {
            if (err || (stop && stop()) || (!retry.length && idx >= chunks.length)) return;
            var more = []; for (var j = active; j < limit; j++) more.push(worker());
            return Promise.all(more).then(function () { if (active) return new Promise(function (r) { setTimeout(r, 300); }).then(loop); return loop(); });
        });
        return all.then(function () {
            TR.busy--; trSaveCache(true);
            if (err && done === 0) throw err;
            return { done: done, total: todo.length, err: err, blocked: (TR.blocked || 0) - blocked0 };
        }, function (e) { TR.busy--; throw e; });
    }

    // ---- 数据库文字（道具 / 技能 / 角色 / 系统用语…）----
    var TR_DB = [["$dataActors", ["name", "nickname", "profile"]], ["$dataClasses", ["name"]], ["$dataSkills", ["name", "description", "message1", "message2"]],
        ["$dataItems", ["name", "description"]], ["$dataWeapons", ["name", "description"]], ["$dataArmors", ["name", "description"]],
        ["$dataEnemies", ["name"]], ["$dataStates", ["name", "message1", "message2", "message3", "message4"]]];
    function trDbEach(fn) {   // fn(对象, 键) 对每个可翻译的字符串调用
        TR_DB.forEach(function (d) {
            (window[d[0]] || []).forEach(function (o) { if (o) d[1].forEach(function (k) { try { if (typeof o[k] === "string") fn(o, k); } catch (e) {} }); });
        });
        var s = window.$dataSystem;
        if (s) {
            ["gameTitle", "currencyUnit"].forEach(function (k) { fn(s, k); });
            ["elements", "skillTypes", "weaponTypes", "armorTypes", "equipTypes"].forEach(function (k) { (s[k] || []).forEach(function (v, i) { if (typeof v === "string") fn(s[k], i); }); });
            var t = s.terms || {};
            ["basic", "commands", "params"].forEach(function (k) { (t[k] || []).forEach(function (v, i) { if (typeof v === "string") fn(t[k], i); }); });
            for (var m in (t.messages || {})) if (typeof t.messages[m] === "string") fn(t.messages, m);
        }
    }
    function trDbStrings() { var out = []; trDbEach(function (o, k) { try { var v = o[k]; if (typeof v === "string") out.push(v); } catch (e) {} }); return out; }
    function trDbApply() {
        var c = trCache(), n = 0;
        trDbEach(function (o, k) {
            var v = o[k];
            if (c[v] == null || c[v] === v) return;
            // 有的插件把数据库字段改成了只读的 getter（比如 profile），这种不能直接改，跳过
            try { var d = Object.getOwnPropertyDescriptor(o, k); if (d && !d.writable && !d.set) return; o[k] = c[v]; n++; } catch (e) {}
        });
        if (window.$dataSystem && c[$dataSystem.gameTitle]) try { document.title = $dataSystem.gameTitle; } catch (e) {}
        return n;
    }

    // ---- 事件里的对话 / 选项（预翻译用）：和运行时 $gameMessage 里的文字一模一样，才能对上缓存 ----
    function trListStrings(list, out) {
        if (!list) return;
        for (var i = 0; i < list.length; i++) {
            var c = list[i];
            if (!c) continue;
            if (c.code === 101) {
                if (typeof c.parameters[4] === "string" && c.parameters[4]) out.push(c.parameters[4]);   // MZ 说话人名字
                var lines = [];
                while (list[i + 1] && list[i + 1].code === 401) { i++; lines.push(list[i].parameters[0]); }
                if (lines.length) out.push(lines.join("\n"));
            } else if (c.code === 102) (c.parameters[0] || []).forEach(function (s) { out.push(s); });
            else if (c.code === 320 || c.code === 324 || c.code === 325) { if (typeof c.parameters[1] === "string") out.push(c.parameters[1]); }
        }
    }
    function trFetchJson(name) {
        return new Promise(function (res) {
            var x = new XMLHttpRequest();
            x.open("GET", "data/" + name); x.overrideMimeType("application/json");
            x.onload = function () { try { res(x.status < 400 ? JSON.parse(x.responseText) : null); } catch (e) { res(null); } };
            x.onerror = function () { res(null); };
            x.send();
        });
    }
    function trCollectAll(onStep) {
        var out = trDbStrings();
        return trFetchJson("MapInfos.json").then(function (infos) {
            var files = ["CommonEvents.json", "Troops.json"];
            (infos || []).forEach(function (m) { if (m) files.push("Map" + ("00" + m.id).slice(-3) + ".json"); });
            var i = 0;
            function next() {
                if (i >= files.length) return out;
                var f = files[i++];
                if (onStep) onStep(i, files.length);
                return trFetchJson(f).then(function (d) {
                    if (!d) return;
                    if (f === "CommonEvents.json") d.forEach(function (e) { if (e) trListStrings(e.list, out); });
                    else if (f === "Troops.json") d.forEach(function (t) { if (t) (t.pages || []).forEach(function (p) { trListStrings(p.list, out); }); });
                    else (d.events || []).forEach(function (e) { if (e) (e.pages || []).forEach(function (p) { trListStrings(p.list, out); }); });
                }).then(next);
            }
            return next();
        });
    }

    // ---- 运行时挂钩 ----
    var trNote = null;
    function trShowNote(on) {
        if (!trNote) { trNote = el("div", { id: "gp-trnote" }, "翻译中…"); isolate(trNote); document.body.appendChild(trNote); }
        trNote.style.display = on ? "block" : "none";
    }
    // ---- 字体：日文游戏自带的字体常常没有简体字（啊、呢、这、们…），译文里这些字会变成空白 ----
    // 字体加载好后用画布试画几个常用简体字：画不出来 → 先加系统中文字体做后备；后备也救不回来（字体把缺的字画成空白）→ 改用系统中文字体
    var TR_FB = "'Noto Sans CJK SC','Noto Sans SC','Source Han Sans SC','Droid Sans Fallback','Microsoft YaHei',sans-serif";
    var TR_FTEST = "啊呢吗这们说还么没对吧给让";
    function trFontBlank(face) {
        try {
            var c = document.createElement("canvas"); c.width = c.height = 40;
            var g = c.getContext("2d"); g.font = "32px " + face; g.fillStyle = "#fff"; g.textBaseline = "top";
            for (var i = 0; i < TR_FTEST.length; i++) {
                g.clearRect(0, 0, 40, 40); g.fillText(TR_FTEST[i], 2, 2);
                var d = g.getImageData(0, 0, 40, 40).data, ink = 0;
                for (var j = 3; j < d.length && ink <= 8; j += 4) if (d[j] > 60) ink++;
                if (ink <= 8) return true;
            }
        } catch (e) {}
        return false;
    }
    function trFontFix() {
        if (typeof Bitmap === "undefined" || typeof Bitmap.prototype._makeFontNameText !== "function") return;
        var memo = {};
        TR.fontFix = memo;
        function pick(face) {
            if (memo[face] != null) return memo[face];
            var first = face.split(",")[0].trim();
            if (/^(sans-serif|serif|monospace)$/i.test(first)) return (memo[face] = face);
            try { if (document.fonts && !document.fonts.check("32px " + first)) return null; } catch (e) {}   // 字体还没载入：先不判断
            var r = face;
            if (trFontBlank(face)) r = !trFontBlank(face + "," + TR_FB) ? face + "," + TR_FB : TR_FB + "," + face;
            if (r !== face) try { console.log("[galport] 游戏字体缺简体字，译文改用：" + r); } catch (e) {}
            return (memo[face] = r);
        }
        var mk = Bitmap.prototype._makeFontNameText;
        Bitmap.prototype._makeFontNameText = function () {
            var s = mk.apply(this, arguments);
            if (!trOn()) return s;
            var face = String(this.fontFace || "");
            if (!face || s.slice(-face.length) !== face) return s;
            var r = pick(face);
            return r && r !== face ? s.slice(0, s.length - face.length) + r : s;
        };
    }

    function hookTranslate() {
        if (!trAvailable() || typeof Window_Message === "undefined" || typeof Game_Message === "undefined") return;
        try { trFontFix(); } catch (e) {}
        // 数据库：读完就套用已有译文；边玩边翻时后台把没翻过的一次翻完
        var dbDone = false;
        function dbReady() {
            if (dbDone || !trOn()) return;
            dbDone = true;
            trDbApply();
            if (trLive()) {
                var todo = trDbStrings().filter(function (s) { return trNeeds(s) && !(s in trCache()); });
                if (todo.length) trTranslate(todo).then(function () { trDbApply(); }, function (e) { toast("翻译失败：" + e.message); });
            }
        }
        if (typeof DataManager !== "undefined" && DataManager.isDatabaseLoaded) {
            var ol = DataManager.onLoad;
            if (typeof ol === "function") DataManager.onLoad = function () { var r = ol.apply(this, arguments); try { if (DataManager.isDatabaseLoaded()) dbReady(); } catch (e) {} return r; };
            try { if (DataManager.isDatabaseLoaded()) dbReady(); } catch (e) {}
        }
        // 对话 / 选项 / 说话人：缓存里有就直接换；没有且开了边玩边翻 → 先翻译再显示
        var sm = Window_Message.prototype.startMessage;
        Window_Message.prototype.startMessage = function () {
            var self = this, gm = $gameMessage;
            if (!trOn()) return sm.apply(this, arguments);
            if (this._gpTrWait) return;
            var key = gm._texts.join("\n"), cache = trCache();
            var name = typeof gm._speakerName === "string" ? gm._speakerName : "";
            var need = [key].concat(gm._choices || [], name ? [name] : []).filter(function (s) { return trNeeds(s) && !(s in cache); });
            var args = arguments;
            function apply() {
                var c = trCache();
                if (c[key] != null) gm._texts = String(c[key]).split("\n");
                if (gm._choices) gm._choices = gm._choices.map(function (s) { return c[s] != null ? c[s] : s; });
                if (name && c[name] != null) gm._speakerName = c[name];
            }
            if (need.length && trLive()) {
                this._gpTrWait = true;
                var t = setTimeout(function () { trShowNote(true); }, 300);
                trTranslate(need).catch(function (e) { toast("翻译失败：" + e.message); }).then(function () {
                    clearTimeout(t); trShowNote(false);
                    self._gpTrWait = false;
                    if (gm.hasText()) { apply(); sm.apply(self, args); }
                });
                return;
            }
            apply();
            return sm.apply(this, arguments);
        };
        if (Window_Message.prototype.updateWait) {   // 等翻译时别让消息窗口继续往下走
            var uw = Window_Message.prototype.updateWait;
            Window_Message.prototype.updateWait = function () { return this._gpTrWait ? true : uw.apply(this, arguments); };
        }
    }

    function tabTr() {
        if (!trAvailable()) { body.appendChild(el("div", { class: "hint" }, "AI 翻译只能在 Nagisaland 手机版里用。")); return; }
        TR.cfg = null;   // 设置可能刚在 App 里改过，重新读
        var c = trCfg();
        var on = el("div", { class: "card" });
        [["trOn", "翻译这个游戏（生肉 → 简体中文）", false], ["trLive", "边玩边翻：对话出现时再翻（要联网，每句会等一下）；菜单里的道具技能等会在后台一次翻完", true]].forEach(function (o) {
            var cb = el("input", { type: "checkbox" });
            cb.checked = store.get(o[0], o[2]);
            cb.onchange = function () {
                store.set(o[0], cb.checked);
                if (o[0] === "trOn") toast(cb.checked ? "已开启翻译，正在重新载入…" : "已关闭翻译，正在重新载入…"), setTimeout(function () { location.reload(); }, 900);
            };
            var l = el("label", { style: "display:flex;gap:8px;margin:6px 0" });
            l.appendChild(cb); l.appendChild(document.createTextNode(o[1]));
            on.appendChild(l);
        });
        on.appendChild(el("div", { class: "hint" }, "开关翻译会重新载入游戏（没存的进度会丢，先存档）。已经翻过的句子会保存在游戏文件夹里，下次直接用，不再联网。"));
        body.appendChild(on);

        var sv = el("div", { class: "card" });
        sv.appendChild(el("div", null, "翻译服务：" + (c.provider === "llm" && c.key ? "大模型 " + c.model : "免费机翻（微软）")));
        sv.appendChild(el("div", { class: "hint" }, "换服务、填 API Key：回到游戏库 → 右上角设置 →「AI 翻译设置…」。"));
        var test = el("button", null, "测试一下");
        var tr = el("span", { class: "hint", style: "margin-left:8px" });
        test.onclick = function () {
            tr.textContent = "翻译中…";
            var s = "こんにちは、\\C[2]勇者\\C[0]さま。今日はいい天気ですね！";
            var p = trProtect(s);
            trEngine([p.t]).then(function (o) { tr.textContent = "✓ " + trRestore(o[0], p.codes).replace(TR_CODE, ""); }, function (e) { tr.textContent = "✗ " + e.message; });
        };
        sv.appendChild(test); sv.appendChild(tr);
        body.appendChild(sv);

        var ocr = el("div", { class: "card" });
        ocr.appendChild(el("div", null, "识屏翻译浮窗"));
        ocr.appendChild(el("div", { class: "hint" }, "画面上的字（图片里的字、标题、非 MV/MZ 的游戏）也能翻：打开后把框对准要翻译的地方，点「翻译」。需要在 App 设置里填识屏用的看图模型 Key。"));
        var ob = el("button", null, N().ocrRunning && N().ocrRunning() ? "识屏翻译已开启" : "开启识屏翻译浮窗");
        ob.onclick = function () {
            if (!N().ocrStart) { toast("这个版本不支持识屏翻译"); return; }
            if (!trCfg().visionKey) { toast("先到 App 设置 →「AI 翻译设置…」里填识屏用的 API Key"); return; }
            var r = N().ocrStart();
            if (r === "overlay") toast("请允许「显示在其他应用上层」，然后回来再点一次");
            else if (r === "ok") { toast("同意录屏后浮窗就会出现"); closePanel(); }
            else if (r === "running") toast("识屏翻译已经开着了");
            else toast("打不开：" + r);
        };
        ocr.appendChild(ob);
        body.appendChild(ocr);

        var pre = el("div", { class: "card" });
        pre.appendChild(el("div", null, "整个游戏预翻译"));
        pre.appendChild(el("div", { class: "hint" }, "把游戏里所有的对话、选项、道具、技能等一次翻完存起来（只支持 RPG Maker MV / MZ）。翻完之后玩起来和汉化版一样，不用联网。已缓存 " + Object.keys(trCache()).length + " 句。"));
        if (c.provider === "llm" && c.key) {
            // 同时发几批：越多越快；服务商限流时会自动减半，不用担心设太高
            var cr = el("div", { style: "margin:6px 0" });
            cr.appendChild(document.createTextNode("同时翻译："));
            TR_CONC.forEach(function (n) {
                var b = el("button", { style: "margin:2px" }, n + (n === 16 ? "（默认）" : ""));
                if (n === trConc()) b.className = "p";
                b.onclick = function () { TR.cfg.conc = n; trSaveCfg(); showTab("tr"); toast("已设为同时 " + n + " 批，下次开始预翻译时生效"); };
                cr.appendChild(b);
            });
            pre.appendChild(cr);
            pre.appendChild(el("div", { class: "hint" }, "越多越快，费用不变（按字数算）。百炼 qwen-flash 可以直接选 64；DeepSeek、Kimi 等限流较严，建议 8~16。被限流时会自动减半继续翻，不会中断。"));
        }
        var go = el("button", { class: "p" }, "开始预翻译"), stopB = el("button", null, "停止"), info = el("div", { class: "hint" });
        var stopping = false;
        stopB.style.display = "none";
        stopB.onclick = function () { stopping = true; stopB.disabled = true; info.textContent = "正在停下…"; };
        go.onclick = function () {
            if (typeof $dataSystem === "undefined" || !$dataSystem) { info.textContent = "等游戏标题画面出来再点。"; return; }
            go.disabled = true; stopping = false; stopB.style.display = ""; stopB.disabled = false;
            info.textContent = "正在读取游戏文字…";
            trCollectAll(function (i, n) { info.textContent = "正在读取游戏文字… " + i + " / " + n; }).then(function (all) {
                var todo = all.filter(function (s) { return trNeeds(s) && !(s in trCache()); });
                var chars = todo.reduce(function (a, s) { return a + s.length; }, 0);
                if (!todo.length) { info.textContent = "✓ 都翻过了（共 " + all.length + " 条文字）"; return; }
                var t0 = Date.now(), dn = 0, tot = todo.length, lim = 0, thr = false;
                var fmt = function (ms) { var m = Math.floor(ms / 60000); return (m ? m + " 分 " : "") + Math.floor(ms / 1000) % 60 + " 秒"; };
                var paint = function () {
                    var el2 = Date.now() - t0;
                    info.textContent = "翻译中 " + dn + " / " + tot + " 句（约 " + chars + " 字）· 同时 " + lim + " 批" + (thr ? "（被限流，已自动降低）" : "") + " · 已用 " + fmt(el2) +
                        (dn ? " · 大约还要 " + fmt(el2 / dn * (tot - dn)) : " · 正在等第一批结果（大模型一批要十几秒）");
                };
                var tick2 = setInterval(paint, 1000); paint();
                return trTranslate(todo, function (d, t, l, th) { dn = d; lim = l; if (th) thr = true; paint(); }, function () { return stopping; })
                    .then(function (r) { clearInterval(tick2); return r; }, function (e) { clearInterval(tick2); throw e; })
                    .then(function (r) {
                        trDbApply();
                        toast((stopping ? "预翻译已停止：" : "预翻译完成：") + r.done + " / " + r.total + " 句");
                        info.textContent = (stopping ? "已停止：" : r.err ? "部分完成：" : "✓ 完成：") + "翻了 " + r.done + " / " + r.total + " 句" + (r.err ? "（出错：" + r.err.message + "，再点一次会接着翻）" : "") + (r.blocked ? "。有 " + r.blocked + " 句被服务商的内容审核拦下，已改用免费机翻（成人向游戏建议换 DeepSeek）" : "") + "。打开「翻译这个游戏」就能用。";
                    });
            }).catch(function (e) { info.textContent = "✗ " + e.message; }).then(function () { go.disabled = false; stopB.style.display = "none"; });
        };
        pre.appendChild(go); pre.appendChild(document.createTextNode(" ")); pre.appendChild(stopB); pre.appendChild(info);
        var clr = el("button", null, "清空这个游戏的译文");
        clr.onclick = function () { if (!confirm("清空这个游戏已经翻译好的全部句子？")) return; TR.cache = {}; trSaveCache(true); toast("已清空"); showTab("tr"); };
        pre.appendChild(el("div", { style: "margin-top:8px" })).appendChild(clr);
        body.appendChild(pre);
        body.appendChild(el("div", { class: "hint" }, "目前只支持 RPG Maker MV / MZ（Tyrano 和其它引擎的游戏暂时不能翻译）。翻译质量取决于服务，大模型明显好于免费机翻；图片上的字翻不了。"));
    }

    // 引擎加载完后挂钩（倍速 / 穿墙 / 不遇敌 / 退出 / MV 手机音频）
    function hookEngine() {
        try { hookSaveImport(); } catch (e) {}
        try { hookSaveSync(); } catch (e) {}
        try { hookTranslate(); } catch (e) { try { console.warn('[galport] 翻译挂钩失败', e); } catch (e2) {} }
        // MV 在手机上默认找 .m4a（PC 版游戏一般只有 .ogg → 没声音），安卓 WebView 能直接放 ogg
        if (typeof AudioManager !== "undefined" && typeof WebAudio !== "undefined") {
            try {
                if (!WebAudio.canPlayOgg || WebAudio.canPlayOgg()) AudioManager.audioFileExt = function () { return ".ogg"; };
                // MV 在安卓 Chrome 上 BGM 改用 <audio> 播放，兼容性差，统一走 WebAudio
                if (AudioManager.shouldUseHtml5Audio) AudioManager.shouldUseHtml5Audio = function () { return false; };
            } catch (e) {}
        }
        if (typeof SceneManager !== "undefined") {
            SceneManager.terminate = exitApp;
            var origUpdateScene = SceneManager.updateScene;
            var extra = false;
            var wrap = function (obj, fn) {
                if (!obj || typeof obj[fn] !== "function") return;
                var o = obj[fn];
                obj[fn] = function () { return extra ? false : o.apply(this, arguments); };
            };
            if (typeof Input !== "undefined") ["isTriggered", "isRepeated"].forEach(function (f) { wrap(Input, f); });
            if (typeof TouchInput !== "undefined")
                ["isTriggered", "isRepeated", "isCancelled", "isClicked", "isReleased"].forEach(function (f) { wrap(TouchInput, f); });
            SceneManager.updateScene = function () {
                origUpdateScene.apply(this, arguments);
                for (var i = 1; i < cheat.speed; i++) {
                    extra = true;   // 额外帧里屏蔽“刚按下”，避免一次点击被触发多次
                    try { origUpdateScene.apply(this, arguments); } finally { extra = false; }
                }
            };
        }
        if (typeof Game_Player !== "undefined") {
            var dt = Game_Player.prototype.isDebugThrough;
            Game_Player.prototype.isDebugThrough = function () { return cheat.through || dt.call(this); };
            var ce = Game_Player.prototype.canEncounter;
            Game_Player.prototype.canEncounter = function () { return !cheat.noEncounter && ce.call(this); };
        }
    }

    if (document.body) buildUi(); else document.addEventListener("DOMContentLoaded", buildUi);
    window.addEventListener("load", hookEngine);
})();
