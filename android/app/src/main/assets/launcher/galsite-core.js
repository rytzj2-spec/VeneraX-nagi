// 小站相关的纯函数：从电脑版 Nagisaland 的 galsite.js 原样复制，两边行为一致
(function () {
  'use strict';
  // ---------- 纯函数（无 DOM，可单独测试） ----------
  const KANA = /[぀-ヿ]/;

  function cleanSiteTitle(t) {
    let s = String(t || '');
    s = s.replace(/【[^】]*】|\[[^\]]*\]|［[^］]*］|〔[^〕]*〕/g, ' ');
    s = s.replace(/^\s*(?:PC\s*[+＋]\s*安卓|PC\s*[\/／]\s*安卓|PC|安卓)\s*/i, '');
    s = s.replace(/\s+#/g, ' ').replace(/^#/, '');
    const cut = s.search(/\s(?:v|ver\.?|Ver\.?|V)\s?\d[\w.\-]*|\s\d+\.\d+(?:\.\d+)*|(?:官方|官中|内嵌|AI|机翻|精翻|完整|云翻|GPT|简体|繁体|中文)*(?:汉化|中文版|步兵|无修|DEMO|Demo|demo|体验版|试玩版|正式版|完全版|豪华版|自带|全CG|附存档|存档|百度|迅雷|夸克|UC网盘|更新)/);
    if (cut > 1) s = s.slice(0, cut);
    s = s.replace(/[\s\-–—_:：|｜\/～~]+$/g, '').replace(/\s+/g, ' ').trim();
    return s || String(t || '').trim();
  }

  // 「中文名 日文原名/英文名」拆开
  const HAN = /[\u3400-\u9fff]/;
  const LATIN = /[A-Za-z]/;
  function splitTitle(clean) {
    const str = String(clean || '').trim();
    const tidy = x => x.replace(/^[\s\-–—～~:：+＋]+|[\s\-–—～~:：+＋]+$/g, '').replace(/\.{2,}$|…$/, '').trim();
    // 「中文名/原名」写法（原名是日文或英文都行，顺序反过来也行）
    const slash = str.split(/\s*[\/／]\s*/).filter(Boolean);
    if (slash.length >= 2) {
      const zhIdx = slash.findIndex(x => HAN.test(x) && !KANA.test(x));
      if (zhIdx >= 0) {
        const others = slash.filter((_, i) => i !== zhIdx);
        if (others.every(x => KANA.test(x) || !HAN.test(x))) return { chinese: tidy(slash[zhIdx]), original: tidy(others.join(' / ')) };
      }
    }
    const tokens = str.split(/\s+/).filter(Boolean);
    // 「中文名 日文名」：从第一个含假名的词开始算原名
    for (let i = 1; i < tokens.length; i++) {
      if (KANA.test(tokens[i]) && !KANA.test(tokens.slice(0, i).join(''))) {
        return { chinese: tidy(tokens.slice(0, i).join(' ')), original: tidy(tokens.slice(i).join(' ')) };
      }
    }
    // 「中文名 English Name」：后半段全是拉丁字母
    for (let i = 1; i < tokens.length; i++) {
      const head = tokens.slice(0, i).join(' '), tail = tokens.slice(i).join(' ');
      if (HAN.test(head) && !HAN.test(tail) && !KANA.test(tail) && LATIN.test(tail) && HAN.test(tokens[i - 1])) {
        return { chinese: tidy(head), original: tidy(tail) };
      }
    }
    return { chinese: str, original: '' };
  }

  function norm(s) {
    return String(s || '').toLowerCase().replace(/[^0-9a-z぀-ヿ㐀-鿿]/g, '');
  }

  function looksLikeCode(s) {
    return /^[A-Za-z]{0,3}\d{2,7}$/.test(String(s || '').trim());
  }

  function basename(p) {
    return String(p || '').replace(/[\\/]+$/, '').split(/[\\/]/).pop() || '';
  }

  function simpleSimilarity(a, b) {
    if (typeof calculateSimilarity === 'function') return calculateSimilarity(a, b);
    if (!a || !b) return 0;
    if (a === b) return 1;
    if (a.includes(b) || b.includes(a)) return 0.6 + Math.min(a.length, b.length) / Math.max(a.length, b.length) * 0.3;
    return 0;
  }

  // 本地文件夹名是否就是某次下载的那个资源
  function matchesPending(p, folder) {
    const n = norm(folder);
    if (!n) return false;
    if ((p.shareNames || []).some(s => norm(s) === n)) return true;
    const t = norm(p.cleanTitle || cleanSiteTitle(p.title));
    if (!t) return false;
    if (t === n) return true;
    if (n.length >= 4 && t.length >= 4 && (t.includes(n) || n.includes(t))) return true;
    return simpleSimilarity(t, n) >= 0.85;
  }

  // 小站标签里去掉网盘名、来源站之类不适合当 TAG 的
  function usefulTags(tags) {
    const drop = /^(风花雪月|夸克|夸克网盘|UC|UC网盘|迅雷|迅雷网盘|百度|百度网盘|MEGA|PC|安卓|PC\+安卓|更新|RJ\d+.*|A\d{1,4}|.*\/.*)$/i;
    const out = [];
    for (const t of tags || []) {
      const s = String(t).trim();
      if (s && s.length <= 12 && !drop.test(s) && !out.includes(s)) out.push(s);
    }
    return out.slice(0, 8);
  }

  const GalsiteCore = { cleanSiteTitle, splitTitle, norm, looksLikeCode, basename, matchesPending, usefulTags };
  window.GalsiteCore = GalsiteCore;
})();
