
function base64ToBytes(b64) {
  const chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
  b64 = String(b64).replace(/=+$/, "");
  const bytes = [];
  let buffer = 0, bits = 0;
  for (let i = 0; i < b64.length; i++) {
    const idx = chars.indexOf(b64[i]);
    if (idx < 0) continue;
    buffer = (buffer << 6) | idx;
    bits += 6;
    if (bits >= 8) { bits -= 8; bytes.push((buffer >> bits) & 0xff); }
  }
  return bytes;
}

function utf8BytesToString(bytes) {
  let str = "";
  for (let i = 0; i < bytes.length; i++) str += String.fromCharCode(bytes[i]);
  return decodeURIComponent(escape(str));
}

// 按 readerContainer 的 data-id 选取的 XOR 密钥表（来自 breeze-plugin-ru-man-hua，已验证）
const IMG_XOR_KEYS = [
  "smkhy258", "smkd95fv", "md496952", "cdcsdwq", "vbfsa256", "cawf151c",
  "cd56cvda", "8kihnt9", "dso15tlo", "5ko6plhy",
];

// ---- Dean-Edwards packer 解包（纯 JS） ----

function _findPackedExpr(html) {
  const re = /<script[^>]*>([\s\S]*?)<\/script>/g;
  let m;
  while ((m = re.exec(html)) !== null) {
    const txt = m[1];
    const idx = txt.indexOf("eval(function(p,a,c,k,e,d)");
    if (idx < 0) continue;
    let depth = 0, inStr = false, quote = "", o = idx + 4, end = -1;
    for (; o < txt.length; o++) {
      const ch = txt[o];
      if (inStr) {
        if (ch === "\\") { o++; continue; }
        if (ch === quote) inStr = false;
        continue;
      }
      if (ch === '"' || ch === "'" || ch === "`") { inStr = true; quote = ch; continue; }
      if (ch === "(") depth++;
      else if (ch === ")" && --depth === 0) { end = o + 1; break; }
    }
    if (end > 0) return txt.slice(idx + 4, end).trim();
  }
  return null;
}

function _unquote(s) {
  s = s.trim();
  const q = s[0];
  if ((q === '"' || q === "'" || q === "`") && s[s.length - 1] === q) return s.slice(1, -1);
  return s;
}

// 字典参数 k 可能是两种形态：
//   1) 纯字符串  "w1|w2|...|wn"
//   2) 方法链    "w1|w2|...|wn".split("|")   ← 本站实际形态，需先提取字符串字面量再切分
// 返回数组，避免把整个 ".split('|')" 当字典字符串按 | 切分产生垃圾项。
function _parseK(raw) {
  raw = raw.trim();
  const m = raw.match(/^["'`]([\s\S]*?)["'`]\s*\.\s*split\s*\(\s*["']([^"']*)["']\s*\)$/);
  if (m) return m[1].split(m[2]);
  const u = _unquote(raw);
  if (u !== raw) return u.split("|");
  return raw.split("|");
}

function _parsePackedArgs(expr) {
  const bo = expr.indexOf("{");
  if (bo < 0) throw new Error("packed: 找不到函数体");
  let depth = 1, i = bo + 1;
  for (; i < expr.length; i++) {
    const ch = expr[i];
    if (ch === "{") depth++;
    else if (ch === "}" && --depth === 0) break;
  }
  const argsStr = expr.slice(i + 1).trim();
  if (!argsStr.startsWith("(") || !argsStr.endsWith(")")) throw new Error("packed: 参数格式异常");
  const inner = argsStr.slice(1, -1);
  const parts = [];
  let buf = "", d = 0, instr = false, q = "";
  for (let j = 0; j < inner.length; j++) {
    const ch = inner[j];
    if (instr) {
      buf += ch;
      if (ch === "\\") { buf += inner[++j] || ""; continue; }
      if (ch === q) instr = false;
      continue;
    }
    if (ch === '"' || ch === "'" || ch === "`") { instr = true; q = ch; buf += ch; continue; }
    if (ch === "(" || ch === "[" || ch === "{") d++;
    else if (ch === ")" || ch === "]" || ch === "}") d--;
    if (ch === "," && d === 0) { parts.push(buf); buf = ""; continue; }
    buf += ch;
  }
  if (buf.length) parts.push(buf);
  if (parts.length < 4) throw new Error("packed: 参数不足");
  return {
    p: _unquote(parts[0].trim()),
    a: parseInt(parts[1].trim(), 10),
    c: parseInt(parts[2].trim(), 10),
    k: _parseK(parts[3].trim()),
  };
}

function _base62sym(n, base) {
  function e(r) {
    const mm = r % base;
    return (r < base ? "" : e(Math.floor(r / base))) +
      (mm > 35 ? String.fromCharCode(mm + 29) : mm.toString(36));
  }
  return e(n);
}

function _unpack(p, a, c, k) {
  const dict = Array.isArray(k) ? k : k.split("|");
  let out = p;
  for (let i = c - 1; i >= 0; i--) {
    const token = dict[i];
    if (!token) continue;
    const sym = _base62sym(i, a);
    out = out.split(new RegExp("\\b" + sym + "\\b", "g")).join(token);
  }
  return out;
}

// 从章节 HTML 解密出图片 URL 数组
function decryptChapterImages(html) {
  const expr = _findPackedExpr(html);
  if (!expr) throw new Error("未找到打包脚本(eval(function(p,a,c,k,e,d))");
  const { p, a, c, k } = _parsePackedArgs(expr);
  const unpacked = _unpack(p, a, c, k);
  const mm = unpacked.match(/var\s+__c0rst96\s*=\s*"([^"]*)"/);
  if (!mm) throw new Error("未找到 __c0rst96 加密数据");
  const payload = mm[1];

  const didM = html.match(/class="readerContainer"[^>]*data-id="(\d+)"/);
  const dataId = didM ? parseInt(didM[1], 10) : 0;
  const key = IMG_XOR_KEYS[dataId] || IMG_XOR_KEYS[0];

  const buf = base64ToBytes(payload);
  const kb = [];
  for (let i = 0; i < key.length; i++) kb.push(key.charCodeAt(i));
  const xored = [];
  for (let i = 0; i < buf.length; i++) xored.push(buf[i] ^ kb[i % kb.length]);
  const latin1 = xored.map((b) => String.fromCharCode(b)).join("");
  const jsonBytes = base64ToBytes(latin1);
  const jsonStr = utf8BytesToString(jsonBytes);
  const arr = JSON.parse(jsonStr);
  if (!Array.isArray(arr)) throw new Error("解密结果不是数组");
  return arr;
}

// ---- 源定义 ----

class RuManhuaMulti extends ComicSource {
  name = "如漫画多域";
  key = "rumanhua_multi";
  version = "2.0.3";
  minAppVersion = "1.6.0";

  // 共存版：url 置空，使原版「如漫画」的更新不会覆盖本副本
  // （key=rumanhua_multi 与原版 key=rumanhua 不同，App 中是两个独立源）
  url = null;

  // 默认走 HTTP：m.rumanhua2.com 的 HTTPS 证书在境内常被校验为不匹配（
  // expected m.rumanhua2.com，presented m.rumanhua.com / rumanhua.com），
  // HTTP 可直连。用户可在源设置里切换线路。
  settings = {
    base_url: {
      title: "访问地址",
      type: "select",
      options: [
        { value: "http://m.rumanhua2.com", text: "http://m.rumanhua2.com（默认，绕证书）" },
        { value: "http://www.rumanhua2.com", text: "http://www.rumanhua2.com" },
        { value: "https://m.rumanhua2.com", text: "https://m.rumanhua2.com（可能证书错误）" },
        { value: "https://www.rumanhua2.com", text: "https://www.rumanhua2.com（可能证书错误）" },
        { value: "http://m.rumanhua1.com", text: "http://m.rumanhua1.com（备用线路）" },
        { value: "http://www.rumanhua1.com", text: "http://www.rumanhua1.com（备用线路）" },
      ],
      default: "http://m.rumanhua2.com",
    },
  };

  get baseUrl() {
    return this.loadSetting("base_url") || "http://m.rumanhua2.com";
  }

  get baseHost() {
    try {
      return this.baseUrl.replace(/^https?:\/\//, "").replace(/\/+$/, "");
    } catch (e) {
      return "m.rumanhua2.com";
    }
  }

  pageHeaders() {
    return {
      "User-Agent":
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36",
      "Accept":
        "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
      "Accept-Language": "zh-CN,zh;q=0.9,en;q=0.8",
      "Referer": this.baseUrl + "/",
    };
  }

  async fetchBody(label, url) {
    let res = await Network.get(url, this.pageHeaders());
    // 部分线路会 302 跳转（如 https://rumanhua.com → http://www.rumanhua2.com），
    // Venera 默认不跟随重定向，这里手动跟随一次。
    if (res.status >= 300 && res.status < 400) {
      let loc = res.headers && (res.headers["location"] || res.headers["Location"]);
      if (loc) {
        if (loc.startsWith("/")) loc = this.baseUrl + loc;
        res = await Network.get(loc, this.pageHeaders());
      }
    }
    if (res.status !== 200) throw label + " 请求失败: " + res.status;
    return res.body;
  }

  // POST 表单请求（本站搜索 /s 走 POST+JSON，GET 时后端忽略参数、一律回 code:-1）
  async postForm(label, url, form) {
    let parts = [];
    for (let k in form) {
      if (!Object.prototype.hasOwnProperty.call(form, k)) continue;
      parts.push(encodeURIComponent(k) + "=" + encodeURIComponent(form[k]));
    }
    let bodyStr = parts.join("&");
    let headers = this.pageHeaders();
    headers["Content-Type"] = "application/x-www-form-urlencoded";
    let res = await Network.post(url, headers, Convert.encodeUtf8(bodyStr));
    if (res.status >= 300 && res.status < 400) {
      let loc = res.headers && (res.headers["location"] || res.headers["Location"]);
      if (loc) {
        if (loc.startsWith("/")) loc = this.baseUrl + loc;
        res = await Network.post(loc, headers, Convert.encodeUtf8(bodyStr));
      }
    }
    if (res.status !== 200) throw label + " 请求失败: " + res.status;
    return res.body;
  }

  // 从任意漫画卡片 <a> 解析出 Comic（首页 .mults / 排行 .rank-list 通用）
  parseComicAnchor(a) {
    if (!a) return null;
    let href = a.attributes["href"] || "";
    let m = href.match(/\/([A-Za-z0-9]+)\/?$/);
    if (!m) return null;
    let id = m[1];

    let img = a.querySelector("img");
    let cover = "";
    if (img) cover = img.attributes["data-src"] || img.attributes["src"] || "";

    let title = "";
    let h2 = a.querySelector("h2");
    if (h2) title = h2.text.trim();
    if (!title) {
      let ct = a.querySelector(".card-title");
      if (ct) title = ct.text.trim();
    }
    if (!title) title = a.attributes["title"] || id;

    // 副标题：取最后一个非 card-title 的 <p>（首页=最新话，排行=最新话/作者末行）
    let sub = "";
    let ps = a.querySelectorAll("p");
    for (let p of ps) {
      let t = p.text.trim();
      if (!t) continue;
      if (p.attributes && (p.attributes["class"] || "").indexOf("card-title") >= 0) continue;
      sub = t;
    }

    if (!cover) return null;
    return new Comic({ id: id, title: title, subTitle: sub, cover: cover });
  }

  // 分类/排行页 .rank-box .rank-list li
  parseRankList(html) {
    let doc = new HtmlDocument(html);
    let comics = [];
    let seen = {};
    for (let li of doc.querySelectorAll(".rank-box .rank-list li")) {
      let a = li.querySelector("a[href]");
      let c = this.parseComicAnchor(a);
      if (!c || seen[c.id]) continue;
      seen[c.id] = true;
      comics.push(c);
    }
    doc.dispose();
    return comics;
  }

  // 搜索 JSON → Comic[]（/s POST 的 data 形如 {id,name,imgurl,remarks}，remarks=最新话）
  jsonDataToComics(arr) {
    let comics = [];
    let seen = {};
    for (let it of arr) {
      if (!it || !it.id || seen[it.id]) continue;
      seen[it.id] = true;
      comics.push(
        new Comic({
          id: it.id,
          title: (it.name || "").trim() || it.id,
          subTitle: (it.remarks || "").trim(),
          cover: it.imgurl || "",
        })
      );
    }
    return comics;
  }

  // 搜索结果页（旧版 HTML 布局兜底，兼容 .rank-list 与 .mults 两种卡片）
  parseSearchList(html) {
    let doc = new HtmlDocument(html);
    let comics = [];
    let seen = {};
    const push = (c) => { if (c && !seen[c.id]) { seen[c.id] = true; comics.push(c); } };
    for (let li of doc.querySelectorAll(".rank-box .rank-list li")) push(this.parseComicAnchor(li.querySelector("a[href]")));
    for (let a of doc.querySelectorAll(".mults .mult-body li a")) push(this.parseComicAnchor(a));
    doc.dispose();
    return comics;
  }

  // 发现页：首页各 .mults 分区
  explore = [
    {
      title: "如漫画多域-首页",
      type: "singlePageWithMultiPart",
      load: async (page) => {
        let body = await this.fetchBody("home", this.baseUrl + "/");
        let doc = new HtmlDocument(body);
        let res = {};
        for (let sec of doc.querySelectorAll(".mults")) {
          let head = sec.querySelector(".mult-head");
          let title = head ? head.text.trim() : "推荐";
          let comics = [];
          let seen = {};
          for (let a of sec.querySelectorAll(".mult-body li a")) {
            let c = this.parseComicAnchor(a);
            if (!c || seen[c.id]) continue;
            seen[c.id] = true;
            comics.push(c);
          }
          if (comics.length) res[title] = comics;
        }
        doc.dispose();
        if (!Object.keys(res).length) res["首页"] = [];
        return res;
      },
    },
  ];

  // 分类 + 排行（站点 /sort/{n} 与 /rank/{n} 共用 .rank-list 卡片结构）
  category = {
    title: "如漫画多域",
    parts: [
      {
        name: "分类",
        type: "fixed",
        itemType: "category",
        categories: [
          "冒险", "热血", "都市", "玄幻", "悬疑", "耽美", "恋爱", "生活",
          "搞笑", "穿越", "修真", "后宫", "女主", "古风", "连载", "完结",
        ],
        categoryParams: [
          "sort/1", "sort/2", "sort/3", "sort/4", "sort/5", "sort/6",
          "sort/7", "sort/8", "sort/9", "sort/10", "sort/11", "sort/12",
          "sort/13", "sort/14", "sort/15", "sort/16",
        ],
      },
      {
        name: "排行榜",
        type: "fixed",
        itemType: "category",
        categories: ["精品榜", "人气榜", "推荐榜", "黑马榜", "最近更新", "新漫画"],
        categoryParams: ["rank/1", "rank/2", "rank/3", "rank/4", "rank/5", "rank/6"],
      },
    ],
    enableRankingPage: false,
  };

  categoryComics = {
    load: async (category, param, options, page) => {
      if (!param) return { comics: [], maxPage: 1 };
      let body = await this.fetchBody("category", this.baseUrl + "/" + param);
      let comics = this.parseRankList(body);
      // 站点「加载更多」为前端 AJAX，静态 HTML 仅含前 20 条
      return { comics: comics, maxPage: 1 };
    },
    optionList: [],
  };

  // 搜索：站点前端真实行为是 $.post("/s",{k:...})，返回 JSON
  // {"code":"200","data":[{id,name,imgurl,remarks}...]}；code=201 无结果、code=-1 参数错误。
  // 按响应体首字符自动分流：JSON → 新接口解析；HTML → 旧布局兜底。
  search = {
    load: async (keyword, options, page) => {
      let url = this.baseUrl + "/s";
      let body = await this.postForm("search", url, { k: keyword });
      let text = (body || "").replace(/^\uFEFF/, "").trim();
      let comics = [];
      if (text.startsWith("{")) {
        let json = null;
        try {
          json = JSON.parse(text);
        } catch (e) {
          json = null;
        }
        if (json && json.code === "200" && Array.isArray(json.data)) {
          comics = this.jsonDataToComics(json.data);
        }
        // code "201"（无结果）/ "-1"（关键词过短等）按空结果返回，避免抛错导致搜索页白屏
      } else {
        comics = this.parseSearchList(body);
      }
      return { comics: comics, maxPage: 1 };
    },
    optionList: [],
    enableTagsSuggestions: false,
  };

  // 单本漫画
  comic = {
    loadInfo: async (id) => {
      let body = await this.fetchBody("detail", this.baseUrl + "/" + id + "/");
      let doc = new HtmlDocument(body);

      let title = "";
      let nameEl = doc.querySelector(".book-name h1.name");
      if (nameEl) title = nameEl.text.trim();
      if (!title) {
        let og = doc.querySelector('meta[property="og:title"]');
        if (og) title = og.attributes["content"] || "";
      }
      if (!title) title = id;

      let cover = "";
      let coverImg = doc.querySelector(".book-cover img");
      if (coverImg) cover = coverImg.attributes["data-src"] || coverImg.attributes["src"] || "";
      if (!cover) {
        let og = doc.querySelector('meta[property="og:image"]');
        if (og) cover = og.attributes["content"] || "";
      }

      let author = "", status = "", update = "";
      for (let p of doc.querySelectorAll(".comic-info-detail p")) {
        let t = p.text.trim();
        if (t.startsWith("作者：")) author = t.replace("作者：", "").trim();
        else if (t.startsWith("状态：")) status = t.replace("状态：", "").trim();
        else if (t.startsWith("更新：")) update = t.replace("更新：", "").trim();
      }

      let description = "";
      let descEl = doc.querySelector(".cartoon-introduction p");
      if (descEl) description = descEl.text.trim();

      let tags = [];
      for (let s of doc.querySelectorAll(".comic-tags span")) {
        let t = s.text.trim();
        if (t) tags.push(t);
      }

      let chapters = {};
      for (let a of doc.querySelectorAll(".chaplist-box li a")) {
        let href = a.attributes["href"] || "";
        let m = href.match(/\/([A-Za-z0-9]+)\.html$/);
        if (!m) continue;
        let chTitle = a.text ? a.text.trim() : "";
        if (!chTitle) continue;
        chapters[m[1]] = chTitle;
      }

      doc.dispose();

      if (Object.keys(chapters).length === 0) throw "未解析到章节列表";

      let tagMap = {};
      if (author) tagMap["作者"] = [author];
      if (status) tagMap["状态"] = [status];
      if (update) tagMap["更新"] = [update];
      if (tags.length) tagMap["标签"] = tags;

      return new ComicDetails({
        title: title,
        subtitle: author,
        cover: cover,
        description: description,
        tags: tagMap,
        chapters: chapters,
      });
    },

    loadEp: async (comicId, epId) => {
      let url = this.baseUrl + "/" + comicId + "/" + epId + ".html";
      let body = await this.fetchBody("ep", url);
      let images = decryptChapterImages(body);
      if (!images.length) throw "章节图片列表为空";
      return { images: images };
    },

    onImageLoad: (url) => {
      return {
        url: url,
        headers: {
          "Referer": this.baseUrl + "/",
          "User-Agent":
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36",
          "Accept": "image/avif,image/webp,image/apng,image/*,*/*;q=0.8",
          "Accept-Language": "zh-CN,zh;q=0.9",
        },
      };
    },

    onThumbnailLoad: (url) => {
      return {
        url: url,
        headers: {
          "Referer": this.baseUrl + "/",
          "User-Agent":
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36",
          "Accept": "image/avif,image/webp,image/apng,image/*,*/*;q=0.8",
          "Accept-Language": "zh-CN,zh;q=0.9",
        },
      };
    },

    // 从外部链接识别漫画 id，兼容多个镜像域名
    link: {
      domains: ["rumanhua2.com", "rumanhua.com", "rumanhua1.com", "rumanhua.org"],
      linkToId: (url) => {
        let m = url.match(/rumanhua(?:1|2)?\.(?:com|org)\/([A-Za-z0-9]+)(?:\/|$)/);
        return m ? m[1] : null;
      },
    },
  };
}
