// Nagisaland 手机版 · 解压（内置 7-Zip 一键解压：修后缀、认分卷、自动试密码、多层解到底、整理入库；ZArchiver 作为手动备选）
'use strict';
const Extract = (() => {
  // 网盘 App 常见的下载位置（存在的才扫）
  const DL_DIRS = ['Download', 'Download/Quark', 'Quark/Download', 'Download/BaiduNetdisk', 'BaiduNetdisk', 'UCDownloads', 'Download/UCDownloads',
    'Download/迅雷下载', 'Download/xunlei', 'Download/123pan', 'Download/Aliyun', 'Download/aDrive', 'Documents'];
  const REAL_ARC = /(\.(7z|zip|rar)(\.\d{3})?|\.part\d+\.rar|\.z\d{2}|\.r\d{2}|\.\d{3})$/i;
  const SKIP_EXT = /\.(apk|mp4|mkv|avi|mov|mp3|flac|jpe?g|png|gif|webp|pdf|docx?|xlsx?|pptx?|txt|exe)$/i;
  const MEDIA = /\.(mp4|mkv|avi|mov|wmv|flv|m4v|ts|rmvb|webm|jpe?g|png|gif|webp|bmp)$/i;
  let groups = [], maybe = [];   // maybe：可能是伪装成视频/图片、把压缩包接在后面的文件
  const extraDirs = () => store.get('dlDirs', []);
  const session = () => store.get('exSession', null);

  function dlDirs() {
    const out = [];
    for (const d of DL_DIRS.map(x => join(ROOT, x)).concat(extraDirs())) if (!out.includes(d) && N.exists(d)) out.push(d);
    return out;
  }

  function sniff(path) {
    const h = N.head(path, 8) || '';
    if (h.startsWith('377abcaf271c')) return '7z';
    if (/^504b0(3|5|7)/.test(h)) return 'zip';
    if (h.startsWith('52617221')) return 'rar';
    return null;
  }
  // 电脑版 organizer.js 的规则：a.7z.001.txt → a.7z.001；a.7z删 → a.7z；假后缀但文件头是压缩包 → 补正确后缀
  // 浏览器/网盘重复下载会加「(1)」：4383.part1(1).rar → 4383.part1.rar（不改回来 7-Zip 会去找 part1(2).rar）
  const DUP = /^(.*?)\s?[(（]\d{1,3}[)）]((?:\.(?:7z|zip|rar))?(?:\.part\d+\.rar|\.(?:7z|zip|rar)|\.\d{3}|\.z\d{2}|\.r\d{2}))$/i;
  function fixedName(name, path, size) {
    const d = name.match(DUP);
    if (d && d[1] && !/[(（]$/.test(d[1])) name = d[1] + d[2];
    if (REAL_ARC.test(name)) return name;
    const m = name.match(/^(.*?(?:\.(?:7z|zip|rar)(?:\.\d{3})?|\.part\d+\.rar))(?=[^\d])/i);
    if (m && m[1] !== name) return m[1];
    if (size > 5 * 1048576 || !SKIP_EXT.test(name)) {
      const t = sniff(path);
      if (t) return (name.replace(/\.(txt|jpe?g|png|gif|mp4|mkv|avi|pdf|docx?|apk|exe|dat|bin|tmp|[^.]{0,4}删|[^.]*)$/i, '') || name) + '.' + t;
    }
    return null;
  }
  function isVolumeEntry(n) {
    if (/\.part0*1\.rar$/i.test(n)) return true;
    if (/\.part\d+\.rar$/i.test(n)) return false;
    if (/\.(7z|zip|rar)\.0*1$/i.test(n)) return true;
    if (/\.(7z|zip|rar)\.\d{3}$/i.test(n)) return false;
    if (/\.0*1$/.test(n) && /\.\d{3}$/.test(n)) return true;
    if (/\.\d{3}$/.test(n) || /\.(z|r)\d{2}$/i.test(n)) return false;
    return /\.(7z|zip|rar)$/i.test(n);
  }
  const groupKey = n => n.replace(/\.part\d+\.rar$/i, '').replace(/\.(7z|zip|rar)\.\d{3}$/i, '.$1').replace(/\.(z|r)\d{2}$/i, '').replace(/\.(7z|zip|rar)$/i, '').replace(/\.\d{3}$/, '');
  const mimeOf = n => /\.zip/i.test(n) ? 'application/zip' : /\.rar/i.test(n) ? 'application/x-rar-compressed' : 'application/x-7z-compressed';

  async function scan() {
    $('#exStatus').textContent = '正在找压缩包…';
    await tick();
    const found = [];
    const look = (dir, depth) => {
      const list = ls(dir) || [];
      for (const e of list) {
        const p = join(dir, e.n);
        if (e.d) { if (depth > 0 && !e.n.startsWith('.')) look(p, depth - 1); continue; }
        // 太小的文件跳过——但分卷的最后一卷可能很小，不能跳
        if (e.s < 64 * 1024 && !/\.(part\d+\s?([(（]\d+[)）])?\.rar|(7z|zip|rar)\.\d{3}|r\d{2}|z\d{2})$/i.test(e.n)) continue;
        const f = fixedName(e.n, p, e.s);
        if (f && !found.some(x => x.path === p)) found.push({ dir, name: e.n, path: p, fixed: f, size: e.s, m: e.m || 0 });
        else if (!f && MEDIA.test(e.n) && e.s > 20 * 1048576 && !maybe.some(x => x.path === p)) maybe.push({ dir, name: e.n, path: p, size: e.s, m: e.m || 0 });
      }
    };
    maybe = [];
    for (const d of dlDirs()) look(d, 1);
    maybe.sort((a, b) => b.m - a.m);
    const map = new Map();
    for (const f of found) {
      const k = f.dir + '|' + groupKey(f.fixed).toLowerCase();
      if (!map.has(k)) map.set(k, { dir: f.dir, key: groupKey(f.fixed), files: [], size: 0, m: 0 });
      const g = map.get(k);
      g.files.push(f); g.size += f.size; g.m = Math.max(g.m, f.m);
    }
    groups = [...map.values()].map(g => {
      // 同一卷有好几份（重复下载带 (1)）：挑大小对的那份（分卷除最后一卷外大小都一样），没法判断就挑大的、再挑新的
      const byName = new Map();
      for (const f of g.files) { if (!byName.has(f.fixed)) byName.set(f.fixed, []); byName.get(f.fixed).push(f); }
      const sizeCount = new Map();
      for (const f of g.files) sizeCount.set(f.size, (sizeCount.get(f.size) || 0) + 1);
      const common = [...sizeCount.entries()].sort((a, b) => b[1] - a[1] || b[0] - a[0])[0][0];
      g.files = [...byName.values()].map(list => {
        if (list.length === 1) return list[0];
        const pick = list.find(f => f.size === common) || list.slice().sort((a, b) => b.size - a.size || b.m - a.m)[0];
        pick.others = list.filter(f => f !== pick);   // 解压前把名字对的那份（坏的）挪开，好的改成正确名字
        return pick;
      });
      g.size = g.files.reduce((a, f) => a + f.size, 0);
      g.files.sort((a, b) => a.fixed.localeCompare(b.fixed, 'en', { numeric: true }));
      // 分卷大小检查：除最后一卷外应该一样大，不一样的多半没下完
      if (g.files.length > 1 && g.files.every(f => /\.(part\d+\.rar|\d{3})$/i.test(f.fixed))) {
        const std = g.files[0].size;
        g.badVols = g.files.slice(0, -1).filter(f => f.size !== std).map(f => f.fixed);
        if (g.files[g.files.length - 1].size > std) g.badVols.push(g.files[g.files.length - 1].fixed);
      } else g.badVols = [];
      g.entry = g.files.find(f => isVolumeEntry(f.fixed)) || g.files[0];
      g.disguised = g.files.some(f => f.fixed !== f.name);
      g.dupName = g.files.some(f => DUP.test(f.name));
      g.res = Site.match(g.key);
      return g;
    }).sort((a, b) => b.m - a.m);
    render();
  }

  function zarchiver() { return findApp('zarchiver'); }

  function render() {
    const z = zarchiver(), zb = bundledFor('zarchiver');
    $('#exZ').innerHTML = canInApp() ? '<span class="tag ok">内置 7-Zip：7z / rar / zip、分卷、密码都能直接解</span>'
      : z ? `<span class="tag ok">ZArchiver 已安装</span>`
      : `<span class="tag warn">没装 ZArchiver</span> ${zb ? `<button class="btn small" data-zinstall="1">安装内置版</button>` : `<button class="btn small" data-zget="1">去下载</button>`}`;
    const s = session();
    $('#exFind').textContent = s ? `解压好了？找游戏（${ago(s.start)}开始解压）` : '解压好了？找解压出来的游戏';
    const box = $('#exList');
    if (!groups.length) {
      box.innerHTML = `<div class="center">下载目录里没找到${maybe.length ? '普通的' : ''}压缩包。<br><span class="hint">会扫描：${dlDirs().map(shown).map(esc).join('、') || '（都不存在）'}</span></div>`;
    } else {
      box.innerHTML = groups.map((g, i) => {
        const pw = Site.passwordsOf(g.res);
        return `<div class="ex-card">
          <div class="ex-name">${esc(g.entry.fixed)}</div>
          <div class="line">
            <span>${fmtSize(g.size)}</span>${g.files.length > 1 ? `<span class="tag">${g.files.length} 个分卷</span>` : ''}
            ${g.badVols.length ? `<span class="tag warn">${esc(g.badVols.join('、'))} 大小不对，可能没下完</span>` : ''}${g.disguised ? '<span class="tag warn">' + (g.dupName ? '文件名带 (1)，会自动改回' : '伪装后缀，会自动改回') + '</span>' : ''}<span>${g.m ? ago(g.m) : ''}</span>
          </div>
          <div class="hint">${esc(shown(g.dir))}</div>
          ${g.res ? `<div class="hint">小站资源：${esc(g.res.cleanTitle || g.res.title)}${pw.length ? ' · 密码 <b>' + esc(pw.join(' / ')) + '</b>' : ''}</div>` : ''}
          <div class="add-row"><button class="btn small primary" data-go="${i}">解压</button><button class="btn small" data-del="${i}">删除压缩包</button></div>
        </div>`;
      }).join('');
    }
    if (maybe.length) box.insertAdjacentHTML('beforeend', `<div class="sec" style="margin-top:14px">可能是伪装成视频 / 图片的压缩包</div>
      <div class="hint">有的资源把压缩包接在一段能播放的视频（或图片）后面，要求「改后缀」。点「检查」会找出里面藏着的压缩包，找到就能一键解压。</div>
      ${maybe.map((f, i) => `<div class="ex-card"><div class="ex-name">${esc(f.name)}</div>
        <div class="line"><span>${fmtSize(f.size)}</span><span>${f.m ? ago(f.m) : ''}</span></div><div class="hint">${esc(shown(f.dir))}</div>
        <div class="add-row"><button class="btn small primary" data-maybe="${i}">检查并解压</button></div></div>`).join('')}`);
    const recent = Site.recentPending(6).filter(p => Site.passwordsOf(p).length);
    $('#exPw').innerHTML = recent.length ? '<div class="sec">最近在小站获取的解压密码</div>' + recent.map(p => `<div class="pw-row"><span class="n">${esc(p.cleanTitle || p.title)}</span>
      ${Site.passwordsOf(p).map(x => `<button class="btn small" data-copy="${esc(x)}">${esc(x)}</button>`).join('')}</div>`).join('') : '';
    $('#exStatus').textContent = groups.length ? `找到 ${groups.length} 个压缩包` : '';
  }

  // 改回真后缀（分卷一起改）
  function fixNames(g) {
    for (const f of g.files) {
      // 挑中的是带 (1) 的那份：把占着正确名字的那份（大小不对）改名挪开
      for (const o of f.others || []) if (o.name === f.fixed && f.name !== f.fixed) {
        const away = unique(join(o.dir, o.name + '.不完整'));
        if (N.rename(o.path, away)) { o.path = away; o.name = basename(away); }
      }
    }
    for (const f of g.files) {
      if (f.fixed === f.name) continue;
      let target = join(f.dir, f.fixed);
      if (N.exists(target)) continue;
      if (N.rename(f.path, target)) { f.path = target; f.name = f.fixed; }
    }
  }

  // ================= 一键解压（和电脑版 organizer.js 同样的流程） =================
  // 改后缀 → 内置 7-Zip 解压（每层依次试密码，包里还是压缩包就接着解，最多 6 层）→ 删广告 → 去掉多余的文件夹层 → 用小站名字命名 → 入库
  const JUNK_NAME = /(H油猫的资源屋|免责声明|更多游戏看这个|哀不爱灰原|网赚|防迷路|机场推荐|翻墙|资源发布页|TG群|资源库|梯子|加速器|收藏我不迷路)/;
  const SMALL_DOC = /\.(txt|nfo|htm|html|jpg|jpeg|png|gif|webp|docx?|pdf|ini|url|md)$/i;
  const BAD_DATA = /data error|crc failed|checksum error/i;
  const g_multi = p => /\.(part\d+\.rar|\d{3})$/i.test(p);
  const WRONG_PW = /wrong password|can ?not open encrypted archive|data error in encrypted|headers error|密码错误/i;
  const NO_PW = 'nagisaland-no-password';   // 给不加密的包一个假密码，防止 7-Zip 等着输入密码
  const safeName = x => String(x || '').replace(/[\\/:*?"<>|]/g, ' ').replace(/\s+/g, ' ').trim().replace(/[. ]+$/, '').slice(0, 80);
  const tmpRoot = () => join(defaultRoot(), '.nagisaland_tmp');
  const unique = p => { if (!N.exists(p)) return p; for (let n = 2; ; n++) if (!N.exists(`${p} (${n})`)) return `${p} (${n})`; };
  const rmTree = p => deleteTreeAsync(p);   // 返回 Promise，需要等删完的地方 await
  const bytes = b64 => { const t = atob(b64 || ''); const u = new Uint8Array(t.length); for (let i = 0; i < t.length; i++) u[i] = t.charCodeAt(i); return u; };
  const canInApp = () => { try { return !!N.p7Ok(); } catch (e) { return false; } };

  let cancelled = false;
  // 跑一次 7-Zip，边跑边读日志里的百分比
  async function run7(args, onPct, onTick) {
    const log = join(tmpRoot(), '7z.log');
    N.mkdirs(tmpRoot()); N.deleteFile(log);
    const err = N.p7Start(JSON.stringify(args), log, false);
    if (err) return { code: -3, out: err };
    let off = 0, out = '', okAt = 0;
    const dec = new TextDecoder('utf-8'), t0 = Date.now();
    for (;;) {
      await tick(250);
      let code = N.p7Exit();
      if (onTick) onTick(Date.now() - t0);
      for (;;) {   // 读新增的日志
        const chunk = bytes(N.readB64(log, off, 262144));
        if (!chunk.length) break;
        off += chunk.length;
        const t = dec.decode(chunk, { stream: true });
        out = (out + t).slice(-60000);
        const m = t.match(/(\d{1,3})%/g);
        if (m && onPct) onPct(Math.min(100, parseInt(m[m.length - 1], 10)));
        if (chunk.length < 262144) break;
      }
      if (cancelled) { N.p7Kill(); return { code: -4, out: '已取消' }; }
      // 有的系统（比如华为卓易通）上 7-Zip 解完了进程却迟迟不退出：日志里已经有最终结果就不再等
      if (code === -1 && /Everything is Ok|Sub items Errors|Can(no|')t open as archive|ERROR:/.test(out) && /(Files|Size|Errors):/.test(out)) {
        if (!okAt) okAt = Date.now();
        else if (Date.now() - okAt > 4000) { N.p7Kill(); code = /Everything is Ok/.test(out) ? 0 : 2; }
      }
      if (code !== -1) return { code, out };
    }
  }
  // 7-Zip 在 Linux 上不会转换 GBK 文件名，所以「有密码 + GBK 文件名」的 zip 由手机自己读文件名，7-Zip 只负责解出数据（-so 按顺序输出）
  async function pipeZip(path, list, outDir, pw, onPct) {
    const log = join(tmpRoot(), '7z.log');
    N.mkdirs(tmpRoot()); N.deleteFile(log);
    const err = N.p7Start(JSON.stringify(['x', '-so', '-p' + pw, path]), log, true);
    if (err) return { code: -3, out: err };
    const total = list.reduce((a, x) => a + x.size, 0) || 1;
    let done = 0, bad = false;
    for (const x of list) {
      const dst = join(outDir, x.name);
      let left = x.size, first = true;
      if (!left) N.p7Pipe(dst, 0, false);
      while (left > 0) {
        const want = Math.min(left, 32 * 1048576);
        const got = N.p7Pipe(dst, want, !first);
        first = false;
        if (got < want) { bad = true; break; }
        left -= got; done += got;
        onPct(Math.floor(100 * done / total));
        await tick(0);
        if (cancelled) { N.p7Kill(); return { code: -4, out: '已取消' }; }
      }
      if (bad) break;
    }
    for (let i = 0; i < 200 && N.p7Exit() === -1; i++) await tick(50);
    const code = bad ? 2 : N.p7Exit();
    let out = ''; try { out = new TextDecoder('utf-8').decode(bytes(N.readB64(log, 0, 60000))); } catch (e) {}
    if (bad && !out) out = 'Wrong password';
    return { code, out };
  }
  // zip 的文件名：先当 UTF-8 读，读不了当 GBK
  function zipNames(path) {
    for (const cs of ['UTF-8', 'GBK']) {
      const raw = N.zipList(path, cs);
      if (raw) return { cs, list: JSON.parse(raw).map(x => ({ name: x[0], size: x[1] })) };
    }
    return null;
  }

  // 解一个压缩包到 outDir，依次试密码 → { password }；失败抛错
  async function extractOne(arc, outDir, passwords, onPct, log, onTick) {
    const isZip = /\.zip$/i.test(arc) || (!/\.(7z|rar|\d{3})$/i.test(arc) && /^504b/.test(N.head(arc, 2) || ''));
    const zn = isZip ? zipNames(arc) : null;
    // 没密码的普通 zip：手机自己解（GBK 文件名也没问题）
    if (zn && zn.list.length) {
      await rmTree(outDir); N.mkdirs(outDir);
      let ok = true, done = 0;
      const total = zn.list.reduce((a, x) => a + x.size, 0) || 1;
      for (let i = 0; i < zn.list.length; i++) {
        const x = zn.list[i];
        if (!N.zipExtract(arc, zn.cs, x.name, join(outDir, x.name))) { ok = false; break; }
        done += x.size;
        if (i % 20 === 0) { onPct(Math.floor(100 * done / total)); await tick(0); }
        if (cancelled) throw new Error('已取消');
      }
      if (ok) return { password: '' };
    }
    if (!canInApp()) throw new Error('这个版本没有内置 7-Zip');
    // 先列一下目录：头部加密的包要密码才能列，文件加密的会显示 Encrypted = +
    const lst = await run7(['l', '-slt', '-ba', '-sccUTF-8', '-p' + NO_PW, arc]);
    if (lst.code === -3) throw new Error('7-Zip 启动失败：' + lst.out);
    if (lst.code > 2 && lst.code !== 255 && !WRONG_PW.test(lst.out)) throw new Error('7-Zip 在这台手机上运行失败（代码 ' + lst.code + '）');
    const needPw = WRONG_PW.test(lst.out) || /Encrypted = \+/.test(lst.out);
    const tries = [...new Set((needPw ? [] : [NO_PW]).concat(passwords.filter(Boolean)))];
    if (!tries.length) { const e = new Error('这个压缩包有密码，小站资源里没记录，请在上面填密码再试'); e.wrongPassword = true; throw e; }
    const gbkPipe = zn && zn.cs === 'GBK';
    let last = '';
    for (const pw of tries) {
      await rmTree(outDir); N.mkdirs(outDir);
      if (pw !== NO_PW) log('试密码：' + pw);
      const res = gbkPipe ? await pipeZip(arc, zn.list, outDir, pw, onPct)
        : await run7(['x', '-y', '-bsp1', '-bb0', '-sccUTF-8', '-p' + pw, '-o' + outDir, arc], onPct, onTick);
      if (res.code === 0) return { password: pw === NO_PW ? '' : pw };
      if (res.code === -4) throw new Error('已取消');
      last = res.out;
      // 不是密码问题（缺分卷、损坏、空间不够…）就别再试了；加密的包里「数据错误」也可能是密码不对，接着试
      if (!WRONG_PW.test(res.out) && !(needPw && BAD_DATA.test(res.out))) break;
    }
    await rmTree(outDir);
    const brief = (last.match(/ERROR[^\n]*|Wrong password[^\n]*|Can not open[^\n]*|Unexpected end[^\n]*|Missing volume[^\n]*|No space[^\n]*/gi) || []).slice(0, 3).join('；');
    const miss = last.match(/Missing volume\s*:\s*([^\n]+)/i);
    const bad = !WRONG_PW.test(last) && last.match(/(?:Data Error|CRC Failed|Checksum error)[^:\n]*:\s*([^\n;]+)/i);
    if (bad) {
      const e = new Error(`解 ${basename(arc)} 时，里面的「${bad[1].trim()}」数据校验不对，说明压缩包本身没下载完整或下载出错${g_multi(arc) ? '（某一卷坏了）' : ''}：在网盘 App 里重新下载再试${needPw ? '；也可能是解压密码不对' : ''}。`);
      e.wrongPassword = needPw; throw e;
    }
    const e = new Error(WRONG_PW.test(last) ? '解压密码都不对，请在上面填正确的密码再试'
      : miss ? `缺少分卷：${miss[1].trim()}（分卷没下全，或者分卷的文件名对不上——所有分卷要放在同一个文件夹里）`
      : ('解压失败：' + (brief || last.trim().split('\n').slice(-2).join(' ')).slice(0, 200)));
    e.wrongPassword = WRONG_PW.test(last);
    throw e;
  }

  // 找解出来的压缩包：只有后缀像压缩包、或者是「大文件 + 可疑后缀」的才读文件头（游戏里几千个普通文件不去一个个读，否则慢系统上会卡很久）
  const NORMAL_EXT = /\.(png|ogg|m4a|mp3|wav|webm|mp4|js|json|txt|html?|css|dll|rpgmvp|rpgmvo|rpgmvm|png_|ogg_|ttf|otf|woff2?|rvdata2?|rxdata|xp3|ks|tjs|rpy|rpyc|rpa|py|pyc|so|dat|bin|pak|ini|cfg|xml|csv|svg|ico|bmp|tga|dds|psd|fnt|wasm|map|md|lua|pck|assets|resource|resS|bundle)$/i;
  function collectArchives(dir, depth, budget = { n: 400 }) {
    const out = [];
    for (const e of ls(dir) || []) {
      if (--budget.n < 0) break;
      const p = join(dir, e.n);
      if (e.d) { if (depth > 0) out.push(...collectArchives(p, depth - 1, budget)); }
      else if (e.s > 1024 && (REAL_ARC.test(e.n) || (e.s > 1048576 && !NORMAL_EXT.test(e.n) && sniff(p)))) out.push({ path: p, name: e.n, dir, size: e.s });
    }
    return out;
  }
  function hasGame(dir) { const d = detectGame(dir); return !!d && (d.engine !== 'unknown' || (ls(d.root) || []).some(x => !x.d && /\.exe$/i.test(x.n))); }

  // 删广告垃圾
  function removeJunk(dir, log, depth = 0) {
    if (depth > 6) return;
    for (const e of ls(dir) || []) {
      const p = join(dir, e.n);
      if (JUNK_NAME.test(e.n) || (!e.d && /\.url$/i.test(e.n))) {
        if (e.d ? N.deleteTree(p) >= 0 : N.deleteFile(p)) log('删除广告：' + e.n);
      } else if (e.d) removeJunk(p, log, depth + 1);
    }
  }
  // 只有一个子文件夹（旁边至多几个小文档）就把里面的内容上提，最多 6 层
  function flatten(dir, log) {
    let bestName = null;
    for (let i = 0; i < 6; i++) {
      const kids = ls(dir) || [];
      const dirs = kids.filter(k => k.d), files = kids.filter(k => !k.d);
      if (dirs.length !== 1 || !files.every(f => SMALL_DOC.test(f.n) && f.s < 3 * 1048576)) break;
      const inner = dirs[0].n;
      if (/^(data|www|js|img|audio|save|savedata|存档|game|system|movie|bgm|se|fonts?|plugins?|lib|resources|locales)$/i.test(inner)) break;   // 游戏自己的子文件夹，不是套娃
      if (!bestName && !MEANINGLESS.test(inner)) bestName = inner;   // 最外面那层有意义的名字通常就是游戏名
      const tmp = join(dir, '__tmp_' + Math.random().toString(16).slice(2, 10));
      if (!N.rename(join(dir, inner), tmp)) break;
      for (const k of ls(tmp) || []) { const dst = join(dir, k.n); N.rename(join(tmp, k.n), unique(dst)); }
      N.deleteTree(tmp);
      log('去掉一层文件夹：' + inner);
    }
    return bestName;
  }
  // 整理好的文件夹 → 游戏库（名字：小站 > 有意义的内层文件夹名 > 压缩包名）
  function organizeInto(dir, want, fallback, log) {
    removeJunk(dir, log);
    const best = flatten(dir, log);
    const name = safeName(want) || (best && safeName(prettyName(best))) || safeName(fallback) || 'game';
    N.mkdirs(defaultRoot());
    const dest = unique(join(defaultRoot(), name));
    if (!N.rename(dir, dest)) throw new Error('移入游戏库失败：' + shown(dest));
    log('放进游戏库：' + name);
    return dest;
  }

  function startExtract(g) {
    if (!g.entry) g.entry = g.files[0];
    fixNames(g);
    const pw = Site.passwordsOf(g.res);
    const others = Site.recentPending(6).filter(p => p !== g.res).flatMap(p => Site.passwordsOf(p));
    const all = [...new Set(pw.concat(others))];
    const inApp = canInApp() || plainZip(g);
    const z = zarchiver();
    const title = g.res ? Site.splitName(g.res.title).chinese : '';
    const delOn = store.get('exDelete', false);
    const s = openSheet(`<div class="body">
      <h3>解压 ${esc(g.entry.fixed)}</h3>
      ${g.disguised ? '<div class="hint">✓ 已把伪装的后缀改回来了</div>' : ''}${g.hidden ? `<div class="hint">✓ 已从「${esc(g.hidden.name)}」里取出藏着的压缩包</div>` : ''}
      ${inApp ? `<div class="hint">一键：自动试密码 → 解到底（压缩包套压缩包也行）→ 删广告 → 去掉多余的文件夹层 → ${title ? '用小站名字「' + esc(title) + '」命名' : '改成游戏名'} → 放进游戏库。</div>` : ''}
      <div class="sec">解压密码 ${all.length ? '（会自动依次试，点一下复制）' : ''}</div>
      ${all.length ? `<div class="add-row">${all.map(x => `<button class="btn small" data-copy="${esc(x)}">${esc(x)}</button>`).join('')}</div>` : '<div class="hint">小站资源里没记录密码（多数不需要）。</div>'}
      <div class="add-row"><input id="exPwIn" placeholder="自己填密码（可选，最先试）"></div>
      ${inApp ? `<label class="hint" style="display:flex;gap:8px;align-items:center"><input type="checkbox" id="exDel" ${delOn ? 'checked' : ''} style="width:auto">解压成功后删除压缩包（省空间）</label>` : ''}
      <div id="exRun"></div>
      <div class="actions">
        ${inApp ? '<button class="btn primary wide" data-a="inapp">一键解压到游戏库</button>' : ''}
        <button class="btn wide" data-a="${z ? 'open' : 'nozip'}">${z ? '用 ZArchiver 手动解压' : '用 ZArchiver 手动解压（先安装）'}</button>
        <button class="btn wide" data-a="other">用别的 App 打开</button>
      </div></div>`);
    s.onclick = e => {
      const c = e.target.closest('[data-copy]');
      if (c) { toast(N.copyText(c.dataset.copy) ? '已复制：' + c.dataset.copy : '复制失败'); return; }
      const b = e.target.closest('button');
      if (!b) return;
      if (b.dataset.a === 'cancel') { cancelled = true; b.disabled = true; b.textContent = '正在取消…'; return; }
      if (b.dataset.a === 'nozip') { closeSheet(); App.tab('emu'); return; }
      if (b.dataset.a === 'inapp') {
        const mine = s.querySelector('#exPwIn').value.trim();
        const del = s.querySelector('#exDel') && s.querySelector('#exDel').checked;
        store.set('exDelete', !!del);
        runAll(g, [mine].concat(all), title, del, s);
        return;
      }
      store.set('exSession', { start: Date.now(), dirs: [g.dir], res: g.res ? g.res.id : null });
      if (pw.length) N.copyText(pw[0]);
      const err = N.openWith(g.entry.path, mimeOf(g.entry.fixed), b.dataset.a === 'open' && z ? z.p : null);
      if (err) {
        if (z && N.launchApp(z.p)) { N.copyText(g.entry.path); toast('已打开 ZArchiver，压缩包路径已复制', 4000); }
        else toast('打不开：' + err, 4000);
      } else toast(pw.length ? '密码已复制：' + pw[0] : '已打开');
      closeSheet(); render();
    };
  }

  // 伪装成视频/图片的压缩包：7-Zip 的 # 解析器能找出文件里接着的 7z / rar / zip，先把它取出来再走一键解压
  async function checkHidden(f) {
    if (!canInApp()) { toast('这个版本没有内置 7-Zip'); return; }
    cancelled = false;
    const s = openSheet(`<div class="body"><h3>检查 ${esc(f.name)}</h3>
      <div class="sec" id="hdStage">正在找里面藏着的压缩包…</div><div class="bar"><i style="width:0"></i></div>
      <div class="actions"><button class="btn wide" data-a="cancel">取消</button></div></div>`);
    const stage = s.querySelector('#hdStage'), fill = s.querySelector('.bar i');
    s.querySelector('[data-a=cancel]').onclick = () => { cancelled = true; };
    for (const e of ls(tmpRoot()) || []) if (e.d && /^carve\d+$/.test(e.n)) rmTree(join(tmpRoot(), e.n));   // 以前取出来没用完的
    const lst = await run7(['l', '-t#', '-slt', '-sccUTF-8', '-p' + NO_PW, f.path], null, ms => { stage.textContent = `正在找里面藏着的压缩包…　已用 ${Math.floor(ms / 1000)} 秒`; });
    if (lst.code === -4) { closeSheet(); return; }
    const items = lst.out.split(/\n\s*\n/).map(b => ({
      path: (b.match(/^Path = (.+)$/m) || [])[1], type: (b.match(/^Type = (.*)$/m) || [])[1] || '', size: +((b.match(/^Size = (\d+)$/m) || [])[1] || 0),
    })).filter(x => x.path && /^(7z|rar5?|zip)$/i.test(x.type)).sort((a, b) => b.size - a.size);
    if (!items.length) { stage.textContent = `没在「${f.name}」里找到压缩包，它可能就是普通的视频 / 图片。`; s.querySelector('.actions').innerHTML = ''; return; }
    const it = items[0], ext = /^rar/i.test(it.type) ? 'rar' : it.type.toLowerCase();
    const carveDir = join(tmpRoot(), 'carve' + Date.now());
    stage.textContent = `找到藏着的 ${it.type} 压缩包（${fmtSize(it.size)}），正在取出来…`;
    const res = await run7(['x', '-t#', '-y', '-bsp1', '-sccUTF-8', '-p' + NO_PW, '-o' + carveDir, f.path, it.path], p => { fill.style.width = p + '%'; });
    const got = join(carveDir, it.path);
    if (res.code === -4 || !N.exists(got)) { rmTree(carveDir); if (res.code !== -4) { stage.textContent = '取出来失败：' + res.out.trim().split('\n').slice(-2).join(' '); s.querySelector('.actions').innerHTML = ''; } else closeSheet(); return; }
    const stem = f.name.replace(/\.[^.]+$/, ''), name = stem + '.' + ext, carved = join(carveDir, name);
    N.rename(got, carved);
    const key = groupKey(name);
    closeSheet();
    startExtract({ dir: carveDir, key, files: [{ dir: carveDir, name, path: carved, fixed: name, size: it.size, m: f.m }], entry: null,
      size: it.size, m: f.m, disguised: false, dupName: false, badVols: [], res: Site.match(key) || Site.match(stem), hidden: f, carveDir });
  }

  let running = false;
  async function runAll(g, passwords, title, del, sheet) {
    if (running) return;
    running = true; cancelled = false;
    const box = sheet.querySelector('#exRun'), btns = sheet.querySelectorAll('.actions button');
    btns.forEach(x => { x.style.display = 'none'; });
    box.innerHTML = `<div class="sec" id="exStage" style="white-space:pre-line">准备中…</div><div class="bar"><i style="width:0"></i></div>
      <div class="ex-log" id="exLog"></div><div class="actions"><button class="btn wide" data-a="cancel">取消</button></div>`;
    const fill = box.querySelector('.bar i'), stage = box.querySelector('#exStage'), logBox = box.querySelector('#exLog');
    const log = m => { logBox.insertAdjacentHTML('beforeend', `<div>${esc(m)}</div>`); logBox.scrollTop = 1e9; };
    // 上次解到一半被强行关掉的临时文件夹（可能很大）先清掉
    for (const e of ls(tmpRoot()) || []) if (e.d && /^job\d+$/.test(e.n)) rmTree(join(tmpRoot(), e.n));   // 后台慢慢删，不用等
    const job = join(tmpRoot(), 'job' + Date.now());
    let pwList = [...new Set(passwords.filter(Boolean))], pwUsed = '';
    try {
      if (g.disguised) log('已改回真后缀：' + g.entry.fixed);
      let current = [{ path: g.entry.path, name: g.entry.fixed }], layer = 0, result = null;
      while (current.length && layer < 6) {
        layer++;
        const layerDir = join(job, 'layer' + layer);
        N.mkdirs(layerDir);
        for (let i = 0; i < current.length; i++) {
          const a = current[i];
          stage.textContent = `第 ${layer} 层：${a.name}${current.length > 1 ? `（${i + 1}/${current.length}）` : ''}`;
          fill.style.width = '0';
          const tmpOut = join(layerDir, '_part' + i);
          const label = stage.textContent;
          const res = await extractOne(a.path, tmpOut, pwUsed ? [pwUsed, ...pwList] : pwList, p => { fill.style.width = p + '%'; }, log,
            ms => { stage.textContent = `${label}　已用 ${Math.floor(ms / 60000)}:${String(Math.floor(ms / 1000) % 60).padStart(2, '0')}`; });
          if (res.password && res.password !== pwUsed) { pwUsed = res.password; log(`第 ${layer} 层密码：${res.password}`); }
          for (const k of ls(tmpOut) || []) N.rename(join(tmpOut, k.n), unique(join(layerDir, k.n)));
          await rmTree(tmpOut);
        }
        result = layerDir;
        // 上一层（里面只是这一层的压缩包）已经用完了，马上删掉省空间，不用等全部解完
        if (layer > 1) { rmTree(join(job, 'layer' + (layer - 1))); log(`删除第 ${layer - 1} 层的临时文件`); }   // 后台删
        log(`第 ${layer} 层解完`);
        stage.textContent = `第 ${layer} 层解完，检查里面的文件…`;
        await tick();
        if (hasGame(layerDir)) break;   // 已经是游戏本体了，不用再找压缩包
        const inner = collectArchives(layerDir, 2);
        if (!inner.length) break;
        // 解出来的还是压缩包 → 改后缀、找第一卷，接着解下一层
        const fixed = inner.map(f => {
          const fx = fixedName(f.name, f.path, f.size);
          if (fx && fx !== f.name) { const t = unique(join(f.dir, fx)); if (N.rename(f.path, t)) return { path: t, name: basename(t) }; }
          return { path: f.path, name: f.name };
        });
        let next = fixed.filter(f => isVolumeEntry(f.name));
        if (!next.length) next = fixed.filter(f => !/\.\d{3}$|\.(z|r)\d{2}$/i.test(f.name));
        current = next;
        log(`里面还是压缩包，接着解第 ${layer + 1} 层`);
      }
      stage.textContent = '整理文件夹…'; fill.style.width = '100%';
      await tick();
      // 中间层解出来的压缩包不留
      if (layer > 1 && !hasGame(result)) for (const f of collectArchives(result, 0)) N.deleteFile(f.path);
      const dest = organizeInto(result, title, groupKey(g.entry.fixed), log);
      rmTree(job);
      if (g.res && g.res.cover && !N.exists(join(dest, 'cover.jpg'))) N.download(g.res.cover, join(dest, 'cover.jpg'));
      if (g.res) Site.markDone(g.res);
      if (del) { const n = g.files.filter(f => N.deleteFile(f.path)).length + (g.hidden && N.deleteFile(g.hidden.path) ? 1 : 0); log(`已删除压缩包 ${n} 个`); }
      if (g.carveDir) rmTree(g.carveDir);
      const det = detectGame(dest);
      stage.textContent = '✓ 完成：' + basename(dest);
      box.querySelector('.actions').innerHTML = `<div class="hint">${esc(det ? (ENGINES[det.engine] || ENGINES.unknown).name : '')}　${esc(shown(dest))}</div>
        <button class="btn primary wide" data-a="done">去游戏库</button>`;
      box.querySelector('[data-a=done]').onclick = () => { closeSheet(); App.tab('lib'); };
      await Library.scan();
      scan();
    } catch (err) {
      rmTree(job);
      stage.textContent = '✗ ' + (err.message || err) + (g.badVols && g.badVols.length ? `\n（检查发现 ${g.badVols.join('、')} 的大小和其它分卷不一样，重新下载这一卷就行）` : '');
      box.querySelector('.actions').innerHTML = '';
      btns.forEach(x => { x.style.display = ''; });
      if (err.wrongPassword) { const i = sheet.querySelector('#exPwIn'); if (i) { i.focus(); i.style.borderColor = '#f472b6'; } }
    } finally { running = false; }
  }

  const plainZip = g => g.files.length === 1 && /\.zip$/i.test(g.entry.fixed);

  // ---------- 找解压出来的游戏 → 入库 ----------
  const SYS_DIRS = /^(android|dcim|pictures|music|movies|alarms|notifications|podcasts|ringtones|recordings|audiobooks|nagisaland|download|documents|easyrpg|emuera|ons|winlator游戏|\..*)$/i;
  async function findGames() {
    $('#exStatus').textContent = '正在找解压出来的游戏…';
    await tick();
    const s = session();
    const since = s ? s.start - 5 * 60000 : Date.now() - 24 * 3600000;
    const dirs = [...new Set(dlDirs().concat(s ? s.dirs : []).concat([ROOT]))];
    const cands = [];
    for (const d of dirs) {
      for (const e of ls(d) || []) {
        if (!e.d || SYS_DIRS.test(e.n) || (e.m || 0) < since) continue;
        const p = join(d, e.n);
        if (p === defaultRoot() || cands.some(c => c.path === p)) continue;
        if (dirs.includes(p) || dirs.some(x => x.startsWith(p + '/')) || defaultRoot().startsWith(p + '/')) continue;   // 下载目录本身不能当游戏
        const det = detectGame(p);
        if (!det) continue;
        const hasGame = det.engine !== 'unknown' || (ls(det.root) || []).some(x => !x.d && /\.exe$/i.test(x.n));
        if (hasGame) cands.push({ path: p, name: e.n, m: e.m, det, res: Site.match(e.n) || (s && s.res ? Site.pending().find(x => x.id === s.res) : null) });
      }
    }
    $('#exStatus').textContent = '';
    const s2 = openSheet(`<div class="body"><h3>解压出来的游戏</h3>
      ${cands.length ? cands.map((c, i) => {
        const name = c.res ? Site.splitName(c.res.title).chinese : ((MEANINGLESS.test(c.name) && innerName(c.path, c.det.root)) || c.name);
        return `<div class="ex-card"><div class="ex-name">${esc(c.name)}</div>
          <div class="line"><span class="tag ${ENGINES[c.det.engine] && ENGINES[c.det.engine].play ? 'ok' : 'warn'}">${esc((ENGINES[c.det.engine] || ENGINES.unknown).name)}</span><span>${ago(c.m)}</span></div>
          <div class="hint">${esc(shown(c.path))}</div>
          ${c.res ? `<div class="hint">小站：${esc(c.res.cleanTitle || c.res.title)}（入库时会下载封面）</div>` : ''}
          <div class="add-row"><input data-name="${i}" value="${esc(name)}"><button class="btn small primary" data-move="${i}">移入游戏库</button></div></div>`;
      }).join('') : '<div class="center">没找到新解压出来的游戏文件夹。<br><span class="hint">确认已经解压完成；如果解压到了别的位置，可以在游戏库设置里把那个目录加进去。</span></div>'}
    </div>`, 'tall');
    s2.onclick = async e => {
      const b = e.target.closest('[data-move]');
      if (!b) return;
      const c = cands[+b.dataset.move];
      const name = (s2.querySelector(`[data-name="${b.dataset.move}"]`).value || c.name).replace(/[\\/:*?"<>|]/g, '_').trim().slice(0, 80) || c.name;
      N.mkdirs(defaultRoot());
      let dest = join(defaultRoot(), name), n = 2;
      while (N.exists(dest)) dest = join(defaultRoot(), `${name} (${n++})`);
      b.disabled = true; b.textContent = '移动中…';
      await tick();
      if (!N.rename(c.path, dest)) { b.disabled = false; b.textContent = '移入游戏库'; toast('移动失败：可能不在同一个存储里（比如 SD 卡），可以在游戏库设置里直接添加它所在的目录', 5000); return; }
      removeJunk(dest, () => {}); flatten(dest, () => {});
      if (c.res && c.res.cover) { const err = N.download(c.res.cover, join(dest, 'cover.jpg')); if (err) console.warn('封面下载失败', err); }
      if (c.res) Site.markDone(c.res);
      b.textContent = '✓ 已入库';
      toast('已移入游戏库：' + name);
      Library.scan();
    };
  }

  // 扫描哪些下载目录：内置的常见网盘目录 + 自己加的（用文件夹选择器选，不用输路径）
  function openDirs() {
    const builtin = DL_DIRS.map(x => join(ROOT, x)).filter(d => N.exists(d));
    const extra = extraDirs();
    const s = openSheet(`<div class="body">
      <h3>扫描的下载目录</h3>
      <div class="hint">解压页会在这些文件夹（和它们的下一层）里找压缩包。</div>
      ${builtin.map(d => `<div class="root-row"><span class="path">${esc(shown(d))}</span><span class="sub">自动</span></div>`).join('')}
      ${extra.map((d, i) => `<div class="root-row"><span class="path">${esc(shown(d))}</span><button class="btn small" data-rm="${i}">移除</button></div>`).join('')}
      <div class="add-row"><button class="btn small primary" data-pick="1">＋ 选择文件夹…</button></div>
    </div>`, 'tall');
    s.onclick = async e => {
      const b = e.target.closest('button');
      if (!b) return;
      if (b.dataset.rm != null) { const x = extraDirs(); x.splice(+b.dataset.rm, 1); store.set('dlDirs', x); openDirs(); scan(); }
      else if (b.dataset.pick) {
        const p = await pickFolder({ title: '选择下载目录', hint: '比如网盘 App 的下载文件夹', start: join(ROOT, 'Download') });
        if (p) {
          if (dlDirs().includes(p)) toast('已经在扫描这个文件夹了');
          else { store.set('dlDirs', extraDirs().concat([p])); toast('已添加：' + shown(p)); scan(); }
        }
        openDirs();
      }
    };
  }

  function init() {
    $('#exRefresh').onclick = () => scan();
    $('#exFind').onclick = () => findGames();
    $('#tabExtract').addEventListener('click', e => {
      const c = e.target.closest('[data-copy]');
      if (c) { toast(N.copyText(c.dataset.copy) ? '已复制：' + c.dataset.copy : '复制失败'); return; }
      const b = e.target.closest('button');
      if (!b) return;
      if (b.dataset.go != null) startExtract(groups[+b.dataset.go]);
      else if (b.dataset.del != null) {
        const g = groups[+b.dataset.del];
        if (!confirm(`删除这 ${g.files.length} 个文件？\n${g.files.map(f => f.name).join('\n')}`)) return;
        const ok = g.files.filter(f => N.deleteFile(f.path)).length;
        toast(`已删除 ${ok} 个文件`); scan();
      } else if (b.dataset.zinstall) { const zb = bundledFor('zarchiver'); if (zb) installBundled(zb); }
      else if (b.dataset.zget) N.openUrl(APPS.zarchiver.url);
      else if (b.dataset.a === 'addDl') openDirs();
      else if (b.dataset.maybe != null) checkHidden(maybe[+b.dataset.maybe]);
    });
  }
  function show() { refreshApps(); scan(); }
  // 游戏库里已有的游戏：整理并改名 → { dest, log }
  async function tidy(dir, root) {
    const log = [];
    const inner = innerName(dir, root);
    removeJunk(dir, m => log.push(m));
    const best = flatten(dir, m => log.push(m));
    const base = basename(dir);
    let want = '', res = null;
    if (Site.loggedIn()) {
      for (const q of [...new Set([inner, best && prettyName(best), MEANINGLESS.test(base) ? '' : base].filter(Boolean))]) {
        try { res = await Site.findForFolder(q); } catch (e) { res = null; }
        if (res) { want = Site.splitName(res.title).chinese; break; }
      }
    }
    const name = safeName(want) || (MEANINGLESS.test(base) ? safeName(inner || (best && prettyName(best)) || '') : '');
    let dest = dir;
    if (name && name !== base) {
      const t = unique(join(dirname(dir), name));
      if (N.rename(dir, t)) { dest = t; log.push('改名：' + base + ' → ' + basename(t)); }
    }
    if (res && res.cover && !N.exists(join(dest, 'cover.jpg'))) N.download(res.cover, join(dest, 'cover.jpg'));
    return { dest, log };
  }
  return { init, show, scan, tidy };
})();
