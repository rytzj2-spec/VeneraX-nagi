// Nagisaland 手机版 · 游戏库
'use strict';
const Library = (() => {
  let games = store.get('games', []);
  let filter = 'all';
  // 排序：名称 / 最近玩过 / 游玩时长 / 最新加入
  const SORTS = [['name', '按名称'], ['played', '按最近玩'], ['time', '按游玩时长'], ['new', '按加入时间']];
  let sort = store.get('sort', 'name');
  const recent = store.get('recent', {});
  // 手机上已安装的 App 游戏（电脑版导出的 APK 等）、从游戏库移除的、改过显示名的
  let appGames = store.get('appGames', []);   // [{ pkg, name, added }]
  const hidden = new Set(store.get('hidden', []));
  const names = store.get('names', {});
  const nameOf = g => names[g.dir] || autoName(g);
  const appEntry = a => ({ kind: 'apk', pkg: a.pkg, name: a.name, dir: 'app:' + a.pkg, root: '', engine: 'apk', m: a.added || 0 });
  const all = () => games.filter(g => !hidden.has(g.dir)).concat(appGames.map(appEntry));
  const isExport = p => /^com\.(nagisaland|galport)\./.test(p) && !/^com\.nagisaland\.(mobile|zip64test)$/.test(p);
  const appInstalled = g => installedApps().some(a => a.p === g.pkg);

  const roots = () => store.get('roots', null) || [defaultRoot()];
  // 单独添加的游戏文件夹（不在任何游戏目录下，留在原位置）
  const singles = () => store.get('singles', []);
  const underRoot = d => roots().some(r => d.startsWith(r.replace(/\/+$/, '') + '/'));
  // 游戏身份：文件夹名 +（真正的根在子目录时）子路径。决定存档归属
  const keyOf = g => g.name + (g.root !== g.dir ? '/' + g.root.slice(g.dir.length + 1) : '');
  const summary = () => { const a = all(); return `共 ${a.length} 个游戏 · ${a.filter(isPlay).length} 个可以直接玩`; };
  const host = (dir, engine, key) => 'https://' + N.setGame(dir, engine, key);

  let scanning = false;
  async function scan() {
    if (scanning) return;
    scanning = true;
    $('#btnRefresh').classList.add('spin');
    const out = [];
    try {
      for (const r of roots()) {
        if (r === defaultRoot()) N.mkdirs(r);
        const list = ls(r);
        if (!list) continue;
        const dirs = list.filter(e => e.d && !e.n.startsWith('.') && !/\.part$/i.test(e.n)).sort((a, b) => a.n.localeCompare(b.n, 'zh'));
        for (let i = 0; i < dirs.length; i++) {
          const dir = join(r, dirs[i].n);
          $('#libStatus').textContent = `正在扫描 ${out.length + 1}：${dirs[i].n}`;
          if (i % 4 === 0) await tick(0);
          const d = detectGame(dir);
          if (d) out.push({ name: dirs[i].n, dir, root: d.root, engine: d.engine, m: dirs[i].m || 0 });
        }
      }
      const have = new Set(out.map(g => g.dir));
      for (const dir of singles()) {
        if (have.has(dir) || !N.exists(dir)) continue;
        const d = detectGame(dir);
        if (d) { out.push({ name: basename(dir), dir, root: d.root, engine: d.engine, m: 0 }); have.add(dir); }
      }
    } finally {
      scanning = false;
      $('#btnRefresh').classList.remove('spin');
    }
    games = out;
    store.set('games', games);
    render();
    $('#libStatus').textContent = summary();
  }

  // ---------- 封面 ----------
  async function rmTitle(g) {
    const sys = readJson(join(g.root, 'data/System.json'));
    if (!sys) return null;
    g.title = sys.gameTitle || '';
    const name = sys.title1Name;
    if (!name) return null;
    const enc = !!sys.hasEncryptedImages;
    const ext = !enc ? '.png' : g.engine === 'rpgmaker-mz' ? '.png_' : '.rpgmvp';
    const url = host(g.root, 'none', keyOf(g) + '#cover') + '/img/titles1/' + encodeURIComponent(name) + ext;
    if (!enc) return url;
    const r = await fetch(url);
    if (!r.ok) return null;
    const b = new Uint8Array(await r.arrayBuffer()).slice(16);   // 去掉 16 字节假文件头
    const k = (sys.encryptionKey || '').match(/../g) || [];
    for (let i = 0; i < 16 && i < k.length; i++) b[i] ^= parseInt(k[i], 16);
    return URL.createObjectURL(new Blob([b]));
  }

  async function coverFor(g) {
    if (g.kind === 'apk') { const b = N.appIcon(g.pkg); return b ? 'data:image/png;base64,' + b : null; }
    // 1) 文件夹里自带的封面（从小站入库时会下载成 cover.jpg）
    const own = (ls(g.dir) || []).find(e => !e.d && /^(cover|folder|poster)\.(jpe?g|png|webp)$/i.test(e.n));
    if (own) return host(g.dir, 'none', keyOf(g) + '#dir') + '/' + encodeURIComponent(own.n);
    const h = () => host(g.root, 'none', keyOf(g) + '#cover');
    // 2) RPG Maker 标题画面，退而求其次用图标
    if (g.engine.startsWith('rpgmaker')) {
      try { const u = await rmTitle(g); if (u) return u; } catch (e) {}
      if (N.exists(join(g.root, 'icon/icon.png'))) return h() + '/icon/icon.png';
    }
    // 3) 其它引擎常见的标题图
    const tries = { renpy: ['game/gui/main_menu.png', 'game/gui/main_menu.jpg', 'game/gui/window_icon.png'], tyrano: ['data/image/title.png', 'data/bgimage/title.jpg', 'data/bgimage/title.png'] }[g.engine] || [];
    for (const t of tries) if (N.exists(join(g.root, t))) return h() + '/' + t.split('/').map(encodeURIComponent).join('/');
    if (g.engine === 'rgss') {
      const d = (ls(join(g.root, 'Graphics/Titles')) || []).find(e => /\.(png|jpe?g)$/i.test(e.n));
      if (d) return h() + '/Graphics/Titles/' + encodeURIComponent(d.n);
    }
    return null;
  }

  const coverCache = new Map();
  function cover(g) {
    if (!coverCache.has(g.dir)) coverCache.set(g.dir, coverFor(g).catch(() => null));
    return coverCache.get(g.dir);
  }
  function loadCover(g, img) {
    cover(g).then(url => {
      if (!url) return;
      img.onload = () => { img.classList.add('loaded'); const ph = img.parentNode.querySelector('.ph'); if (ph) ph.remove(); };
      img.src = url;
    });
  }
  const io = 'IntersectionObserver' in window ? new IntersectionObserver(ents => {
    for (const e of ents) if (e.isIntersecting) { io.unobserve(e.target); loadCover(e.target._game, e.target.querySelector('img')); }
  }, { rootMargin: '300px' }) : null;

  // ---------- 列表 ----------
  function visible() {
    const q = $('#q').value.trim().toLowerCase();
    let list = all().filter(g => !q || nameOf(g).toLowerCase().includes(q) || g.name.toLowerCase().includes(q) || engOf(g).name.toLowerCase().includes(q));
    if (filter === 'play') list = list.filter(isPlay);
    if (filter === 'app') list = list.filter(g => !isPlay(g));
    if (filter === 'recent') return list.filter(g => recent[g.dir]).sort((a, b) => recent[b.dir] - recent[a.dir]);
    const by = { played: g => recent[g.dir] || 0, time: g => playtime[g.dir] || 0, new: g => g.m || 0 }[sort];
    const byName = (a, b) => nameOf(a).localeCompare(nameOf(b), 'zh');
    return list.slice().sort(by ? (a, b) => by(b) - by(a) || byName(a, b) : byName);
  }

  function render() {
    const grid = $('#grid');
    grid.innerHTML = '';
    const A = all();
    const counts = { all: A.length, play: A.filter(isPlay).length, app: A.filter(g => !isPlay(g)).length, recent: A.filter(g => recent[g.dir]).length };
    document.querySelectorAll('#chips .chip[data-f]').forEach(c => { c.querySelector('b').textContent = counts[c.dataset.f] || ''; c.classList.toggle('on', c.dataset.f === filter); });
    $('#sortChip').textContent = (SORTS.find(x => x[0] === sort) || SORTS[0])[1] + ' ⇅';
    $('#sortChip').hidden = filter === 'recent';
    $('#empty').hidden = A.length > 0;
    $('#defRoot').textContent = shown(defaultRoot());
    for (const g of visible()) {
      const eng = engOf(g);
      const c = document.createElement('button');
      c.className = 'card';
      const label = g.kind === 'apk' ? (appInstalled(g) ? '安卓 App' : '已卸载') : eng.play ? '直接玩' : eng.unpack ? '可解包' : (eng.apps && eng.apps.length ? APPS[eng.apps[0]].name : '电脑导出');
      const nm = nameOf(g);
      c.innerHTML = `<div class="cover${g.kind === 'apk' ? ' app' : ''}" style="background:${tint(g.name)}"><div class="ph">${esc([...nm][0] || '?')}</div><img alt="">
        <span class="badge ${eng.play ? 'play' : ''}">${esc(label)}</span></div>
        <div class="meta"><div class="name">${esc(nm)}</div><div class="sub">${esc(eng.name)}${playtime[g.dir] ? ' · ' + fmtTime(playtime[g.dir]) : recent[g.dir] ? ' · ' + ago(recent[g.dir]) : ''}</div></div>`;
      c._game = g;
      c.onclick = () => openDetail(g);
      grid.appendChild(c);
      if (io) io.observe(c); else loadCover(g, c.querySelector('img'));
    }
  }

  const mark = g => { recent[g.dir] = Date.now(); store.set('recent', recent); };
  // 游玩时长：进游戏时记下时间，回到游戏库时结算（单次最多算 6 小时，防止挂机/忘了退）
  const playtime = store.get('playtime', {});
  function settlePlaying() {
    const p = store.get('playing', null);
    if (!p || !p.dir) return;
    const ms = Math.min(Date.now() - p.t, 6 * 3600000);
    if (ms > 30000) { playtime[p.dir] = (playtime[p.dir] || 0) + ms; store.set('playtime', playtime); }
    store.set('playing', null);
  }
  const fmtTime = ms => { const m = Math.round(ms / 60000); return m < 60 ? m + ' 分钟' : Math.floor(m / 60) + ' 小时' + (m % 60 ? ' ' + (m % 60) + ' 分' : ''); };
  // 小站资料（补封面时记下的标题）
  const siteMeta = store.get('siteMeta', {});

  function openDetail(g) {
    const eng = engOf(g);
    let actions = '';
    if (g.kind === 'apk') actions = appInstalled(g) ? '<button class="btn primary wide" data-a="play">启动</button>' : '<div class="hint">这个 App 已经卸载了，可以把它移出游戏库。</div>';
    else if (eng.play) actions = '<button class="btn primary wide" data-a="play">开始游戏</button>';
    else if (eng.unpack) {
      const u = Unpack.info(g);
      actions = !u ? '<div class="hint">没找到打包的游戏文件。</div>'
        : u.error ? `<div class="hint">${esc(u.error)}</div>`
        : `<button class="btn primary wide" data-a="unpack">解包后在手机上玩（${u.count} 个文件，需要 ${fmtSize(u.bytes)}）</button>`;
    }
    else if (eng.apps) actions = eng.apps.map((k, i) => {
      const inst = findApp(k), b = bundledFor(k), A = APPS[k];
      const pri = i === 0 ? 'primary ' : '';
      if (inst && A.direct && gameFile(g)) return `<button class="btn ${pri}wide" data-direct="${k}">用 ${esc(A.name)} 直接开始游戏</button>`;
      if (inst && A.home) {
        const home = join(ROOT, A.home);
        if (g.dir.startsWith(home + '/')) return `<button class="btn ${pri}wide" data-open="${esc(inst.p)}">打开 ${esc(A.name)}（游戏已经在它的列表里）</button>`;
        return `<button class="btn ${pri}wide" data-move="${k}">放进 ${esc(A.name)} 的游戏目录并打开</button>
          <button class="btn wide" data-open="${esc(inst.p)}">只打开 ${esc(A.name)}（自己找文件夹）</button>`;
      }
      if (inst && GUIDES[k]) return `<button class="btn ${pri}wide" data-guide="${k}">用 ${esc(A.name)} 玩（第一次要在它里面选一下）</button>`;
      if (inst) return `<button class="btn ${pri}wide" data-open="${esc(inst.p)}">用 ${esc(A.name)} 打开</button>`;
      if (b) return `<button class="btn wide" data-install="${esc(b.file)}">安装内置的 ${esc(APPS[k].name)}（${fmtSize(b.size)}）</button>`;
      return `<button class="btn wide" data-get="${k}">获取 ${esc(APPS[k].name)}（未安装）</button>`;
    }).join('');
    const s = openSheet(`
      <img class="hero${g.kind === 'apk' ? ' app' : ''}" id="hero" alt="" style="background:${tint(g.name)}">
      <div class="body">
        <h3>${esc(nameOf(g))}</h3>
        <div class="line"><span class="tag ${eng.play ? 'ok' : 'warn'}">${esc(eng.name)}</span>${g.title && g.title !== g.name ? '<span>' + esc(g.title) + '</span>' : ''}${playtime[g.dir] ? '<span class="sub">已玩 ' + fmtTime(playtime[g.dir]) + '</span>' : ''}</div>
        ${siteMeta[g.dir] ? '<div class="line"><span class="sub">小站：' + esc(siteMeta[g.dir].title) + '</span></div>' : ''}
        ${g.kind === 'apk' ? `<div class="line"><span class="path">${esc(g.pkg)}</span></div>`
          : `<div class="line"><span class="path">${esc(shown(g.root))}</span><button class="btn small" data-a="copy">复制路径</button></div>`}
        <div class="actions">${actions}</div>
        ${g.kind === 'apk' || !eng.play ? '<div class="add-row"><button class="btn small" data-a="ocr">识屏翻译浮窗（生肉游戏用）</button></div>' : ''}
        <div class="edit-row" id="editRow">
          <button class="btn small" data-a="rename">改名</button>
          <button class="btn small" data-a="hide">移出游戏库</button>
          ${g.kind !== 'apk' && (MEANINGLESS.test(g.name) || (g.root !== g.dir && !/\/(nagisaland_www|www|package\.nw)$/i.test(g.root))) ? '<button class="btn small" data-a="tidy">整理文件夹</button>' : ''}
          ${g.kind === 'apk' ? (appInstalled(g) ? '<button class="btn small danger" data-a="uninstall">卸载 App</button>' : '') : '<button class="btn small danger" data-a="delete">删除游戏文件</button>'}
        </div>
        ${g.kind === 'apk'
          ? '<div class="hint">从这里启动的 App 游戏会记录游玩时长（从游戏切回 Nagisaland 时结算）。</div>'
          : eng.play
          ? '<div class="hint">游戏里左侧按钮栏：菜单 / 确定 / 快进 / 自动 / 隐藏对话框 / 方向键 / 修改器 / 退出，右上角「返回」。MV/MZ 的存档会同步到游戏文件夹的 save/；生肉游戏可以在 修改器 →「翻译」里用 AI 翻成中文。</div>'
          : `<ol class="steps">${(eng.tips || []).map(t => '<li>' + esc(t) + '</li>').join('')}</ol>
             ${eng.apps && eng.apps.length ? '<div class="hint">点「用 … 打开」时会先把游戏路径复制好，在那个 App 里选文件夹时可以照着找。</div>' : ''}`}
      </div>`);
    const hero = s.querySelector('#hero');
    cover(g).then(u => { if (u) hero.src = u; });
    s.onclick = e => {
      const b = e.target.closest('button');
      if (!b) return;
      if (b.dataset.a === 'play') play(g);
      else if (b.dataset.a === 'unpack') unpack(g, b);
      else if (b.dataset.a === 'rename') renameUi(g, s);
      else if (b.dataset.a === 'rsave') {
        const v = s.querySelector('#renameInput').value.trim();
        if (v && v !== g.name) names[g.dir] = v; else delete names[g.dir];
        store.set('names', names); render(); openDetail(g); toast('已改名');
      }
      else if (b.dataset.a === 'hide') removeFromLibrary(g);
      else if (b.dataset.a === 'tidy') tidyGame(g, b);
      else if (b.dataset.a === 'ocr') startOcr();
      else if (b.dataset.direct) directLaunch(g, b.dataset.direct);
      else if (b.dataset.move) moveToEmulator(g, b.dataset.move);
      else if (b.dataset.guide) emuGuide(g, b.dataset.guide);
      else if (b.dataset.a === 'delete') deleteGame(g);
      else if (b.dataset.a === 'uninstall') { if (!N.uninstall(g.pkg)) toast('打不开卸载界面'); }
      else if (b.dataset.a === 'copy') toast(N.copyText(g.root) ? '已复制路径' : '复制失败');
      else if (b.dataset.open) {
        N.copyText(g.root);
        mark(g);
        const ok = N.launchApp(b.dataset.open);
        if (ok) store.set('playing', { dir: g.dir, t: Date.now() });   // 用模拟器玩也记时长
        toast(ok ? '路径已复制，在 App 里选这个文件夹' : '打不开这个 App');
      } else if (b.dataset.install) { const bb = BUNDLED.find(x => x.file === b.dataset.install); if (bb) installBundled(bb); }
      else if (b.dataset.get) N.openUrl(APPS[b.dataset.get].url);
    };
  }

  // ---------- 和模拟器打通 ----------
  // 能直接启动的模拟器（PPSSPP）：找到游戏文件，带着路径启动
  const gameFile = g => { const f = (ls(g.root) || []).find(e => !e.d && /\.(iso|cso|chd|pbp)$/i.test(e.n)); return f ? join(g.root, f.n) : null; };
  function directLaunch(g, k) {
    const inst = findApp(k), f = gameFile(g);
    if (!inst || !f) return;
    mark(g);
    const err = N.launchIntent(JSON.stringify({ pkg: inst.p, cls: 'org.ppsspp.ppsspp.PpssppActivity', action: 'android.intent.action.VIEW', data: f }));
    if (err) {   // 版本不对等原因打不开：退回到打开 App + 复制路径
      N.copyText(f);
      toast(N.launchApp(inst.p) ? '没能直接启动，已打开 ' + APPS[k].name + '，游戏路径已复制' : '打不开：' + err, 4000);
    }
    store.set('playing', { dir: g.dir, t: Date.now() });
  }
  // 没有对外接口、也不扫描固定目录的模拟器（JoiPlay / Kirikiroid2 / mkxp / QSP）：只能第一次在它里面手动选一次
  // 这里算好要选的那个文件、复制好路径、写清楚每一步；选过一次以后就一直在它的列表 / 最近记录里
  function mainFile(g, re) {
    const list = ls(g.root) || [];
    if (re) { const f = list.find(e => !e.d && re.test(e.n)); if (f) return join(g.root, f.n); }
    const exes = list.filter(e => !e.d && /\.exe$/i.test(e.n) && !/(unins|setup|config|-32\.exe$|dxsetup|vcredist)/i.test(e.n));
    const pick = exes.find(e => /^game\.exe$/i.test(e.n)) || exes.sort((a, b) => b.s - a.s)[0];
    return pick ? join(g.root, pick.n) : g.root;
  }
  const GUIDES = {
    joiplay: g => {
      // 插件没有桌面图标，普通的 App 列表里查不到，要按包名查所有已安装的包（每次打开都重新查，刚装的也能认出来）
      let apps = [];
      try { apps = JSON.parse(N.pkgs('joiplay') || '[]'); } catch (e) {}
      if (!apps.length) apps = installedApps().filter(a => /joiplay/i.test(a.p + a.l));
      const has = re => apps.some(a => re.test(a.p + ' ' + a.l));
      const plugin = g.engine === 'renpy' ? { name: "Ren'Py 插件", ok: has(/ren'?py/i) }
        : g.engine === 'rgss' ? { name: 'RPG Maker 插件', ok: has(/rpgm|rpg ?maker|mkxp/i) } : null;
      const f = mainFile(g);
      return { file: f, plugin, steps: [
        plugin && !plugin.ok ? `先安装 JoiPlay 的${plugin.name}（和 JoiPlay 在同一个下载页）。` : '',
        '打开 JoiPlay，点右下角 <b>＋</b>。',
        `「Executable / 可执行文件」那栏点文件夹图标，选中：${pathBox(f)}`,
        '名字随便填 → 保存。之后在 JoiPlay 列表里点它就能玩。'] };
    },
    kirikiroid: g => {
      const f = mainFile(g, /^data\.xp3$/i);
      return { file: g.root, steps: [
        '打开 Kirikiroid2，在它的文件列表里一层层进到这个文件夹：' + pathBox(g.root),
        `点里面的 <b>${esc(basename(f))}</b>（或直接点这个文件夹）开始游戏。`,
        '玩过一次后，它首页的「最近」列表里就有这个游戏，下次直接点。'] };
    },
    mkxp: g => ({ file: g.root, steps: ['打开 mkxp-z，选择游戏文件夹：' + pathBox(g.root), '之后它会记住这个游戏。'] }),
    qsp: g => { const f = mainFile(g, /\.qsp$/i); return { file: f, steps: ['打开 Quest Navigator，打开游戏文件：' + pathBox(f)] }; },
  };
  const pathBox = p => `<div class="path" style="margin:6px 0">${esc(shown(p))}</div>`;
  function emuGuide(g, k) {
    const A = APPS[k], inst = findApp(k), G = GUIDES[k](g);
    const s = openSheet(`<div class="body"><h3>用 ${esc(A.name)} 玩「${esc(nameOf(g))}」</h3>
      <div class="hint">${esc(A.name)} 不会自动显示手机里的游戏，也不接受别的 App 直接传游戏给它，所以第一次要在它里面选一下（路径已经帮你复制好）。</div>
      ${G.plugin ? `<div class="line"><span class="tag ${G.plugin.ok ? 'ok' : 'warn'}">${G.plugin.ok ? '✓ 已装' : '还没装'} ${esc(A.name)} ${G.plugin.name}</span></div>` : ''}
      <ol class="steps">${G.steps.filter(Boolean).map(x => '<li>' + x + '</li>').join('')}</ol>
      <div class="actions"><button class="btn primary wide" data-go="1">复制路径并打开 ${esc(A.name)}</button></div></div>`);
    s.onclick = e => {
      if (!e.target.closest('[data-go]')) return;
      N.copyText(G.file); mark(g);
      if (inst && N.launchApp(inst.p)) { store.set('playing', { dir: g.dir, t: Date.now() }); toast('路径已复制', 3000); }
      else toast('打不开 ' + A.name);
    };
  }
  // 自己扫描固定目录的模拟器（uEmuera / ONScripter / EasyRPG）：把游戏挪进去，打开模拟器就在列表里
  function moveKeys(from, to) {
    const sg = singles(); const i = sg.indexOf(from);
    if (i >= 0) { sg[i] = to; store.set('singles', sg); }
    for (const [m, k] of [[playtime, 'playtime'], [recent, 'recent'], [names, 'names'], [siteMeta, 'siteMeta']]) {
      if (m[from] != null) { m[to] = m[from]; delete m[from]; store.set(k, m); }
    }
  }
  async function moveToEmulator(g, k) {
    const A = APPS[k], inst = findApp(k);
    const home = join(ROOT, A.home);
    N.mkdirs(home);
    let dest = join(home, g.name), n = 2;
    while (N.exists(dest)) dest = join(home, `${g.name} (${n++})`);
    if (!confirm(`把「${nameOf(g)}」移动到 ${A.name} 的游戏目录？\n${shown(dest)}\n\n游戏库里会自动显示这个目录，不用重新添加。`)) return;
    if (!N.rename(g.dir, dest)) { toast('移动失败：可能不在同一个存储里（比如 SD 卡）', 4000); return; }
    moveKeys(g.dir, dest);
    const rs = roots();
    if (!rs.includes(home)) { rs.push(home); store.set('roots', rs); }
    closeSheet();
    await scan();
    if (A.homeTip) toast(A.homeTip, 5000);
    if (inst) {
      mark({ dir: dest });
      store.set('playing', { dir: dest, t: Date.now() });
      N.launchApp(inst.p);
      if (!A.homeTip) toast(`已放进 ${A.name} 的游戏目录，在它的列表里点「${dest.split('/').pop()}」`, 4000);
    }
  }

  // 整理：删广告、去掉多余的文件夹层、按小站 / 内层文件夹名改名（和解压入库时一样）
  async function tidyGame(g, b) {
    const guided = (engOf(g).apps || []).some(k => GUIDES[k]);
    if (!confirm('整理会删掉广告文件、去掉多余的文件夹层，并把文件夹改成游戏名。' + (guided ? '\n\n注意：如果已经在 JoiPlay / 吉里吉里等模拟器里添加过这个游戏，整理后路径变了，要在那边删掉重新添加一次。' : '') + '\n\n继续吗？')) return;
    b.disabled = true; b.textContent = '整理中…';
    try {
      const r = await Extract.tidy(g.dir, g.root);
      if (r.dest !== g.dir) { moveKeys(g.dir, r.dest); delete names[r.dest]; store.set('names', names); }
      closeSheet(); await scan();
      toast(r.log.length ? r.log.slice(-3).join('；') : '已经很整齐了', 4000);
    } catch (e) { b.disabled = false; b.textContent = '整理文件夹'; toast('整理失败：' + (e.message || e), 4000); }
  }

  // ---------- 编辑：改名 / 移出游戏库 / 删除 ----------
  function renameUi(g, s) {
    s.querySelector('#editRow').innerHTML = `<input id="renameInput" value="${esc(nameOf(g))}" style="flex:1;min-width:0">
      <button class="btn small primary" data-a="rsave">保存</button>`;
    const i = s.querySelector('#renameInput'); i.focus(); i.select();
    i.addEventListener('keydown', e => { if (e.key === 'Enter') s.querySelector('[data-a=rsave]').click(); });
  }
  function removeFromLibrary(g) {
    if (g.kind === 'apk') {
      appGames = appGames.filter(a => a.pkg !== g.pkg); store.set('appGames', appGames);
    } else {
      if (!confirm(`把「${nameOf(g)}」移出游戏库？\n只是不显示，游戏文件还在；可以在游戏库设置里恢复。`)) return;
      hidden.add(g.dir); store.set('hidden', [...hidden]);
    }
    closeSheet(); render(); $('#libStatus').textContent = summary();
    toast('已移出游戏库');
  }
  async function deleteGame(g) {
    // 只允许删游戏库目录下面的游戏文件夹本身
    const under = (underRoot(g.dir) || singles().includes(g.dir)) && !g.dir.endsWith('/') && g.dir !== ROOT && g.dir !== join(ROOT, 'Download');
    if (!under) { toast('这个文件夹不在游戏目录里，不能在这里删'); return; }
    if (!confirm(`删除「${nameOf(g)}」的整个游戏文件夹？\n${shown(g.dir)}\n\n删除后不能恢复（存档如果只在这个文件夹里也会一起删掉）。`)) return;
    if (!confirm('真的删除吗？最后确认一次。')) return;
    closeSheet(); toast('删除中…', 60000);
    const n = await deleteTreeAsync(g.dir);
    if (n < 0) { toast('删除失败'); return; }
    delete playtime[g.dir]; store.set('playtime', playtime);
    delete names[g.dir]; store.set('names', names);
    toast(N.exists(g.dir) ? `删了 ${n} 个文件，有些没删掉（可能被占用）` : '已删除');
    scan();
  }

  // ---------- 添加：已安装的 App 游戏 / 游戏目录 ----------
  function openAdd() {
    const s = openSheet(`<div class="body">
      <h3>添加游戏</h3>
      <div class="sec">游戏文件夹（自己解压的、放在别处的）</div>
      <div class="hint">选一个游戏的文件夹，或者一个放了很多游戏的文件夹：会自动认出每个游戏的引擎、该用什么打开，可以顺便整理（删广告、去多余的层、改名）后加进游戏库。</div>
      <div class="add-row"><button class="btn small primary" data-a="pickGame">选择游戏文件夹…</button><button class="btn small" data-a="pickRoot">添加游戏目录…</button></div>
      <div class="hint">「添加游戏目录」：把整个文件夹加进来，以后里面新放的游戏会自动出现。</div>
      <div class="sec">手机上已经装好的游戏 App</div>
      <div class="hint">电脑版 Nagisaland 导出的 APK 排在最前面。加进来后可以在游戏库里启动，并记录游玩时长。</div>
      <input id="appQ" placeholder="搜索 App 名字" style="width:100%;box-sizing:border-box;margin:6px 0">
      <div id="appList"><div class="hint">正在读取已安装的 App…</div></div>
    </div>`, 'tall');
    let apps = [];
    const iconObs = 'IntersectionObserver' in window ? new IntersectionObserver(ents => {
      for (const e of ents) if (e.isIntersecting) { iconObs.unobserve(e.target); const b = N.appIcon(e.target.dataset.pkg); if (b) e.target.src = 'data:image/png;base64,' + b; }
    }, { root: s }) : null;
    function draw() {
      const q = s.querySelector('#appQ').value.trim().toLowerCase();
      const have = new Set(appGames.map(a => a.pkg));
      const list = apps.filter(a => !q || a.l.toLowerCase().includes(q) || a.p.toLowerCase().includes(q));
      const box = s.querySelector('#appList');
      box.innerHTML = list.length ? list.slice(0, 200).map(a => `<div class="app-pick"><img data-pkg="${esc(a.p)}" alt="">
        <div class="n">${esc(a.l)}${isExport(a.p) ? ' <span class="tag ok">电脑导出</span>' : ''}<small>${esc(a.p)}</small></div>
        ${have.has(a.p) ? '<button class="btn small" disabled>已添加</button>' : `<button class="btn small primary" data-add="${esc(a.p)}">添加</button>`}</div>`).join('')
        : '<div class="hint">没有匹配的 App</div>';
      box.querySelectorAll('img[data-pkg]').forEach(img => { if (iconObs) iconObs.observe(img); });
    }
    setTimeout(() => {
      try { apps = JSON.parse(N.listApps() || '[]'); } catch (e) { apps = []; }
      apps = apps.filter(a => a.p !== 'com.nagisaland.mobile');
      const seen = new Set();
      apps = apps.filter(a => !seen.has(a.p) && seen.add(a.p));
      apps.sort((a, b) => (isExport(b.p) - isExport(a.p)) || a.l.localeCompare(b.l, 'zh'));
      draw();
    }, 30);
    s.querySelector('#appQ').addEventListener('input', draw);
    s.onclick = async e => {
      const b = e.target.closest('button');
      if (!b) return;
      if (b.dataset.add) {
        const a = apps.find(x => x.p === b.dataset.add);
        if (!a || appGames.some(x => x.pkg === a.p)) return;
        appGames.push({ pkg: a.p, name: a.l, added: Date.now() }); store.set('appGames', appGames);
        b.disabled = true; b.textContent = '已添加';
        render(); $('#libStatus').textContent = summary();
        toast('已添加：' + a.l);
      } else if (b.dataset.a === 'pickGame') {
        importFolder();
      } else if (b.dataset.a === 'pickRoot') {
        const p = await pickFolder({ title: '选择放游戏的文件夹', hint: '选中的文件夹里，每个子文件夹当作一个游戏' });
        if (!p) return;
        addRoot(p);
      }
    };
  }
  // ---------- 选游戏文件夹导入：识别引擎 → 可选整理 / 移进游戏库 ----------
  const runnerOf = e => { const E = ENGINES[e] || ENGINES.unknown; return E.play ? '直接在 App 里玩' : E.unpack ? '解包后直接玩' : (E.apps || []).map(k => APPS[k].name).join(' / ') || 'Windows 模拟器'; };
  async function importFolder() {
    const p = await pickFolder({ title: '选择游戏文件夹', hint: '点进去选中游戏所在的文件夹（有 exe、index.html、data.xp3 的那层或它上一层都行）；也可以选一个放了很多游戏的文件夹。' });
    if (!p) { openAdd(); return; }
    if (p === ROOT) { toast('不能选整个内部存储，请点进具体的文件夹'); openAdd(); return; }
    const s = openSheet('<div class="body center">正在识别…</div>', 'tall');
    await tick(30);
    const known = new Set(games.map(g => g.dir));
    const found = [];
    let unknown = 0;
    const one = detectGame(p);
    // 先看子文件夹：有 2 个以上各自是游戏 → 这是放一堆游戏的目录（否则会被当成「套了几层的一个游戏」）
    const subs = (ls(p) || []).filter(e => e.d && !e.n.startsWith('.')).sort((a, b) => a.n.localeCompare(b.n, 'zh')).slice(0, 300);
    const inSubs = [];
    const selfIsGame = one && one.engine !== 'unknown' && one.root === p;   // 这一层本身就有游戏文件
    if (!selfIsGame) {
      for (let i = 0; i < subs.length; i++) {
        if (i % 4 === 0) { s.innerHTML = `<div class="grab"></div><div class="body center">正在识别 ${i + 1} / ${subs.length}：${esc(subs[i].n)}</div>`; await tick(0); }
        const dir = join(p, subs[i].n), d = detectGame(dir);
        if (d && d.engine !== 'unknown') inSubs.push({ dir, root: d.root, engine: d.engine }); else unknown++;
      }
    }
    if (!selfIsGame && inSubs.length >= 2) found.push(...inSubs);
    else if (one && one.engine !== 'unknown') { found.push({ dir: p, root: one.root, engine: one.engine }); unknown = 0; }
    else if (inSubs.length) found.push(...inSubs);
    // 什么都没认出：有 exe 的话把选的文件夹本身当作「未识别」游戏（用 Windows 模拟器试）
    if (!found.length && (ls(p) || []).some(e => !e.d && /\.exe$/i.test(e.n))) { found.push({ dir: p, root: p, engine: 'unknown' }); unknown = 0; }
    if (!found.length) {
      s.innerHTML = `<div class="grab"></div><div class="body"><h3>没找到游戏</h3><div class="hint">「${esc(shown(p))}」里没认出游戏。如果里面是压缩包，请先到「解压」页一键解压；如果游戏还套在更深的文件夹里，重新选的时候点进去一层。</div>
        <div class="actions"><button class="btn wide primary" data-a="again">重新选择</button><button class="btn wide" data-a="close">关闭</button></div></div>`;
      s.onclick = e => { const b = e.target.closest('button'); if (!b) return; if (b.dataset.a === 'again') importFolder(); else closeSheet(); };
      return;
    }
    found.forEach(f => { f.have = known.has(f.dir); f.name = autoName({ name: basename(f.dir), dir: f.dir, root: f.root }); });
    const inLib = d => d.startsWith(defaultRoot().replace(/\/+$/, '') + '/');
    s.innerHTML = `<div class="grab"></div><div class="body">
      <h3>找到 ${found.length} 个游戏</h3>
      ${unknown ? `<div class="hint">另有 ${unknown} 个文件夹没认出引擎，已跳过（可能是压缩包、存档或工具）。</div>` : ''}
      <div class="dir-list" style="max-height:none">${found.map((f, i) => `<label class="line" style="display:flex;gap:10px;align-items:flex-start;padding:8px 2px;border-bottom:1px solid var(--line,#333)">
        <input type="checkbox" data-i="${i}" ${f.have ? '' : 'checked'} style="width:auto;margin-top:4px">
        <div style="min-width:0"><div>${esc(f.name)}${f.have ? ' <span class="tag">已在游戏库</span>' : ''}</div>
          <div class="sub">${esc((ENGINES[f.engine] || ENGINES.unknown).name)} → ${esc(runnerOf(f.engine))}</div>
          <div class="path">${esc(shown(f.dir))}</div></div></label>`).join('')}</div>
      <div class="sec">导入时</div>
      <label style="display:flex;gap:8px;align-items:flex-start;margin:6px 0"><input type="checkbox" id="impTidy" checked style="width:auto"> 整理：删广告文件、去掉多余的文件夹层、按小站 / 游戏名改文件夹名</label>
      <label style="display:flex;gap:8px;align-items:flex-start;margin:6px 0"><input type="radio" name="impWhere" value="stay" checked style="width:auto"> 留在原来的位置</label>
      <label style="display:flex;gap:8px;align-items:flex-start;margin:6px 0"><input type="radio" name="impWhere" value="move" style="width:auto"> 移到游戏库文件夹（内部存储/Nagisaland/Games）</label>
      <div class="hint">要用模拟器（ONScripter、EasyRPG、uEmuera、Winlator）的游戏，导入后在详情里还可以一键放进模拟器的目录。</div>
      <div class="actions"><button class="btn wide primary" data-a="go">导入选中的游戏</button><button class="btn wide" data-a="close">取消</button></div>
    </div>`;
    s.onclick = async e => {
      const b = e.target.closest('button');
      if (!b) return;
      if (b.dataset.a === 'close') { closeSheet(); return; }
      if (b.dataset.a !== 'go') return;
      const pick = [...s.querySelectorAll('input[data-i]')].filter(x => x.checked).map(x => found[+x.dataset.i]);
      if (!pick.length) { toast('没有选中游戏'); return; }
      const doTidy = s.querySelector('#impTidy').checked, move = (s.querySelector('input[name=impWhere]:checked') || {}).value === 'move';
      b.disabled = true;
      const log = [], sg = singles();
      for (let i = 0; i < pick.length; i++) {
        let dir = pick[i].dir;
        b.textContent = `导入中 ${i + 1} / ${pick.length}…`; await tick(0);
        if (move && !inLib(dir)) {
          N.mkdirs(defaultRoot());
          let dest = join(defaultRoot(), basename(dir)), n = 2;
          while (N.exists(dest)) dest = join(defaultRoot(), `${basename(dir)} (${n++})`);
          if (N.rename(dir, dest)) { moveKeys(dir, dest); dir = dest; } else log.push(basename(dir) + ' 移动失败，留在原处');
        }
        if (doTidy) {
          try {
            const d = detectGame(dir);
            const r = await Extract.tidy(dir, d ? d.root : dir);
            if (r.dest !== dir) { moveKeys(dir, r.dest); dir = r.dest; }
          } catch (err) { log.push(basename(dir) + ' 整理失败：' + (err.message || err)); }
        }
        hidden.delete(dir);
        if (!underRoot(dir) && !sg.includes(dir)) sg.push(dir);
      }
      store.set('singles', sg); store.set('hidden', [...hidden]);
      closeSheet();
      await scan();
      toast(`已导入 ${pick.length} 个游戏` + (log.length ? '；' + log.slice(0, 2).join('；') : ''), 4000);
    };
  }
  function addRoot(p) {
    const cur = roots();
    if (cur.includes(p)) { toast('这个文件夹已经在游戏目录里了'); return; }
    cur.push(p); store.set('roots', cur);
    toast('已添加游戏目录：' + shown(p)); scan();
  }

  async function unpack(g, btn) {
    const box = btn.parentNode;
    box.innerHTML = '<div class="bar"><i style="width:0"></i></div><div class="hint" id="upText">准备解包…</div>';
    const fill = box.querySelector('.bar i'), txt = box.querySelector('#upText');
    await tick();
    try {
      const r = await Unpack.run(g, (d, t, s) => { fill.style.width = Math.floor(100 * d / t) + '%'; txt.textContent = `解包中 ${fmtSize(d)} / ${fmtSize(t)}（${s}）`; });
      fill.style.width = '100%';
      txt.textContent = `完成：${r.files} 个文件，${fmtSize(r.bytes)}`;
      closeSheet();
      await scan();
      const ng = games.find(x => x.dir === g.dir);
      toast(ng && isPlay(ng) ? '解包完成，可以直接玩了' : '解包完成');
      if (ng) openDetail(ng);
    } catch (e) {
      txt.textContent = '解包失败：' + e.message;
      toast('解包失败');
    }
  }

  function play(g) {
    if (g.kind === 'apk') {
      mark(g);
      if (!N.launchApp(g.pkg)) { toast('打不开，可能已经卸载了'); return; }
      store.set('playing', { dir: g.dir, t: Date.now() });
      closeSheet();
      return;
    }
    const kind = g.engine.startsWith('rpgmaker') ? 'rpgmaker' : 'generic';
    let portrait = false;   // 按游戏分辨率决定横竖屏
    if (g.engine.startsWith('rpgmaker')) {
      const sys = readJson(join(g.root, 'data/System.json'));
      const a = sys && sys.advanced;
      if (a && a.screenWidth && a.screenHeight) portrait = a.screenHeight > a.screenWidth;
    } else if (g.engine === 'tyrano') {
      const cfg = N.readText(join(g.root, 'data/system/Config.tjs')) || '';
      const w = cfg.match(/;\s*scWidth\s*=\s*(\d+)/), h = cfg.match(/;\s*scHeight\s*=\s*(\d+)/);
      if (w && h) portrait = +h[1] > +w[1];
    }
    mark(g);
    store.set('playing', { dir: g.dir, t: Date.now() });
    const url = host(g.root, kind, keyOf(g)) + '/index.html';
    N.setOrientation(portrait ? 7 : 6);
    location.href = url;
  }

  const hasOwnCover = g => (ls(g.dir) || []).some(e => !e.d && /^(cover|folder|poster)\.(jpe?g|png|webp)$/i.test(e.n));
  async function fillCovers(status, btn) {
    if (!Site.loggedIn()) { toast('先在「小站」页登录'); return; }
    const todo = games.filter(g => !hidden.has(g.dir) && !hasOwnCover(g));
    if (!todo.length) { status.textContent = '所有游戏都有封面了'; return; }
    btn.disabled = true;
    let ok = 0, miss = [];
    for (let i = 0; i < todo.length; i++) {
      const g = todo[i];
      status.textContent = `${i + 1} / ${todo.length}：${g.name}`;
      await tick(0);
      try {
        const r = await Site.findForFolder(g.name);
        if (!r || !r.cover) { miss.push(g.name); continue; }
        const err = N.download(r.cover, join(g.dir, 'cover.jpg'));
        if (err) { miss.push(g.name); continue; }
        siteMeta[g.dir] = { id: r.id, title: r.title };
        coverCache.delete(g.dir);
        ok++;
      } catch (e) {
        status.textContent = e.message; btn.disabled = false; return;
      }
    }
    store.set('siteMeta', siteMeta);
    btn.disabled = false;
    status.textContent = `补上 ${ok} 个` + (miss.length ? `，${miss.length} 个没找到` : '');
    render();
  }

  // ---------- 设置：游戏目录 ----------
  function openSettings() {
    const rs = roots();
    const s = openSheet(`<div class="body">
      <h3>游戏目录</h3>
      <div class="hint">每个游戏一个文件夹，放在这些目录下面。</div>
      ${rs.map((r, i) => `<div class="root-row"><span class="path">${esc(shown(r))}</span><button class="btn small" data-del="${i}">移除</button></div>`).join('')}
      <div class="add-row"><button class="btn small primary" data-a="pick">＋ 选择文件夹…</button>
        <button class="btn small" data-quick="Download">＋ 下载文件夹</button>
        <button class="btn small" data-quick="Documents">＋ 文档文件夹</button>
        ${rs.includes(defaultRoot()) ? '' : '<button class="btn small" data-quick="Nagisaland/Games">＋ 默认目录</button>'}
      </div>
      ${hidden.size ? `<div class="sec">移出游戏库的游戏</div><div class="add-row"><span class="sub">${hidden.size} 个（文件还在）</span><button class="btn small" data-a="unhide">全部恢复</button></div>` : ''}
      <div class="sec">封面</div>
      <div class="hint">没有封面的游戏，按文件夹名去小站找对应资源，把封面下载成游戏文件夹里的 cover.jpg（需要先在「小站」页登录）。</div>
      <div class="add-row"><button class="btn small primary" data-a="covers">从小站补全封面</button><span class="sub" id="coverStatus"></span></div>
      <div class="sec">AI 翻译</div>
      <div class="hint">生肉游戏翻成中文：选翻译服务、填 API Key（支持粘贴），设置识屏翻译。</div>
      <div class="add-row"><button class="btn small primary" data-a="trset">AI 翻译设置…</button><button class="btn small" data-a="ocr">开启识屏翻译浮窗</button></div>
      <div class="sec">关于</div>
      <div class="hint">Nagisaland 手机版 ${esc(N.version())} · Android API ${N.sdk()}<br>
        RPG Maker MV/MZ、TyranoScript 游戏在 App 里直接运行，每个游戏的存档分开保存（MV/MZ 的存档同时写到游戏文件夹的 save/，电脑版能直接读）；
        打包在 exe / package.nw / app.asar 里的网页游戏可以解包后玩；其它引擎交给对应的模拟器 App。</div>
    </div>`);
    s.onclick = e => {
      const b = e.target.closest('button');
      if (!b) return;
      const cur = roots();
      let changed = false;
      if (b.dataset.del != null) { cur.splice(+b.dataset.del, 1); changed = true; }
      else if (b.dataset.quick) { const p = join(ROOT, b.dataset.quick); if (!cur.includes(p)) { cur.push(p); changed = true; } }
      else if (b.dataset.a === 'covers') { fillCovers(s.querySelector('#coverStatus'), b); return; }
      else if (b.dataset.a === 'trset') { TrSettings.open(); return; }
      else if (b.dataset.a === 'ocr') { startOcr(); return; }
      else if (b.dataset.a === 'pick') {
        pickFolder({ title: '选择放游戏的文件夹', hint: '选中的文件夹里，每个子文件夹当作一个游戏' }).then(p => { if (p) { addRoot(p); openSettings(); } else openSettings(); });
        return;
      }
      else if (b.dataset.a === 'unhide') { hidden.clear(); store.set('hidden', []); openSettings(); render(); $('#libStatus').textContent = summary(); toast('已全部恢复'); return; }
      if (changed) { store.set('roots', cur); openSettings(); scan(); }
    };
  }

  function init() {
    $('#q').addEventListener('input', render);
    $('#chips').onclick = e => {
      const c = e.target.closest('.chip');
      if (!c) return;
      if (c.id === 'sortChip') {
        const i = SORTS.findIndex(x => x[0] === sort);
        sort = SORTS[(i + 1) % SORTS.length][0]; store.set('sort', sort);
      } else filter = c.dataset.f;
      render();
    };
    $('#btnRefresh').onclick = () => { coverCache.clear(); refreshApps(); scan(); };
    $('#btnSettings').onclick = openSettings;
    $('#btnAdd').onclick = openAdd;
  }

  function start() {
    settlePlaying();
    render();
    if (games.length) $('#libStatus').textContent = summary();
    scan();
  }

  // 从别的 App（App 游戏、模拟器）切回来：结算游玩时长
  function onResume() {
    const had = store.get('playing', null);
    settlePlaying();
    refreshApps();   // 从别的 App 回来时可能刚装了模拟器 / 插件
    if (had) render();
  }

  function back() {
    if ($('#q').value) { $('#q').value = ''; $('#q').blur(); render(); return true; }
    return false;
  }

  return { init, start, scan, render, back, onResume, games: () => games };
})();
