
/** @type {import('./_venera_.js')} */
class WuManTianTang extends ComicSource {
  name = "污漫天堂";

  key = "wumantiantang";

  version = "1.0.0";

  minAppVersion = "1.4.0";

  url = "";

  // 静态成员只能在类上访问（this.ua 在实例上是 undefined）
  static ua =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36";

  // ============ 设置项 ============
  settings = {
    domain: {
      title: "访问域名",
      type: "select",
      options: [
        { value: "wmtt5.com", text: "wmtt5.com（默认）" },
        { value: "wmtt3.com", text: "wmtt3.com" },
        { value: "wmtt6.com", text: "wmtt6.com" },
        { value: "wmtt7.com", text: "wmtt7.com" },
        { value: "wmtt8.com", text: "wmtt8.com" },
        { value: "wmtt9.com", text: "wmtt9.com" },
      ],
      default: "wmtt5.com",
    },
  };

  get baseUrl() {
    const d = this.loadSetting("domain") || this.settings.domain.default;
    return "https://" + d;
  }

  /**
   * 统一请求头。关键点：
   *  - Accept-Encoding 只保留 gzip, deflate（去掉 br），避免触发 Cloudflare 托管质询
   *  - 保持单一固定的浏览器化头部，不按页面改 Sec-Fetch-* 等字段
   */
  get headers() {
    return {
      "User-Agent": WuManTianTang.ua,
      Accept:
        "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
      "Accept-Language": "zh-CN,zh;q=0.9",
      "Accept-Encoding": "gzip, deflate",
      Referer: this.baseUrl + "/",
    };
  }

  // ============ 通用工具 ============

  absoluteUrl(url) {
    if (!url) return "";
    if (url.startsWith("http://") || url.startsWith("https://")) return url;
    if (url.startsWith("//")) return "https:" + url;
    if (url.startsWith("/")) return this.baseUrl + url;
    return url;
  }

  // /mangaread/66781368.html -> 66781368
  comicIdFromHref(href) {
    if (!href) return "";
    const m = href.match(/\/mangaread\/(\d+)\.html/);
    return m ? m[1] : "";
  }

  // /mangaread/66781368/ZdPojmvdxEdkTEnETnct.html -> ZdPojmvdxEdkTEnETnct
  epIdFromHref(href) {
    if (!href) return "";
    const m = href.match(/\/mangaread\/\d+\/([^/]+)\.html/);
    return m ? m[1] : "";
  }

  /**
   * 解析列表卡片（首页分区/分类/搜索共用同一结构）
   * @param item 单个 a.module-poster-item.module-item 元素
   */
  parseCard(item) {
    const href = item.attributes.href || "";
    const id = this.comicIdFromHref(href);
    if (!id) return null;

    let cover = "";
    const img = item.querySelector("img");
    if (img) {
      cover = img.attributes["data-original"] || img.attributes.src || "";
    }

    const titleEl = item.querySelector(".module-poster-item-title");
    let title = titleEl ? titleEl.text.trim() : "";
    // 排行页标题形如 "<b>1</b>. 秘密教学"，text 会展开成 "1. 秘密教学"。
    // 仅当确实存在 <b> 序号元素时才剥掉前缀，避免误伤正常标题。
    if (titleEl && titleEl.querySelector("b")) {
      title = title.replace(/^\d+\s*\.\s*/, "").trim();
    }
    if (!title) return null;

    let subTitle = "";
    const noteEl = item.querySelector(".module-item-note");
    if (noteEl) subTitle = noteEl.text.trim();

    return new Comic({
      id,
      title,
      subTitle,
      cover: this.absoluteUrl(cover),
    });
  }

  parseCards(root) {
    const items = root.querySelectorAll(".module-poster-item.module-item");
    const comics = [];
    for (const it of items) {
      const c = this.parseCard(it);
      if (c) comics.push(c);
    }
    return comics;
  }

  /**
   * 从分页区解析最大页码。
   * 分页容器为 <div id="page">，页码链接 href 形如 .../page/{n}。
   * 取「当前页」与「所有页码链接」的最大值；无分页区则视为只有 1 页。
   */
  parseMaxPage(doc, currentPage) {
    let max = currentPage > 0 ? currentPage : 1;
    const pageEl = doc.querySelector("#page");
    if (pageEl) {
      const links = pageEl.querySelectorAll("a");
      for (const a of links) {
        const href = a.attributes.href || "";
        const m = href.match(/\/page\/(\d+)/);
        if (m) {
          const n = parseInt(m[1], 10);
          if (n > max) max = n;
        }
      }
      // 当前页高亮是 <span class="page-current">N</span>
      const cur = pageEl.querySelector(".page-current");
      if (cur) {
        const n = parseInt((cur.text || "").trim(), 10);
        if (n > max) max = n;
      }
    }
    return max;
  }

  /** 拉取一个列表页并解析为 {comics, maxPage} */
  async loadListPage(url, page) {
    const res = await Network.get(url, this.headers);
    if (res.status !== 200) {
      throw "请求失败: " + res.status;
    }
    const doc = new HtmlDocument(res.body);
    const comics = this.parseCards(doc);
    const maxPage = this.parseMaxPage(doc, page);
    doc.dispose();
    return { comics, maxPage };
  }

  // ============ 探索页（多分区） ============
  explore = [
    {
      title: "污漫天堂",
      type: "multiPartPage",

      /**
       * 各分区使用独立 URL，解析逻辑完全统一；
       * 串行请求避免并发突发触发 Cloudflare 风控。
       * @returns {Promise<{title: string, comics: Comic[]}[]>}
       */
      load: async () => {
        const parts = [
          { title: "热门漫画", url: this.baseUrl + "/mangarank" },
          { title: "最新更新", url: this.baseUrl + "/newmanga" },
          { title: "站长推荐", url: this.baseUrl + "/newmanga/recommend" },
          { title: "新上架漫画", url: this.baseUrl + "/newmanga/newmanga" },
          {
            title: "已完结漫画",
            url: this.baseUrl + "/mangacata/all/ob/time/st/completed",
          },
        ];
        const result = [];
        for (const part of parts) {
          try {
            const res = await Network.get(part.url, this.headers);
            if (res.status !== 200) continue;
            const doc = new HtmlDocument(res.body);
            const comics = this.parseCards(doc);
            doc.dispose();
            if (comics.length > 0) {
              result.push({ title: part.title, comics });
            }
          } catch (e) {
            // 单个分区失败不影响其他分区
          }
        }
        return result;
      },
    },
  ];

  // ============ 分类 ============
  category = {
    title: "污漫天堂",
    parts: [
      {
        name: "分类",
        type: "fixed",
        categories: ["全部", "韩漫", "日漫", "3D漫画", "真人漫画", "短篇"],
        itemType: "category",
        categoryParams: [
          "cata-all",
          "cata-韩漫",
          "cata-日漫",
          "cata-3D漫画",
          "cata-真人漫画",
          "cata-短篇",
        ],
      },
      {
        name: "进度",
        type: "fixed",
        categories: ["全部", "已完结", "更新中"],
        itemType: "category",
        categoryParams: ["st-all", "st-completed", "st-serialized"],
      },
      {
        name: "排序",
        type: "fixed",
        categories: ["按时间", "按人气"],
        itemType: "category",
        categoryParams: ["ob-time", "ob-hits"],
      },
    ],
    enableRankingPage: false,
  };

  // ============ 分类漫画列表 ============
  categoryComics = {
    /**
     * @param category {string} 当前 part 选中的显示名
     * @param param {string?} 当前 part 选中的 categoryParam（cata- / st- / ob- 前缀）
     * @param options {string[]} 其他 part 选中的 categoryParam
     * @param page {number}
     */
    load: async (category, param, options, page) => {
      const raw = [];
      if (param) raw.push(param);
      if (Array.isArray(options)) {
        for (const o of options) {
          if (o) raw.push(o);
        }
      }
      let cata = "all";
      let st = "all";
      let ob = "time";
      for (const r of raw) {
        if (r.startsWith("cata-")) cata = r.slice(5);
        else if (r.startsWith("st-")) st = r.slice(3);
        else if (r.startsWith("ob-")) ob = r.slice(3);
      }
      const url =
        this.baseUrl +
        "/mangacata/" +
        encodeURIComponent(cata) +
        "/ob/" +
        ob +
        "/st/" +
        st +
        "/page/" +
        page;
      return await this.loadListPage(url, page);
    },
  };

  // ============ 搜索 ============
  search = {
    /**
     * @param keyword {string}
     * @param options {string[]}
     * @param page {number}
     */
    load: async (keyword, options, page) => {
      const kw = encodeURIComponent(keyword);
      const url =
        this.baseUrl + "/searchmanga/" + kw + "/ob/time/st/all/page/" + page;
      return await this.loadListPage(url, page);
    },
  };

  // ============ 漫画详情与章节 ============
  comic = {
    /**
     * @param id {string} 漫画 ID（数字串）
     * @returns {Promise<ComicDetails>}
     */
    loadInfo: async (id) => {
      const url = this.baseUrl + "/mangaread/" + id + ".html";
      const res = await Network.get(url, this.headers);
      if (res.status !== 200) {
        throw "Invalid status code: " + res.status;
      }
      const doc = new HtmlDocument(res.body);

      // 标题：<h1>《狱火重生》</h1>
      let title = "";
      const titleEl = doc.querySelector("h1");
      if (titleEl) title = titleEl.text.trim().replace(/[《》]/g, "").trim();

      // 封面：.module-info-poster 内的 img[data-original]
      let cover = "";
      const posterEl = doc.querySelector(".module-info-poster");
      if (posterEl) {
        const img = posterEl.querySelector("img");
        if (img) {
          cover = img.attributes["data-original"] || img.attributes.src || "";
        }
      }
      if (!cover) {
        const anyImg = doc.querySelector(".module-item-pic img");
        if (anyImg) {
          cover = anyImg.attributes["data-original"] || anyImg.attributes.src || "";
        }
      }
      cover = this.absoluteUrl(cover);

      // 分类与标签：两组 .module-info-tag-link，第一组是分类，第二组是标签
      const cataTags = [];
      const normalTags = [];
      const tagGroups = doc.querySelectorAll(".module-info-tag-link");
      let gi = 0;
      for (const group of tagGroups) {
        const links = group.querySelectorAll("a");
        for (const a of links) {
          const t = a.text.trim();
          if (!t) continue;
          if (gi === 0) cataTags.push(t);
          else normalTags.push(t);
        }
        gi += 1;
      }

      // 简介
      let description = "";
      const descEl = doc.querySelector(".module-info-introduction-content");
      if (descEl) description = descEl.text.trim();

      // 作者 / 更新状态：.module-info-item 里 "作者：" "更新："
      let author = "";
      let status = "";
      const infoItems = doc.querySelectorAll(".module-info-item");
      for (const item of infoItems) {
        const labelEl = item.querySelector(".module-info-item-title");
        const contentEl = item.querySelector(".module-info-item-content");
        if (!labelEl || !contentEl) continue;
        const label = labelEl.text.trim();
        // 内容尾部常带分隔斜杠与换行空白，需清理：
        //   "Hoshi&黑兔\n\t\t\t\t/\n" -> "Hoshi&黑兔"
        const value = contentEl.text
          .replace(/\s*\/\s*$/, "")
          .replace(/\s+/g, " ")
          .trim();
        if (!value) continue;
        if (label.indexOf("作者") >= 0) author = value;
        else if (label.indexOf("更新") >= 0) status = value;
      }

      // 章节：a.module-play-list-link -> /mangaread/{id}/{token}.html
      // 用 Map 保持插入顺序（与站点列表顺序一致：Preview、第1話、第2話…）
      const chapters = new Map();
      const chapterLinks = doc.querySelectorAll("a.module-play-list-link");
      for (const cl of chapterLinks) {
        const epId = this.epIdFromHref(cl.attributes.href || "");
        if (!epId) continue;
        const span = cl.querySelector("span");
        let chTitle = span ? span.text.trim() : cl.text.trim();
        if (!chTitle) chTitle = cl.attributes.title || epId;
        if (!chapters.has(epId)) chapters.set(epId, chTitle);
      }

      doc.dispose();

      const tags = {};
      if (cataTags.length) tags["分类"] = cataTags;
      if (normalTags.length) tags["标签"] = normalTags;
      if (status) tags["状态"] = [status];

      return new ComicDetails({
        title,
        subTitle: author,
        cover,
        description,
        tags,
        chapters,
      });
    },

    /**
     * 加载章节图片。
     * 阅读页结构：<div class="img-wrap"><img data-src="https://img.nnpic.xyz/..."></div>
     * 图片为明文 URL，无加密；域名实测统一为 img.nnpic.xyz。
     * @param comicId {string}
     * @param epId {string} 章节 token
     * @returns {Promise<{images: string[]}>}
     */
    loadEp: async (comicId, epId) => {
      const url = this.baseUrl + "/mangaread/" + comicId + "/" + epId + ".html";
      const res = await Network.get(url, this.headers);
      if (res.status !== 200) {
        throw "Invalid status code: " + res.status;
      }
      const doc = new HtmlDocument(res.body);
      const images = [];
      const wraps = doc.querySelectorAll(".img-wrap");
      for (const w of wraps) {
        const img = w.querySelector("img");
        if (!img) continue;
        const src = img.attributes["data-src"] || img.attributes.src || "";
        if (src) images.push(this.absoluteUrl(src));
      }
      // 兜底：若 .img-wrap 结构变更，直接扫所有 img[data-src]
      if (images.length === 0) {
        const all = doc.querySelectorAll("img");
        for (const img of all) {
          const src = img.attributes["data-src"] || "";
          if (src && src.indexOf("http") === 0) images.push(src);
        }
      }
      doc.dispose();
      return { images };
    },

    // 图片域名实测无防盗链（带/不带 Referer 均 200），仍统一带上以对齐浏览器行为
    onImageLoad: (url, comicId, epId) => {
      return {
        headers: {
          Referer: this.baseUrl + "/",
          "User-Agent": WuManTianTang.ua,
        },
      };
    },

    onThumbnailLoad: (url) => {
      return {
        headers: {
          Referer: this.baseUrl + "/",
          "User-Agent": WuManTianTang.ua,
        },
      };
    },
  };
}
