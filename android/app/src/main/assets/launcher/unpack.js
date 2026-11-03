// Nagisaland 手机版 · 解包打包进程序里的网页游戏
//   package.nw / 拼接在 exe 尾部的 zip / resources/app.asar → 游戏文件夹/nagisaland_www/
//   解一次以后就和普通文件夹游戏一样直接玩；电脑版存档（save/）也一起带过去
'use strict';
const Unpack = (() => {
  const OUT = 'nagisaland_www';
  const bytes = b64 => { const s = atob(b64 || ''); const u = new Uint8Array(s.length); for (let i = 0; i < s.length; i++) u[i] = s.charCodeAt(i); return u; };
  const u32 = (u, o) => (u[o] | (u[o + 1] << 8) | (u[o + 2] << 16) | (u[o + 3] << 24)) >>> 0;

  // exe 尾部是不是拼了一个 zip（NW.js 常见打包方式）：看最后 22 字节是不是 zip 目录结尾
  function exeHasZip(path) {
    const t = bytes(N.readB64(path, -22, 22));
    return t.length === 22 && t[0] === 0x50 && t[1] === 0x4b && t[2] === 0x05 && t[3] === 0x06;
  }

  // 找打包来源：{ kind: 'zip'|'asar', path }
  function findSource(dir) {
    const top = ls(dir) || [];
    const f = name => top.find(e => !e.d && e.n.toLowerCase() === name);
    const pnw = f('package.nw');
    if (pnw) return { kind: 'zip', path: join(dir, pnw.n) };
    const res = top.find(e => e.d && e.n.toLowerCase() === 'resources');
    if (res) {
      const a = (ls(join(dir, res.n)) || []).find(e => !e.d && e.n.toLowerCase() === 'app.asar');
      if (a) return { kind: 'asar', path: join(join(dir, res.n), a.n) };
    }
    const exes = top.filter(e => !e.d && /\.exe$/i.test(e.n) && e.s > 3 * 1048576).sort((a, b) => b.s - a.s);
    for (const e of exes) if (exeHasZip(join(dir, e.n))) return { kind: 'zip', path: join(dir, e.n) };
    return null;
  }

  // 网页游戏的根：包里最浅的 index.html 所在目录
  function htmlRoot(names) {
    let best = null;
    for (const n of names) {
      if (!/(^|\/)index\.html$/i.test(n)) continue;
      if (/(^|\/)node_modules\//i.test(n)) continue;
      const pre = n.slice(0, n.length - 'index.html'.length);
      if (best === null || pre.split('/').length < best.split('/').length) best = pre;
    }
    return best;
  }

  // 列出要解的文件：[{ name, size, rel }]
  function plan(src) {
    if (src.kind === 'zip') {
      let raw = N.zipList(src.path, 'UTF-8'), cs = 'UTF-8';
      if (!raw) { raw = N.zipList(src.path, 'GBK'); cs = 'GBK'; }
      if (!raw) throw new Error('读不了这个压缩包');
      const list = JSON.parse(raw).map(x => ({ name: x[0], size: x[1] }));
      const pre = htmlRoot(list.map(x => x.name));
      if (pre === null) throw new Error('包里没有 index.html，不是网页游戏');
      return { cs, files: list.filter(x => x.name.startsWith(pre)).map(x => ({ ...x, rel: x.name.slice(pre.length) })) };
    }
    // asar：[4][头部大小][4][json 长度][json] + 文件数据
    const h = bytes(N.readB64(src.path, 0, 16));
    if (h.length < 16) throw new Error('读不了 app.asar');
    const headerSize = u32(h, 4), jsonLen = u32(h, 12), base = 8 + headerSize;
    const tree = JSON.parse(new TextDecoder('utf-8').decode(bytes(N.readB64(src.path, 16, jsonLen))));
    const all = [];
    (function walk(node, p) {
      for (const [k, v] of Object.entries(node.files || {})) {
        const r = p ? p + '/' + k : k;
        if (v.files) walk(v, r);
        else if (!v.link) all.push({ name: r, size: v.size || 0, off: base + Number(v.offset || 0), unpacked: !!v.unpacked });
      }
    })(tree, '');
    const pre = htmlRoot(all.map(x => x.name));
    if (pre === null) throw new Error('包里没有 index.html，不是网页游戏');
    return { files: all.filter(x => x.name.startsWith(pre)).map(x => ({ ...x, rel: x.name.slice(pre.length) })) };
  }

  // 电脑版存档：exe 旁边的 save/（MV 也可能在 www/save）
  function pcSaveDirs(dir) {
    return [join(dir, 'save'), join(join(dir, 'www'), 'save')].filter(d => (ls(d) || []).some(e => /\.(rmmzsave|rpgsave)$/i.test(e.n)));
  }

  function info(g) {
    const src = findSource(g.dir);
    if (!src) return null;
    try { const p = plan(src); return { src, count: p.files.length, bytes: p.files.reduce((a, f) => a + f.size, 0) }; }
    catch (e) { return { src, error: e.message }; }
  }

  // 解包；onProgress(done, total, text)
  async function run(g, onProgress) {
    const src = findSource(g.dir);
    if (!src) throw new Error('没找到打包的游戏文件');
    const p = plan(src);
    const dst = join(g.dir, OUT), part = dst + '.part';
    if (N.exists(dst)) throw new Error('已经解包过了（' + OUT + ' 文件夹已存在）');
    const total = p.files.reduce((a, f) => a + f.size, 0) || 1;
    let done = 0, since = 0, t = Date.now();
    for (let i = 0; i < p.files.length; i++) {
      const f = p.files[i], out = join(part, f.rel);
      const ok = src.kind === 'zip' ? N.zipExtract(src.path, p.cs, f.name, out)
        : f.unpacked ? N.copyRange(src.path + '.unpacked/' + f.name, 0, f.size, out)
        : N.copyRange(src.path, f.off, f.size, out);
      if (!ok) throw new Error('解压失败：' + f.name + '（手机空间够吗？）');
      done += f.size; since += f.size;
      if (since > 8 * 1048576 || i % 25 === 0 || Date.now() - t > 300) {
        since = 0; t = Date.now();
        onProgress && onProgress(done, total, `${i + 1} / ${p.files.length} 个文件`);
        await tick(0);
      }
    }
    // 电脑版存档一起带过去（手机上第一次开游戏时自动导入）
    for (const sd of pcSaveDirs(g.dir)) {
      for (const e of ls(sd) || []) {
        if (e.d || !/\.(rmmzsave|rpgsave)$/i.test(e.n)) continue;
        N.copyRange(join(sd, e.n), 0, e.s, join(join(part, 'save'), e.n));
      }
    }
    if (!N.rename(part, dst)) throw new Error('解包完成，但改名失败：' + part);
    return { files: p.files.length, bytes: total };
  }

  return { info, run, findSource, exeHasZip, OUT };
})();
