// Nagisaland Android bridge（通用网页游戏：TyranoScript / NW.js HTML5）
// 锁定画面不被拖动缩放；“退出”关闭 App；返回键模拟 Esc。
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
    function exitApp() { location.href = "galport://exit"; }
    window.close = exitApp;
    try { if (window.nw && nw.App) nw.App.quit = exitApp; } catch (e) {}

    function fire(type) {
        var ev = new KeyboardEvent(type, { key: "Escape", code: "Escape", bubbles: true, cancelable: true });
        try { Object.defineProperty(ev, "keyCode", { get: function () { return 27; } }); } catch (e) {}
        try { Object.defineProperty(ev, "which", { get: function () { return 27; } }); } catch (e) {}
        (document.activeElement || document.body || document).dispatchEvent(ev);
    }
    window.__galportBack = function () { fire("keydown"); setTimeout(function () { fire("keyup"); }, 80); };

    function lock() {
        var st = document.createElement("style");
        st.textContent = "html,body{overflow:hidden!important;overscroll-behavior:none;touch-action:manipulation;" +
            "-webkit-user-select:none;user-select:none;-webkit-tap-highlight-color:transparent}";
        document.head.appendChild(st);
        var vp = document.querySelector('meta[name="viewport"]');
        if (!vp) { vp = document.createElement("meta"); vp.name = "viewport"; document.head.appendChild(vp); }
        vp.setAttribute("content", "width=device-width,initial-scale=1,maximum-scale=1,minimum-scale=1,user-scalable=no,viewport-fit=cover");
    }
    // Nagisaland 手机版里：左上角小按钮返回游戏库
    function homeButton() {
        if (!window.NagisaNative || !document.body) return;
        var b = document.createElement("button");
        b.textContent = "⌂";
        b.title = "返回游戏库";
        b.style.cssText = "position:fixed;left:6px;top:6px;z-index:2147483647;width:34px;height:34px;border-radius:50%;" +
            "border:1px solid rgba(255,255,255,.5);background:rgba(0,0,0,.35);color:#fff;font:18px/1 sans-serif;opacity:.45;padding:0";
        b.addEventListener("click", function (e) {
            e.stopPropagation(); e.preventDefault();
            if (confirm("返回游戏库？没存档的进度会丢失。")) exitApp();
        }, true);
        ["touchstart", "touchend", "mousedown", "mouseup", "pointerdown", "pointerup"].forEach(function (t) {
            b.addEventListener(t, function (e) { e.stopPropagation(); }, false);
        });
        document.body.appendChild(b);
    }
    if (document.head) lock(); else document.addEventListener("DOMContentLoaded", lock);
    if (document.body) homeButton(); else document.addEventListener("DOMContentLoaded", homeButton);
})();
