// Nagisaland 手机版 · 小站（和电脑版用同一套 /api/client 接口）
'use strict';
const Site = (() => {
  const C = window.GalsiteCore;
  const gs = Object.assign({ base: '', token: '', user: null, username: '', deviceId: '', links: {}, pending: [] }, store.get('gs', {}));
  if (!gs.deviceId) gs.deviceId = 'nagisaland-mobile-' + Math.random().toString(36).slice(2, 10);
  const view = { q: '', section: 'game', sort: 'new', page: 1, items: [], hasNext: false, loading: false, deep: false, cursor: null, maxId: 0, seq: 0, stop: false };
  let detail = null;
  let built = false;

  function save() {
    const ids = Object.keys(gs.links);
    if (ids.length > 300) ids.sort((a, b) => (gs.links[a].fetchedAt || 0) - (gs.links[b].fetchedAt || 0)).slice(0, ids.length - 300).forEach(id => delete gs.links[id]);
    const cutoff = Date.now() - 90 * 86400000;
    gs.pending = gs.pending.filter(p => (p.addedAt || 0) > cutoff).slice(-200);
    store.set('gs', gs);
  }

  class GsError extends Error { constructor(m, status, data) { super(m); this.status = status; this.data = data; } }
  const abs = u => !u ? '' : (/^https?:\/\//i.test(u) ? u : gs.base.replace(/\/+$/, '') + (u.startsWith('/') ? u : '/' + u));

  // 网络请求放到后台线程：慢网络（比如华为卓易通）下界面不会卡住；老版本外壳没有这个接口就退回同步请求
  let httpSeq = 0;
  async function httpAsync(method, url, headers, body) {
    if (!N.httpStart) return N.http(method, url, headers, body);
    const id = 'h' + (++httpSeq) + '_' + Date.now();
    N.httpStart(id, method, url, headers, body);
    const t0 = Date.now();
    for (;;) {
      await tick(60);
      const r = N.httpResult(id);
      if (r != null) return r;
      if (Date.now() - t0 > 90000) return JSON.stringify({ status: 0, error: '请求超时（90 秒）' });
    }
  }
  async function api(method, path, body) {
    await tick();
    const headers = { 'User-Agent': 'Nagisaland-Mobile/0.2 (+galsite-client)', Accept: 'application/json' };
    if (gs.token) headers.Authorization = 'Bearer ' + gs.token;
    if (body) headers['Content-Type'] = 'application/json';
    let r;
    // 连不上时自动重试 2 次（有的网络环境，比如华为卓易通，偶尔第一次连接会失败）
    for (let i = 0; i < 3; i++) {
      try { r = JSON.parse(await httpAsync(method, abs(path), JSON.stringify(headers), body ? JSON.stringify(body) : null)); }
      catch (e) { r = { status: 0, error: String(e) }; }
      if (r && r.status) break;
      if (i < 2) await tick(800 * (i + 1));
    }
    if (!r || !r.status) throw new GsError('连不上小站（' + ((r && r.error) || '网络错误') + '）。检查小站地址和网络。');
    let data = null;
    try { data = JSON.parse(r.body); } catch (e) {}
    if (!data) {
      if (r.status === 404 || r.status === 405) throw new GsError('小站还没有客户端接口（HTTP ' + r.status + '）');
      throw new GsError('小站返回异常（HTTP ' + r.status + '）');
    }
    if (r.status === 401 && path !== '/api/client/login') {
      gs.token = ''; gs.user = null; save(); showLogin(data.error || '登录已失效，请重新登录');
      throw new GsError(data.error || '请重新登录', 401);
    }
    if (!data.ok) throw new GsError(data.error || ('HTTP ' + r.status), r.status, data);
    return data;
  }

  // ---------- 登录 ----------
  function showLogin(err) {
    $('#siteLogin').hidden = false;
    $('#siteBrowse').hidden = true;
    $('#gsBase').value = gs.base || '';
    $('#gsUser').value = gs.username || '';
    $('#gsErr').textContent = err || '';
  }
  async function doLogin() {
    const base = $('#gsBase').value.trim().replace(/\/+$/, '');
    const username = $('#gsUser').value.trim(), password = $('#gsPass').value;
    const err = $('#gsErr');
    if (!/^https?:\/\//i.test(base)) { err.textContent = '小站地址要以 http:// 或 https:// 开头'; return; }
    if (!username || !password) { err.textContent = '请输入账号和密码'; return; }
    const btn = $('#gsLoginBtn');
    btn.disabled = true; btn.textContent = '登录中…'; err.textContent = '';
    gs.base = base;
    try {
      const d = await api('POST', '/api/client/login', { username, password, device_id: gs.deviceId });
      gs.token = d.token; gs.user = d.user; gs.username = username;
      if (d.device_id) gs.deviceId = d.device_id;
      save();
      $('#gsPass').value = '';
      toast('欢迎回来，' + ((d.user && d.user.nickname) || username));
      showBrowse();
      view.page = 1; search();
    } catch (e) { err.textContent = e.message; }
    finally { btn.disabled = false; btn.textContent = '登录'; }
  }
  function openAccount() {
    const u = gs.user || {};
    const s = openSheet(`<div class="body"><h3>${esc(u.nickname || gs.username)}</h3>
      <div class="line"><span class="tag">积分 ${esc(u.points != null ? u.points : '?')}</span><span class="path">${esc(gs.base)}</span></div>
      <div class="hint">已获取过的下载链接保存在手机上，之后再看不会重复扣分。</div>
      <div class="actions"><button class="btn wide" data-a="logout">退出登录</button></div></div>`);
    s.onclick = async e => {
      if (!e.target.closest('[data-a=logout]')) return;
      if (!confirm('退出登录？已获取的链接缓存会保留。')) return;
      try { await api('POST', '/api/client/logout'); } catch (err) {}
      gs.token = ''; gs.user = null; save(); closeSheet(); showLogin();
    };
  }
  function renderUser() {
    const u = gs.user || {};
    $('#gsUserChip').textContent = (u.nickname || gs.username || '账号') + (u.points != null ? ' · ' + u.points + ' 分' : '');
  }

  // ---------- 搜索 ----------
  function showBrowse() {
    $('#siteLogin').hidden = true;
    $('#siteBrowse').hidden = false;
    renderUser();
  }
  // 「含简介」搜索：小站新版支持分段扫描（scan=1 + 游标），一段段接着搜，没有时间上限，边搜边出结果；
  // 小站还是旧版时退回老的 deep=1（服务器最多搜 8 秒）
  async function deepSearch(more) {
    const seq = ++view.seq;
    view.stop = false; view.loading = true;
    if (!more) { view.items = []; view.cursor = null; renderGrid(); }
    $('#gsMore').hidden = true;
    const target = view.items.length + 24;   // 每次至少再找 24 个（或搜完）才停下
    const st = $('#gsStatus');
    const paint = extra => {
      const pct = view.maxId && view.cursor ? Math.max(1, Math.min(99, Math.round((view.maxId - view.cursor) / view.maxId * 100))) : 0;
      st.innerHTML = `“${esc(view.q)}”含简介 · 已找到 ${view.items.length} 个${extra || (pct ? ` · 已搜 ${pct}%` : '')} <a href="#" id="gsStop">停止</a>`;
      const b = $('#gsStop'); if (b) b.onclick = e => { e.preventDefault(); view.stop = true; };
    };
    paint(' · 搜索中…');
    try {
      for (;;) {
        const qs = new URLSearchParams({ section: view.section, sort: 'new', page: '1', q: view.q, deep: '1', scan: '1' });
        if (view.cursor) qs.set('cursor', String(view.cursor));
        const d = await api('GET', '/api/client/search?' + qs.toString());
        if (seq !== view.seq) return;
        if (!d.scan) {   // 旧版小站：只能搜 8 秒
          view.items = d.items || []; view.hasNext = false; view.cursor = null; view.loading = false; renderGrid();
          st.textContent = `“${view.q}”含简介 · 找到 ${view.items.length} 个` + (d.timed_out ? ' · 小站还是旧版，含简介最多只能搜 8 秒，更新小站后就没有这个限制' : '');
          return;
        }
        const seen = new Set(view.items.map(x => x.id));
        view.items = view.items.concat((d.items || []).filter(x => !seen.has(x.id)));
        view.maxId = d.max_id || view.maxId; view.cursor = d.next_cursor;
        renderGrid();
        if (d.done || !view.cursor) { view.hasNext = false; view.loading = false; renderGrid(); $('#gsMore').hidden = true; st.textContent = `“${view.q}”含简介 · 共找到 ${view.items.length} 个（已搜完全部）`; return; }
        view.hasNext = true;
        if (view.stop || view.items.length >= target) { $('#gsMore').hidden = false; paint(view.stop ? ' · 已停止' : ''); $('#gsStop') && $('#gsStop').remove(); return; }
        paint();
      }
    } catch (e) {
      if (seq === view.seq && e.status !== 401) { st.textContent = e.message; $('#gsMore').hidden = !view.cursor; }
    } finally { if (seq === view.seq) view.loading = false; }
  }
  async function search(more) {
    if (!more) { view.q = $('#gsQ').value.trim(); view.deep = $('#gsDeep').checked && !!view.q; }
    if (view.deep && view.q) {
      if (!more) { view.section = $('#gsSection').value; view.sort = 'new'; }
      if (view.loading && more) return;
      return deepSearch(more);
    }
    view.seq++;
    if (view.loading && more) return;
    if (!more) { view.page = 1; view.section = $('#gsSection').value; view.sort = $('#gsSort').value; }
    view.loading = true;
    $('#gsStatus').textContent = '加载中…';
    $('#gsMore').hidden = true;
    const qs = new URLSearchParams({ section: view.section, sort: view.sort, page: String(view.page) });
    if (view.q) qs.set('q', view.q);
    const seq = view.seq;
    try {
      const d = await api('GET', '/api/client/search?' + qs.toString());
      if (seq !== view.seq) return;
      view.items = more ? view.items.concat(d.items || []) : (d.items || []);
      view.hasNext = !!d.has_next;
      renderGrid();
      $('#gsStatus').textContent = (view.q ? `“${view.q}”` : '全部') + (d.total != null ? ` · 共 ${d.total} 个` : '');
    } catch (e) {
      if (seq === view.seq && e.status !== 401) $('#gsStatus').textContent = e.message;
    } finally { if (seq === view.seq) view.loading = false; }
  }
  function renderGrid() {
    const g = $('#gsGrid');
    if (!view.items.length) { g.innerHTML = view.deep && view.loading ? '' : '<div class="center">没有找到，换个关键词试试。</div>'; return; }
    g.innerHTML = view.items.map(it => `
      <button class="card" data-id="${it.id}">
        <div class="cover tall" style="background:${tint(it.title)}">${it.cover ? `<img class="loaded" loading="lazy" src="${esc(abs(it.cover))}" onerror="this.remove()">` : ''}
          ${gs.links[it.id] ? '<span class="badge play">已获取</span>' : ''}</div>
        <div class="meta"><div class="name">${esc(C.cleanSiteTitle(it.title))}</div>
          <div class="sub">${esc([it.size, ...C.usefulTags(it.tags).slice(0, 2)].filter(Boolean).join(' · '))}</div></div>
      </button>`).join('');
    $('#gsMore').hidden = !view.hasNext;
  }

  // ---------- 详情 ----------
  async function openDetail(id) {
    const s = openSheet('<div class="body center">加载中…</div>', 'tall');
    try {
      const d = await api('GET', `/api/client/resource/${id}?view=1`);
      detail = d.resource;
      if (gs.user && d.user) { gs.user.points = d.user.points; save(); renderUser(); }
      renderDetail();
    } catch (e) {
      if (e.status !== 401) s.innerHTML = `<div class="grab"></div><div class="body center">${esc(e.message)}</div>`;
    }
  }
  function quotedPasswords(p) {
    const m = String(p).match(/[“"「『]([^”"」』]+)[”"」』]/g);
    if (m && m.length) return m.map(x => x.slice(1, -1).trim()).filter(Boolean);
    return [String(p).trim()];
  }
  function linksHtml(c) {
    const rows = (c.links || []).map((l, i) => `
      <div class="link-row">
        <span class="pan">${esc(l.label || l.provider || l.pan)}</span>
        <div class="n"><div class="url">${esc(String(l.url).replace(/^https?:\/\//, ''))}</div>
          ${l.code ? `<button class="code" data-copy="${esc(l.code)}">提取码 <b>${esc(l.code)}</b></button>` : '<small>无提取码</small>'}</div>
        <button class="btn small primary" data-link="${i}">${l.pan === 'thread' ? '原帖' : '打开'}</button>
      </div>`).join('');
    const pw = (c.unzip_passwords || []).map(p => `<div class="unzip">🔑 解压密码 <b>${esc(p)}</b>
      ${quotedPasswords(p).map(x => `<button class="btn small" data-copy="${esc(x)}">复制「${esc(x)}」</button>`).join('')}</div>`).join('');
    return `<div class="sec">下载链接（点「打开」会先复制提取码，再跳到网盘 App 或浏览器）</div>
      ${rows || '<div class="hint">没有解析出链接，看下面原文。</div>'}
      ${pw || '<div class="hint">资源里没写解压密码（多数不需要）。</div>'}
      ${c.full_text ? `<details class="raw"><summary>完整原文</summary><pre>${esc(c.full_text)}</pre><button class="btn small" data-copy="${esc(c.full_text)}">复制全文</button></details>` : ''}
      <div class="actions"><button class="btn wide" data-a="toExtract">下载好了？去「解压」页</button></div>`;
  }
  function renderDetail() {
    const r = detail;
    if (!r) return;
    const cached = gs.links[r.id];
    const nm = C.splitTitle(C.cleanSiteTitle(r.title));
    const s = openSheet(`
      ${r.cover ? `<img class="hero" src="${esc(abs(r.cover))}" onerror="this.remove()">` : ''}
      <div class="body">
        <h3>${esc(r.title)}</h3>
        <div class="line">${[r.size && '📦 ' + r.size, r.upload_time && '🕒 ' + r.upload_time, '⬇ ' + (r.downloads || 0)].filter(Boolean).map(x => `<span>${esc(x)}</span>`).join('')}</div>
        <div class="line">${(r.pans || []).map(p => `<span class="tag">${esc(p)}</span>`).join('')}${r.has_unzip_password ? '<span class="tag warn">有解压密码</span>' : ''}</div>
        ${(r.share_names || []).length ? `<div class="hint">网盘分享名：${esc(r.share_names.join(' / '))}</div>` : ''}
        <div class="actions"><button class="btn primary wide" data-a="links">${cached ? '查看下载链接' : r.can_download_free ? '获取下载链接（免费）' : `获取下载链接（${esc(r.download_cost)} 积分）`}</button></div>
        <div id="gsLinks">${cached ? linksHtml(cached) : ''}</div>
        <div class="line tags">${C.usefulTags(r.tags).map(t => `<span class="tag">${esc(t)}</span>`).join('')}</div>
        ${(r.images || []).length ? `<div class="shots">${r.images.slice(0, 12).map(u => `<img loading="lazy" src="${esc(abs(u))}" onerror="this.remove()">`).join('')}</div>` : ''}
        <div class="desc">${esc(r.description || '（没有简介）')}</div>
        <div class="hint">入库名：${esc(nm.chinese)}${nm.original ? ' / ' + esc(nm.original) : ''}</div>
      </div>`, 'tall');
    s.onclick = e => {
      const c = e.target.closest('[data-copy]');
      if (c) { toast(N.copyText(c.dataset.copy) ? '已复制' : '复制失败'); return; }
      const b = e.target.closest('button');
      if (!b) return;
      if (b.dataset.a === 'links') getLinks();
      else if (b.dataset.a === 'toExtract') { closeSheet(); App.tab('extract'); }
      else if (b.dataset.link != null) {
        const l = (gs.links[r.id] || {}).links[+b.dataset.link];
        if (!l) return;
        addPending(r);
        if (l.code) N.copyText(l.code);
        N.openUrl(abs(l.url));
        toast(l.code ? '提取码已复制：' + l.code : '正在打开');
      }
    };
  }
  async function getLinks() {
    const r = detail;
    if (!r) return;
    if (gs.links[r.id]) { const box = $('#gsLinks'); box.innerHTML = linksHtml(gs.links[r.id]); box.scrollIntoView({ behavior: 'smooth' }); return; }
    if (!r.can_download_free && !confirm(`将消耗 ${r.download_cost} 积分（当前 ${gs.user ? gs.user.points : '?'}）。获取后链接会保存在手机上，之后再看不会重复扣分。`)) return;
    const btn = $('#sheet [data-a=links]');
    btn.disabled = true; btn.textContent = '获取中…';
    try {
      const d = await api('POST', `/api/client/download/${r.id}`);
      gs.links[r.id] = { title: r.title, links: d.links, unzip_passwords: d.unzip_passwords, raw: d.raw, full_text: d.full_text || '', fetchedAt: Date.now() };
      if (gs.user && d.points != null) gs.user.points = d.points;
      addPending(r);
      save(); renderUser();
      if (d.charged) toast(`已扣除 ${d.charged} 积分，剩余 ${d.points}`);
      renderDetail(); renderGrid();
    } catch (e) {
      if (e.status === 401) return;
      toast(e.message, 4000);
      btn.disabled = false; btn.textContent = '重试获取下载链接';
    }
  }

  // ---------- 下载过的资源（解压、入库时用来找密码和封面） ----------
  function addPending(r) {
    const ex = gs.pending.find(p => p.id === r.id);
    if (ex) { ex.addedAt = Date.now(); save(); return; }
    gs.pending.push({ id: r.id, title: r.title, cleanTitle: C.cleanSiteTitle(r.title), shareNames: r.share_names || [], cover: r.cover ? abs(r.cover) : '', addedAt: Date.now(), done: false });
    save();
  }
  function passwordsOf(p) { const c = p && gs.links[p.id]; return c ? [].concat(...(c.unzip_passwords || []).map(quotedPasswords)) : []; }
  // 文件名/文件夹名 → 对应的小站资源（按网盘分享名、标题匹配）
  function match(name) {
    const n = String(name).replace(/\.(part\d+\.rar|7z\.\d{3}|zip\.\d{3}|rar|7z|zip|\d{3})$/i, '');
    return gs.pending.slice().reverse().find(p => C.matchesPending(p, n)) || null;
  }
  // 按游戏文件夹名在小站上找对应的资源（补封面/标题用）→ { id, title, cover } 或 null
  async function findForFolder(name) {
    if (!gs.token || !gs.base) throw new GsError('还没登录小站');
    const base = String(name).replace(/\s*[(（\[【][^)）\]】]*[)）\]】]\s*$/, '').replace(/\s+[vV]?\d+(\.\d+)+.*$/, '').trim() || String(name);
    const n = C.norm(base), full = C.norm(name);
    if (!n) return null;
    const qs = new URLSearchParams({ section: 'game', sort: 'new', page: '1', q: base });
    const d = await api('GET', '/api/client/search?' + qs.toString());
    let best = null, bestScore = 0;
    for (const it of d.items || []) {
      const clean = C.cleanSiteTitle(it.title), parts = C.splitTitle(clean);
      const cands = [clean, parts.chinese, parts.original].concat(it.share_names || []).map(C.norm).filter(Boolean);
      let sc = 0;
      for (const t of cands) {
        if (t === n || t === full) sc = Math.max(sc, 1);
        else if (n.length >= 3 && (t.includes(n) || n.includes(t))) sc = Math.max(sc, 0.6 + Math.min(t.length, n.length) / Math.max(t.length, n.length) * 0.3);
      }
      if (sc > bestScore) { bestScore = sc; best = it; }
    }
    return best && bestScore >= 0.7 ? { id: best.id, title: best.title, cover: best.cover ? abs(best.cover) : '' } : null;
  }
  const loggedIn = () => !!(gs.token && gs.base);
  function recentPending(n) { return gs.pending.slice().reverse().filter(p => !p.done).slice(0, n || 8); }
  function markDone(p) { const x = gs.pending.find(q => q.id === p.id); if (x) { x.done = true; save(); } }

  function init() {
    $('#gsLoginBtn').onclick = doLogin;
    $('#gsPass').addEventListener('keydown', e => { if (e.key === 'Enter') doLogin(); });
    $('#gsSearchBtn').onclick = () => search();
    $('#gsQ').addEventListener('keydown', e => { if (e.key === 'Enter') { e.target.blur(); search(); } });
    $('#gsSection').onchange = () => search();
    $('#gsSort').onchange = () => search();
    $('#gsMore').onclick = () => { if (!view.deep) view.page++; search(true); };
    $('#gsDeep').onchange = () => { if ($('#gsQ').value.trim()) search(); };
    $('#gsUserChip').onclick = openAccount;
    $('#gsGrid').onclick = e => { const c = e.target.closest('.card'); if (c) openDetail(c.dataset.id); };
  }
  function show() {
    if (!gs.token) { showLogin(); return; }
    showBrowse();
    if (!built) { built = true; search(); }
  }

  return { init, show, match, passwordsOf, recentPending, markDone, abs, findForFolder, loggedIn, pending: () => gs.pending, splitName: t => C.splitTitle(C.cleanSiteTitle(t)) };
})();
