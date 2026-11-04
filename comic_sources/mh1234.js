class MH1234 extends ComicSource {
    name = "漫画1234"
    key = "mh1234"
    version = "1.4.0" 
    
    minAppVersion = "1.4.0"
    url = "https://cdn.jsdelivr.net/gh/venera-app/venera-configs@main/mh1234.js"

    // 站点独立阅读域（/go/ 跳转目标）
    get readerUrl() {
        return "https://reader.hqread.cc";
    }

    settings = {
        domains: {
            title: "域名",
            type: "input",
            default: "wmh1234.com"
        }
    }

    get baseUrl() {
        return `https://m.${this.loadSetting('domains')}`;
    }

    // ================= 新版 mint 模板解析器 =================

    // 取图片地址：优先懒加载真值，忽略占位图
    _imgSrc(img) {
        if (!img) return "";
        const a = img.attributes || {};
        const url = a["data-src"] || a["data-original"] || a["src"] || "";
        if (url && url.indexOf("placeholder.svg") >= 0) return "";
        return url;
    }

    // 取字符串里第一个数字，用于判断章节序号方向
    _firstNum(s) {
        if (s === undefined || s === null) return null;
        const m = String(s).match(/\d+/);
        return m ? parseInt(m[0], 10) : null;
    }

    // 站点 SSR 默认「最新在前」（775 → 774 → … → 第1话），Venera 按插入顺序翻页，
    // 直接入库会出现「75话的下一章是74话」——必须翻转为正序（第1话在前）。
    // 若站点日后改为正序下发，这里会自动识别、不再翻转。
    _ascChapters(entries) {
        if (!entries || entries.length < 2) return entries || [];
        let a = this._firstNum(entries[0].num);
        let b = this._firstNum(entries[entries.length - 1].num);
        if (a === null || b === null || a === b) {
            a = this._firstNum(entries[0].title);
            b = this._firstNum(entries[entries.length - 1].title);
        }
        if (a === null || b === null || a === b) {
            return entries.slice().reverse(); // 无法判定：按站点现状（最新在前）翻转
        }
        return a > b ? entries.slice().reverse() : entries;
    }

    // 新版封面网格卡片：a.mint-cover-card > img + h3
    parseMintCards(root) {
        const comics = [];
        if (!root) return comics;
        for (let a of root.querySelectorAll("a.mint-cover-card")) {
            const href = a.attributes["href"] || "";
            const idMatch = href.match(/\/comic\/(\d+)\.html/);
            if (!idMatch) continue;
            const img = a.querySelector("img");
            comics.push(new Comic({
                id: idMatch[1],
                title: (a.querySelector("h3")?.text || img?.attributes["alt"] || "").trim(),
                cover: this._imgSrc(img)
            }));
        }
        return comics;
    }

    // 新版最新更新卡片：a.mint-update-card > img + strong + small(最新话)
    parseMintUpdateCards(root) {
        const comics = [];
        if (!root) return comics;
        for (let a of root.querySelectorAll("a.mint-update-card")) {
            const href = a.attributes["href"] || "";
            const idMatch = href.match(/\/comic\/(\d+)\.html/);
            if (!idMatch) continue;
            const img = a.querySelector("img");
            comics.push(new Comic({
                id: idMatch[1],
                title: (a.querySelector("strong")?.text || img?.attributes["alt"] || "").trim(),
                subTitle: (a.querySelector("small")?.text || "").trim(),
                cover: this._imgSrc(img)
            }));
        }
        return comics;
    }

    // 新版首页头图推荐：article.mint-feature > a.mint-feature__cover + h2
    parseMintFeature(root) {
        const comics = [];
        if (!root) return comics;
        for (let art of root.querySelectorAll("article.mint-feature")) {
            const a = art.querySelector("a.mint-feature__cover");
            const href = (a?.attributes["href"]) || art.attributes["data-comic-id"] || "";
            const idMatch = String(href).match(/\/comic\/(\d+)\.html/) ||
                            (art.attributes["data-comic-id"] ? [null, art.attributes["data-comic-id"]] : null);
            if (!idMatch) continue;
            comics.push(new Comic({
                id: idMatch[1],
                title: (art.querySelector("h2")?.text || "").trim(),
                cover: this._imgSrc(art.querySelector("img"))
            }));
        }
        return comics;
    }

    // 探索页（首页）：新版 mint 首页 + 旧模板兜底 + 人气排行
    explore = [{
        title: "漫画1234",
        type: "singlePageWithMultiPart",
        load: async () => {
            const result = {};
            const res = await Network.get(this.baseUrl);
            if (res.status !== 200) throw `Invalid status code: ${res.status}`;
            const doc = new HtmlDocument(res.body);

            // 1) 新版首页头图「今日推荐」
            const feat = this.parseMintFeature(doc);
            if (feat.length > 0) result["今日推荐"] = feat;

            // 2) 新版首页各分区（最新更新 / 发现好故事）
            const sections = doc.querySelectorAll("section.mint-section");
            for (let sec of sections) {
                const tabTitle = (sec.querySelector(".mint-section-heading h2")?.text || "").trim();
                if (!tabTitle) continue;
                let items = this.parseMintUpdateCards(sec);
                if (items.length === 0) items = this.parseMintCards(sec);
                if (items.length === 0) items = this.parseList(sec);
                if (items.length > 0) result[tabTitle] = items;
            }

            // 3) 旧模板兜底（section.comic-section + article.comic-card）
            if (Object.keys(result).length === 0) {
                for (let section of doc.querySelectorAll("section.comic-section")) {
                    const tabTitle = (section.querySelector(".section-header .section-title")?.text || "推荐").trim();
                    const items = this.parseList(section);
                    if (items.length > 0) result[tabTitle] = items;
                }
            }

            // 4) 人气排行（/custom/top 仍为旧模板，稳定可用）
            try {
                const r2 = await Network.get(`${this.baseUrl}/custom/top`);
                if (r2.status === 200) {
                    const topItems = this.parseList(new HtmlDocument(r2.body));
                    if (topItems.length > 0) result["人气排行"] = topItems.slice(0, 30);
                }
            } catch (e) { /* 排行失败不影响首页 */ }

            if (Object.keys(result).length === 0) {
                throw "首页解析失败，未找到任何漫画，页面结构可能已变化";
            }
            return result;
        }
    }];

    // 分类
    category = {
        title: "漫画1234",
        parts: [
            {
                name: "题材",
                type: "fixed",
                // 2026-09 复测：分类 ID 与线上 /category/ 页面 100/100 完全一致，无需改动
                // 注：站点"全部"(tags/0) 服务端模板损坏返回空列表，故不收录
                categories: [
                    "恋爱", "搞笑", "日漫", "其他", "热血", "都市", "国漫", "少女", "科幻", "魔幻", "奇幻", "冒险", "生活", "韩漫", "纯爱", "少年", "校园", "耽美", "古风", "剧情", "喜剧", "日常", "悬疑", "玄幻", "格斗", "穿越", "恐怖", "武侠", "灵异", "大女主", "百合", "推理", "战斗", "治愈", "侦探", "竞技", "重生", "系统", "逆袭", "短篇", "浪漫", "职场", "动作", "魔法", "后宫", "ABO", "体育", "青春", "总裁", "霸总", "复仇", "架空", "西幻", "现代", "宫廷", "异能", "欧风", "神鬼", "蔷薇", "美食", "欢乐向", "欧美", "唯美", "四格", "女神", "励志", "故事漫画", "战争", "脑洞", "修真", "社会", "萌系", "高甜", "妖怪", "年下", "修仙", "轻小说", "末日", "怪物", "历史", "改编", "游戏", "神仙", "神魔", "惊悚", "娱乐圈", "东方", "轻松", "权谋", "宫斗", "SM", "同人", "多攻", "明星", "音乐", "仙侠", "机甲", "偶像", "虐心", "正能量"
                ],
                itemType: "category",
                categoryParams: [
                    "17", "13", "240", "97", "6", "31", "257", "187", "8", "69", "96", "7", "29", "209", "77", "204", "11", "16", "28", "84", "380", "104", "18", "10", "94", "14", "19", "66", "26", "172", "27", "112", "107", "67", "80", "23", "171", "173", "126", "354", "100", "73", "21", "95", "15", "285", "132", "99", "170", "9", "124", "25", "224", "222", "136", "108", "228", "74", "93", "89", "70", "258", "65", "83", "226", "24", "60", "20", "90", "12", "150", "85", "181", "177", "387", "178", "79", "175", "176", "72", "188", "143", "174", "149", "117", "128", "86", "103", "137", "161", "384", "22", "391", "75", "278", "82", "133", "201", "68", "283"
                ],
            }
        ],
        enableRankingPage: false,
    }

    // 旧模板通用列表解析（article.comic-card），首页/搜索/分类/排行/最新更新均在使用
    parseList(doc) {
        const comics = [];
        if (!doc) return comics;
        for (let card of doc.querySelectorAll("article.comic-card")) {
            const link = card.querySelector("a.comic-card__link");
            if (!link) continue;
            const href = link.attributes["href"];
            const idMatch = href?.match(/\/comic\/(\d+)\.html/);
            if (!idMatch) continue;
            const img = card.querySelector("img.lazy") || card.querySelector("img");
            comics.push(new Comic({
                id: idMatch[1],
                title: (card.querySelector(".comic-card__title")?.text || "").trim(),
                subTitle: (card.querySelector(".comic-card__chapter")?.text || "").trim(),
                cover: this._imgSrc(img)
            }));
        }
        if (comics.length === 0) {
            for (let comic of doc.querySelectorAll(".list-comic")) {
                comics.push(new Comic({
                    id: comic.attributes["data-key"],
                    title: comic.querySelector(".txtA")?.text,
                    cover: comic.querySelector("img")?.attributes["src"]
                }));
            }
        }
        return comics;
    }

    parseComics(html, onePage = false) {
        const doc = new HtmlDocument(html);
        let comics = this.parseMintCards(doc);
        if (comics.length === 0) comics = this.parseMintUpdateCards(doc);
        if (comics.length === 0) comics = this.parseList(doc);
        if (comics.length === 0) {
            for (let comic of doc.querySelectorAll(".itemBox")) {
                comics.push(new Comic({
                    id: comic.attributes["data-key"],
                    title: comic.querySelector(".title")?.text,
                    cover: comic.querySelector("img")?.attributes["src"]
                }));
            }
        }
        const maxPageEl = doc.querySelector("#total-page");
        const maxPage = maxPageEl ? parseInt(maxPageEl.attributes["value"]) : 1;
        return { comics, maxPage: onePage ? 1 : maxPage };
    }

    // 解析阅读页图片：新版阅读页为 main.reader-content 内 img.reader-image（data-src 为带签名CDN地址）
    // 注意：.reader-content 内还嵌了一张 img[data-mint-comic-cover] 作品封面，必须排除，否则会变成第 1 张图
    parseReaderImages(html) {
        const doc = new HtmlDocument(html);
        const images = [];
        const push = (url) => {
            if (!url) return;
            if (url.indexOf("placeholder.svg") >= 0) return;
            if (!/^https?:\/\//.test(url)) return;
            if (images.indexOf(url) < 0) images.push(url);
        };
        // 新版 mint 阅读页：只认 img.reader-image
        for (let img of doc.querySelectorAll("img.reader-image")) {
            const a = img.attributes || {};
            push(a["data-src"] || a["data-original"] || a["src"]);
        }
        // 旧版兜底
        if (images.length === 0) {
            for (let img of doc.querySelectorAll(".reader-content img, .reader-image, img.lazy")) {
                const a = img.attributes || {};
                if (a["data-mint-comic-cover"] !== undefined) continue; // 跳过内嵌作品封面
                push(a["data-src"] || a["data-original"] || a["src"]);
            }
        }
        doc.dispose?.();
        return images;
    }

    categoryComics = {
        load: async (category, params, options, page) => {
            let url = `${this.baseUrl}/category/tags/${params}`;
            const status = options[0];
            if (status !== "0") {
                url += `/finish/${status}`;
            }
            const sort = options[1];
            if (sort !== "id") {
                url += `/order/${sort}`;
            }
            url += `/page/${page}`;
            const res = await Network.get(url);
            if (res.status !== 200) throw `Invalid status code: ${res.status}`;
            const doc = new HtmlDocument(res.body);
            const comics = this.parseList(doc);
            let maxPage = 1;
            const pageLinks = doc.querySelectorAll(".pagination-wrapper a[href*='/page/']");
            for (let link of pageLinks) {
                const href = link.attributes["href"];
                const match = href?.match(/\/page\/(\d+)/);
                if (match) {
                    const p = parseInt(match[1]);
                    if (p > maxPage) maxPage = p;
                }
            }
            return { comics, maxPage };
        },
        optionLoader: async (category, params) => {
            return [
                { options: [ "0-全部", "1-连载", "2-完结" ] },
                { options: [ "id-最新", "hits-热门", "addtime-更新" ] }
            ];
        }
    }

    search = {
        load: async (keyword, options, page) => {
            const url = `${this.baseUrl}/search?key=${encodeURIComponent(keyword)}&sort=${options[0]}&page=${page}`;
            const res = await Network.get(url);
            if (res.status !== 200) throw `Invalid status code: ${res.status}`;
            return this.parseComics(res.body);
        },
        optionList: [
            { options: [ "update-更新", "post-发布", "click-点击" ], label: "排序" }
        ],
        enableTagsSuggestions: false,
    }

    comic = {
        loadInfo: async (id) => {
            const res = await Network.get(`${this.baseUrl}/comic/${id}.html`);
            if (res.status !== 200) throw `Invalid status code: ${res.status}`;
            const doc = new HtmlDocument(res.body);

            // ===== 标题 =====
            let title = (doc.querySelector("#mintWorkTitle")?.text ||
                         doc.querySelector(".mint-work-info h2")?.text ||
                         doc.querySelector(".comic-hero__title")?.text ||
                         doc.querySelector("h1")?.text ||
                         doc.querySelector(".BarTit")?.text || "").trim() || "未知标题";

            // ===== 封面 =====
            let cover = "";
            const coverSelectors = [
                "img.mint-work-cover",
                ".comic-hero__cover img",
                ".comic-cover-large img.lazy",
                ".pic img",
                ".cover img",
                ".comic-cover img",
                "img.lazy"
            ];
            for (let sel of coverSelectors) {
                cover = this._imgSrc(doc.querySelector(sel));
                if (cover) break;
            }

            // ===== 简介 =====
            let description = (doc.querySelector("#mintIntroPanel div")?.text ||
                               doc.querySelector(".mint-intro div")?.text ||
                               doc.querySelector(".comic-desc")?.text ||
                               doc.querySelector(".comic-description-inline__content")?.text ||
                               doc.querySelector("#full-des")?.text ||
                               doc.querySelector(".comic-description")?.text || "").trim();

            // ===== 作者 / 状态 / 更新 / 标签 =====
            let author = "", update = "", status = "";
            const tags = [];

            const mintInfo = doc.querySelector(".mint-work-info");
            if (mintInfo) {
                // 新版 mint 详情页
                status = (mintInfo.querySelector("span.mint-tag")?.text || "").trim();
                const metaText = (mintInfo.querySelector(".mint-work-meta")?.text || "").trim();
                const uMatch = metaText.match(/(\d{2}-\d{2})\s*更新/);
                if (uMatch) update = uMatch[1];
                else if (metaText) update = metaText;

                for (let p of mintInfo.querySelectorAll("p")) {
                    const t = (p.text || "").trim();
                    if (!t) continue;
                    if (t === metaText) continue;               // 已处理的 meta 行
                    if (!author && /著\s*$/.test(t) && t.length < 80) {
                        author = t.replace(/著\s*$/, "").trim(); // "动漫堂,米二,米橙子 著"
                        continue;
                    }
                    // 剩下的即为「标签 + 状态」行
                    let tt = status ? t.split(status).join("") : t;
                    for (const x of tt.split(/\s+/)) {
                        if (x && tags.indexOf(x) < 0) tags.push(x);
                    }
                }
            } else {
                // 旧版模板兜底
                const metaItems = doc.querySelectorAll(".comic-hero__meta .meta-item");
                if (metaItems.length >= 1) {
                    author = (metaItems[0].text || "").replace(/作者：/g, '').trim();
                }
                const statItems = doc.querySelectorAll(".comic-hero__stats .stat-item");
                if (statItems.length >= 1) {
                    const statusEl = statItems[0].querySelector(".stat-value");
                    if (statusEl) status = (statusEl.text || "").trim();
                }
                if (!author && !status) {
                    const authorInfoEl = doc.querySelector(".comic-author-info");
                    if (authorInfoEl) {
                        const text = authorInfoEl.text;
                        const authorMatch = text.match(/作者：([^·]+)/);
                        if (authorMatch) author = authorMatch[1].trim();
                        const updateMatch = text.match(/更新时间：([^·]+)/);
                        if (updateMatch) update = updateMatch[1].trim();
                        const statusMatch = text.match(/状态：([^·]+)/);
                        if (statusMatch) status = statusMatch[1].trim();
                    } else {
                        const infos = doc.querySelectorAll(".txtItme");
                        if (infos.length > 0) {
                            author = (infos[0]?.text || "").replaceAll("\n", "").replaceAll("\r", "").trim();
                            if (infos[3]) {
                                update = infos[3].querySelector(".date")?.text || "";
                            }
                        }
                    }
                }
                const tagSelectors = [
                    ".comic-hero__meta .meta-item:nth-child(2)",
                    ".comic-tags-large .tag-large",
                    ".sub_r a",
                    ".tag-list a",
                    ".comic-tags a"
                ];
                for (let sel of tagSelectors) {
                    const tagEl = doc.querySelector(sel);
                    if (tagEl && tagEl.text) {
                        const splitted = tagEl.text.split(/\s+/).filter(t => t.length > 0);
                        tags.push(...splitted);
                    }
                    if (tags.length > 0) break;
                }
            }

            // ===== 章节列表 =====
            // 新版站点：.mint-chapter-grid > a[href="/go/<token>"]，token 本身即为章节ID
            // 旧版站点：.chapter-list > a.chapter-item / /comic/ID/EP.html 直链
            const entries = [];
            const seen = {};
            const addChapter = (token, t, num) => {
                if (!token || !t) return;
                t = String(t).trim();
                if (!t || t.indexOf("APP观看") >= 0) return;
                if (seen[token] !== undefined) return;
                seen[token] = true;
                entries.push({ token: token, title: t, num: num });
            };
            for (let a of doc.querySelectorAll(".mint-chapter-grid a")) {
                const href = a.attributes["href"] || "";
                const m = href.match(/\/go\/([A-Za-z0-9+/=_-]+)/);
                if (!m) continue;
                addChapter(m[1], a.text, a.attributes["data-chapter-id"]);
            }
            if (entries.length === 0) {
                for (let item of doc.querySelectorAll(".chapter-list a.chapter-item")) {
                    const href = item.attributes["href"] || "";
                    const m = href.match(/\/go\/([A-Za-z0-9+/=_-]+)/);
                    if (!m) continue;
                    addChapter(m[1], item.querySelector(".chapter-title")?.text || item.text,
                               item.attributes["data-chapter-id"]);
                }
            }
            if (entries.length === 0) {
                for (let link of doc.querySelectorAll("a[href]")) {
                    const href = link.attributes["href"] || "";
                    const m = href.match(/^\/go\/([A-Za-z0-9+/=_-]+)$/);
                    if (!m) continue;
                    addChapter(m[1], link.text, link.attributes["data-chapter-id"]);
                }
            }
            if (entries.length === 0) {
                for (let link of doc.querySelectorAll("a[href]")) {
                    const href = link.attributes["href"];
                    if (!href) continue;
                    const match = href.match(/\/comic\/(\d+)\/(\d+)\.html/);
                    if (!match) continue;
                    const chapterId = `${match[1]}_${match[2]}`;
                    let chapterTitle = (link.text || "").trim();
                    if (!chapterTitle) {
                        const parent = link.parentNode;
                        const titleSpan = parent?.querySelector?.(".chapter-title, .chapter-name");
                        if (titleSpan) chapterTitle = (titleSpan.text || "").trim();
                    }
                    if (!chapterTitle) chapterTitle = "第" + match[2] + "话";
                    addChapter(chapterId, chapterTitle, match[2]);
                }
            }
            if (entries.length === 0) {
                throw "未找到任何章节，页面结构可能已变化";
            }
            // 翻转为正序（第1话在前），否则阅读时「下一章」会倒着走
            const chapters = {};
            for (const e of this._ascChapters(entries)) {
                chapters[e.token] = e.title;
            }

            // ===== 推荐漫画 =====
            let recommend = this.parseMintCards(doc).filter(c => String(c.id) !== String(id));
            if (recommend.length === 0) {
                const recCards = doc.querySelectorAll(".recommendations .comic-card, .comic-card");
                for (let card of recCards) {
                    const link = card.querySelector("a");
                    const href = link?.attributes["href"] || card.attributes["onclick"] || "";
                    const idMatch = href.match(/\/comic\/(\d+)\.html/);
                    if (!idMatch) continue;
                    const recId = idMatch[1];
                    if (recId === id) continue;
                    recommend.push(new Comic({
                        id: recId,
                        title: (card.querySelector(".comic-card__title, .comic-title")?.text || "").trim(),
                        cover: this._imgSrc(card.querySelector("img.lazy") || card.querySelector("img"))
                    }));
                }
            }

            return {
                title,
                cover,
                description,
                tags: {
                    "作者": [author],
                    "更新": [update],
                    "标签": tags
                },
                chapters,
                recommend
            };
        },

        loadEp: async (comicId, epId) => {
            if (!epId) {
                throw new Error(`章节ID无效: ${epId}，请检查详情页是否成功加载章节列表`);
            }
            // 新版：epId 为 /go/ token，阅读页在独立域名 reader.hqread.cc/r/<token>
            // 旧版 epId 格式为 comicId_chapterId，需拼回旧路径再跟随 302 / location.replace
            let html = "";
            let status = 0;
            if (epId.indexOf("_") > 0) {
                const ids = epId.split("_");
                const res = await Network.get(`${this.baseUrl}/comic/${ids[0]}/${ids[1]}.html`, {
                    "Referer": `${this.baseUrl}/comic/${comicId}.html`
                });
                status = res.status;
                html = res.body;
                // 旧路径现已 302 至 /go/<token>，返回的是 JS 跳转页，需跟随 location.replace
                if (status === 200) {
                    const m = html.match(/location\.replace\("([^"]+)"\)/);
                    if (m) {
                        const res3 = await Network.get(m[1], { "Referer": `${this.baseUrl}/` });
                        status = res3.status;
                        html = res3.body;
                    }
                }
            } else {
                // 优先直连阅读域（/go/ 中转页有时会被插入"App抢先看"广告）
                const res = await Network.get(`${this.readerUrl}/r/${epId}`, {
                    "Referer": `${this.baseUrl}/`
                });
                status = res.status;
                html = res.body;
                let images = this.parseReaderImages(html);
                if (images.length > 0) return { images };
                // 直连失败时走 /go/ 中转，解析 location.replace 跳转
                const res2 = await Network.get(`${this.baseUrl}/go/${epId}`, {
                    "Referer": `${this.baseUrl}/comic/${comicId}.html`
                });
                status = res2.status;
                html = res2.body;
                if (status === 200) {
                    const m = html.match(/location\.replace\("([^"]+)"\)/);
                    if (m) {
                        const res3 = await Network.get(m[1], { "Referer": `${this.baseUrl}/` });
                        status = res3.status;
                        html = res3.body;
                    }
                }
            }
            if (status !== 200) throw `Invalid status code: ${status}`;
            const images = this.parseReaderImages(html);
            if (images.length === 0) {
                throw "未找到任何图片，该章节可能为App抢先看或页面结构已变化";
            }
            return { images };
        },

        enableTagsTranslate: false,
    }

    // 图片CDN（wmh1234.wszwhg.net）带签名参数，一般无Referer要求；统一带上站点Referer以防万一
    onImageLoad(url, comicId, epId) {
        return { headers: { "Referer": `${this.baseUrl}/` } };
    }

    onThumbnailLoad(url) {
        return { headers: { "Referer": `${this.baseUrl}/` } };
    }
}
