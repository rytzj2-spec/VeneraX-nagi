
// ---------------------------------------------------------------------------
class CopyMangaWeb extends ComicSource {
    name = "拷贝漫画（网页版）"
    key = "copy_manga_web"
    version = "2.0.0"
    minAppVersion = "1.6.0"
    // 共存副本：不指向原始更新地址，避免官方源更新时覆盖本版本。
    url = null

    // ---------------- 默认配置 ----------------
    static defaultApiUrl = 'api.copy4000.com'
    static defaultImageQuality = '1500'
    // 候选 API 域名（2026-09 实测可用；域名会轮换，也可在设置里手动填 custom_base_url 覆盖）
    static apiDomainOptions = [
        'api.copy4000.com',
        'api.copy2000.online',
        'api.copy202601.com',
        'api.copy-manga.com',
    ]

    // 主题（拼音 slug → 中文显示名，源自 copy3000.com/comics 题材链接）
    static theme_dict = {
        "全部": "",
        "AA": "aa",
        "愛情": "aiqing",
        "百合": "baihe",
        "長條": "changtiao",
        "重生": "chongsheng",
        "穿越": "chuanyue",
        "漫展": "comiket",
        "耽美": "danmei",
        "東方": "dongfang",
        "都市": "dushi",
        "FATE": "fate",
        "搞笑": "gaoxiao",
        "格鬥": "gedou",
        "後宮": "hougong",
        "歡樂向": "huanlexiang",
        "舰娘": "jianniang",
        "节操": "jiecao",
        "竞技": "jingji",
        "惊悚": "jingsong",
        "机战": "jizhan",
        "科幻": "kehuan",
        "恐怖": "kongbu",
        "历史": "lishi",
        "励志": "lizhi",
        "LoveLive": "love",
        "冒險": "maoxian",
        "美食": "meishi",
        "萌系": "mengxi",
        "魔幻": "mohuan",
        "奇幻": "qihuan",
        "轻小说": "qingxiaoshuo",
        "其他": "qita",
        "热血": "rexue",
        "生存": "shengcun",
        "生活": "shenghuo",
        "神鬼": "shengui",
        "四格": "sige",
        "TL": "teenslove",
        "伪娘": "weiniang",
        "武侠": "wuxia",
        "仙侠": "xianxia",
        "校园": "xiaoyuan",
        "性转换": "xingzhuanhuan",
        "悬疑": "xuanyi",
        "音乐舞蹈": "yinyuewudao",
        "异世界": "yishijie",
        "雜誌附贈寫真集": "zazhifuzengxiezhenji",
        "宅系": "zhaixi",
        "战争": "zhanzheng",
        "侦探": "zhentan",
        "职场": "zhichang",
        "治愈": "zhiyu",
        "转生": "zhuansheng",
    }

    // ---------------- 请求头（网页端浏览器头，绕开 App 风控） ----------------
    get headers() {
        let token = this.loadData("token")
        let h = {
            "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "Accept": "application/json",
            "Accept-Language": "en-US,en;q=0.9,zh-TW;q=0.8,zh;q=0.7",
            "version": "2025.05.09",
            "platform": "1",
            "Origin": "https://2025copy.com",
            "webp": "0",
            "region": "0",
        }
        if (token) {
            h["authorization"] = `Token ${token}`
        }
        return h
    }

    // 候选域名池：自定义域名 > 预设选择 > 其余候选 > 自动刷新节点（去重保序）
    get candidateHosts() {
        let pool = []
        let custom = this.loadSetting('custom_base_url')
        let sel = this.loadSetting('base_url')
        if (custom) pool.push(custom)
        if (sel && !pool.includes(sel)) pool.push(sel)
        for (let d of CopyMangaWeb.apiDomainOptions) {
            if (!pool.includes(d)) pool.push(d)
        }
        let auto = this.loadData("_api_host")
        if (auto && !pool.includes(auto)) pool.push(auto)
        return pool
    }

    // API 地址：轮换状态(_active_host) > 候选池第一个 > 内置默认
    get apiUrl() {
        return `https://${this.loadData("_active_host") || this.candidateHosts[0] || CopyMangaWeb.defaultApiUrl}`
    }
    get imageQuality() {
        return this.loadSetting('image_quality') || CopyMangaWeb.defaultImageQuality
    }

    // 切到候选池里下一个域名（小霖子用于内容接口命中 IP 封禁时绕开），返回新域名；无更多则 null
    switchApiHost() {
        let pool = this.candidateHosts
        if (pool.length <= 1) return null
        let cur = this.loadData("_active_host") || pool[0]
        let idx = pool.indexOf(cur)
        if (idx === -1) idx = 0
        let next = pool[(idx + 1) % pool.length]
        this.saveData("_active_host", next)
        return next
    }

    // ---------------- 统一 GET 封装 ----------------
    // isContent：内容接口（详情/章节列表/章节图片）命中「破解版本」IP 封禁(210)时自动换域名重试一次；
    //            元数据接口（首页/搜索/分类/评论/排行）的 210 是业务限额，不触发换域名。
    async getJson(url, isContent = false) {
        let res = await Network.get(url, this.headers)
        if (isContent && res.status === 210) {
            let msg = ""
            try { msg = JSON.parse(res.body).message || "" } catch (e) { msg = res.body || "" }
            // 「破解版本」封禁文案（含 破解/正版/下載/官網/小時 等）；区别于评论「一天5条」类业务限额
            if (/破解|正版|下載|官網|官网|小時|小时/.test(msg)) {
                let next = this.switchApiHost()
                if (next) {
                    let newUrl = url.replace(/^https:\/\/[^/]+/, `https://${next}`)
                    res = await Network.get(newUrl, this.headers)
                }
            }
        }
        if (res.status === 401) {
            throw "登录已失效，请重新登录"
        }
        if (res.status !== 200) {
            throw `Invalid status code: ${res.status}`
        }
        const data = JSON.parse(res.body)
        // 罕见情况：域名切到 hotmanga/manga2025 主机时会返回加密体，本版本不处理，
        // 提示用户换回 copyXXXX 域名即可。
        if (data.manga2025_data || data.enc_data) {
            throw "当前 API 域名返回加密数据，请在设置中更换 API 地址（如 api.copy4000.com）"
        }
        if (data.code !== 200) {
            throw `Invalid code: ${data.code} - ${data.message || ""}`
        }
        return data.results
    }

    init() {
        this.refreshApiHost()
    }

    // 启动时从 cmbok 的 url_config.json 自动刷新 API 域名（失败则保留默认值）
    async refreshApiHost() {
        try {
            const res = await Network.get(
                "https://cdn.jsdelivr.net/gh/hlning/cmbok@main/url_config.json",
                { "User-Agent": this.headers["User-Agent"] }
            )
            if (res.status === 200) {
                const cfg = JSON.parse(res.body)
                const host = (cfg.copy_url || "").trim().replace(/^https?:\/\//, "").replace(/\/$/, "")
                if (host) {
                    this.saveData("_api_host", host)
                }
            }
        } catch (e) {
            // 拉取失败不影响使用，回落默认域名
        }
    }

    // ---------------- 通用漫画解析 ----------------
    _parseComic(comic) {
        if (comic["comic"] !== null && comic["comic"] !== undefined) {
            comic = comic["comic"]
        }
        let tags = []
        if (comic["theme"] !== null && comic["theme"] !== undefined && Array.isArray(comic["theme"])) {
            tags = comic["theme"].map(t => t["name"]).filter(n => n)
        }
        let author = null
        if (Array.isArray(comic["author"]) && comic["author"].length > 0) {
            author = comic["author"][0]["name"]
        }
        return {
            id: comic["path_word"],
            title: comic["name"],
            subTitle: author,
            cover: comic["cover"],
            tags: tags,
            description: comic["datetime_updated"] || null,
        }
    }

    /// explore（首页）
    explore = [
        {
            title: "拷贝漫画（网页版）",
            type: "singlePageWithMultiPart",
            load: async () => {
                const data = await this.getJson(`${this.apiUrl}/api/v3/h5/homeIndex`)
                const parse = this._parseComic.bind(this)
                let res = {}
                res["推荐"] = (data["recComics"] && data["recComics"]["list"] || []).map(parse)
                res["热门"] = (data["hotComics"] || []).map(parse)
                res["最新"] = (data["newComics"] || []).map(parse)
                res["完结"] = (data["finishComics"] && data["finishComics"]["list"] || []).map(parse)
                res["今日排行"] = (data["rankDayComics"] && data["rankDayComics"]["list"] || []).map(parse)
                res["本周排行"] = (data["rankWeekComics"] && data["rankWeekComics"]["list"] || []).map(parse)
                res["本月排行"] = (data["rankMonthComics"] && data["rankMonthComics"]["list"] || []).map(parse)
                return res
            }
        }
    ]

    /// category
    category = {
        title: "拷贝漫画（网页版）",
        parts: [
            {
                name: "排行",
                type: "fixed",
                categories: ["排行"],
                categoryParams: ["ranking"],
                itemType: "category"
            },
            {
                name: "主题",
                type: "fixed",
                categories: Object.keys(CopyMangaWeb.theme_dict),
                categoryParams: Object.values(CopyMangaWeb.theme_dict),
                itemType: "category"
            }
        ]
    }

    categoryComics = {
        load: async (category, param, options, page) => {
            // 排行分支：audience_type 支持 male/female 字符串（全部=不传）；
            // App 端的数字 0/1/2 在网页端会报 210「不是有效的選擇」，勿用。
            if (category === "排行" || param === "ranking") {
                let audience = (options && options[0] ? options[0] : "")   // "" 全部 / male 男生向 / female 女生向
                let dateType = (options && options[1] ? options[1] : "day")
                let url = `${this.apiUrl}/api/v3/ranks?limit=30&offset=${(page - 1) * 30}&type=1&date_type=${dateType}`
                if (audience) {
                    url += `&audience_type=${audience}`
                }
                const data = await this.getJson(url)
                const parseRank = (item) => {
                    let base = this._parseComic(item)
                    let sort = item["sort"]
                    let rise = item["rise_sort"]
                    let popular = item["popular"]
                    let desc = sort ? `第${sort}名` : ""
                    if (popular) desc += ` · 人气${(popular / 10000).toFixed(1)}W`
                    if (rise !== undefined && rise !== null && rise !== 0) {
                        desc += ` ${rise > 0 ? '▲' : '▽'}${Math.abs(rise)}`
                    }
                    base.description = desc || base.description
                    return base
                }
                return {
                    comics: (data["list"] || []).map(parseRank),
                    maxPage: Math.ceil((data["total"] || 0) / 30)
                }
            }

            let theme = param
            if (theme === undefined || theme === null) {
                theme = CopyMangaWeb.theme_dict[category] || ""
            }
            // options[0]=排序 key（* 前缀代表降序，load 里转回 -），options[1]=地区/状态 key
            let ordering = (options && options[0] ? options[0] : "*datetime_updated").replace("*", "-")
            let top = options && options[1] ? options[1] : ""
            let url = `${this.apiUrl}/api/v3/comics?limit=30&offset=${(page - 1) * 30}&ordering=${ordering}&theme=${theme}&top=${top}`
            const data = await this.getJson(url)
            const parse = this._parseComic.bind(this)
            return {
                comics: (data["list"] || []).map(parse),
                maxPage: Math.ceil((data["total"] || 0) / 30)
            }
        },
        optionList: [
            {
                options: [
                    "-全部",
                    "male-男生向",
                    "female-女生向"
                ],
                notShowWhen: null,
                showWhen: ["排行"]
            },
            {
                options: [
                    "day-日榜",
                    "week-周榜",
                    "month-月榜"
                ],
                notShowWhen: null,
                showWhen: ["排行"]
            },
            {
                options: [
                    "*datetime_updated-最新更新",
                    "*popular-最热门",
                    "*datetime_visited-最多浏览"
                ],
                notShowWhen: null,
                showWhen: Object.keys(CopyMangaWeb.theme_dict)
            },
            {
                options: [
                    "-全部",
                    "japan-日漫",
                    "korea-韩漫",
                    "west-美漫",
                    "finish-已完结"
                ],
                notShowWhen: null,
                showWhen: Object.keys(CopyMangaWeb.theme_dict)
            }
        ]
    }

    /// search
    search = {
        load: async (keyword, options, page) => {
            let q_type = options && options[0] ? options[0] : ""
            keyword = encodeURIComponent(keyword)
            let url = `${this.apiUrl}/api/v3/search/comic?format=json&platform=1&q=${keyword}&q_type=${q_type}&limit=30&offset=${(page - 1) * 30}`
            const data = await this.getJson(url)
            const parse = this._parseComic.bind(this)
            return {
                comics: (data["list"] || []).map(parse),
                maxPage: Math.ceil((data["total"] || 0) / 30)
            }
        },
        optionList: [
            {
                type: "select",
                options: [
                    "-全部",
                    "name-名称",
                    "author-作者",
                    "local-汉化组"
                ],
                label: "搜索选项"
            }
        ]
    }

    /// comic
    comic = {
        loadInfo: async (id) => {
            // 一次请求同时拿详情(comic)与分组(groups)
            const detail = await this.getJson(`${this.apiUrl}/api/v3/comic2/${id}?platform=1`, true)
            const comicData = detail["comic"] || detail
            let title = comicData["name"]
            let cover = comicData["cover"]
            let authors = (comicData["author"] || []).map(e => e["name"])
            let tags = (comicData["theme"] || []).map(e => (e && e["name"]) || null).filter(n => n)
            let description = comicData["brief"] || ""
            let status = (comicData["status"] && comicData["status"]["display"]) || ""

            // 拉取各分组章节列表
            let groups = detail["groups"] || {}
            let chapters = new Map()
            for (let key of Object.keys(groups)) {
                let group = groups[key]
                let path = group["path_word"] || key
                let groupName = group["name"] || key
                let data = await this.getJson(
                    `${this.apiUrl}/api/v3/comic/${id}/group/${path}/chapters?limit=500&offset=0`,
                    true
                )
                let list = data["list"] || []
                for (let e of list) {
                    // 分组名作为章节前缀，避免多分组重名冲突
                    let titleText = groupName && groupName !== "默認" && groupName !== "默认"
                        ? `${groupName} ${e["name"]}`
                        : e["name"]
                    chapters.set(e["uuid"], titleText)
                }
            }

            return {
                title: title,
                cover: cover,
                description: description,
                tags: {
                    "作者": authors,
                    "状态": [status],
                    "标签": tags,
                },
                chapters: chapters,
                subId: comicData["uuid"],
            }
        },

        loadEp: async (comicId, epId) => {
            const data = await this.getJson(
                `${this.apiUrl}/api/v3/comic/${comicId}/chapter2/${epId}?platform=1`,
                true
            )
            const chapter = data["chapter"] || {}
            const contents = chapter["contents"] || []
            const words = chapter["words"] || []
            let imagesUrls = contents.map(e => e["url"])
            // 图片质量重写：c\d+x → c{quality}x（保持 jpg，webp:0 头已协商为 jpg）
            let hdImages = imagesUrls.map(url => {
                return url.replace(/([./])c\d+x\.([a-zA-Z]+)$/, `$1c${this.imageQuality}x.$2`)
            })
            let images = new Array(hdImages.length).fill("")
            for (let i = 0; i < hdImages.length; i++) {
                images[words[i] !== undefined ? words[i] : i] = hdImages[i]
            }
            return { images: images }
        },

        // ---------------- 评论区（漫画 + 章内） ----------------
        loadComments: async (comicId, subId, page, replyTo) => {
            let url = `${this.apiUrl}/api/v3/comments?comic_id=${subId}&limit=20&offset=${(page - 1) * 20}`
            if (replyTo) {
                url += `&reply_id=${replyTo}&_update=true`
            }
            const data = await this.getJson(url)
            let total = data["total"] || 0
            let list = data["list"] || []
            return {
                comments: list.map(e => ({
                    userName: replyTo ? `${e.user_name}  👉  ${e.parent_user_name}` : e.user_name,
                    avatar: e.user_avatar,
                    content: e.comment,
                    time: e.create_at,
                    replyCount: e.count,
                    id: e.id,
                })),
                maxPage: Math.ceil(total / 20),
            }
        },
        sendComment: async (comicId, subId, content, replyTo) => {
            let token = this.loadData("token")
            if (!token) throw "未登录"
            let res = await Network.post(
                `${this.apiUrl}/api/v3/member/comment`,
                {
                    ...this.headers,
                    "Content-Type": "application/x-www-form-urlencoded;charset=utf-8"
                },
                `comic_id=${subId}&comment=${encodeURIComponent(content)}&reply_id=${replyTo || ''}`
            )
            if (res.status === 401) throw "登录已失效，请重新登录"
            if (res.status !== 200) throw `Invalid status code: ${res.status}`
            return "ok"
        },
        loadChapterComments: async (comicId, epId, page, replyTo) => {
            let url = `${this.apiUrl}/api/v3/roasts?chapter_id=${epId}&limit=20&offset=${(page - 1) * 20}`
            const data = await this.getJson(url)
            let total = data["total"] || 0
            let list = data["list"] || []
            return {
                comments: list.map(e => ({
                    userName: e.user_name,
                    avatar: e.user_avatar,
                    content: e.comment,
                    time: e.create_at,
                    replyCount: null,
                    id: null,
                })),
                maxPage: Math.ceil(total / 20),
            }
        },
        sendChapterComment: async (comicId, epId, content, replyTo) => {
            let token = this.loadData("token")
            if (!token) throw "未登录"
            let res = await Network.post(
                `${this.apiUrl}/api/v3/member/roast`,
                {
                    ...this.headers,
                    "Content-Type": "application/x-www-form-urlencoded;charset=utf-8"
                },
                `chapter_id=${epId}&roast=${encodeURIComponent(content)}`
            )
            if (res.status === 401) throw "登录已失效，请重新登录"
            if (res.status !== 200) throw `Invalid status code: ${res.status}`
            return "ok"
        },
    }

    /// account（登录/登出，待验证：需真实账号确认 token 返回字段）
    account = {
        login: async (account, pwd) => {
            let salt = Math.floor(1000 + Math.random() * 9000)
            let base64 = Convert.encodeBase64(Convert.encodeUtf8(`${pwd}-${salt}`))
            let res = await Network.post(
                `${this.apiUrl}/api/v3/login`,
                {
                    ...this.headers,
                    "Content-Type": "application/x-www-form-urlencoded;charset=utf-8"
                },
                `username=${account}&password=${base64}&salt=${salt}&authorization=Token+`
            )
            if (res.status === 200) {
                let data = JSON.parse(res.body)
                let token = data.results && data.results.token
                if (token) {
                    this.saveData('token', token)
                    return "ok"
                }
                throw `登录失败：${data.message || "未返回 token"}`
            }
            throw `Invalid Status Code ${res.status}`
        },
        logout: () => {
            this.deleteData('token')
        },
        registerWebsite: null
    }

    /// favorites（收藏同步，待验证：需真实账号确认收藏接口）
    favorites = {
        multiFolder: false,
        addOrDelFavorite: async (comicId, folderId, isAdding) => {
            let token = this.loadData("token")
            if (!token) throw "未登录"
            let is_collect = isAdding ? 1 : 0
            // 先取 comic 的 uuid（收藏接口要的是 uuid 而非 path_word）
            const detail = await this.getJson(`${this.apiUrl}/api/v3/comic2/${comicId}?platform=1`)
            let comic_id = (detail["comic"] || detail)["uuid"]
            let res = await Network.post(
                `${this.apiUrl}/api/v3/member/collect/comic`,
                {
                    ...this.headers,
                    "Content-Type": "application/x-www-form-urlencoded;charset=utf-8"
                },
                `comic_id=${comic_id}&is_collect=${is_collect}&authorization=Token+${token}`
            )
            if (res.status === 401) throw "登录已失效，请重新登录"
            if (res.status !== 200) throw `Invalid status code: ${res.status}`
            return "ok"
        },
        loadComics: async (page, folder) => {
            let ordering = this.loadSetting('favorites_ordering') || '-datetime_updated'
            const data = await this.getJson(
                `${this.apiUrl}/api/v3/member/collect/comics?limit=30&offset=${(page - 1) * 30}&free_type=1&ordering=${ordering}`
            )
            const parse = this._parseComic.bind(this)
            return {
                comics: (data["list"] || []).map(parse),
                maxPage: Math.ceil((data["total"] || 0) / 30)
            }
        }
    }

    // 图片 / 封面 Referer（防盗链：站点首页）
    onImageLoad(url, comicId, epId) {
        return { headers: { Referer: "https://2025copy.com/" } }
    }
    onThumbnailLoad(url) {
        return { headers: { Referer: "https://2025copy.com/" } }
    }

    /// settings
    settings = {
        base_url: {
            title: "API域名",
            type: "select",
            options: CopyMangaWeb.apiDomainOptions.map(d => ({ value: d, text: d })),
            default: CopyMangaWeb.defaultApiUrl,
        },
        custom_base_url: {
            title: "自定义API域名(留空则用上方选项)",
            type: "input",
            validator: '^(?!:\\/\\/)(?=.{1,253})([a-zA-Z0-9]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?\\.)+[a-zA-Z]{2,}$',
            default: "",
        },
        favorites_ordering: {
            title: "收藏排序方式",
            type: "select",
            options: [
                { value: '-datetime_updated', text: '更新时间' },
                { value: '-datetime_modifier', text: '收藏时间' },
                { value: '-datetime_browse', text: '阅读时间' },
            ],
            default: '-datetime_updated',
        },
        image_quality: {
            title: "图片质量",
            type: "select",
            options: [
                { value: '800', text: '低 (800)' },
                { value: '1200', text: '中 (1200)' },
                { value: '1500', text: '高 (1500)' },
            ],
            default: CopyMangaWeb.defaultImageQuality,
        },
    }
}
