
const MANWANG_SBOX = [
  0x63,0x7c,0x77,0x7b,0xf2,0x6b,0x6f,0xc5,0x30,0x01,0x67,0x2b,0xfe,0xd7,0xab,0x76,
  0xca,0x82,0xc9,0x7d,0xfa,0x59,0x47,0xf0,0xad,0xd4,0xa2,0xaf,0x9c,0xa4,0x72,0xc0,
  0xb7,0xfd,0x93,0x26,0x36,0x3f,0xf7,0xcc,0x34,0xa5,0xe5,0xf1,0x71,0xd8,0x31,0x15,
  0x04,0xc7,0x23,0xc3,0x18,0x96,0x05,0x9a,0x07,0x12,0x80,0xe2,0xeb,0x27,0xb2,0x75,
  0x09,0x83,0x2c,0x1a,0x1b,0x6e,0x5a,0xa0,0x52,0x3b,0xd6,0xb3,0x29,0xe3,0x2f,0x84,
  0x53,0xd1,0x00,0xed,0x20,0xfc,0xb1,0x5b,0x6a,0xcb,0xbe,0x39,0x4a,0x4c,0x58,0xcf,
  0xd0,0xef,0xaa,0xfb,0x43,0x4d,0x33,0x85,0x45,0xf9,0x02,0x7f,0x50,0x3c,0x9f,0xa8,
  0x51,0xa3,0x40,0x8f,0x92,0x9d,0x38,0xf5,0xbc,0xb6,0xda,0x21,0x10,0xff,0xf3,0xd2,
  0xcd,0x0c,0x13,0xec,0x5f,0x97,0x44,0x17,0xc4,0xa7,0x7e,0x3d,0x64,0x5d,0x19,0x73,
  0x60,0x81,0x4f,0xdc,0x22,0x2a,0x90,0x88,0x46,0xee,0xb8,0x14,0xde,0x5e,0x0b,0xdb,
  0xe0,0x32,0x3a,0x0a,0x49,0x06,0x24,0x5c,0xc2,0xd3,0xac,0x62,0x91,0x95,0xe4,0x79,
  0xe7,0xc8,0x37,0x6d,0x8d,0xd5,0x4e,0xa9,0x6c,0x56,0xf4,0xea,0x65,0x7a,0xae,0x08,
  0xba,0x78,0x25,0x2e,0x1c,0xa6,0xb4,0xc6,0xe8,0xdd,0x74,0x1f,0x4b,0xbd,0x8b,0x8a,
  0x70,0x3e,0xb5,0x66,0x48,0x03,0xf6,0x0e,0x61,0x35,0x57,0xb9,0x86,0xc1,0x1d,0x9e,
  0xe1,0xf8,0x98,0x11,0x69,0xd9,0x8e,0x94,0x9b,0x1e,0x87,0xe9,0xce,0x55,0x28,0xdf,
  0x8c,0xa1,0x89,0x0d,0xbf,0xe6,0x42,0x68,0x41,0x99,0x2d,0x0f,0xb0,0x54,0xbb,0x16
];
const MANWANG_ISBOX = [
  0x52,0x09,0x6a,0xd5,0x30,0x36,0xa5,0x38,0xbf,0x40,0xa3,0x9e,0x81,0xf3,0xd7,0xfb,
  0x7c,0xe3,0x39,0x82,0x9b,0x2f,0xff,0x87,0x34,0x8e,0x43,0x44,0xc4,0xde,0xe9,0xcb,
  0x54,0x7b,0x94,0x32,0xa6,0xc2,0x23,0x3d,0xee,0x4c,0x95,0x0b,0x42,0xfa,0xc3,0x4e,
  0x08,0x2e,0xa1,0x66,0x28,0xd9,0x24,0xb2,0x76,0x5b,0xa2,0x49,0x6d,0x8b,0xd1,0x25,
  0x72,0xf8,0xf6,0x64,0x86,0x68,0x98,0x16,0xd4,0xa4,0x5c,0xcc,0x5d,0x65,0xb6,0x92,
  0x6c,0x70,0x48,0x50,0xfd,0xed,0xb9,0xda,0x5e,0x15,0x46,0x57,0xa7,0x8d,0x9d,0x84,
  0x90,0xd8,0xab,0x00,0x8c,0xbc,0xd3,0x0a,0xf7,0xe4,0x58,0x05,0xb8,0xb3,0x45,0x06,
  0xd0,0x2c,0x1e,0x8f,0xca,0x3f,0x0f,0x02,0xc1,0xaf,0xbd,0x03,0x01,0x13,0x8a,0x6b,
  0x3a,0x91,0x11,0x41,0x4f,0x67,0xdc,0xea,0x97,0xf2,0xcf,0xce,0xf0,0xb4,0xe6,0x73,
  0x96,0xac,0x74,0x22,0xe7,0xad,0x35,0x85,0xe2,0xf9,0x37,0xe8,0x1c,0x75,0xdf,0x6e,
  0x47,0xf1,0x1a,0x71,0x1d,0x29,0xc5,0x89,0x6f,0xb7,0x62,0x0e,0xaa,0x18,0xbe,0x1b,
  0xfc,0x56,0x3e,0x4b,0xc6,0xd2,0x79,0x20,0x9a,0xdb,0xc0,0xfe,0x78,0xcd,0x5a,0xf4,
  0x1f,0xdd,0xa8,0x33,0x88,0x07,0xc7,0x31,0xb1,0x12,0x10,0x59,0x27,0x80,0xec,0x5f,
  0x60,0x51,0x7f,0xa9,0x19,0xb5,0x4a,0x0d,0x2d,0xe5,0x7a,0x9f,0x93,0xc9,0x9c,0xef,
  0xa0,0xe0,0x3b,0x4d,0xae,0x2a,0xf5,0xb0,0xc8,0xeb,0xbb,0x3c,0x83,0x53,0x99,0x61,
  0x17,0x2b,0x04,0x7e,0xba,0x77,0xd6,0x26,0xe1,0x69,0x14,0x63,0x55,0x21,0x0c,0x7d
];
const MANWANG_RCON = [0x00,0x01,0x02,0x04,0x08,0x10,0x20,0x40,0x80,0x1b,0x36];

function manwangMul(a, b) {
  let r = 0;
  while (b) {
    if (b & 1) r ^= a;
    const hi = a & 0x80;
    a = (a << 1) & 0xff;
    if (hi) a ^= 0x1b;
    b >>>= 1;
  }
  return r;
}

function manwangExpandKey(key) {
  const Nk = 4, Nr = 10;
  const w = [];
  for (let i = 0; i < Nk; i++) {
    w.push(((key[4*i]) << 24) | ((key[4*i+1]) << 16) | ((key[4*i+2]) << 8) | (key[4*i+3]));
  }
  for (let i = Nk; i < 4*(Nr+1); i++) {
    let t = w[i-1];
    if (i % Nk === 0) {
      t = ((MANWANG_SBOX[(t>>>24)&0xff]) << 24) | ((MANWANG_SBOX[(t>>>16)&0xff]) << 16) |
          ((MANWANG_SBOX[(t>>>8)&0xff]) << 8) | (MANWANG_SBOX[t&0xff]);
      t = ((t << 8) & 0xffffffff) | (t >>> 24);
      t ^= (MANWANG_RCON[i/Nk] << 24);
    }
    w[i] = ((w[i-Nk] ^ t) >>> 0);
  }
  const rk = [];
  for (let r = 0; r <= Nr; r++) {
    const block = [];
    for (let c = 0; c < 4; c++) {
      const word = w[r*4 + c];
      block.push((word>>>24)&0xff, (word>>>16)&0xff, (word>>>8)&0xff, word&0xff);
    }
    rk.push(block);
  }
  return rk;
}

function manwangAddRoundKey(s, rk) { for (let i = 0; i < 16; i++) s[i] ^= rk[i]; }
function manwangInvSubBytes(s) { for (let i = 0; i < 16; i++) s[i] = MANWANG_ISBOX[s[i]]; }
function manwangInvShiftRows(s) {
  const t = s.slice();
  s[1]=t[13]; s[5]=t[1]; s[9]=t[5]; s[13]=t[9];
  s[2]=t[10]; s[6]=t[14]; s[10]=t[2]; s[14]=t[6];
  s[3]=t[7];  s[7]=t[11]; s[11]=t[15]; s[15]=t[3];
}
function manwangInvMixColumns(s) {
  for (let c = 0; c < 4; c++) {
    const i = c*4;
    const a0=s[i], a1=s[i+1], a2=s[i+2], a3=s[i+3];
    s[i]   = manwangMul(a0,14) ^ manwangMul(a1,11) ^ manwangMul(a2,13) ^ manwangMul(a3,9);
    s[i+1] = manwangMul(a0,9)  ^ manwangMul(a1,14) ^ manwangMul(a2,11) ^ manwangMul(a3,13);
    s[i+2] = manwangMul(a0,13) ^ manwangMul(a1,9)  ^ manwangMul(a2,14) ^ manwangMul(a3,11);
    s[i+3] = manwangMul(a0,11) ^ manwangMul(a1,13) ^ manwangMul(a2,9)  ^ manwangMul(a3,14);
  }
}
function manwangDecryptBlock(block, rk) {
  const st = block.slice(0, 16);
  manwangAddRoundKey(st, rk[10]);
  for (let r = 9; r >= 1; r--) {
    manwangInvShiftRows(st);
    manwangInvSubBytes(st);
    manwangAddRoundKey(st, rk[r]);
    manwangInvMixColumns(st);
  }
  manwangInvShiftRows(st);
  manwangInvSubBytes(st);
  manwangAddRoundKey(st, rk[0]);
  for (let i = 0; i < 16; i++) block[i] = st[i];
}
function manwangCbcDecrypt(inp, keyBytes, ivBytes) {
  const rk = manwangExpandKey(keyBytes);
  const out = [];
  let prev = ivBytes.slice(0, 16);
  for (let off = 0; off + 16 <= inp.length; off += 16) {
    const block = inp.slice(off, off + 16);
    manwangDecryptBlock(block, rk);
    for (let i = 0; i < 16; i++) {
      block[i] = (block[i] ^ prev[i]) & 0xff;
      out[off + i] = block[i];
    }
    prev = inp.slice(off, off + 16);
  }
  if (out.length) {
    const pad = out[out.length - 1];
    if (pad >= 1 && pad <= 16 && pad <= out.length) return out.slice(0, out.length - pad);
  }
  return out;
}
function manwangB64decode(b64) {
  const CH = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
  const bytes = [];
  let buf = 0, bits = 0;
  for (let i = 0; i < b64.length; i++) {
    const c = b64.charAt(i);
    if (c === "=") break;
    const v = CH.indexOf(c);
    if (v < 0) continue;
    buf = (buf << 6) | v;
    bits += 6;
    if (bits >= 8) {
      bits -= 8;
      bytes.push((buf >>> bits) & 0xff);
    }
  }
  return bytes;
}
function manwangUtf8decode(bytes) {
  let s = "";
  for (let i = 0; i < bytes.length;) {
    const b = bytes[i++];
    if (b < 0x80) {
      s += String.fromCharCode(b);
    } else if (b < 0xe0) {
      s += String.fromCharCode(((b & 0x1f) << 6) | (bytes[i++] & 0x3f));
    } else if (b < 0xf0) {
      s += String.fromCharCode(((b & 0x0f) << 12) | ((bytes[i++] & 0x3f) << 6) | (bytes[i++] & 0x3f));
    } else {
      let cp = ((b & 0x07) << 18) | ((bytes[i++] & 0x3f) << 12) | ((bytes[i++] & 0x3f) << 6) | (bytes[i++] & 0x3f);
      cp -= 0x10000;
      s += String.fromCharCode(0xd800 + (cp >> 10), 0xdc00 + (cp & 0x3ff));
    }
  }
  return s;
}
// 解密章节页 params 密文 → JSON 文本
function manwangDecryptParams(blobB64, keyHex) {
  const raw = manwangB64decode(String(blobB64));
  if (raw.length < 17) return "";
  const keyBytes = [];
  for (let i = 0; i < 16; i++) keyBytes.push(parseInt(keyHex.substr(i * 2, 2), 16));
  const iv = raw.slice(0, 16);
  const ct = raw.slice(16);
  return manwangUtf8decode(manwangCbcDecrypt(ct, keyBytes, iv));
}

/* ============================ 漫画源类 ============================ */
class ManWang extends ComicSource {
  name = "漫网";
  key = "manwang";
  version = "2.0.1";
  minAppVersion = "1.0.0";
  url = "https://www.manwang.net/";

  // 主站（apex manwang.net 同站；无已确认同构镜像，不做域名切换设置）
  HOST = "https://www.manwang.net";

  // params 解密固定密钥(16 字节 hex，站点 pic-v2.js 常量，跨章节恒定)
  AES_KEY_HEX = "39533824764a6e5532414e6553526f46";

  UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36";

  // ---------------- 通用 ----------------

  async _get(url, headers) {
    const h = Object.assign({
      "User-Agent": this.UA,
      "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
      "Accept-Language": "zh-CN,zh;q=0.9,en;q=0.8",
      "Accept-Encoding": "gzip, deflate",
      "Sec-Fetch-Site": "none",
      "Sec-Fetch-Mode": "navigate",
      "Sec-Fetch-User": "?1",
      "Sec-Fetch-Dest": "document"
    }, headers || {});
    let res;
    try {
      res = await Network.get(url, h);
    } catch (e) {
      const msg = String((e && e.message) || e);
      if (/cloudflare|cf-|challenge/i.test(msg)) {
        throw new Error("漫网触发 Cloudflare 验证，请在漫网源内先完成「登录/过验证」再浏览");
      }
      throw new Error("漫网网络请求失败: " + msg);
    }
    const status = res && res.status ? res.status : 0;
    const body = res && res.body !== undefined ? res.body : "";
    if (status !== 200 && status !== 0) {
      throw new Error("漫网返回 HTTP " + status + " (" + url + ")");
    }
    if (typeof body === "string" && /challenge-platform|cf-browser-verification|Just a moment/i.test(body.slice(0, 2000))) {
      throw new Error("漫网触发 Cloudflare 验证，请在漫网源内先完成「登录/过验证」再浏览");
    }
    return body;
  }

  _abs(u) {
    if (!u) return "";
    if (/^https?:\/\//i.test(u)) return u;
    if (u.charAt(0) === "/") return this.HOST + u;
    return this.HOST + "/" + u;
  }

  _comicId(href) {
    if (!href) return "";
    const m = String(href).match(/\/book\/(\d+)/);
    return m ? m[1] : "";
  }

  _epIdFromHref(href) {
    if (!href) return "";
    const m = String(href).match(/\/chapter\/(\d+)-(\d+)/);
    return m ? m[2] : "";
  }

  _txt(el) {
    if (!el) return "";
    return String(el.text || "").replace(/\s+/g, " ").trim();
  }

  _imgSrc(img) {
    if (!img) return "";
    const attrs = img.attributes || {};
    return attrs["data-src"] || attrs.src || attrs["data-original"] || "";
  }

  _parseListComics(doc) {
    const comics = [];
    const seen = {};
    const items = doc.querySelectorAll("ul.list-comic-book li");
    for (const li of items) {
      const a = li.querySelector("a.comic-item") || li.querySelector("a[href*='/book/']");
      if (!a) continue;
      const id = this._comicId(a.attributes && a.attributes.href);
      if (!id || seen[id]) continue;
      const img = a.querySelector("img");
      const h2 = a.querySelector("h2");
      const process = a.querySelector("p.process");
      const desc = a.querySelector("p.desc");
      let title = h2 ? this._txt(h2) : "";
      if (!title && img) title = (img.attributes && (img.attributes.alt || img.attributes.title)) || "";
      seen[id] = true;
      comics.push(new Comic({
        id: id,
        title: String(title),
        subTitle: process ? this._txt(process) : "",
        cover: this._abs(this._imgSrc(img)),
        description: desc ? this._txt(desc) : ""
      }));
    }
    return comics;
  }

  // ---------------- 首页探索 ----------------

  explore = [{
    title: "漫网",
    type: "multiPartPage",
    load: async (page) => {
      const html = await this._get(this.HOST + "/");
      const doc = new HtmlDocument(html);
      const parts = [];
      const sections = doc.querySelectorAll(".panel-comic, .panel-anime");
      for (const sec of sections) {
        const mt = sec.querySelector(".mod-title");
        const title = this._txt(mt);
        const seen = {};
        const comics = [];
        for (const a of sec.querySelectorAll("a[href*='/book/']")) {
          const img = a.querySelector("img");
          if (!img) continue;
          const id = this._comicId(a.attributes && a.attributes.href);
          if (!id || seen[id]) continue;
          let t = this._txt(a.querySelector("h4")) || this._txt(a.querySelector("h3"));
          if (!t) t = (img.attributes && (img.attributes.alt || img.attributes.title)) || "";
          const latest = this._txt(a.querySelector(".comic-mask p"));
          seen[id] = true;
          comics.push(new Comic({
            id: id,
            title: String(t),
            subTitle: latest,
            cover: this._abs(this._imgSrc(img))
          }));
        }
        if (comics.length >= 2) parts.push({ title: title || "漫网推荐", comics: comics });
      }
      // 兜底：若分区选择器变化，退化为首页全部带图卡片
      if (!parts.length) {
        const seen = {};
        const comics = [];
        for (const a of doc.querySelectorAll("a[href*='/book/']")) {
          const img = a.querySelector("img");
          if (!img) continue;
          const id = this._comicId(a.attributes && a.attributes.href);
          if (!id || seen[id]) continue;
          seen[id] = true;
          comics.push(new Comic({
            id: id,
            title: (img.attributes && (img.attributes.alt || img.attributes.title)) || id,
            cover: this._abs(this._imgSrc(img))
          }));
        }
        if (comics.length) parts.push({ title: "漫网推荐", comics: comics });
      }
      doc.dispose();
      return parts;
    }
  }];

  // ---------------- 分类 ----------------
  // 地区行(region-N)与题材行(tag-N)各自 URL 自包含：题材走全站 /category/tags/{id}

  REGIONS = [
    { id: "1", name: "国产漫画" },
    { id: "2", name: "日本漫画" },
    { id: "3", name: "韩国漫画" },
    { id: "4", name: "欧美漫画" }
  ];

  TAGS = [
    { id: "2569", name: "奇幻" }, { id: "2570", name: "搞笑" },
    { id: "2571", name: "都市" }, { id: "2572", name: "热血" },
    { id: "2573", name: "穿越" }, { id: "2574", name: "纯爱" },
    { id: "2575", name: "机甲" }, { id: "2576", name: "竞技" },
    { id: "2585", name: "玄幻" }, { id: "2586", name: "修仙" },
    { id: "2587", name: "校园" }, { id: "2588", name: "治愈" },
    { id: "2589", name: "科幻" }, { id: "2593", name: "古风" },
    { id: "2597", name: "系统" }, { id: "2598", name: "大女主" }
  ];

  category = {
    title: "漫网",
    parts: [
      {
        name: "地区",
        type: "fixed",
        categories: this.REGIONS.map(r => r.name),
        itemType: "category",
        categoryParams: this.REGIONS.map(r => "region-" + r.id),
        groupParam: null
      },
      {
        name: "题材",
        type: "fixed",
        categories: this.TAGS.map(t => t.name),
        itemType: "category",
        categoryParams: this.TAGS.map(t => "tag-" + t.id),
        groupParam: null
      }
    ],
    enableRankingPage: false
  };

  categoryComics = {
    optionList: [],
    load: async (category, param, options, page) => {
      page = page || 1;
      let base;
      if (param && param.indexOf("tag-") === 0) {
        base = this.HOST + "/category/tags/" + param.slice(4);
      } else {
        const rid = param && param.indexOf("region-") === 0 ? param.slice(7) : "1";
        base = this.HOST + "/category/list/" + rid;
      }
      const url = page > 1 ? base + "/" + page + "/" : base + "/";
      const html = await this._get(url, { "Referer": this.HOST + "/" });
      const doc = new HtmlDocument(html);
      const comics = this._parseListComics(doc);
      doc.dispose();
      // 站点分页 HTML 为固定占位(无页码) → 自限制 maxPage：非空则假设还有下一页
      return { comics: comics, maxPage: comics.length ? page + 1 : page - 1 };
    }
  };

  // ---------------- 搜索 ----------------
  // 站点 /index.php/search?key= 目前全站「搜索结果（0）」（站点搜索索引为空，
  // 与请求方无关）。源照常实现：站点修复索引后自动恢复。

  search = {
    optionList: [],
    load: async (keyword, options, page) => {
      if (!String(keyword || "").trim() || (page || 1) > 1) return { comics: [], maxPage: 1 };
      const url = this.HOST + "/index.php/search?key=" + encodeURIComponent(String(keyword).trim());
      const html = await this._get(url, { "Referer": this.HOST + "/" });
      const doc = new HtmlDocument(html);
      const comics = this._parseListComics(doc);
      doc.dispose();
      return { comics: comics, maxPage: 1 };
    }
  };

  // ---------------- 详情与阅读 ----------------

  comic = {
    loadInfo: async (id) => {
      const html = await this._get(this.HOST + "/book/" + id + "/");
      const doc = new HtmlDocument(html);
      const meta = (p) => {
        const el = doc.querySelector('meta[property="og:' + p + '"]');
        return el && el.attributes ? (el.attributes.content || "") : "";
      };
      const title = String(meta("title") || meta("novel:book_name") || id).replace(/[《》]/g, "");
      const cover = this._abs(meta("image"));
      const author = meta("novel:author");
      const status = meta("novel:status");
      const categoryRaw = meta("novel:category");
      const description = meta("description");
      const updateTime = meta("novel:update_time");

      const subtitleParts = [];
      if (status) subtitleParts.push(status);
      if (updateTime) subtitleParts.push(updateTime);

      const tags = {};
      if (author) tags["作者"] = [author];
      if (status) tags["状态"] = [status];
      if (categoryRaw) {
        const cats = String(categoryRaw).split(/[\s,，/]+/).filter(Boolean);
        if (cats.length) tags["分类"] = cats;
      }

      const chapters = {};
      let lastTitle = "";
      const list = doc.querySelector("#j_chapter_list") || doc.querySelector("ol.chapter-list");
      if (list) {
        const anchors = list.querySelectorAll("a[href*='/chapter/']");
        for (const a of anchors) {
          const ep = this._epIdFromHref(a.attributes && a.attributes.href);
          if (!ep) continue;
          const t = String((a.attributes && a.attributes.title) || this._txt(a) || "").trim();
          if (t && !chapters[ep]) chapters[ep] = t;
          if (t) lastTitle = t;
        }
      }
      // 兜底整页扫描
      if (!Object.keys(chapters).length) {
        for (const a of doc.querySelectorAll("a[href*='/chapter/']")) {
          const ep = this._epIdFromHref(a.attributes && a.attributes.href);
          if (!ep || chapters[ep]) continue;
          const t = String((a.attributes && a.attributes.title) || this._txt(a) || "").trim();
          if (t) chapters[ep] = t;
        }
      }
      if (lastTitle) subtitleParts.push(lastTitle);

      doc.dispose();
      return new ComicDetails({
        title: title,
        subtitle: subtitleParts.filter(Boolean).join(" · "),
        cover: cover,
        description: description,
        tags: tags,
        chapters: chapters
      });
    },

    loadEp: async (comicId, epId) => {
      if (!epId) return { images: [] };
      const url = /^\//.test(String(epId))
        ? this.HOST + epId
        : this.HOST + "/chapter/" + comicId + "-" + epId;
      const html = await this._get(url);
      // 章节页内联: params='<base64 密文>'（IV 前缀 + AES-128-CBC 密文）
      const m = html.match(/params\s*=\s*'([^']+)'/);
      if (!m) return { images: [] };
      let data;
      try {
        data = JSON.parse(manwangDecryptParams(m[1], this.AES_KEY_HEX));
      } catch (e) {
        throw new Error("漫网章节图片解密失败: " + (e && e.message));
      }
      const imgs = (data && data.images) || [];
      const result = [];
      for (const u of imgs) {
        if (!u) continue;
        if (/^https?:\/\//i.test(u)) {
          result.push(u);
        } else if (u.charAt(0) === "/") {
          result.push(this.HOST + u);
        } else {
          // source_id=12 旧式相对路径图源(img1.baipiaoguai.org)兜底
          result.push("https://img1.baipiaoguai.org" + (u.charAt(0) === "/" ? u : "/" + u));
        }
      }
      return { images: result };
    },

    onImageLoad: (url, comicId, epId) => ({
      headers: {
        "Referer": "https://www.manwang.net/",
        "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36",
        "Accept": "image/webp,image/apng,image/avif,image/*,*/*;q=0.8"
      },
      timeout: 30000,
      retryCount: 3
    }),

    onThumbnailLoad: (url) => ({
      headers: {
        "Referer": "https://www.manwang.net/",
        "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36",
        "Accept": "image/webp,image/apng,image/avif,image/*,*/*;q=0.8"
      },
      timeout: 30000,
      retryCount: 3
    })
  };
}

if (typeof module !== "undefined" && module.exports) module.exports = ManWang;
