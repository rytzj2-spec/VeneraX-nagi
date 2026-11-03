// Nagisaland 手机版 · 模拟器（内置的一键安装，其它的给下载入口）
'use strict';
const Emulators = (() => {
  const USE = {
    kirikiroid: '吉里吉里 (krkr) 游戏', joiplay: "Ren'Py、RPG Maker XP/VX/VA（需要装对应插件）", easyrpg: 'RPG Maker 2000/2003',
    onscripter: 'NScripter / ONS 游戏', ppsspp: 'PSP 游戏', winlator: 'Windows exe 游戏（Unity、BGI、Siglus 等）',
    emuera: 'era 系列（Emuera）', qsp: 'QSP 游戏', mkxp: 'RPG Maker XP/VX/VA', zarchiver: '解压压缩包（解压页会用到）',
  };

  function render() {
    refreshApps();
    const keys = Object.keys(APPS);
    // 打包进来但不在已知列表里的（比如 JoiPlay 的插件）
    const extra = BUNDLED.filter(b => !b.key || !APPS[b.key]);
    const row = (name, use, inst, b, getUrl, key) => `<div class="app-row">
      <div class="n">${esc(name)}<small>${esc(use || '')}</small>
        <small>${inst ? '✓ 已安装' + (inst.l && inst.l !== name ? ' · ' + esc(inst.l) : '') : b ? '内置 ' + esc(b.version || '') + ' · ' + fmtSize(b.size) : '未安装'}</small></div>
      ${inst ? `<button class="btn small" data-open="${esc(inst.p)}">打开</button>` : ''}
      ${b && !inst ? `<button class="btn small primary" data-install="${esc(b.file)}">安装</button>` : ''}
      ${b && inst ? `<button class="btn small" data-install="${esc(b.file)}" title="重新安装/更新">重装</button>` : ''}
      ${!b && !inst && getUrl ? `<button class="btn small" data-url="${esc(getUrl)}">获取</button>` : ''}
    </div>`;
    $('#emuList').innerHTML =
      keys.map(k => row(APPS[k].name, USE[k], findApp(k), bundledFor(k), APPS[k].url, k)).join('') +
      extra.map(b => row(b.label || b.file, '内置安装包', installedApps().find(x => x.p === b.pkg), b, null)).join('');
    const n = BUNDLED.length;
    $('#emuNote').innerHTML = n
      ? `这个安装包里内置了 ${n} 个模拟器，点「安装」直接装，不用联网。第一次安装时系统会让你允许 Nagisaland「安装未知应用」。`
      : '这个安装包没有内置模拟器，点「获取」去下载。';
  }

  function init() {
    $('#emuList').onclick = e => {
      const b = e.target.closest('button');
      if (!b) return;
      if (b.dataset.open) N.launchApp(b.dataset.open);
      else if (b.dataset.install) { const x = BUNDLED.find(y => y.file === b.dataset.install); if (x) installBundled(x); }
      else if (b.dataset.url) N.openUrl(b.dataset.url);
    };
  }
  function show() { bundledReady.then(render); }
  return { init, show, render };
})();
