// Nagisaland 手机版 · AI 翻译的服务设置（游戏里的「翻译」页和识屏翻译都用这里的设置）
// 设置存在 内部存储/Nagisaland/translate.json（游戏页面在另一个域名下，只能通过文件共享）
'use strict';
const TrSettings = (() => {
  const DEF = { provider: 'free', base: 'https://api.deepseek.com', key: '', model: 'deepseek-flash', english: true, profiles: {},
    visionBase: 'https://dashscope.aliyuncs.com/compatible-mode/v1', visionKey: '', visionModel: 'qwen3-vl-flash', conc: 16 };
  const PRESETS = [['百炼 qwen-flash（快）', 'https://dashscope.aliyuncs.com/compatible-mode/v1', 'qwen-flash'], ['DeepSeek（成人向用这个）', 'https://api.deepseek.com', 'deepseek-flash'], ['百炼 qwen-plus（更好）', 'https://dashscope.aliyuncs.com/compatible-mode/v1', 'qwen-plus'],
    ['Kimi', 'https://api.moonshot.cn/v1', 'moonshot-v1-8k'], ['OpenAI', 'https://api.openai.com/v1', 'gpt-4o-mini']];
  const path = () => join(ROOT, 'Nagisaland/translate.json');
  // 接口地址整理：粘贴进来多余的字去掉；阿里云百炼的原生地址（/api/v1、专属域名）换成 OpenAI 兼容地址 /compatible-mode/v1
  function normBase(v) {
    let u = String(v || '').trim();
    const m = u.match(/https?:\/\/[^\s"'，。]+/);
    if (m) u = m[0];
    else if (/^[\w.-]+\.[a-z]{2,}(\/|$)/i.test(u)) u = 'https://' + u.split(/\s/)[0];
    u = u.replace(/\/+$/, '').replace(/\/chat\/completions$/, '');
    if (/aliyuncs\.com/i.test(u)) {
      const h = u.match(/^https?:\/\/[^/]+/)[0];
      if (!/\/compatible-mode\/v1$/.test(u)) u = h + '/compatible-mode/v1';
    }
    return u;
  }
  // 按接口地址认出是哪家：每家的 Key 分开记，切换预设时自动换成那家的 Key
  const PROV = { bailian: '阿里云百炼', deepseek: 'DeepSeek', kimi: 'Kimi', openai: 'OpenAI', custom: '自定义接口' };
  function prov(base) { const u = String(base || ''); return /aliyuncs\.com/i.test(u) ? 'bailian' : /deepseek\.com/i.test(u) ? 'deepseek' : /moonshot\.cn/i.test(u) ? 'kimi' : /openai\.com/i.test(u) ? 'openai' : 'custom'; }
  function chatBody(c, messages) {
    const o = { model: c.model, messages };
    if (prov(c.base) === 'deepseek') { if (/^deepseek-(chat|v4-flash)$/.test(o.model)) o.model = 'deepseek-flash'; o.thinking = { type: 'disabled' }; }   // DeepSeek 新模型默认会先"思考"，翻译用不着，关掉快很多
    else o.temperature = 0.3;
    return JSON.stringify(o);
  }
  function load() {
    const c = readJson(path()) || {}; const o = {}; for (const k in DEF) o[k] = c[k] != null ? c[k] : DEF[k];
    // 旧版本只有一个 Key：第一次打开时按情况分到各家
    // 识屏填的是百炼 → 那个就是百炼的 Key；文字翻译的 Key 和它不一样，又停在百炼上 → 多半是 DeepSeek 官方的 Key（以前只有一个框，切预设时没换）
    if (!o.profiles || typeof o.profiles !== 'object' || !Object.keys(o.profiles).length) {
      const pf = {};
      if (o.visionKey && prov(o.visionBase) === 'bailian') pf.bailian = { base: o.visionBase, key: o.visionKey, model: 'qwen-flash' };
      if (o.key) {
        if (prov(o.base) === 'bailian' && pf.bailian && o.key !== pf.bailian.key) {
          pf.deepseek = { base: 'https://api.deepseek.com', key: o.key, model: 'deepseek-flash' };
          o.base = pf.deepseek.base; o.key = pf.deepseek.key; o.model = pf.deepseek.model;
        } else pf[prov(o.base)] = { base: o.base, key: o.key, model: o.model };
      }
      o.profiles = pf;
    }
    return o;
  }
  function b64(text) { const u = new TextEncoder().encode(text); let s = ''; for (let i = 0; i < u.length; i += 0x8000) s += String.fromCharCode.apply(null, u.subarray(i, i + 0x8000)); return btoa(s); }
  function save(c) { N.mkdirs(join(ROOT, 'Nagisaland')); return N.writeB64(path(), b64(JSON.stringify(c, null, 1))); }

  let seq = 0;
  async function http(method, url, headers, body) {
    if (!N.httpStart) return JSON.parse(N.http(method, url, JSON.stringify(headers), body));
    const id = 'ts' + (++seq) + '_' + Date.now(), t0 = Date.now();
    N.httpStart(id, method, url, JSON.stringify(headers), body);
    for (;;) { await tick(80); const r = N.httpResult(id); if (r != null) return JSON.parse(r); if (Date.now() - t0 > 60000) return { status: 0, error: '超时' }; }
  }
  const fail = r => new Error(r.status ? `HTTP ${r.status} ${String(r.body || '').slice(0, 120)}` : `连不上（${r.error || '网络错误'}）`);
  async function testText(c) {
    const src = 'こんにちは、勇者さま。今日はいい天気ですね！';
    if (c.provider === 'llm') {
      if (!c.key) throw new Error('还没填 API Key');
      const r = await http('POST', c.base.replace(/\/+$/, '') + '/chat/completions', { Authorization: 'Bearer ' + c.key, 'Content-Type': 'application/json' },
        chatBody(c, [{ role: 'system', content: '把用户给的日文翻译成简体中文，只输出译文。' }, { role: 'user', content: src }]));
      if (r.status !== 200) throw fail(r);
      return JSON.parse(r.body).choices[0].message.content.trim();
    }
    const a = await http('GET', 'https://edge.microsoft.com/translate/auth', { 'User-Agent': 'Mozilla/5.0' });
    if (a.status !== 200) throw fail(a);
    const r = await http('POST', 'https://api-edge.cognitive.microsofttranslator.com/translate?api-version=3.0&to=zh-Hans',
      { Authorization: 'Bearer ' + String(a.body).trim(), 'Content-Type': 'application/json' }, JSON.stringify([{ Text: src }]));
    if (r.status !== 200) throw fail(r);
    return JSON.parse(r.body)[0].translations[0].text;
  }
  async function testVision(c) {
    if (!c.visionKey) throw new Error('还没填识屏用的 API Key');
    // 画一张带日文的小图（太小的图百炼会报 image length and width do not meet the model restriction）
    const cv = document.createElement('canvas'); cv.width = 360; cv.height = 96;
    const g = cv.getContext('2d'); g.fillStyle = '#fff'; g.fillRect(0, 0, 360, 96); g.fillStyle = '#000'; g.font = 'bold 40px sans-serif'; g.fillText('こんにちは', 40, 62);
    const img = cv.toDataURL('image/jpeg', 0.9);
    const r = await http('POST', c.visionBase.replace(/\/+$/, '') + '/chat/completions', { Authorization: 'Bearer ' + c.visionKey, 'Content-Type': 'application/json' },
      JSON.stringify({ model: c.visionModel, messages: [{ role: 'user', content: [{ type: 'image_url', image_url: { url: img } }, { type: 'text', text: '识别图里的日文并翻译成简体中文，只输出「原文 → 译文」。' }] }] }));
    if (r.status !== 200) throw fail(r);
    return JSON.parse(r.body).choices[0].message.content.trim();
  }

  // 密钥输入框：用普通文本框 + 圆点遮挡（type=password 会弹安全键盘、不能粘贴），旁边给「粘贴」「显示」
  const keyField = (id, v) => `<div class="add-row"><input id="${id}" class="secret" value="${esc(v)}" autocomplete="off" autocapitalize="off" spellcheck="false" style="flex:1;min-width:0">
    <button class="btn small" data-paste="${id}">粘贴</button><button class="btn small" data-eye="${id}">显示</button></div>`;
  const field = (id, v, ph) => `<div class="add-row"><input id="${id}" value="${esc(v)}" placeholder="${esc(ph || '')}" autocomplete="off" autocapitalize="off" spellcheck="false" style="flex:1;min-width:0">
    <button class="btn small" data-paste="${id}">粘贴</button></div>`;

  const modelField = (id, v, kind) => `<div class="add-row"><input id="${id}" value="${esc(v)}" autocomplete="off" autocapitalize="off" spellcheck="false" style="flex:1;min-width:0">
    <button class="btn small" data-models="${kind}">查询可用模型</button></div><div id="${id}List" hidden></div>`;

  // 查询对方能调用的模型（OpenAI 兼容接口的 GET /models，和 Chatbox 的「获取模型」一样）
  const NOT_CHAT = /embed|rerank|tts|asr|paraformer|sambert|cosyvoice|wanx|wan2|flux|stable-diffusion|image-|-image|video|audio|realtime|speech|whisper|dall-e|moderation|livetranslate|gte-|text-embedding/i;
  const VISION = /vl|vision|omni|qvq|gpt-4o|gpt-4\.1|gpt-5|gemini|glm-4v|4v/i;
  const FALLBACK = {   // 接口不给列表时的常用模型
    bailian: { text: ['qwen-flash', 'qwen-plus', 'qwen-max', 'qwen3-max', 'qwen-turbo', 'qwen-mt-plus', 'qwen-mt-turbo'], vision: ['qwen3-vl-flash', 'qwen3-vl-plus', 'qwen-vl-max', 'qwen-vl-plus'] },
    deepseek: { text: ['deepseek-flash', 'deepseek-v4-pro'], vision: [] },
  };
  async function listModels(base, key) {
    const r = await http('GET', base.replace(/\/+$/, '') + '/models', key ? { Authorization: 'Bearer ' + key } : {});
    if (r.status !== 200) throw fail(r);
    const j = JSON.parse(r.body), arr = Array.isArray(j) ? j : j.data || j.models || [];
    return [...new Set(arr.map(x => typeof x === 'string' ? x : x.id || x.name || x.model).filter(Boolean).map(String))];
  }
  function showModels(s, inputId, kind, ids, note, onPick) {
    const box = s.querySelector('#' + inputId + 'List');
    const isV = kind === 'vision';
    let all = ids.slice().sort();
    const hidden = all.filter(x => NOT_CHAT.test(x) && !(isV && VISION.test(x)));
    all = all.filter(x => !hidden.includes(x));
    if (isV) all.sort((a, b) => (VISION.test(b) ? 1 : 0) - (VISION.test(a) ? 1 : 0));
    let showAll = false;
    const paint = () => {
      const q = (box.querySelector('input') || {}).value || '';
      const list = (showAll ? all.concat(hidden) : all).filter(x => x.toLowerCase().includes(q.toLowerCase()));
      box.querySelector('.mlist').innerHTML = list.slice(0, 300).map(x => `<button class="btn small" data-pick="${esc(x)}" style="margin:3px">${esc(x)}${isV && VISION.test(x) ? ' · 看图' : ''}</button>`).join('') || '<div class="hint">没有匹配的模型</div>';
    };
    box.innerHTML = `<div class="hint">${esc(note)}共 ${all.length} 个${isV ? '（标「看图」的能认图，识屏要选这种）' : ''}${hidden.length ? `，另有 ${hidden.length} 个不是对话模型（向量、语音、画图等）已隐藏 <a href="#" data-showall>显示</a>` : ''}。点一下就填进去。</div>
      <div class="add-row"><input placeholder="搜索模型名…" autocomplete="off" autocapitalize="off" spellcheck="false" style="flex:1;min-width:0"></div>
      <div class="mlist" style="max-height:240px;overflow:auto"></div>`;
    box.hidden = false;
    box.querySelector('input').oninput = paint;
    const sa = box.querySelector('[data-showall]');
    if (sa) sa.onclick = e => { e.preventDefault(); showAll = true; sa.remove(); paint(); };
    box.onclick = e => { const b = e.target.closest('[data-pick]'); if (!b) return; e.stopPropagation(); s.querySelector('#' + inputId).value = b.dataset.pick; box.hidden = true; onPick(); };
    paint();
  }

  function open() {
    const c = load();
    try { save(c); } catch (e) {}   // 旧设置分好各家 Key 后马上存，游戏里也能用上
    const s = openSheet(`<div class="body">
      <h3>AI 翻译设置</h3>
      <div class="hint">给生肉游戏用：RPG Maker MV / MZ 在游戏里 修改器 →「翻译」直接翻游戏文字；其它游戏用「识屏翻译」。这里的设置两边共用。</div>
      <div class="sec">文字翻译服务</div>
      <label class="line"><input type="radio" name="trp" value="free" ${c.provider === 'free' ? 'checked' : ''} style="width:auto"> 免费机翻（微软翻译，不用 Key，质量一般）</label>
      <label class="line"><input type="radio" name="trp" value="llm" ${c.provider === 'llm' ? 'checked' : ''} style="width:auto"> 大模型 API（自己填 Key，质量好，很便宜）</label>
      <div id="llmBox" ${c.provider === 'llm' ? '' : 'hidden'}>
        <div class="add-row">${PRESETS.map((p, i) => `<button class="btn small${c.base === p[1] ? ' primary' : ''}" data-preset="${i}">${p[0]}</button>`).join('')}</div>
        <div class="hint">成人向（R18）游戏请用 <b>DeepSeek</b>：阿里云百炼对输入有内容审核，会报 "Input data may contain inappropriate content"。DeepSeek 的 Key 要到 platform.deepseek.com 单独申请，和百炼的 Key 不通用。每家的 Key 分开保存，切换预设会自动换成那家的 Key。</div>
        <div class="sub">接口地址</div>${field('trBase', c.base)}
        <div class="sub">API Key（<span id="trKeyFor">${PROV[prov(c.base)]}</span>）</div>${keyField('trKey', c.key)}
        <div class="sub">模型</div>${modelField('trModel', c.model, 'text')}
        <div class="sub">预翻译同时发几批</div>
        <div class="add-row">${[4, 8, 16, 32, 64].map(n => `<button class="btn small${+c.conc === n ? ' primary' : ''}" data-conc="${n}">${n}${n === 16 ? '（默认）' : ''}</button>`).join('')}</div>
        <div class="hint">越多越快，费用不变（按字数算）。百炼、DeepSeek 限流都很宽，可以直接选 64；Kimi 等小平台建议 8~16。服务商限流（429）时会自动减半继续翻，不会中断。</div>
      </div>
      <label class="line"><input type="checkbox" id="trEn" ${c.english ? 'checked' : ''} style="width:auto"> 英文游戏也翻译（不勾只翻日文）</label>
      <div class="add-row"><button class="btn small" data-a="test">测试文字翻译</button><span class="sub" id="trTest"></span></div>
      <div class="sec">识屏翻译（看图识字，需要支持图片的大模型）</div>
      <div class="hint">识屏翻译要先认出屏幕上的日文，只有支持图片的大模型能做（免费机翻做不了）。推荐通义千问 qwen-vl-plus（便宜），也可以用 GPT-4o-mini、Kimi 视觉版等。</div>
      <div class="add-row"><button class="btn small" data-vpreset="0">百炼 qwen3-vl-flash（推荐）</button><button class="btn small" data-vpreset="3">百炼 qwen3-vl-plus（更准）</button><button class="btn small" data-vpreset="1">OpenAI gpt-4o-mini</button><button class="btn small" data-vpreset="2">Kimi 视觉</button></div>
      <div class="hint">阿里云百炼：API Key 在百炼控制台「API-KEY」里创建；接口地址用默认的就行。如果用的是业务空间专属域名（ws-xxx…maas.aliyuncs.com），直接粘贴进来，会自动改成兼容地址。</div>
      <div class="sub">接口地址</div>${field('vBase', c.visionBase)}
      <div class="sub">API Key</div>${keyField('vKey', c.visionKey)}
      <div class="sub">模型</div>${modelField('vModel', c.visionModel, 'vision')}
      <div class="add-row"><button class="btn small" data-a="vtest">测试识屏接口</button><button class="btn small primary" data-a="ocr">开启识屏翻译浮窗</button><span class="sub" id="vTest"></span></div>
      <div class="hint">识屏翻译：开启后屏幕上会出现一个小工具条和一个框。去打开游戏，点「调框」把框拖到对话框上（拖右下角调大小）→「完成」→ 点「翻译」，译文会盖在对话框上；点「自动」会在对话变化时自动翻译（每次截图时浮窗会闪一下）。第一次要允许「显示在其他应用上层」和录屏。</div>
      <div class="hint">Key 只保存在手机的 内部存储/Nagisaland/translate.json 里，别把这个文件发给别人。改完自动保存。</div>
    </div>`, 'tall');
    const V = { 3: ['https://dashscope.aliyuncs.com/compatible-mode/v1', 'qwen3-vl-plus'], 0: ['https://dashscope.aliyuncs.com/compatible-mode/v1', 'qwen3-vl-flash'], 1: ['https://api.openai.com/v1', 'gpt-4o-mini'], 2: ['https://api.moonshot.cn/v1', 'moonshot-v1-8k-vision-preview'] };
    const collect = () => {
      c.provider = (s.querySelector('input[name=trp]:checked') || {}).value || 'free';
      c.base = normBase(s.querySelector('#trBase').value); s.querySelector('#trBase').value = c.base; c.key = s.querySelector('#trKey').value.trim(); c.model = s.querySelector('#trModel').value.trim();
      c.english = s.querySelector('#trEn').checked;
      if (!c.profiles || typeof c.profiles !== 'object') c.profiles = {};
      c.profiles[prov(c.base)] = { base: c.base, key: c.key, model: c.model };
      s.querySelector('#trKeyFor').textContent = PROV[prov(c.base)];
      c.visionBase = normBase(s.querySelector('#vBase').value); s.querySelector('#vBase').value = c.visionBase; c.visionKey = s.querySelector('#vKey').value.trim(); c.visionModel = s.querySelector('#vModel').value.trim();
      save(c);
    };
    s.addEventListener('change', e => { if (/^tr(Base|Key|Model)$/.test(e.target.id || '') || e.target.name === 'trp') s.querySelector('#trTest').textContent = ''; collect(); s.querySelector('#llmBox').hidden = c.provider !== 'llm'; });
    s.onclick = async e => {
      const b = e.target.closest('button');
      if (!b) return;
      if (b.dataset.paste) {
        const t = (N.readClip ? N.readClip() : '') || '';
        if (!t) { toast('剪贴板是空的（或者没有读取权限）'); return; }
        s.querySelector('#' + b.dataset.paste).value = t.trim(); if (/^tr/.test(b.dataset.paste)) s.querySelector('#trTest').textContent = ''; collect(); toast('已粘贴'); return;
      }
      if (b.dataset.eye) { const i = s.querySelector('#' + b.dataset.eye); i.classList.toggle('secret'); b.textContent = i.classList.contains('secret') ? '显示' : '隐藏'; return; }
      if (b.dataset.preset != null) {
        collect();   // 先把当前这家的设置记下
        const p = PRESETS[+b.dataset.preset], pv = prov(p[1]), saved = (c.profiles || {})[pv];
        let base = p[1], key = '';
        if (saved) { base = pv === 'bailian' ? saved.base || p[1] : p[1]; key = saved.key || ''; }
        else if (pv === 'bailian' && prov(c.visionBase) === 'bailian') { base = c.visionBase; key = c.visionKey; }   // 识屏已经填了百炼 → 直接借用
        s.querySelector('#trBase').value = base; s.querySelector('#trKey').value = key; s.querySelector('#trModel').value = p[2];
        s.querySelector('#trTest').textContent = key ? '' : '← 还没填 ' + PROV[pv] + ' 的 Key';
        collect(); s.querySelectorAll('[data-preset]').forEach(x => x.classList.toggle('primary', x === b)); return;
      }
      if (b.dataset.vpreset != null) { const p = V[b.dataset.vpreset]; const cur = s.querySelector('#vBase').value; s.querySelector('#vBase').value = /aliyuncs/.test(p[0]) && /maas\.aliyuncs/.test(cur) ? cur : p[0]; s.querySelector('#vModel').value = p[1]; collect(); return; }
      if (b.dataset.conc) { c.conc = +b.dataset.conc; save(c); s.querySelectorAll('[data-conc]').forEach(x => x.classList.toggle('primary', x === b)); toast('预翻译同时 ' + c.conc + ' 批'); return; }
      if (b.dataset.models) {
        collect();
        const isV = b.dataset.models === 'vision', base = isV ? c.visionBase : c.base, key = isV ? c.visionKey : c.key, id = isV ? 'vModel' : 'trModel';
        const box = s.querySelector('#' + id + 'List');
        if (!box.hidden) { box.hidden = true; return; }
        if (!key) { toast('先填 API Key 再查询'); return; }
        b.disabled = true; b.textContent = '查询中…';
        let ids = null, note = '';
        try { ids = await listModels(base, key); }
        catch (err) {
          const fb = (FALLBACK[prov(base)] || {})[isV ? 'vision' : 'text'];
          if (!fb || !fb.length) { toast('查询失败：' + err.message); }
          else { ids = fb; note = '接口没给出列表（' + err.message.slice(0, 40) + '），下面是常用的：'; }
        }
        b.disabled = false; b.textContent = '查询可用模型';
        if (ids) {
          if (!ids.length) { toast('对方没返回任何模型'); return; }
          showModels(s, id, isV ? 'vision' : 'text', ids, note, () => { if (!isV) s.querySelector('#trTest').textContent = ''; collect(); toast('已选择模型，建议点一下测试'); });
        }
        return;
      }
      if (b.dataset.a === 'ocr') { collect(); startOcr(); return; }
      if (b.dataset.a === 'test' || b.dataset.a === 'vtest') {
        collect();
        const out = s.querySelector(b.dataset.a === 'test' ? '#trTest' : '#vTest');
        out.textContent = '测试中…'; b.disabled = true;
        try { out.textContent = '✓ ' + (b.dataset.a === 'test' ? await testText(c) : await testVision(c)); }
        catch (err) { out.textContent = '✗ ' + err.message; }
        b.disabled = false;
      }
    };
  }
  return { open, load, save };
})();
