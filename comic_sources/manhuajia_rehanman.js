

class ManHuaJia extends ComicSource {
    name = "漫画家";
    key = "rehanman_manhuajia";
    // ⚠️ 必须是 semver 字符串，不能写 int。int 会让 ComicSource 构造抛
    // "type 'int' is not a subtype of type 'String'"。
    version = "1.0.1";
    minAppVersion = "1.0.0";
    url = "";

    static WEB_HOST = "https://rehanman.com";
    static DEFAULT_API = "https://api.rehanman.com/manga-graphql";
    static DEFAULT_CDN = "https://img.rehanman.com/uploads/data/china18sky";
    static UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";

    // GraphQL 查询（与站点前端所用查询一致, 已实测）
    static ENTRIES_QUERY = "query entries($inputs: InputEntries){entries(inputs:$inputs){docs{_id,title,alt_title,description,title_normalized,adult,released_year,status,thumbnail,type,authors{name},genres{name,uid},volumes{chapters_count}},totalPages,limit,totalDocs,page}}";
    static ENTRY_QUERY = "query entry($inputs: InputEntries){entry(inputs:$inputs){_id,title,alt_title,description,title_normalized,released_year,status,thumbnail,rating,authors{name},genres{name,uid},entries_data{chapters{name,title,index,images}}}}";
    static CONFIG_QUERY = "query configDomain($inputs: InputConfigDomain){configDomain(inputs:$inputs){domain,cdn_images}}";

    settings = {
        api_url: {
            title: "API地址（一般无需修改）",
            type: "input",
            validator: null,
            default: "https://api.rehanman.com/manga-graphql",
        },
        image_cdn: {
            title: "图片CDN",
            type: "select",
            options: [
                { value: "auto", text: "自动（推荐）" },
                { value: "https://img.rehanman.com/uploads/data/china18sky", text: "img.rehanman.com" },
            ],
            default: "auto",
        },
    };

    init() {
        // 启动时尝试通过 configDomain 动态发现图片 CDN（失败则回退硬编码默认值）
        this._refreshImageCdn().catch(() => {});
    }

    async _refreshImageCdn() {
        const s = this.loadSetting("image_cdn");
        if (s && s !== "auto") return;
        const json = await this._post({
            query: ManHuaJia.CONFIG_QUERY,
            variables: { inputs: { domain: "rehanman.com", typeConfig: "config", type: "domain" } },
        });
        const cdn = json && json.data && json.data.configDomain && json.data.configDomain.cdn_images;
        if (typeof cdn === "string" && cdn.indexOf("http") === 0) {
            this.saveData("_image_cdn", cdn);
        }
    }

    get apiUrl() {
        const s = this.loadSetting("api_url");
        return (typeof s === "string" && s.indexOf("http") === 0) ? s : ManHuaJia.DEFAULT_API;
    }

    get imageCdn() {
        const s = this.loadSetting("image_cdn");
        if (typeof s === "string" && s.indexOf("http") === 0) return s;
        const cached = this.loadData("_image_cdn");
        if (typeof cached === "string" && cached.indexOf("http") === 0) return cached;
        return ManHuaJia.DEFAULT_CDN;
    }

    async _post(payload) {
        const res = await Network.post(this.apiUrl, {
            "Content-Type": "application/json",
            "Accept": "application/json",
            "User-Agent": ManHuaJia.UA,
            "Origin": ManHuaJia.WEB_HOST,
            "Referer": ManHuaJia.WEB_HOST + "/",
        }, Convert.encodeUtf8(JSON.stringify(payload)));
        if (res.status !== 200) {
            throw new Error("API请求失败: HTTP " + res.status + " (" + this.apiUrl + ")");
        }
        let json;
        try {
            json = JSON.parse(res.body);
        } catch (e) {
            throw new Error("API响应不是有效JSON (len=" + (res.body || "").length + ")");
        }
        if (json.errors && json.errors.length > 0) {
            throw new Error("API错误: " + (json.errors[0].message || "unknown"));
        }
        return json;
    }

    // 统一的列表查询: 返回 {docs, totalPages, totalDocs, page}
    async _entriesQuery(inputs) {
        const json = await this._post({
            query: ManHuaJia.ENTRIES_QUERY,
            variables: { inputs: inputs },
        });
        const e = json && json.data && json.data.entries;
        return {
            docs: (e && Array.isArray(e.docs)) ? e.docs : [],
            totalPages: (e && e.totalPages) ? e.totalPages : 1,
            totalDocs: (e && e.totalDocs) ? e.totalDocs : 0,
            page: (e && e.page) ? e.page : (inputs.page || 1),
        };
    }

    async _entryQuery(titleNormalized) {
        const json = await this._post({
            query: ManHuaJia.ENTRY_QUERY,
            variables: { inputs: { title_normalized: String(titleNormalized) } },
        });
        const e = json && json.data && json.data.entry;
        if (!e) {
            throw new Error("未找到漫画: " + titleNormalized);
        }
        return e;
    }

    _imageUrl(path) {
        if (!path) return "";
        if (typeof path === "string" && path.indexOf("http") === 0) return path;
        return this.imageCdn.replace(/\/+$/, "") + "/" + String(path).replace(/^\/+/, "");
    }

    _docToComic(doc) {
        if (!doc || !doc.title_normalized) return null;
        let chapters = 0;
        if (doc.volumes && doc.volumes.length) {
            for (const v of doc.volumes) {
                chapters += (v && v.chapters_count) ? v.chapters_count : 0;
            }
        }
        const author = (doc.authors || []).map(function (a) { return a && a.name; })
            .filter(Boolean).join(" / ");
        const parts = [];
        parts.push(doc.status === "completed" ? "完结" : "连载中");
        if (chapters > 0) parts.push(chapters + "话");
        if (author) parts.push(author);
        return new Comic({
            id: String(doc.title_normalized),
            title: doc.title || "",
            subTitle: parts.join(" · "),
            cover: this._imageUrl(doc.thumbnail),
            tags: (doc.genres || []).map(function (g) { return g && g.name; }).filter(Boolean),
        });
    }

    explore = [
        {
            title: "漫画家",
            type: "multiPartPage",
            load: async (page) => {
                const data = await this._entriesQuery({ type: "new", limit: 30, page: page });
                const comics = data.docs.map(d => this._docToComic(d)).filter(Boolean);
                if (comics.length === 0) return [];
                return [{ title: "最新上架", comics: comics }];
            },
        },
    ];

    // 题材列表来自 GraphQL genres 查询 (2026-09-03 实测 28 个, 剔除空名与法文标签)
    category = {
        title: "漫画家",
        parts: [
            {
                name: "题材",
                type: "fixed",
                itemType: "category",
                categories: [
                    "全部", "正妹", "恋爱", "肉慾", "浪漫", "女大生", "狗血剧",
                    "巨乳", "调教", "3D", "大尺度", "动作", "不伦", "校园",
                    "同居", "好友", "有夫之妇", "后宫", "耽美", "日漫", "出版漫画",
                ],
                categoryParams: [
                    "", "正妹", "恋爱", "肉慾", "浪漫", "女大生", "狗血剧",
                    "巨乳", "调教", "3D", "大尺度", "动作", "不伦", "校园",
                    "同居", "好友", "有夫之妇", "后宫", "耽美", "日漫", "出版漫画",
                ],
            },
        ],
        enableRankingPage: false,
    };

    categoryComics = {
        load: async (category, param, options, page) => {
            // param 为 categoryParams 对应值: "" 表示全部
            let genre = param;
            if (genre === undefined || genre === null) {
                genre = (category === "全部") ? "" : category;
            }
            const inputs = { limit: 30, page: page };
            if (!genre) {
                inputs.type = "new";
            } else {
                inputs.type = "genres";
                inputs.genre = genre;
                inputs.genres = [genre];
            }
            const data = await this._entriesQuery(inputs);
            const comics = data.docs.map(d => this._docToComic(d)).filter(Boolean);
            return { comics: comics, maxPage: Math.max(1, data.totalPages) };
        },
    };

    search = {
        load: async (keyword, options, page) => {
            const kw = String(keyword || "").trim();
            if (!kw) return { comics: [], maxPage: 1 };
            const data = await this._entriesQuery({
                type: "search",
                key: kw,
                limit: 30,
                page: page,
            });
            const comics = data.docs.map(d => this._docToComic(d)).filter(Boolean);
            return { comics: comics, maxPage: Math.max(1, data.totalPages) };
        },
    };

    comic = {
        loadInfo: async (id) => {
            const e = await this._entryQuery(id);
            const chs = (e.entries_data && e.entries_data.chapters) ? e.entries_data.chapters : [];
            const chapters = new Map();
            for (const c of chs) {
                if (!c) continue;
                const cid = String(c.index);
                const cname = c.name || c.title || ("第" + (c.index + 1) + "话");
                chapters.set(cid, cname);
            }
            const tags = {};
            const authors = (e.authors || []).map(function (a) { return a && a.name; }).filter(Boolean);
            const genres = (e.genres || []).map(function (g) { return g && g.name; }).filter(Boolean);
            if (authors.length) tags["作者"] = authors;
            if (genres.length) tags["题材"] = genres;
            tags["状态"] = [e.status === "completed" ? "完结" : "连载中"];
            if (e.released_year) tags["年份"] = [String(e.released_year)];
            let subtitle = e.status === "completed" ? "完结" : "连载中";
            if (chs.length) subtitle += " · 共" + chs.length + "话";
            return new ComicDetails({
                title: e.title || String(id),
                subtitle: subtitle,
                cover: this._imageUrl(e.thumbnail),
                description: e.description || "暂无简介",
                tags: tags,
                chapters: chapters,
                stars: (typeof e.rating === "number" && e.rating > 0) ? e.rating : null,
                updateTime: e.modified_date ? String(e.modified_date).slice(0, 10) : null,
            });
        },

        loadEp: async (comicId, epId) => {
            const e = await this._entryQuery(comicId);
            const chs = (e.entries_data && e.entries_data.chapters) ? e.entries_data.chapters : [];
            let ch = null;
            const target = String(epId);
            for (const c of chs) {
                if (c && String(c.index) === target) { ch = c; break; }
            }
            if (!ch || !Array.isArray(ch.images) || ch.images.length === 0) {
                throw new Error("章节不存在或无图片: comic=" + comicId + " ep=" + epId);
            }
            const images = [];
            for (const p of ch.images) {
                const u = this._imageUrl(p);
                if (u) images.push(u);
            }
            if (images.length === 0) {
                throw new Error("章节图片路径无效: comic=" + comicId + " ep=" + epId);
            }
            return { images: images };
        },

        onImageLoad: (url, comicId, epId) => {
            return {
                headers: {
                    "Referer": ManHuaJia.WEB_HOST + "/",
                    "User-Agent": ManHuaJia.UA,
                },
            };
        },

        onThumbnailLoad: (url) => {
            return {
                headers: {
                    "Referer": ManHuaJia.WEB_HOST + "/",
                    "User-Agent": ManHuaJia.UA,
                },
            };
        },
    };
}
