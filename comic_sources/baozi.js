
class Baozi extends ComicSource {
  // 此漫画源的名称
  name = "包子漫画";

  // 唯一标识符
  key = "baozi";

  version = "1.1.8";

  minAppVersion = "1.0.0";

  // 更新链接
  url = "https://cdn.jsdelivr.net/gh/rytzj2-spec/VeneraX-nagi@unify-app/comic_sources/baozi.js";

  settings = {
    language: {
      title: "简繁切换",
      type: "select",
      options: [
        { value: "cn", text: "简体" },
        { value: "tw", text: "繁體" },
      ],
      default: "cn",
    },
    domains: {
      title: "主域名",
      type: "select",
      options: [
        { value: "webmota.com" },
        { value: "kukuc.co" },
        { value: "twmanga.com" },
        { value: "dinnerku.com" },
      ],
      default: "webmota.com",
    },
    cdn_domains: {
      title: "图片线路",
      type: "select",
      options: [
        { value: "", text: "跟随站点" },
        { value: "s1.bzcdn.net" },
        { value: "s2.bzcdn.net" },
      ],
      default: "",
    },
    image_quality: {
      title: "图片质量",
      type: "select",
      options: [
        {
          value: "/w640",
          text: "640p"
        },
        {
          value: "",
          text: "原图"
        }
      ],
      default: "/w640",
    },
  };

  // 已失效的历史默认值回退到当前主站，保留其他已选线路。
  get lang() {
    return this.loadSetting("language") || this.settings.language.default;
  }

  get baseUrl() {
    const selected = this.loadSetting("domains");
    const domain = this.settings.domains.options.some((entry) => entry.value === selected)
      ? selected : this.settings.domains.default;
    return `https://${this.lang}.${domain}`;
  }

  /// 封面主机。static-tw.baozimh.com 在部分地区（如大陆）会返回 403，
  /// 因此默认改用与主站同域的 static-tw.bzmgcn.com；切换主域名时跟随。
  get coverHost() {
    let domain = this.loadSetting("domains") || this.settings.domains.default;
    const staticDomains = ["bzmgcn.com", "baozimhcn.com"];
    if (staticDomains.indexOf(domain) >= 0) {
      return `static-tw.${domain}`;
    }
    return "static-tw.bzmgcn.com";
  }

  /// 把站点返回的各类 static-tw 封面 URL 统一归到当前 coverHost，
  /// 避免 static-tw.baozimh.com 等主机在部分网络下 403。
  _normalizeCover(url) {
    if (!url) return url;
    return url.replace(/^https:\/\/static-tw\.[^/]+/, `https://${this.coverHost}`);
  }

  /// 响应头大小写不敏感取值（不同平台返回的头名大小写不一致）
  _headerValue(headers, name) {
    if (!headers) return "";
    const target = String(name).toLowerCase();
    for (const key in headers) {
      if (String(key).toLowerCase() === target) {
        const value = headers[key];
        return Array.isArray(value) ? value[0] || "" : value || "";
      }
    }
    return "";
  }

  _absoluteUrl(url) {
    if (!url) return "";
    if (/^https?:\/\//i.test(url)) return url;
    if (url.startsWith("//")) return "https:" + url;
    if (url.charAt(0) === "/") return this.baseUrl + url;
    return this.baseUrl + "/" + url;
  }

  /**
   * 发起 GET 并跟随 3xx 重定向。
   * Venera 的 Network.get 不会自动跟随重定向，而站点对短 comic_id 一律 302
   * 到带 _xxxxxx 后缀的规范 ID，不跟随就会拿到 302 空响应。
   */
  async _getFollowingRedirect(url, maxRedirects = 3) {
    let current = url;
    for (let i = 0; i < maxRedirects; i++) {
      const res = await Network.get(current);
      if (res.status >= 300 && res.status < 400) {
        const location = this._headerValue(res.headers, "location");
        if (!location) break;
        current = this._absoluteUrl(location);
        continue;
      }
      return res;
    }
    // 重定向链结束后仍未拿到 200，再请求一次拿到最终状态用于报错
    return await Network.get(current);
  }

  /// 账号
  /// 设置为null禁用账号功能
  account = {
    /// 登录
    /// 返回任意值表示登录成功
    login: async (account, pwd) => {
      let res = await Network.post(
        `${this.baseUrl}/api/bui/signin`,
        {
          "content-type":
            "multipart/form-data; boundary=----WebKitFormBoundaryFUNUxpOwyUaDop8s",
        },
        '------WebKitFormBoundaryFUNUxpOwyUaDop8s\r\nContent-Disposition: form-data; name="username"\r\n\r\n' +
        account +
        '\r\n------WebKitFormBoundaryFUNUxpOwyUaDop8s\r\nContent-Disposition: form-data; name="password"\r\n\r\n' +
        pwd +
        "\r\n------WebKitFormBoundaryFUNUxpOwyUaDop8s--\r\n"
      );
      if (res.status !== 200) {
        throw "Invalid status code: " + res.status;
      }
      let json = JSON.parse(res.body);
      let token = json.data;
      Network.setCookies(this.baseUrl, [
        new Cookie({
          name: "TSID",
          value: token,
          domain: this.loadSetting("domains") || this.settings.domains.default,
        }),
      ]);
      return "ok";
    },

    // 退出登录时将会调用此函数
    logout: function () {
      Network.deleteCookies(
        this.loadSetting("domains") || this.settings.domains.default
      );
    },

    get registerWebsite() {
      return `${this.baseUrl}/user/signup`;
    },
  };

  /// 解析漫画列表
  parseComic(e) {
    let url = e.querySelector("a").attributes["href"];
    let id = url.split("/").pop();
    let title = e.querySelector("h3").text.trim();
    let cover = this._normalizeCover(
      e.querySelector("a > amp-img").attributes["src"]
    );
    let tags = e.querySelectorAll("div.tabs > span").map((e) => e.text.trim());
    let description = e.querySelector("small").text.trim();
    return {
      id: id,
      title: title,
      cover: cover,
      tags: tags,
      description: description,
    };
  }

  parseJsonComic(e) {
    return {
      id: e.comic_id,
      title: e.name,
      subTitle: e.author,
      cover: `https://${this.coverHost}/cover/${e.topic_img}?w=285&h=375&q=100`,
      tags: e.type_names,
    };
  }

  /// 探索页面
  /// 一个漫画源可以有多个探索页面
  explore = [
    {
      /// 标题
      /// 标题同时用作标识符, 不能重复
      title: "包子漫画",

      /// singlePageWithMultiPart 或者 multiPageComicList
      type: "singlePageWithMultiPart",

      load: async () => {
        var res = await this._getFollowingRedirect(this.baseUrl);
        if (res.status !== 200) {
          throw "Invalid status code: " + res.status;
        }
        let document = new HtmlDocument(res.body);
        let parts = document.querySelectorAll("div.index-recommend-items");
        let result = {};
        for (let part of parts) {
          let title = part.querySelector("div.catalog-title").text.trim();
          let comics = part
            .querySelectorAll("div.comics-card")
            .map((e) => this.parseComic(e));
          if (comics.length > 0) {
            result[title] = comics;
          }
        }
        return result;
      },
    },
  ];

  /// 分类页面
  /// 一个漫画源只能有一个分类页面, 也可以没有, 设置为null禁用分类页面
  category = {
    /// 标题, 同时为标识符, 不能与其他漫画源的分类页面重复
    title: "包子漫画",
    parts: [
      {
        name: "类型",

        // fixed 或者 random
        // random用于分类数量相当多时, 随机显示其中一部分
        type: "fixed",

        // 如果类型为random, 需要提供此字段, 表示同时显示的数量
        // randomNumber: 5,

        categories: [
          "全部",
          "恋爱",
          "纯爱",
          "古风",
          "异能",
          "悬疑",
          "剧情",
          "科幻",
          "奇幻",
          "玄幻",
          "穿越",
          "冒险",
          "推理",
          "武侠",
          "格斗",
          "战争",
          "热血",
          "搞笑",
          "大女主",
          "都市",
          "总裁",
          "后宫",
          "日常",
          "韩漫",
          "少年",
          "其它",
        ],

        // category或者search
        // 如果为category, 点击后将进入分类漫画页面, 使用下方的`categoryComics`加载漫画
        // 如果为search, 将进入搜索页面
        itemType: "category",

        // 若提供, 数量需要和`categories`一致, `categoryComics.load`方法将会收到此参数
        categoryParams: [
          "all",
          "lianai",
          "chunai",
          "gufeng",
          "yineng",
          "xuanyi",
          "juqing",
          "kehuan",
          "qihuan",
          "xuanhuan",
          "chuanyue",
          "mouxian",
          "tuili",
          "wuxia",
          "gedou",
          "zhanzheng",
          "rexie",
          "gaoxiao",
          "danuzhu",
          "dushi",
          "zongcai",
          "hougong",
          "richang",
          "hanman",
          "shaonian",
          "qita",
        ],
      },
    ],
    enableRankingPage: false,
  };

  /// 分类漫画页面, 即点击分类标签后进入的页面
  categoryComics = {
    load: async (category, param, options, page) => {
      let res = await this._getFollowingRedirect(
        `${this.baseUrl}/api/bzmhq/amp_comic_list?type=${param}&region=${options[0]}&state=${options[1]}&filter=%2a&page=${page}&limit=36&language=${this.lang}&__amp_source_origin=${this.baseUrl}`
      );
      if (res.status !== 200) {
        throw "Invalid status code: " + res.status;
      }
      let maxPage = null;
      let json = JSON.parse(res.body);
      if (!json.next) {
        maxPage = page;
      }
      return {
        comics: json.items.map((e) => this.parseJsonComic(e)),
        maxPage: maxPage,
      };
    },
    // 提供选项
    optionList: [
      {
        options: ["all-全部", "cn-国漫", "jp-日本", "kr-韩国", "en-欧美"],
      },
      {
        options: ["all-全部", "serial-连载中", "pub-已完结"],
      },
    ],
  };

  /// 搜索
  search = {
    load: async (keyword, options, page) => {
      // 中文关键词必须做百分号编码，站点对未编码查询返回 400
      let res = await this._getFollowingRedirect(
        `${this.baseUrl}/search?q=${encodeURIComponent(keyword)}`
      );
      if (res.status !== 200) {
        throw "Invalid status code: " + res.status;
      }
      let document = new HtmlDocument(res.body);
      let comics = document
        .querySelectorAll("div.comics-card")
        .map((e) => this.parseComic(e));
      return {
        comics: comics,
        maxPage: 1,
      };
    },

    // 提供选项
    optionList: [],
  };

  /// 收藏
  favorites = {
    /// 是否为多收藏夹
    multiFolder: false,
    /// 添加或者删除收藏
    addOrDelFavorite: async (comicId, folderId, isAdding) => {
      if (!isAdding) {
        let res = await Network.post(
          `${this.baseUrl}/user/operation_v2?op=del_bookmark&comic_id=${comicId}`
        );
        if (!res.status || res.status >= 400) {
          throw "Invalid status code: " + res.status;
        }
        return "ok";
      } else {
        let res = await Network.post(
          `${this.baseUrl}/user/operation_v2?op=set_bookmark&comic_id=${comicId}&chapter_slot=0`
        );
        if (!res.status || res.status >= 400) {
          throw "Invalid status code: " + res.status;
        }
        return "ok";
      }
    },
    // 加载收藏夹, 仅当multiFolder为true时有效
    // 当comicId不为null时, 需要同时返回包含该漫画的收藏夹
    loadFolders: null,
    /// 加载漫画
    loadComics: async (page, folder) => {
      let res = await this._getFollowingRedirect(
        `${this.baseUrl}/user/my_bookshelf`
      );
      if (res.status !== 200) {
        throw "Invalid status code: " + res.status;
      }
      let document = new HtmlDocument(res.body);
      function parseComic(e) {
        let title = e.querySelector("h4 > a").text.trim();
        let url = e.querySelector("h4 > a").attributes["href"];
        let id = url.split("/").pop();
        let author = e
          .querySelector("div.info > ul")
          .children[1].text.split("：")[1]
          .trim();
        let description = e
          .querySelector("div.info > ul")
          .children[4].children[0].text.trim();

        return {
          id: id,
          title: title,
          subTitle: author,
          description: description,
          cover: this._normalizeCover(
            e.querySelector("amp-img").attributes["src"]
          ),
        };
      }
      let comics = document
        .querySelectorAll("div.bookshelf-items")
        .map((e) => parseComic(e));
      return {
        comics: comics,
        maxPage: 1,
      };
    },
  };

  /// 单个漫画相关
  comic = {
    // 加载漫画信息
    loadInfo: async (id) => {
      // 短 ID（分类/搜索接口返回）会被 302 到带 _xxxxxx 后缀的规范 ID，必须跟随
      let res = await this._getFollowingRedirect(`${this.baseUrl}/comic/${id}`);
      if (res.status !== 200) {
        throw "Invalid status code: " + res.status;
      }
      let document = new HtmlDocument(res.body);

      let title = document.querySelector("h1.comics-detail__title").text.trim();
      let cover = this._normalizeCover(
        document.querySelector("div.l-content > div > div > amp-img").attributes[
          "src"
        ]
      );
      let author = document
        .querySelector("h2.comics-detail__author")
        .text.trim();
      let tags = document
        .querySelectorAll("div.tag-list > span")
        .map((e) => e.text.trim());
      tags = [...tags.filter((e) => e !== "")];
      let updateTime = document
        .querySelector("div.supporting-text > div > span > em")
        ?.text.trim()
        .replace("(", "")
        .replace(")", "");
      if (!updateTime) {
        const getLastChapterText = () => {
          // 合并所有章节容器（处理可能存在多个列表的情况）
          const containers = [
            ...document.querySelectorAll(
              "#chapter-items, #chapters_other_list"
            ),
          ];
          let allChapters = [];
          containers.forEach((container) => {
            const chapters = container.querySelectorAll(".comics-chapters > a");
            allChapters.push(...Array.from(chapters));
          });
          const lastChapter = allChapters[allChapters.length - 1];
          return (
            lastChapter?.querySelector("div > span")?.text.trim() ||
            "暂无更新信息"
          );
        };
        updateTime = getLastChapterText();
      }
      let description = document
        .querySelector("p.comics-detail__desc")
        .text.trim();
      let chapters = new Map();
      let i = 0;
      for (let c of document.querySelectorAll(
        "div#chapter-items > div.comics-chapters > a > div > span"
      )) {
        chapters.set(i.toString(), c.text.trim());
        i++;
      }
      for (let c of document.querySelectorAll(
        "div#chapters_other_list > div.comics-chapters > a > div > span"
      )) {
        chapters.set(i.toString(), c.text.trim());
        i++;
      }
      if (i === 0) {
        // 将倒序的最新章节反转
        const spans = Array.from(
          document.querySelectorAll("div.comics-chapters > a > div > span")
        ).reverse();
        for (let c of spans) {
          chapters.set(i.toString(), c.text.trim());
          i++;
        }
      }
      let recommend = [];
      for (let c of document.querySelectorAll("div.recommend--item")) {
        if (c.querySelectorAll("div.tag-comic").length > 0) {
          let title = c.querySelector("span").text.trim();
          let cover = this._normalizeCover(
            c.querySelector("amp-img").attributes["src"]
          );
          let url = c.querySelector("a").attributes["href"];
          let id = url.split("/").pop();
          recommend.push({
            id: id,
            title: title,
            cover: cover,
          });
        }
      }
      // updateTime 将 Y年 M月 D日 转化为 Y-M-D
      let updateDate = updateTime
        .replace(/年/g, "-")
        .replace(/月/g, "-")
        .replace(/日/g, "");

      return new ComicDetails({
        title: title,
        cover: cover,
        description: description,
        tags: {
          作者: [author],
          标签: tags,
        },
        chapters: chapters,
        recommend: recommend,
        updateTime: updateDate,
      });
    },
    loadEp: async (comicId, epId) => {
      const url = `${this.baseUrl}/comic/chapter/${encodeURIComponent(comicId)}/0_${encodeURIComponent(epId)}.html`;
      const res = await this._getFollowingRedirect(url);
      if (res.status !== 200) throw `章节加载失败（HTTP ${res.status}）`;
      const doc = new HtmlDocument(res.body);
      const selected = this.loadSetting("cdn_domains");
      const quality = this.loadSetting("image_quality") === "/w640" ? "/w640" : "";
      const images = [];
      for (const image of doc.querySelectorAll(".comic-contain .comic-contain__item")) {
        const value = image.attributes["data-src"] || image.attributes["src"];
        const match = value?.match(/^(?:https:)?\/\/([^/]+)(\/(?:w640\/)?[a-z]comic\/[^\s]+)$/i);
        if (!match) continue;
        const host = selected === "s1.bzcdn.net" || selected === "s2.bzcdn.net" ? selected : match[1];
        const path = match[2].replace(/^\/w640(?=\/)/, "");
        images.push(`https://${host}${quality}${path}`);
      }
      if (!images.length) throw "来源没有返回可读图片，请检查该章节是否公开或切换来源线路";
      return { images: [...new Set(images)] };
    },
  };

  /// 图片请求头：CDN 可能校验防盗链，统一带上来源
  onImageLoad(url, comicId, epId) {
    return {
      headers: {
        Referer: this.baseUrl + "/",
        "User-Agent":
          "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36",
      },
    };
  }

  /// 封面（static-tw 域）同样带 Referer，避免防盗链拦截
  onThumbnailLoad(url) {
    return {
      headers: {
        Referer: this.baseUrl + "/",
        "User-Agent":
          "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36",
      },
    };
  }
}
