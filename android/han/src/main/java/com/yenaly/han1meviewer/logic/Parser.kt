package com.yenaly.han1meviewer.logic

import android.annotation.SuppressLint
import android.util.Log
import com.yenaly.han1meviewer.EMPTY_STRING
import com.yenaly.han1meviewer.HanimeResolution
import com.yenaly.han1meviewer.LOCAL_DATE_FORMAT
import com.yenaly.han1meviewer.Preferences.isAlreadyLogin
import com.yenaly.han1meviewer.logic.exception.ParseException
import com.yenaly.han1meviewer.logic.model.HanimeInfo
import com.yenaly.han1meviewer.logic.model.HanimePreview
import com.yenaly.han1meviewer.logic.model.HanimeVideo
import com.yenaly.han1meviewer.logic.model.HomePage
import com.yenaly.han1meviewer.logic.model.MyListItems
import com.yenaly.han1meviewer.logic.model.Playlists
import com.yenaly.han1meviewer.logic.model.Subscription
import com.yenaly.han1meviewer.logic.model.VideoComments
import com.yenaly.han1meviewer.logic.state.PageLoadingState
import com.yenaly.han1meviewer.logic.state.VideoLoadingState
import com.yenaly.han1meviewer.logic.state.WebsiteState
import com.yenaly.han1meviewer.suite.SuiteDebug
import com.yenaly.han1meviewer.Preferences
import com.yenaly.han1meviewer.toVideoCode
import kotlinx.datetime.LocalDate
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Comment
import org.jsoup.nodes.Element
import org.jsoup.select.Elements

/**
 * @project Han1meViewer
 * @author Yenaly Liew
 * @time 2023/07/31 031 16:43
 */
object Parser {

    /**
     * 所需 Regex
     */
    object Regex {
        val videoSource = Regex("""const source = '(.+)'""")
        // Nagisaland suite：网站有时给简体（观看次数）
        val viewAndUploadTime = Regex("""(?:觀看次數|观看次数)[：:]\s*(.+?)次\s*(\d{4}-\d{2}-\d{2})""")
        val mediaUrl = Regex("""(?:https?:)?//[^\s'"<>\\]+?\.(?:mp4|m3u8)(?:\?[^\s'"<>\\]*)?""")
        val resolutionInUrl = Regex("""(?<!\d)(2160|1440|1080|720|480|360|240)[pP](?![a-zA-Z])""")
    }

    // <editor-fold desc="Nagisaland suite：2026 年网页结构">

    /**
     * 带上站点地址解析，相对链接（/watch?v=…）的 absUrl 才不是空串。
     */
    private fun parseHtml(body: String): Element =
        Jsoup.parse(body, runCatching { Preferences.baseUrl }.getOrDefault("https://hanime1.me/")).body()

    private fun Element.videoCodeFromHref(): String? =
        absUrl("href").toVideoCode() ?: attr("href").toVideoCode()
            ?: attr("data-href").toVideoCode()

    private fun Element.imageUrl(): String? {
        for (attr in arrayOf("src", "data-src", "data-original", "data-lazy-src")) {
            val url = absUrl(attr).ifBlank { attr(attr) }
            if (url.isNotBlank() && !url.startsWith("data:")) return url
        }
        return null
    }

    /**
     * 2026 年 hanime1 的视频卡片：
     * `div.video-item-container > div.horizontal-card > a.video-link[href*=watch?v=]`
     * （里面有 img.main-thumb、div.duration、div.stats-container > div.stat-item[好评率, 播放量]、div.title），
     * 旁边是 `div.subtitle`（a = 上传者，span.subtitle-time = 时间）。
     * 也兼容没有 video-item-container、只有 horizontal-card 的写法。同一个视频只取一次。
     */
    fun hanimeItemsVer3(root: Element?): MutableList<HanimeInfo> {
        val result = mutableListOf<HanimeInfo>()
        if (root == null) return result
        val seen = HashSet<String>()
        val containers = root.select("div.video-item-container").ifEmpty {
            root.select("div[class*=horizontal-card]")
        }
        for (container in containers) {
            hanimeItemVer3(container)?.let { if (seen.add(it.videoCode)) result += it }
        }
        return result
    }

    // 字段取法以本地 v0.17.2（真机验证过）为准，取不到时再用更宽松的兜底
    private fun hanimeItemVer3(container: Element): HanimeInfo? {
        val card = container.selectFirst("div[class*=horizontal-card]") ?: container
        val link = container.selectFirst("a.video-link")
            ?: card.selectFirst("a[href*=watch]")
            ?: container.selectFirst("a[href*=watch]")
            ?: return null
        val videoCode = link.absUrl("href").ifEmpty { link.attr("href") }.toVideoCode()
            ?: link.videoCodeFromHref() ?: return null
        val img = link.selectFirst("img.main-thumb") ?: link.selectFirst("img")
            ?: card.select("img").firstOrNull { it.imageUrl()?.contains("avatar") == false }
        val coverUrl = img?.absUrl("src")?.ifEmpty { img.attr("src") }?.ifEmpty { null }
            ?: img?.imageUrl() ?: return null
        val title = link.selectFirst("div.title")?.text()
            ?: container.attr("title").ifEmpty { null }
            ?: card.selectFirst("[class*=title]")?.text()?.trim()?.takeIf { it.isNotEmpty() }
            ?: link.attr("title").trim().takeIf { it.isNotEmpty() }
            ?: img?.attr("alt")?.trim()?.takeIf { it.isNotEmpty() }
            ?: return null
        val duration = link.selectFirst("div.duration")?.text()
            ?: card.selectFirst("[class*=duration]")?.text()?.trim()
        val stats = link.select("div.stats-container > div.stat-item")
            .ifEmpty { card.select("div.stats-container div.stat-item") }
        val views = stats.lastOrNull()?.ownText()?.trim()?.ifEmpty { null }
            ?: stats.getOrNull(1)?.text()?.trim()
        val subtitle = container.selectFirst("div.subtitle") ?: card.selectFirst("div.subtitle")
        val uploader = container.selectFirst("div.subtitle > a")?.text() ?: subtitle?.selectFirst("a")?.text()?.trim()
        val uploadTime = (container.selectFirst("span.subtitle-time")?.text()
            ?: subtitle?.selectFirst("span")?.text())
            ?.trim()?.removePrefix("•")?.trim()?.removePrefix("•")?.trim()
        return HanimeInfo(
            title = title,
            coverUrl = coverUrl,
            videoCode = videoCode,
            duration = duration,
            uploader = uploader,
            views = views,
            uploadTime = uploadTime,
            genre = null,
            itemType = HanimeInfo.NORMAL
        )
    }

    /**
     * 旧结构：`a[href=watch] + div.card-mobile-panel`。
     */
    private fun hanimeItemsVer2(root: Element?): MutableList<HanimeInfo> {
        val result = mutableListOf<HanimeInfo>()
        root?.select("div[class^=card-mobile-panel]")?.forEachStep2 { item ->
            hanimeNormalItemVer2(item)?.let(result::add)
        }
        return result
    }

    /**
     * 简化结构：`a[href=watch] > div.home-rows-videos-div`。
     */
    private fun hanimeItemsSimplified(root: Element?): MutableList<HanimeInfo> {
        val result = mutableListOf<HanimeInfo>()
        root?.select("div.home-rows-videos-div")?.forEach { div ->
            val a = div.closest("a") ?: return@forEach
            val videoCode = a.videoCodeFromHref() ?: return@forEach
            val coverUrl = div.selectFirst("img")?.imageUrl() ?: return@forEach
            val title = div.selectFirst("div[class$=title]")?.text() ?: return@forEach
            result += HanimeInfo(
                title = title, coverUrl = coverUrl, videoCode = videoCode,
                itemType = HanimeInfo.SIMPLIFIED
            )
        }
        return result
    }

    /** 新结构 → 旧结构 → 简化结构，哪个先解析出东西就用哪个。 */
    private fun hanimeItemsAny(root: Element?): MutableList<HanimeInfo> =
        hanimeItemsVer3(root).ifEmpty {
            runCatching { hanimeItemsVer2(root) }.getOrDefault(mutableListOf())
        }.ifEmpty {
            runCatching { hanimeItemsSimplified(root) }.getOrDefault(mutableListOf())
        }

    /** 首页某一行的标题 + 「更多」链接，用来判断是哪个分类。 */
    private fun homeRowLabel(row: Element): String {
        val sb = StringBuilder()
        row.select("h1, h2, h3, h4, h5, [class*=title]").forEach { el ->
            if (el.parents().none { it.hasClass("video-item-container") || it.hasClass("home-rows-videos-div") ||
                        it.className().contains("horizontal-card") || it.className().contains("card-mobile-panel") }
            ) sb.append(el.text()).append(' ')
        }
        row.select("a[href]").forEach { a ->
            val href = a.attr("href")
            if (!href.contains("watch")) {
                sb.append(runCatching { java.net.URLDecoder.decode(href, "UTF-8") }.getOrDefault(href)).append(' ')
            }
        }
        return sb.toString()
    }

    // </editor-fold>


    fun extractTokenFromLoginPage(body: String): String {
        val parseBody = parseHtml(body)
        return parseBody.selectFirst("input[name=_token]")?.attr("value")
            ?: throw ParseException("Can't find csrf token from login page.")
    }

    fun homePageVer2(body: String): WebsiteState<HomePage> {
        val parseBody = parseHtml(body)
        val csrfToken = parseBody.selectFirst("input[name=_token]")?.attr("value") // csrf token

        val homePageParse = parseBody.select("div[id=home-rows-wrapper] > div")
        val userInfo = parseBody.selectFirst("div[id=user-modal-dp-wrapper]")
        val avatarUrl: String? = userInfo?.selectFirst("img")?.absUrl("src")
        val username: String? = userInfo?.getElementById("user-modal-name")?.text()

        val banner = runCatching { homeBanner(parseBody) }
            .onFailure { Log.w("Parser", "home banner", it) }.getOrNull()

        val latestHanimeList = mutableListOf<HanimeInfo>()
        val latestReleaseList = mutableListOf<HanimeInfo>()
        val latestUploadList = mutableListOf<HanimeInfo>()
        val chineseSubtitleList = mutableListOf<HanimeInfo>()
        val hanimeTheyWatchedList = mutableListOf<HanimeInfo>()
        val hanimeCurrentList = mutableListOf<HanimeInfo>()
        val hotHanimeMonthlyList = mutableListOf<HanimeInfo>()

        // Nagisaland suite：先按位置取各行（本地 v0.17.2 真机验证过的写法：旧结构解析不出来就用 2026 新卡片结构）
        fun at(i: Int) = homePageParse.getOrNull(i)
        fun rowItems(row: Element?, simplified: Boolean = false): MutableList<HanimeInfo> {
            val first = runCatching {
                if (simplified) hanimeItemsSimplified(row) else hanimeItemsVer2(row)
            }.getOrDefault(mutableListOf())
            return first.ifEmpty { runCatching { hanimeItemsVer3(row) }.getOrDefault(mutableListOf()) }
        }
        latestHanimeList += rowItems(at(0), simplified = true)
        latestReleaseList += rowItems(at(1))
        latestUploadList += rowItems(at(2))
        chineseSubtitleList += rowItems(at(3))
        hanimeTheyWatchedList += rowItems(at(4))
        hanimeCurrentList += rowItems(at(homePageParse.size - 3))
        hotHanimeMonthlyList += rowItems(at(homePageParse.size - 2))

        // 按位置一行都取不到时（首页行的顺序又变了），再按每一行的标题 /「更多」链接认分类
        val allEmpty = listOf(
            latestHanimeList, latestReleaseList, latestUploadList, chineseSubtitleList,
            hanimeTheyWatchedList, hanimeCurrentList, hotHanimeMonthlyList
        ).all { it.isEmpty() }
        if (allEmpty) for (row in homePageParse) {
            val label = homeRowLabel(row)
            val target = when {
                label.contains("最新上市") -> latestReleaseList
                label.contains("最新上傳") || label.contains("最新上传") -> latestUploadList
                label.contains("中文字幕") -> chineseSubtitleList
                label.contains("他們在看") || label.contains("他们在看") -> hanimeTheyWatchedList
                label.contains("本日排行") || label.contains("今日排行") -> hanimeCurrentList
                label.contains("本月排行") -> hotHanimeMonthlyList
                (label.contains("裏番") || label.contains("里番")) &&
                        !label.contains("泡麵") && !label.contains("泡面") -> latestHanimeList
                else -> null
            } ?: continue
            if (target.isNotEmpty()) continue
            val items = runCatching { hanimeItemsAny(row) }
                .onFailure { Log.w("Parser", "home row [$label]", it) }
                .getOrDefault(mutableListOf())
            target.addAll(items)
        }

        val total = latestHanimeList.size + latestReleaseList.size + latestUploadList.size +
                chineseSubtitleList.size + hanimeTheyWatchedList.size + hanimeCurrentList.size +
                hotHanimeMonthlyList.size
        if (total == 0) {
            SuiteDebug.dumpHtml("home", body, "home page parsed 0 items, rows=${homePageParse.size}")
        }

        // emit!
        return WebsiteState.Success(
            HomePage(
                csrfToken,
                avatarUrl, username, banner = banner,
                latestHanime = latestHanimeList,
                latestRelease = latestReleaseList,
                latestUpload = latestUploadList,
                chineseSubtitle = chineseSubtitleList,
                hanimeTheyWatched = hanimeTheyWatchedList,
                hanimeCurrent = hanimeCurrentList,
                hotHanimeMonthly = hotHanimeMonthlyList,
            )
        )
    }

    private fun homeBanner(parseBody: Element): HomePage.Banner? {
        val bannerCSS = parseBody.selectFirst("div[id=home-banner-wrapper]")
        val bannerImg = bannerCSS?.previousElementSibling()
        val bannerTitle = bannerImg?.selectFirst("img")?.attr("alt")
            .logIfParseNull(Parser::homePageVer2.name, "bannerTitle")
        val bannerPic = bannerImg?.select("img")?.let { imgList ->
            imgList.getOrNull(1)?.imageUrl() ?: imgList.getOrNull(0)?.imageUrl()
        }.logIfParseNull(Parser::homePageVer2.name, "bannerPic")
        val bannerDesc = bannerCSS?.selectFirst("h4")?.ownText()
        var bannerVideoCode =
            bannerCSS?.selectFirst("a[class~=play-btn]")?.videoCodeFromHref()
        if (bannerVideoCode == null) {
            // 2026：播放按钮的地址写在脚本里
            bannerVideoCode = parseBody.select("script")
                .firstOrNull { it.data().contains("watch?v=") }
                ?.data()?.let { Regex("""watch\?v=(\d+)""").find(it)?.groupValues?.get(1) }
        }
        // 目前先判断注释里的，以后可能会有变化
        if (bannerVideoCode == null) {
            bannerCSS?.traverse { node, _ ->
                if (bannerVideoCode == null && node is Comment) {
                    node.data.toVideoCode()?.let { bannerVideoCode = it }
                }
            }
        }
        bannerVideoCode.logIfParseNull(Parser::homePageVer2.name, "bannerVideoCode")
        return if (bannerTitle != null && bannerPic != null) {
            HomePage.Banner(
                title = bannerTitle, description = bannerDesc,
                picUrl = bannerPic, videoCode = bannerVideoCode,
            )
        } else null
    }

    fun hanimeSearch(body: String): PageLoadingState<MutableList<HanimeInfo>> {
        val parseBody = parseHtml(body)
        val allContentsClass =
            parseBody.getElementsByClass("content-padding-new").firstOrNull()
        val allSimplifiedContentsClass =
            parseBody.getElementsByClass("home-rows-videos-wrapper").firstOrNull()

        // Nagisaland suite：先按 2026 年的新卡片结构解析
        val ver3 = hanimeItemsVer3(parseBody)  // 本地 v0.17.2：整页找新卡片
        if (ver3.isNotEmpty()) {
            Log.d("search_result", "ver3: $ver3")
            return PageLoadingState.Success(ver3)
        }

        // emit!
        val result = when {
            allContentsClass != null -> hanimeSearchNormalVer2(allContentsClass)
            allSimplifiedContentsClass != null -> hanimeSearchSimplified(allSimplifiedContentsClass)
            else -> PageLoadingState.Success(mutableListOf())
        }
        // 有结果容器却一个都解析不出来，多半是网页又改版了，存下来排查
        val empty = result is PageLoadingState.NoMoreData ||
                (result is PageLoadingState.Success && result.info.isEmpty())
        if (empty && (allContentsClass ?: parseBody).select("a[href*=watch]").isNotEmpty()) {
            SuiteDebug.dumpHtml("search", body, "search parsed 0 items but page has watch links")
        }
        return result
    }

    // 每一个正常视频单元
    // #issue-38: 解析錯誤，原來是加廣告了！所以遇到無法處理的直接返回null。
    private fun hanimeNormalItemVer2(hanimeSearchItem: Element): HanimeInfo? {
        val title =
            hanimeSearchItem.selectFirst("div[class=card-mobile-title]")?.text()
                .logIfParseNull(Parser::hanimeNormalItemVer2.name, "title") // title
        val coverUrl =
            hanimeSearchItem.select("img").getOrNull(1)?.absUrl("src")
                .logIfParseNull(Parser::hanimeNormalItemVer2.name, "coverUrl") // coverUrl
        val videoCode =
            hanimeSearchItem.previousElementSibling()?.absUrl("href")?.toVideoCode()
                .logIfParseNull(Parser::hanimeNormalItemVer2.name, "videoCode") // videoCode
        if (title == null || coverUrl == null || videoCode == null) return null
        val durationAndViews = hanimeSearchItem.select("div[class=card-mobile-duration]")
        val mDuration = durationAndViews.getOrNull(0)?.text() // 改了
        val views = durationAndViews.getOrNull(2)?.text() // 改了
        return HanimeInfo(
            title = title,
            coverUrl = coverUrl,
            videoCode = videoCode,
            duration = mDuration.logIfParseNull(Parser::hanimeNormalItemVer2.name, "duration"),
            uploader = null,
            views = views.logIfParseNull(Parser::hanimeNormalItemVer2.name, "views"),
            uploadTime = null,
            genre = null,
            itemType = HanimeInfo.NORMAL
        )
    }

    // 每一个简化版视频单元
    private fun hanimeSimplifiedItem(hanimeSearchItem: Element): HanimeInfo? {
        val videoCode = hanimeSearchItem.attr("href").toVideoCode()
            .logIfParseNull(Parser::hanimeSimplifiedItem.name, "videoCode")
        val coverUrl = hanimeSearchItem.selectFirst("img")?.attr("src")
            .logIfParseNull(Parser::hanimeSimplifiedItem.name, "coverUrl")
        val title = hanimeSearchItem.selectFirst("div[class=home-rows-videos-title]")?.text()
            .logIfParseNull(Parser::hanimeSimplifiedItem.name, "title")
        if (videoCode == null || coverUrl == null || title == null) return null
        return HanimeInfo(
            title = title,
            coverUrl = coverUrl,
            videoCode = videoCode,
            itemType = HanimeInfo.SIMPLIFIED
        )
    }

    // 出来后是正常视频单元的页面用这个
    private fun hanimeSearchNormalVer2(
        allContentsClass: Element,
    ): PageLoadingState<MutableList<HanimeInfo>> {
        val hanimeSearchList = mutableListOf<HanimeInfo>()
        val hanimeSearchItems =
            allContentsClass.select("div[class^=card-mobile-panel]")
        if (hanimeSearchItems.isEmpty()) {
            return PageLoadingState.NoMoreData
        } else {
            hanimeSearchItems.forEachStep2 { hanimeSearchItem ->
                hanimeNormalItemVer2(hanimeSearchItem)?.let(hanimeSearchList::add)
            }
        }
        Log.d("search_result", "$hanimeSearchList")
        return PageLoadingState.Success(hanimeSearchList)
    }

    // 出来后是简化版视频单元的页面用这个
    private fun hanimeSearchSimplified(
        allSimplifiedContentsClass: Element,
    ): PageLoadingState<MutableList<HanimeInfo>> {
        val hanimeSearchList = mutableListOf<HanimeInfo>()
        val hanimeSearchItems = allSimplifiedContentsClass.children()
        if (hanimeSearchItems.isEmpty()) {
            return PageLoadingState.NoMoreData
        } else hanimeSearchItems.forEach { hanimeSearchItem ->
            hanimeSimplifiedItem(hanimeSearchItem)?.let(hanimeSearchList::add)
        }
        return PageLoadingState.Success(hanimeSearchList)
    }

    /**
     * Nagisaland suite：2026 年 watch 页改版后，原来的相关视频解析
     * （`select("div[class^=card-mobile-panel]")[0]`）在新网页上取不到元素，
     * 抛出 `Index 0 out of bounds for length 0`，整页失败 →「播放一直报错 0」。
     * 现在标题、播放地址之外的部分（相关视频、播放清单、作者、收藏状态……）各自失败只记日志，
     * 不影响播放；解析出错或找不到播放地址时，把网页存到「内部存储/Nagisaland/debug/h-watch-*.html」。
     */
    fun hanimeVideoVer2(body: String): VideoLoadingState<HanimeVideo> {
        val state = try {
            hanimeVideoVer3(body)
        } catch (e: Exception) {
            SuiteDebug.dumpHtml("watch", body, "${e.javaClass.simpleName}: ${e.message}")
            throw e
        }
        if (state is VideoLoadingState.Success && state.info.videoUrls.isEmpty()) {
            SuiteDebug.dumpHtml("watch", body, "no video source found")
        }
        return state
    }

    private inline fun <T> optionalPart(name: String, default: T, block: () -> T): T =
        try {
            block()
        } catch (e: Exception) {
            Log.w("Parser", "hanimeVideo: $name failed", e)
            default
        }

    private fun hanimeVideoVer3(body: String): VideoLoadingState<HanimeVideo> {
        val parseBody = parseHtml(body)
        val document = parseBody.ownerDocument()
        val csrfToken = parseBody.selectFirst("input[name=_token]")?.attr("value") // csrf token

        val currentUserId =
            parseBody.selectFirst("input[name=like-user-id]")?.attr("value") // current user id

        val title = parseBody.getElementById("shareBtn-title")?.text()?.trim()?.takeIf { it.isNotEmpty() }
            ?: document?.selectFirst("meta[property=og:title]")?.attr("content")?.trim()?.takeIf { it.isNotEmpty() }
            .throwIfParseNull(Parser::hanimeVideoVer2.name, "title")

        val likeStatus = optionalPart("likeStatus", null as String?) {
            parseBody.selectFirst("[name=like-status]")?.attr("value")
        }
        val likesCount = optionalPart("likesCount", null as Int?) {
            parseBody.selectFirst("input[name=likes-count]")?.attr("value")?.toIntOrNull()
        }

        val videoDetailWrapper = parseBody.selectFirst("div.video-details-wrapper")
            ?: parseBody.selectFirst("div[class=video-details-wrapper]")
        val videoCaptionText = videoDetailWrapper?.selectFirst("div[class^=video-caption-text]")
        val chineseTitle = optionalPart("chineseTitle", null as String?) {
            videoCaptionText?.previousElementSibling()?.ownText()
        }
        val introduction = videoCaptionText?.ownText()
        val uploadTimeWithViewsGroups = optionalPart("uploadTime", null as MatchGroupCollection?) {
            val text = videoDetailWrapper?.selectFirst("div > div > div")?.text()
            (text?.let { Regex.viewAndUploadTime.find(it) }
                ?: videoDetailWrapper?.text()?.let { Regex.viewAndUploadTime.find(it) })?.groups
        }
        val uploadTime = uploadTimeWithViewsGroups?.get(2)?.value?.let { time ->
            runCatching { LocalDate.parse(time, LOCAL_DATE_FORMAT) }.getOrNull()
        }
        val views = uploadTimeWithViewsGroups?.get(1)?.value

        val tagList = optionalPart("tags", emptyList<String>()) {
            parseBody.getElementsByClass("single-video-tag").mapNotNull { tag ->
                val child = tag.childOrNull(0)
                if (child != null && child.hasAttr("href")) {
                    child.text().substringBefore(" (").removePrefix("#").trim().takeIf { it.isNotEmpty() }
                } else null
            }
        }

        val myList = optionalPart("myList", null as HanimeVideo.MyList?) {
            val myListInfo = mutableListOf<HanimeVideo.MyList.MyListInfo>()
            parseBody.select("div[class~=playlist-checkbox-wrapper]").forEach {
                val listTitle = it.selectFirst("span")?.ownText()
                    .logIfParseNull(Parser::hanimeVideoVer2.name, "myListTitle", loginNeeded = true)
                val listInput = it.selectFirst("input")
                val listCode = listInput?.attr("id")
                    .logIfParseNull(Parser::hanimeVideoVer2.name, "myListCode", loginNeeded = true)
                val isSelected = listInput?.hasAttr("checked") == true
                if (listTitle != null && listCode != null) {
                    myListInfo += HanimeVideo.MyList.MyListInfo(
                        code = listCode, title = listTitle, isSelected = isSelected
                    )
                }
            }
            val isWatchLater = parseBody.getElementById("playlist-save-checkbox")
                ?.selectFirst("input")?.hasAttr("checked") == true
            HanimeVideo.MyList(isWatchLater = isWatchLater, myListInfo = myListInfo)
        }

        val playlist = optionalPart("playlist", null as HanimeVideo.Playlist?) {
            videoPlaylist(parseBody)
        }

        val relatedAnimeList = optionalPart("related", mutableListOf<HanimeInfo>()) {
            hanimeItemsAny(parseBody.getElementById("related-tabcontent"))
        }
        Log.d("related_anime_list", relatedAnimeList.toString())

        val videoClass = parseBody.selectFirst("video[id=player]") ?: parseBody.selectFirst("video")
        val videoCoverUrl = videoClass?.absUrl("poster")?.takeIf { it.isNotBlank() }
            ?: document?.selectFirst("meta[property=og:image]")?.attr("content").orEmpty()
        val hanimeResolution = videoSources(parseBody, videoClass)

        val artist = optionalPart("artist", null as HanimeVideo.Artist?) {
            val artistAvatarUrl = parseBody
                .select("div.video-details-wrapper > div > a > div > img[style*='position: absolute'][style*='border-radius: 50%']")
                .attr("src")
            val artistNameCSS = parseBody.getElementById("video-artist-name")
            val artistGenre = artistNameCSS?.nextElementSibling()?.text()?.trim()
            val artistName = artistNameCSS?.text()?.trim()
            val postCSS = parseBody.getElementById("video-subscribe-form")
            val post = postCSS?.let {
                val userId = it.selectFirst("input[name=subscribe-user-id]")?.attr("value")
                val artistId = it.selectFirst("input[name=subscribe-artist-id]")?.attr("value")
                val isSubscribed = it.selectFirst("input[name=subscribe-status]")?.attr("value")
                if (userId != null && artistId != null && isSubscribed != null) {
                    HanimeVideo.Artist.POST(
                        userId = userId,
                        artistId = artistId,
                        isSubscribed = isSubscribed == "1"
                    )
                } else null
            }
            if (artistName != null && artistGenre != null) {
                HanimeVideo.Artist(
                    name = artistName,
                    avatarUrl = artistAvatarUrl,
                    genre = artistGenre,
                    post = post,
                )
            } else null
        }

        return VideoLoadingState.Success(
            HanimeVideo(
                title = title, coverUrl = videoCoverUrl,
                chineseTitle = chineseTitle.logIfParseNull(
                    Parser::hanimeVideoVer2.name,
                    "chineseTitle"
                ),
                uploadTime = uploadTime.logIfParseNull(Parser::hanimeVideoVer2.name, "uploadTime"),
                views = views.logIfParseNull(Parser::hanimeVideoVer2.name, "views"),
                introduction = introduction.logIfParseNull(
                    Parser::hanimeVideoVer2.name,
                    "introduction"
                ),
                videoUrls = hanimeResolution.toResolutionLinkMap(),
                tags = tagList,
                myList = myList,
                playlist = playlist,
                relatedHanimes = relatedAnimeList,
                artist = artist.logIfParseNull(Parser::hanimeVideoVer2.name, "artist"),
                favTimes = likesCount,
                // 原版：like-status 有值就当作已收藏
                isFav = !likeStatus.isNullOrEmpty(),
                csrfToken = csrfToken,
                currentUserId = currentUserId
            )
        )
    }

    /**
     * 播放地址：`video#player > source[src][size]` → 播放器脚本里的 `const source = '…'`
     * → 页面脚本里直接写的 .mp4 / .m3u8 地址。
     */
    private fun videoSources(parseBody: Element, videoClass: Element?): HanimeResolution {
        val hanimeResolution = HanimeResolution()
        var found = 0
        videoClass?.select("source")?.forEach { source ->
            val url = source.absUrl("src").ifBlank { source.attr("src") }.trim()
            if (url.isEmpty()) return@forEach
            val size = source.attr("size").ifBlank { source.attr("res") }.ifBlank { source.attr("label") }
            val resolution = size.filter(Char::isDigit).takeIf { it.isNotEmpty() }?.let { "${it}P" }
            hanimeResolution.parseResolution(resolution, url, source.attr("type"))
            found++
        }
        if (found == 0) {
            val direct = videoClass?.absUrl("src")?.trim().orEmpty()
            if (direct.isNotEmpty() && !direct.startsWith("blob:")) {
                hanimeResolution.parseResolution(null, direct)
                found++
            }
        }
        if (found == 0) {
            val scripts = (parseBody.selectFirst("div[id=player-div-wrapper]")?.select("script").orEmpty() +
                    parseBody.select("script"))
            for (script in scripts) {
                val data = script.data()
                if (data.isBlank()) continue
                val result = Regex.videoSource.find(data)?.groups?.get(1)?.value ?: continue
                hanimeResolution.parseResolution(null, result.replace("\\/", "/"))
                found++
                break
            }
        }
        if (found == 0) {
            val candidates = LinkedHashSet<String>()
            for (script in parseBody.select("script")) {
                val data = script.data().replace("\\/", "/")
                Regex.mediaUrl.findAll(data).forEach { candidates += it.value }
            }
            // 优先站点自己的媒体 CDN，其它（广告等）放后面
            candidates.sortedByDescending { url ->
                (if (url.contains("hembed") || url.contains("cdn77") || url.contains("vdownload")) 2 else 0) +
                        (if (url.contains(".m3u8")) 1 else 0)
            }.take(4).forEach { url ->
                val res = Regex.resolutionInUrl.find(url)?.groupValues?.get(1)?.let { "${it}P" }
                val full = if (url.startsWith("//")) "https:$url" else url
                hanimeResolution.parseResolution(res, full)
            }
        }
        return hanimeResolution
    }

    private fun videoPlaylist(parseBody: Element): HanimeVideo.Playlist? {
        val playlistWrapper = parseBody.selectFirst("div.video-playlist-wrapper")
            ?: parseBody.selectFirst("div[id=video-playlist-wrapper]")
            ?: return null
        val playlistScroll = playlistWrapper.getElementById("playlist-scroll") ?: return null
        val children = playlistScroll.children()
        val playlistVideoList = mutableListOf<HanimeInfo>()
        val isNewLayout = children.any { it.hasClass("playlist-hover-wrap") || it.hasAttr("data-href") }
        val playlistName = if (isNewLayout) {
            playlistWrapper.selectFirst("#playlist-top-block h4 a")?.text()
                ?: playlistWrapper.selectFirst("h4")?.text()
        } else {
            playlistWrapper.selectFirst("div > div > h4")?.text()
        }
        for (child in children) {
            if (child.tagName() == "a" && !isNewLayout) continue
            try {
                val item = if (isNewLayout) playlistItemNew(child) else playlistItemOld(child)
                item?.let(playlistVideoList::add)
            } catch (e: Exception) {
                Log.w("Parser", "playlist item", e)
            }
        }
        return HanimeVideo.Playlist(playlistName = playlistName, video = playlistVideoList)
    }

    // 2026：div.playlist-hover-wrap[data-href] > .thumb-container(img.main-thumb, .duration, .stat-item) + h4.video-title a
    private fun playlistItemNew(child: Element): HanimeInfo? {
        val videoCode = child.attr("data-href").toVideoCode()
            ?: child.selectFirst("a[href*=watch]")?.videoCodeFromHref()
            ?: return null
        val thumbContainer = child.selectFirst(".thumb-container")
        val coverUrl = (thumbContainer?.selectFirst("img.main-thumb") ?: child.selectFirst("img"))
            ?.imageUrl() ?: return null
        val title = child.selectFirst("h4.video-title a")?.text()
            ?: child.selectFirst(".video-title, .title")?.text()
            ?: child.selectFirst("img")?.attr("alt")
            ?: return null
        val duration = thumbContainer?.selectFirst(".duration")?.text()
        val views = thumbContainer?.select(".stat-item")?.getOrNull(1)?.text()
        val isPlaying = child.hasClass("videos-scroll")
        return HanimeInfo(
            title = title, coverUrl = coverUrl,
            videoCode = videoCode,
            duration = duration,
            uploader = child.selectFirst(".meta-author a")?.text(),
            views = views,
            uploadTime = child.selectFirst(".meta-stats span")?.text(),
            genre = child.selectFirst(".meta-stats a")?.text(),
            isPlaying = isPlaying,
            itemType = HanimeInfo.NORMAL
        )
    }

    // 旧结构：div > a[href=watch] + div.card-mobile-panel
    private fun playlistItemOld(parent: Element): HanimeInfo? {
        val videoCode = parent.selectFirst("div > a")?.videoCodeFromHref() ?: return null
        val cardMobilePanel = parent.selectFirst("div[class^=card-mobile-panel]")
        val eachTitleCover = cardMobilePanel?.select("div > div > div > img")?.getOrNull(1)
            ?: cardMobilePanel?.selectFirst("img")
        val eachIsPlaying = cardMobilePanel?.select("div > div > div > div")
            ?.firstOrNull()
            ?.text()
            ?.contains("播放") == true
        val cardMobileDuration = cardMobilePanel?.select("div[class*=card-mobile-duration]")
        val eachDuration = cardMobileDuration?.firstOrNull()?.text()
        val eachViews = cardMobileDuration?.getOrNull(2)?.text()?.substringBefore("次")
        val coverUrl = eachTitleCover?.imageUrl() ?: return null
        val title = eachTitleCover?.attr("alt")?.takeIf { it.isNotBlank() } ?: return null
        return HanimeInfo(
            title = title, coverUrl = coverUrl,
            videoCode = videoCode,
            duration = eachDuration,
            views = eachViews,
            isPlaying = eachIsPlaying,
            itemType = HanimeInfo.NORMAL
        )
    }

    fun hanimePreview(body: String): WebsiteState<HanimePreview> {
        val parseBody = parseHtml(body)

        // latest hanime
        val latestHanimeList = mutableListOf<HanimeInfo>()
        val latestHanimeClass = parseBody.selectFirst("div[class$=owl-theme]")
        latestHanimeClass?.let {
            val latestHanimeItems = latestHanimeClass.select("div[class=home-rows-videos-div]")
            latestHanimeItems.forEach { latestHanimeItem ->
                val coverUrl = latestHanimeItem.selectFirst("img")?.absUrl("src")
                    .throwIfParseNull(Parser::hanimePreview.name, "coverUrl")
                val title = latestHanimeItem.selectFirst("div[class$=title]")?.text()
                    .throwIfParseNull(Parser::hanimePreview.name, "title")
                latestHanimeList.add(
                    HanimeInfo(
                        coverUrl = coverUrl,
                        title = title,
                        videoCode = EMPTY_STRING /* empty string here! */,
                        itemType = HanimeInfo.SIMPLIFIED
                    )
                )
            }
        }

        val contentPaddingClass = parseBody.select("div[class=content-padding] > div")
        val previewInfo = mutableListOf<HanimePreview.PreviewInfo>()
        for (i in 0 until contentPaddingClass.size / 2) {

            val firstPart = contentPaddingClass.getOrNull(i * 2)
            val secondPart = contentPaddingClass.getOrNull(i * 2 + 1)

            val videoCode = firstPart?.id()
            val title = firstPart?.selectFirst("h4")?.text()
            val coverUrl =
                firstPart?.selectFirst("div[class=preview-info-cover] > img")?.absUrl("src")
            val previewInfoContentClass =
                firstPart?.getElementsByClass("preview-info-content-padding")?.firstOrNull()
            val videoTitle = previewInfoContentClass?.selectFirst("h4")?.text()
            val brand = previewInfoContentClass?.selectFirst("h5")?.selectFirst("a")?.text()
            val releaseDate = previewInfoContentClass?.select("h5")?.getOrNull(1)?.ownText()

            val introduction = secondPart?.selectFirst("h5")?.text()
            val tagClass = secondPart?.select("div[class=single-video-tag] > a")
            val tags = mutableListOf<String>()
            tagClass?.forEach { tag: Element? ->
                tag?.let { tags.add(tag.text()) }
            }
            val relatedPicClass = secondPart?.select("img[class=preview-image-modal-trigger]")
            val relatedPics = mutableListOf<String>()
            relatedPicClass?.forEach { relatedPic: Element? ->
                relatedPic?.let { relatedPics.add(relatedPic.absUrl("src")) }
            }

            previewInfo.add(
                HanimePreview.PreviewInfo(
                    title = title,
                    videoTitle = videoTitle,
                    coverUrl = coverUrl,
                    introduction = introduction.logIfParseNull(
                        Parser::hanimePreview.name,
                        "$title introduction"
                    ),
                    brand = brand.logIfParseNull(Parser::hanimePreview.name, "$title brand"),
                    releaseDate = releaseDate.logIfParseNull(
                        Parser::hanimePreview.name,
                        "$title releaseDate"
                    ),
                    videoCode = videoCode.logIfParseNull(
                        Parser::hanimePreview.name,
                        "$title videoCode"
                    ),
                    tags = tags,
                    relatedPicsUrl = relatedPics
                )
            )
        }

        val header = parseBody.selectFirst("div[id=player-div-wrapper]")
        val headerPicUrl = header?.selectFirst("img")?.absUrl("src")
        val hasPrevious = parseBody.getElementsByClass("hidden-md hidden-lg").firstOrNull()
            ?.select("div[style*=left]")?.firstOrNull() != null
        val hasNext = parseBody.getElementsByClass("hidden-md hidden-lg").firstOrNull()
            ?.select("div[style*=right]")?.firstOrNull() != null

        return WebsiteState.Success(
            HanimePreview(
                headerPicUrl = headerPicUrl.logIfParseNull(
                    Parser::hanimePreview.name,
                    "headerPicUrl"
                ),
                hasPrevious = hasPrevious,
                hasNext = hasNext,
                latestHanime = latestHanimeList,
                previewInfo = previewInfo
            )
        )
    }

    fun myListItems(body: String): PageLoadingState<MyListItems<HanimeInfo>> {
        val parseBody = parseHtml(body)
        val csrfToken = parseBody.selectFirst("input[name=_token]")?.attr("value")
        val desc = parseBody.getElementById("playlist-show-description")?.ownText()

        // Nagisaland suite：2026 年列表页是 div.horizontal-row > div.user-tab-item-wrapper（新卡片）
        val newRoot = parseBody.getElementsByClass("horizontal-row").firstOrNull()
        val ver3 = hanimeItemsVer3(newRoot).ifEmpty {
            newRoot?.select("div[class^=user-tab-item-wrapper]")
                ?.mapNotNull { runCatching { hanimeItemVer3(it) }.getOrNull() }
                ?.distinctBy { it.videoCode }?.toMutableList() ?: mutableListOf()
        }
        if (ver3.isNotEmpty()) {
            return PageLoadingState.Success(MyListItems(ver3, desc = desc, csrfToken = csrfToken))
        }

        val myListHanimeList = mutableListOf<HanimeInfo>()
        val allHanimeClass = parseBody.getElementsByClass("home-rows-videos-wrapper").firstOrNull()
        if (allHanimeClass == null && newRoot != null) {
            if (newRoot.select("a[href*=watch]").isEmpty()) return PageLoadingState.NoMoreData
            SuiteDebug.dumpHtml("mylist", body, "list page parsed 0 items")
        }
        allHanimeClass?.let {
            if (allHanimeClass.childrenSize() == 0) {
                return PageLoadingState.NoMoreData
            }
            allHanimeClass.children().forEach { videoElement ->
                val title =
                    videoElement.getElementsByClass("home-rows-videos-title")
                        .firstOrNull()?.text()
                        .logIfParseNull(Parser::myListItems.name, "title") ?: return@forEach
                val coverUrl =
                    videoElement.select("img").let {
                        it.getOrNull(1) ?: it.firstOrNull()
                    }?.imageUrl()
                        .logIfParseNull(Parser::myListItems.name, "coverUrl") ?: return@forEach
                val videoCode =
                    (videoElement.getElementsByClass("playlist-show-links").firstOrNull()
                        ?: videoElement.selectFirst("a[href*=watch]"))?.videoCodeFromHref()
                        .logIfParseNull(Parser::myListItems.name, "videoCode") ?: return@forEach
                myListHanimeList.add(
                    HanimeInfo(
                        title = title, coverUrl = coverUrl,
                        videoCode = videoCode, itemType = HanimeInfo.SIMPLIFIED
                    )
                )
            }
        }.logIfParseNull(Parser::myListItems.name, "allHanimeClass_CSS")

        return PageLoadingState.Success(
            MyListItems(
                myListHanimeList,
                desc = desc,
                csrfToken = csrfToken
            )
        )
    }

    fun subscriptionItems(body: String): PageLoadingState<MyListItems<Subscription>> {
        val parseBody = parseHtml(body)
        val csrfToken = parseBody.selectFirst("input[name=_token]")?.attr("value")

        val subscriptionList = mutableListOf<Subscription>()
        val allArtistsClass = parseBody.getElementsByClass("home-rows-videos-wrapper").firstOrNull()
        allArtistsClass?.let { artistClass ->
            if (allArtistsClass.childrenSize() == 0) {
                return PageLoadingState.NoMoreData
            }
            allArtistsClass.children().forEach { artistElement ->
                val title = artistElement.selectFirst("div[class$=search-artist-title]")?.text()
                    .logIfParseNull(Parser::subscriptionItems.name, "title", loginNeeded = true)
                val avatarUrl = artistElement.select("img").let {
                    it.getOrNull(1) ?: it.firstOrNull()
                }?.absUrl("src")
                    .logIfParseNull(Parser::subscriptionItems.name, "avatarUrl", loginNeeded = true)
                val artistId =
                    artistElement.selectFirst("input[name=playlist-show-video-id]")
                        ?.attr("value").logIfParseNull(
                            Parser::subscriptionItems.name, "artistId", loginNeeded = true
                        )
                if (title != null) {
                    subscriptionList += Subscription(
                        name = title, avatarUrl = avatarUrl,
                        artistId = artistId
                    )
                }
                println("-------------------$subscriptionList")
            }
        }.logIfParseNull(Parser::subscriptionItems.name, "allArtistsClass_CSS")

        return PageLoadingState.Success(
            MyListItems(
                subscriptionList,
                desc = null,
                csrfToken = csrfToken
            )
        )
    }

    fun playlists(body: String): WebsiteState<Playlists> {
        val parseBody = parseHtml(body)
        val csrfToken = parseBody.selectFirst("input[name=_token]")?.attr("value")
        val lists = parseBody.select("div[class~=single-user-playlist]")
        val playlists = mutableListOf<Playlists.Playlist>()
        lists.forEach {
            val listCode = it.childOrNull(0)?.absUrl("href")?.substringAfter('=')
                .throwIfParseNull(Parser::playlists.name, "listCode")
            val listTitle = it.selectFirst("div[class=card-mobile-title]")?.ownText()
                .throwIfParseNull(Parser::playlists.name, "listTitle")
            val listTotal = it.selectFirst("div[style]")?.text()?.toIntOrNull()
                .throwIfParseNull(Parser::playlists.name, "listName")
            playlists += Playlists.Playlist(
                listCode = listCode, title = listTitle, total = listTotal
            )
        }
        return WebsiteState.Success(Playlists(playlists = playlists, csrfToken = csrfToken))
    }

    @SuppressLint("BuildListAdds")
    fun comments(body: String): WebsiteState<VideoComments> {
        val jsonObject = JSONObject(body)
        val commentBody = jsonObject.get("comments").toString()
        val parseBody = Jsoup.parse(commentBody).body()
        val csrfToken = parseBody.selectFirst("input[name=_token]")?.attr("value")
        val currentUserId = parseBody.selectFirst("input[name=comment-user-id]")?.attr("value")
        val commentList = mutableListOf<VideoComments.VideoComment>()
        val allCommentsClass = parseBody.getElementById("comment-start")

        buildList {
            allCommentsClass?.children()?.chunked(5)?.forEach { elements ->
                this += Element("div").apply { appendChildren(elements) }
            }
        }.forEach { child: Element ->
            val avatarUrl = child.selectFirst("img")?.absUrl("src")
                .throwIfParseNull(Parser::comments.name, "avatarUrl")
            val textClass = child.getElementsByClass("comment-index-text")
            val nameAndDateClass = textClass.firstOrNull()
            val username = nameAndDateClass?.selectFirst("a")?.ownText()?.trim()
                .throwIfParseNull(Parser::comments.name, "username")
            val date = nameAndDateClass?.selectFirst("span")?.ownText()?.trim()
                .throwIfParseNull(Parser::comments.name, "date")
            val content = textClass.getOrNull(1)?.text()
                .throwIfParseNull(Parser::comments.name, "content")
            val hasMoreReplies = child.selectFirst("div[class^=load-replies-btn]") != null
            val thumbUp = child.getElementById("comment-like-form-wrapper")
                ?.select("span[style]")?.getOrNull(1)
                ?.text()?.toIntOrNull()
            val id = child.selectFirst("div[id^=reply-section-wrapper]")
                ?.id()?.substringAfterLast("-")

            val foreignId = child.getElementById("foreign_id")?.attr("value")
            val isPositive = child.getElementById("is_positive")?.attr("value")
            val likeUserId = child.selectFirst("input[name=comment-like-user-id]")?.attr("value")
            val commentLikesCount =
                child.selectFirst("input[name=comment-likes-count]")?.attr("value")
            val commentLikesSum = child.selectFirst("input[name=comment-likes-sum]")?.attr("value")
            val likeCommentStatus =
                child.selectFirst("input[name=like-comment-status]")?.attr("value")
            val unlikeCommentStatus =
                child.selectFirst("input[name=unlike-comment-status]")?.attr("value")

            val post = VideoComments.VideoComment.POST(
                foreignId.logIfParseNull(Parser::comments.name, "foreignId", loginNeeded = true),
                isPositive == "1",
                likeUserId.logIfParseNull(Parser::comments.name, "likeUserId", loginNeeded = true),
                commentLikesCount?.toIntOrNull().logIfParseNull(
                    Parser::comments.name,
                    "commentLikesCount", loginNeeded = true
                ),
                commentLikesSum?.toIntOrNull().logIfParseNull(
                    Parser::comments.name,
                    "commentLikesSum", loginNeeded = true
                ),
                likeCommentStatus == "1",
                unlikeCommentStatus == "1",
            )
            commentList.add(
                VideoComments.VideoComment(
                    avatar = avatarUrl, username = username, date = date,
                    content = content, hasMoreReplies = hasMoreReplies,
                    thumbUp = thumbUp.logIfParseNull(Parser::comments.name, "thumbUp"),
                    id = id.logIfParseNull(Parser::comments.name, "id"),
                    isChildComment = false, post = post
                )
            )
        }
        Log.d("commentList", commentList.toString())
        return WebsiteState.Success(
            VideoComments(
                commentList,
                currentUserId,
                csrfToken
            )
        )
    }

    fun commentReply(body: String): WebsiteState<VideoComments> {
        val jsonObject = JSONObject(body)
        val replyBody = jsonObject.get("replies").toString()
        val replyList = mutableListOf<VideoComments.VideoComment>()
        val parseBody = Jsoup.parse(replyBody).body()
        val replyStart = parseBody.selectFirst("div[id^=reply-start]")
        replyStart?.let {
            val allRepliesClass = it.children()
            for (i in allRepliesClass.indices step 2) {
                val basicClass = allRepliesClass.getOrNull(i)
                val postClass = allRepliesClass.getOrNull(i + 1)

                val avatarUrl = basicClass?.selectFirst("img")?.absUrl("src")
                    .throwIfParseNull(Parser::commentReply.name, "avatarUrl")
                val textClass = basicClass?.getElementsByClass("comment-index-text")
                val nameAndDateClass = textClass?.firstOrNull()
                val username = nameAndDateClass?.selectFirst("a")?.ownText()?.trim()
                    .throwIfParseNull(Parser::commentReply.name, "name")
                val date = nameAndDateClass?.selectFirst("span")?.ownText()?.trim()
                    .throwIfParseNull(Parser::commentReply.name, "date")
                val content = textClass?.getOrNull(1)?.text()
                    .throwIfParseNull(Parser::commentReply.name, "content")
                val thumbUp = postClass
                    ?.select("span[style]")?.getOrNull(1)
                    ?.text()?.toIntOrNull()

                val foreignId =
                    postClass?.getElementById("foreign_id")?.attr("value")
                val isPositive =
                    postClass?.getElementById("is_positive")?.attr("value")
                val likeUserId =
                    postClass?.selectFirst("input[name=comment-like-user-id]")?.attr("value")
                val commentLikesCount =
                    postClass?.selectFirst("input[name=comment-likes-count]")?.attr("value")
                val commentLikesSum =
                    postClass?.selectFirst("input[name=comment-likes-sum]")?.attr("value")
                val likeCommentStatus =
                    postClass?.selectFirst("input[name=like-comment-status]")?.attr("value")
                val unlikeCommentStatus =
                    postClass?.selectFirst("input[name=unlike-comment-status]")?.attr("value")
                val post = VideoComments.VideoComment.POST(
                    foreignId.logIfParseNull(
                        Parser::commentReply.name,
                        "foreignId",
                        loginNeeded = true
                    ),
                    isPositive == "1",
                    likeUserId.logIfParseNull(
                        Parser::commentReply.name,
                        "likeUserId",
                        loginNeeded = true
                    ),
                    commentLikesCount?.toIntOrNull().logIfParseNull(
                        Parser::commentReply.name,
                        "commentLikesCount", loginNeeded = true
                    ),
                    commentLikesSum?.toIntOrNull().logIfParseNull(
                        Parser::commentReply.name,
                        "commentLikesSum", loginNeeded = true
                    ),
                    likeCommentStatus == "1",
                    unlikeCommentStatus == "1",
                )
                replyList.add(
                    VideoComments.VideoComment(
                        avatar = avatarUrl, username = username, date = date,
                        content = content,
                        thumbUp = thumbUp.logIfParseNull(Parser::commentReply.name, "thumbUp"),
                        id = null,
                        isChildComment = true, post = post
                    )
                )
            }
        }

        return WebsiteState.Success(VideoComments(replyList))
    }

    /**
     * 這個網站的網頁結構真的很奇怪，所以我寫了一個 forEachStep2 來處理
     */
    private inline fun Elements.forEachStep2(action: (Element) -> Unit) {
        for (i in 0 until size step 2) {
            action(get(i))
        }
    }

    /**
     * 得到 Element 的 child，如果 index 超出範圍，就返回 null
     */
    private fun Element.childOrNull(index: Int): Element? {
        return try {
            child(index)
        } catch (_: IndexOutOfBoundsException) {
            null
        }
    }

    /**
     * 基本都是必需的參數，所以如果是 null，就直接丟出 [ParseException]
     *
     * @param funcName 這個參數是在哪個函數中被使用的
     * @param varName 這個參數的名稱
     * @return 如果 [this] 不是 null，就回傳 [this]
     * @throws ParseException 如果 [this] 是 null，就丟出 [ParseException]
     */
    private fun <T> T?.throwIfParseNull(funcName: String, varName: String): T = this
        ?: throw ParseException(funcName, varName)

    /**
     * 如果 [this] 是 null，就在 logcat 中顯示訊息
     *
     * @param funcName 這個參數是在哪個函數中被使用的
     * @param varName 這個參數的名稱
     * @return 回傳 [this]
     */
    private fun <T> T?.logIfParseNull(
        funcName: String, varName: String, loginNeeded: Boolean = false,
    ): T? = also {
        if (it == null) {
            if (loginNeeded) {
                if (isAlreadyLogin) {
                    Log.d("Parse::$funcName", "[$varName] is null. 而且處於登入狀態，這有點不正常")
                }
            } else {
                Log.d("Parse::$funcName", "[$varName] is null. 這有點不正常")
            }
        }
    }
}