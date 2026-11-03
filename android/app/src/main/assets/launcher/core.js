// Nagisaland 手机版 · 公共部分：原生接口、存储、提示、弹窗、引擎识别、模拟器表
'use strict';
const N = window.NagisaNative;
const $ = s => document.querySelector(s);
const esc = s => String(s == null ? '' : s).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
const store = {
  get(k, d) { try { const v = localStorage.getItem('nl.' + k); return v == null ? d : JSON.parse(v); } catch (e) { return d; } },
  set(k, v) { try { localStorage.setItem('nl.' + k, JSON.stringify(v)); } catch (e) {} },
};
const join = (a, b) => a.replace(/\/+$/, '') + '/' + b;
const dirname = p => p.replace(/\/+$/, '').replace(/\/[^/]*$/, '') || '/';
const basename = p => p.replace(/\/+$/, '').split('/').pop();
const tick = (ms = 30) => new Promise(r => setTimeout(r, ms));   // 让界面先画出来再做同步的原生调用
function ls(p) { try { const r = N.listDir(p); return r && r !== 'null' ? JSON.parse(r) : null; } catch (e) { return null; } }
function readJson(p) { try { const t = N.readText(p); return t ? JSON.parse(t.replace(/^﻿/, '')) : null; } catch (e) { return null; } }
function fmtSize(n) { return n >= 1073741824 ? (n / 1073741824).toFixed(2) + ' GB' : n >= 1048576 ? Math.round(n / 1048576) + ' MB' : Math.max(1, Math.round(n / 1024)) + ' KB'; }
function ago(t) {
  const m = Math.round((Date.now() - t) / 60000);
  if (m < 60) return m <= 1 ? '刚刚' : m + ' 分钟前';
  const h = Math.round(m / 60);
  return h < 24 ? h + ' 小时前' : Math.round(h / 24) + ' 天前';
}
function tint(s) { let h = 0; for (const c of String(s)) h = (h * 31 + c.codePointAt(0)) >>> 0; const a = h % 360; return `linear-gradient(135deg,hsl(${a},45%,30%),hsl(${(a + 40) % 360},50%,20%))`; }

let ROOT = '/storage/emulated/0';
const defaultRoot = () => join(ROOT, 'Nagisaland/Games');
const shown = p => String(p).replace(ROOT, '内部存储');

// ---------- 提示 / 底部弹窗 ----------
let toastTimer;
function toast(msg, ms) {
  const t = $('#toast'); t.textContent = msg; t.classList.add('show');
  clearTimeout(toastTimer); toastTimer = setTimeout(() => t.classList.remove('show'), ms || 2400);
}
function openSheet(html, cls) {
  const s = $('#sheet');
  s.className = 'sheet' + (cls ? ' ' + cls : '');
  s.innerHTML = '<div class="grab"></div>' + html;
  s.scrollTop = 0;
  requestAnimationFrame(() => s.classList.add('show'));
  $('#mask').classList.add('show');
  s.onclick = null;
  return s;
}
function closeSheet() { $('#sheet').classList.remove('show'); $('#mask').classList.remove('show'); }
function sheetOpen() { return $('#sheet').classList.contains('show'); }

// ---------- 选文件夹：手机上没法输路径，在 App 里一层层点进去选 ----------
function pickFolder(opts = {}) {
  return new Promise(resolve => {
    let cur = opts.start && N.exists(opts.start) ? opts.start : ROOT;
    let done = false;
    const finish = v => { if (done) return; done = true; if (!opts.keepOpen) closeSheet(); resolve(v); };
    function draw() {
      const list = (ls(cur) || []).filter(e => e.d && !e.n.startsWith('.')).sort((a, b) => a.n.localeCompare(b.n, 'zh'));
      const up = cur !== ROOT && cur.startsWith(ROOT + '/');
      const here = cur === ROOT ? '内部存储' : cur.split('/').pop();
      const s = openSheet(`<div class="body">
        <h3>${esc(opts.title || '选择文件夹')}</h3>
        ${opts.hint ? `<div class="hint">${esc(opts.hint)}</div>` : ''}
        <div class="line"><span class="path">${esc(shown(cur))}</span></div>
        <div class="add-row">${up ? '<button class="btn small" data-up="1">⬆ 上一级</button>' : ''}
          <button class="btn small" data-go="${esc(ROOT)}">内部存储</button>
          <button class="btn small" data-go="${esc(join(ROOT, 'Download'))}">Download</button></div>
        <div class="dir-list">${list.length ? list.map((e, i) => `<button class="dir-row" data-i="${i}"><span>📁</span>${esc(e.n)}</button>`).join('') : '<div class="hint" style="padding:12px">这里没有子文件夹</div>'}</div>
        <div class="actions"><button class="btn primary wide" data-ok="1">选择「${esc(here)}」</button><button class="btn wide" data-cancel="1">取消</button></div>
      </div>`, 'tall');
      s.onclick = e => {
        const b = e.target.closest('button');
        if (!b) return;
        if (b.dataset.i != null) { cur = join(cur, list[+b.dataset.i].n); draw(); }
        else if (b.dataset.up) { cur = cur.slice(0, cur.lastIndexOf('/')) || ROOT; draw(); }
        else if (b.dataset.go) { cur = N.exists(b.dataset.go) ? b.dataset.go : ROOT; draw(); }
        else if (b.dataset.ok) finish(cur);
        else if (b.dataset.cancel) finish(null);
      };
    }
    draw();
  });
}

// ---------- 手机上的 App ----------
const bing = q => 'https://www.bing.com/search?q=' + encodeURIComponent(q);
const APPS = {
  kirikiroid: { name: 'Kirikiroid2', re: /kirikiroid|kirikiri2|吉里吉里/i, url: bing('Kirikiroid2 安卓 下载') },
  joiplay: { name: 'JoiPlay', re: /joiplay/i, url: bing('JoiPlay android 下载') },
  // home：这个模拟器自己扫描的游戏目录（把游戏放进去，打开模拟器就在列表里）；direct：能直接带路径启动游戏
  easyrpg: { name: 'EasyRPG Player', re: /easyrpg/i, url: 'https://easyrpg.org/player/downloads/', home: 'easyrpg/games',
    homeTip: '第一次用 EasyRPG 时，在它的设置里把游戏文件夹选成「内部存储/easyrpg」。' },
  onscripter: { name: 'ONScripter', re: /onscripter/i, url: bing('ONScripter-Jh android 下载'), home: 'ons' },
  ppsspp: { name: 'PPSSPP', re: /ppsspp/i, url: 'https://www.ppsspp.org/download/', direct: 'psp' },
  winlator: { name: 'Winlator', re: /winlator/i, url: 'https://github.com/brunodev85/winlator/releases', home: 'Download/Winlator游戏',
    homeTip: 'Winlator 默认把「内部存储/Download」当作 D 盘：在 Winlator 里打开 D:\\Winlator游戏，双击游戏的 exe 就能运行。' },
  emuera: { name: 'uEmuera', re: /emuera/i, url: bing('uEmuera android 下载'), home: 'emuera' },
  qsp: { name: 'Quest Navigator', re: /quest ?navigator|\bqsp\b/i, url: bing('QSP Quest Navigator android') },
  mkxp: { name: 'mkxp-z', re: /mkxp/i, url: bing('mkxp-z android') },
  zarchiver: { name: 'ZArchiver', re: /zarchiver/i, url: bing('ZArchiver 下载'), tool: true },
};
let appsCache = null;
function installedApps() {
  if (!appsCache) { try { appsCache = JSON.parse(N.listApps() || '[]'); } catch (e) { appsCache = []; } }
  return appsCache;
}
function refreshApps() { appsCache = null; }
function findApp(key) { const a = APPS[key]; return a && installedApps().find(x => a.re.test(x.l) || a.re.test(x.p)); }

// 打包在 App 里的模拟器（构建时生成 emulators.json）
let BUNDLED = [];
const bundledReady = fetch('emulators.json').then(r => r.ok ? r.json() : []).catch(() => []).then(l => { BUNDLED = l || []; return BUNDLED; });
function bundledFor(key) { return BUNDLED.find(b => b.key === key); }
function installBundled(b) {
  toast('正在准备安装包…');
  return tick(60).then(() => {
    const err = N.installBundled(b.file);
    if (err) toast('安装失败：' + err, 4000);
    else toast('第一次安装要在弹出的设置里允许「安装未知应用」', 4000);
  });
}

// ---------- 引擎 ----------
const WIN = '这是 Windows 程序，手机上只能用 Windows 模拟器硬跑，流畅度看手机性能，部分游戏可能打不开。';
const ENGINES = {
  apk: { name: '安卓 App', play: true },
  'rpgmaker-mz': { name: 'RPG Maker MZ', play: true },
  'rpgmaker-mv': { name: 'RPG Maker MV', play: true },
  tyrano: { name: 'TyranoScript', play: true },
  packed: { name: '网页游戏（打包在程序里）', apps: [], unpack: true, tips: ['游戏文件被打包进了 exe / package.nw / app.asar。', '点上面的「解包」，解一次以后就能在手机上直接玩（会在游戏文件夹里多一个 nagisaland_www 文件夹，需要同样大小的空间）。', '也可以在电脑上用 Nagisaland「导出到安卓」直接打成 APK。'] },
  renpy: { name: "Ren'Py", apps: ['joiplay'], tips: ["JoiPlay 需要再装 Ren'Py 插件。在 JoiPlay 里点 + 添加游戏，选这个文件夹里的 exe 或 game 目录。"] },
  kirikiri: { name: '吉里吉里 (krkr)', apps: ['kirikiroid'], tips: ['在 Kirikiroid2 里找到这个文件夹，点 data.xp3 所在的目录启动。', '用了特殊 DLL 插件的游戏可能不兼容。'] },
  ons: { name: 'NScripter / ONScripter', apps: ['onscripter'], tips: ['点「放进 ONScripter 的游戏目录」，打开 ONScripter 后游戏就在列表里。'] },
  rgss: { name: 'RPG Maker XP / VX / VA', apps: ['joiplay', 'mkxp'], tips: ['JoiPlay 需要再装 RPG Maker 插件，添加游戏时选 Game.exe。', '用了 Win32API 脚本的游戏可能报错。'] },
  rm2k: { name: 'RPG Maker 2000/2003', apps: ['easyrpg', 'joiplay'], tips: ['点「放进 EasyRPG 的游戏目录」，打开 EasyRPG 后在列表里点开。部分游戏需要 RTP 素材包。'] },
  emuera: { name: 'Emuera（era 系列）', apps: ['emuera'], tips: ['点「放进 uEmuera 的游戏目录」，打开 uEmuera 后游戏就在列表里。'] },
  qsp: { name: 'QSP', apps: ['qsp'], tips: ['用 QSP 播放器打开文件夹里的 .qsp 文件。'] },
  psp: { name: 'PSP 镜像', apps: ['ppsspp'], tips: ['装了 PPSSPP 的话点「直接开始游戏」就行。'] },
  wolf: { name: 'WOLF RPG', apps: ['winlator'], tips: [WIN] },
  unity: { name: 'Unity（PC 版）', apps: ['winlator'], tips: [WIN, 'Unity 游戏不少有官方安卓版，可以先找找。'] },
  unreal: { name: 'Unreal', apps: ['winlator'], tips: [WIN] },
  godot: { name: 'Godot', apps: ['winlator'], tips: [WIN] },
  siglus: { name: 'SiglusEngine', apps: ['winlator'], tips: [WIN] },
  bgi: { name: 'BGI', apps: ['winlator'], tips: [WIN] },
  catsystem: { name: 'CatSystem2', apps: ['winlator'], tips: [WIN] },
  artemis: { name: 'Artemis', apps: ['winlator'], tips: [WIN] },
  reallive: { name: 'RealLive (Key)', apps: ['winlator'], tips: [WIN] },
  yuris: { name: 'YU-RIS', apps: ['winlator'], tips: [WIN] },
  livemaker: { name: 'LiveMaker', apps: ['winlator'], tips: [WIN] },
  srpg: { name: 'SRPG Studio', apps: ['winlator'], tips: [WIN] },
  gamemaker: { name: 'GameMaker', apps: ['winlator'], tips: [WIN] },
  nitroplus: { name: 'Nitroplus', apps: ['winlator'], tips: [WIN] },
  entis: { name: 'EntisGLS', apps: ['winlator'], tips: [WIN] },
  unknown: { name: '未识别', apps: ['winlator'], tips: ['没认出这个游戏用的引擎。如果是 Windows 的 exe 游戏，只能用 Windows 模拟器试试。'] },
};
const engOf = g => ENGINES[g.engine] || ENGINES.unknown;
const isPlay = g => !!engOf(g).play;

// ---------- 引擎识别（和电脑版 android-export.js 同一套规则） ----------
function html5At(dir, list) {
  const set = new Set(list.map(e => e.n.toLowerCase()));
  if (!set.has('index.html')) return null;
  const jsDir = list.find(e => e.d && e.n.toLowerCase() === 'js');
  if (jsDir) {
    const js = new Set((ls(join(dir, jsDir.n)) || []).map(e => e.n.toLowerCase()));
    if (js.has('rmmz_core.js')) return 'rpgmaker-mz';
    if (js.has('rpg_core.js')) return 'rpgmaker-mv';
  }
  if (set.has('tyrano') && set.has('data')) return 'tyrano';
  return null;
}

function otherAt(dir, list) {
  const files = list.filter(e => !e.d).map(e => e.n.toLowerCase());
  const dirs = list.filter(e => e.d).map(e => e.n.toLowerCase());
  const sub = name => { const e = list.find(x => x.d && x.n.toLowerCase() === name); return e ? (ls(join(dir, e.n)) || []).map(x => x.n.toLowerCase()) : []; };
  const any = (arr, re) => arr.some(f => re.test(f));
  if (dirs.includes('renpy') || (dirs.includes('game') && any(sub('game'), /\.(rpa|rpyc)$/))) return 'renpy';
  if (any(files, /\.xp3$/)) return 'kirikiri';
  if (files.some(f => ['0.txt', '00.txt', 'nscript.dat', 'nscr_sec.dat', 'nscript.___', 'onscript.nt2', 'onscript.nt3'].includes(f)) || any(files, /\.(nsa|sar|ns2)$/)) return 'ons';
  if (files.includes('rpg_rt.ldb')) return 'rm2k';
  if (any(files, /\.(rgssad|rgss2a|rgss3a)$/)) return 'rgss';
  if (dirs.includes('data') && any(sub('data'), /\.(rxdata|rvdata|rvdata2)$/)) return 'rgss';
  if (files.includes('data.wolf') || (dirs.includes('data') && any(sub('data'), /\.wolf$/))) return 'wolf';
  if (files.includes('unityplayer.dll') || dirs.some(d => d.endsWith('_data') && files.includes(d.replace(/_data$/, '.exe')))) return 'unity';
  if (files.some(f => f.startsWith('siglusengine')) || files.includes('scene.pck') || files.includes('gameexe.dat')) return 'siglus';
  if (files.includes('bgi.exe') || files.includes('sysprg.arc')) return 'bgi';
  if (files.includes('cs2conf.dll') || files.some(f => /^cs2.*\.exe$/.test(f))) return 'catsystem';
  if (any(files, /\.pfs(\.\d+)?$/)) return 'artemis';
  if (dirs.includes('erb') && dirs.includes('csv')) return 'emuera';
  if (any(files, /\.qsp$/)) return 'qsp';
  if (files.some(f => /^avg32.*\.dll$/.test(f) || /^reallive/.test(f)) || (dirs.includes('dat') && (dirs.includes('g00') || dirs.includes('pdt')))) return 'reallive';
  if (any(files, /\.ypf$/) || files.includes('yscfg.dat') || files.includes('yssfs.dat')) return 'yuris';
  if (files.includes('live.dll') || any(files, /\.lsb$/)) return 'livemaker';
  if (files.includes('data.dts') || (any(files, /\.dts$/) && any(files, /\.rts$/))) return 'srpg';
  if (files.includes('data.win') || any(files, /\.win$/)) return 'gamemaker';
  if (any(files, /\.npa$/)) return 'nitroplus';
  if (any(files, /\.noa$/) || (dirs.includes('system') && sub('system').some(f => f.endsWith('.noa')))) return 'entis';
  if (files.filter(f => f.endsWith('.int')).length >= 3) return 'catsystem';
  if (any(files, /\.(iso|cso|chd)$/)) return 'psp';
  if (dirs.includes('engine') && any(files, /\.exe$/)) return 'unreal';
  if (any(files, /\.pck$/)) return 'godot';
  if (files.includes('package.nw') || (dirs.includes('resources') && sub('resources').includes('app.asar'))) return 'packed';
  // NW.js 把游戏 zip 拼在 exe 尾部
  const bigExe = list.filter(e => !e.d && /\.exe$/i.test(e.n) && e.s > 3 * 1048576);
  if (bigExe.length && typeof Unpack !== 'undefined' && bigExe.some(e => Unpack.exeHasZip(join(dir, e.n)))) return 'packed';
  return 'unknown';
}

// 删文件夹放到后台（文件多、存储慢的系统上同步删除会卡住整个界面）；老外壳没这个接口就同步删
let delSeq = 0;
async function deleteTreeAsync(p) {
  if (!N.exists(p)) return 0;
  if (!N.deleteTreeStart) return N.deleteTree(p);
  const id = 'd' + (++delSeq) + '_' + Date.now();
  if (!N.deleteTreeStart(id, p)) return -1;
  for (;;) { await tick(120); const r = N.httpResult(id); if (r != null) return parseInt(r, 10); }
}
// 识屏翻译浮窗：给没法直接改文字的游戏（JoiPlay、吉里吉里、安卓 App 游戏…）用，截图交给看图的大模型翻译
function startOcr() {
  if (!N.ocrStart) { toast('这个版本不支持识屏翻译'); return false; }
  if (N.ocrRunning && N.ocrRunning()) { toast('识屏翻译浮窗已经开着了'); return true; }
  const cfg = TrSettings.load();
  if (!cfg.visionKey) { toast('先填识屏翻译用的 API Key（支持看图的大模型）', 4000); TrSettings.open(); return false; }
  const r = N.ocrStart();
  if (r === 'overlay') toast('请在打开的设置里允许 Nagisaland「显示在其他应用上层」，然后回来再点一次', 6000);
  else if (r === 'ok') toast('同意录屏后浮窗就会出现，然后去打开游戏；把框对准对话框，点「翻译」', 6000);
  else if (r !== 'running') toast('打不开识屏翻译：' + r, 5000);
  return r === 'ok' || r === 'running';
}
// 没意义的文件夹名（网盘分享的编号、「新建文件夹」之类）
const MEANINGLESS = /^([A-Za-z]{0,4}[-_]?\d{2,10}([(（]\d+[)）])?|\d+|PC(Ver.*)?|game|gal|www|新建文件夹|未命名)(\.(7z|zip|rar|zst|tar|gz))?$/i;
// TheDemonLordsProject-0.28-pc → The Demon Lords Project 0.28
function prettyName(n) {
  let s = String(n).replace(/[-_. ]+(pc|win(dows)?|x64|x86|win64|win32|market)$/i, '');
  s = s.replace(/_/g, ' ').replace(/(\D)-|-(?=\D)/g, '$1 ').replace(/-(?=\d)/g, ' ');
  if (!/\s/.test(s.replace(/\s\d.*$/, ''))) s = s.replace(/([a-z])([A-Z])/g, '$1 $2');
  return s.replace(/\s+/g, ' ').trim() || String(n);
}
// 文件夹名没意义时，用里面第一层有意义的文件夹名（游戏在更深的地方时）
function innerName(dir, root) {
  const segs = String(root || '').startsWith(dir + '/') ? root.slice(dir.length + 1).split('/') : [];
  const s = segs.find(x => !MEANINGLESS.test(x) && !/^(nagisaland_www|www|package\.nw)$/i.test(x));
  return s ? prettyName(s) : '';
}
const autoName = g => (MEANINGLESS.test(g.name) && innerName(g.dir, g.root)) || g.name;

// dir：一个游戏文件夹 → { engine, root } ；读不了返回 null
function detectGame(dir) {
  const top = ls(dir);
  if (!top) return null;
  const cands = [[dir, top]];
  // 手机上解包出来的（见 unpack.js）优先；package.nw 也可能本身就是个文件夹
  for (const e of top) if (e.d && (e.n === 'nagisaland_www' || e.n.toLowerCase() === 'package.nw')) cands.unshift([join(dir, e.n), null]);
  const www = top.find(e => e.d && e.n.toLowerCase() === 'www');
  if (www) cands.push([join(dir, www.n), null]);
  // 游戏套了好几层文件夹（常见：游戏名/游戏名 v1.0/汉化版/Game.exe）：没有 exe / index.html 的层就往下找，最多 5 层
  const SKIP_SUB = /^(locales|swiftshader|save|savedata|www|__macosx|_commonredist|redist|directx)$/i;
  const expand = (d, list, depth) => {
    if (depth >= 5 || list.some(e => !e.d && (/\.exe$/i.test(e.n) || e.n.toLowerCase() === 'index.html'))) return;
    const subs = list.filter(e => e.d && !SKIP_SUB.test(e.n) && !e.n.startsWith('.'));
    if (!subs.length || subs.length > 4) return;
    for (const s of subs) {
      const p = join(d, s.n), sl = ls(p);
      if (!sl) continue;
      cands.push([p, sl]);
      if (sl.some(e => e.d && e.n.toLowerCase() === 'www')) cands.push([join(p, 'www'), null]);
      expand(p, sl, depth + 1);
    }
  };
  expand(dir, top, 0);
  for (const c of cands) {
    const list = c[1] || ls(c[0]);
    if (!list) continue;
    c[1] = list;
    const e = html5At(c[0], list);
    if (e) return { engine: e, root: c[0] };
  }
  for (const c of cands) {
    if (!c[1]) continue;
    const e = otherAt(c[0], c[1]);
    if (e !== 'unknown') return { engine: e, root: c[0] };
  }
  return { engine: 'unknown', root: dir };
}
