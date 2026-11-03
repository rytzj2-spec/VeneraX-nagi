import 'package:flutter/material.dart';

import '../nagi_theme.dart';
import 'detail_page.dart';
import 'emby_api.dart';
import 'widgets.dart';

/// 打开任意条目：媒体库 / 文件夹 → 列表；其它 → 详情
void openItem(BuildContext context, MediaItem item) {
  if (item.type == 'CollectionFolder' || item.type == 'UserView' || item.type == 'Folder' || item.type == 'BoxSet') {
    Navigator.of(context).push(MaterialPageRoute(builder: (_) => LibraryPage(item)));
  } else {
    DetailPage.open(context, item);
  }
}

/// 一个服务器的首页：继续观看、下一集、各媒体库、各库最新
class ServerHomePage extends StatefulWidget {
  final MediaServer server;
  const ServerHomePage(this.server, {super.key});

  @override
  State<ServerHomePage> createState() => _ServerHomePageState();
}

class _ServerHomePageState extends State<ServerHomePage> {
  String? error;
  bool loading = true;
  List<MediaItem> views = [], resume = [], nextUp = [];
  final Map<String, List<MediaItem>> latest = {};

  EmbyApi get api => EmbyApi(widget.server);

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    setState(() {
      loading = true;
      error = null;
    });
    try {
      final r = await Future.wait([api.views(), api.resume(), api.nextUp().catchError((_) => <MediaItem>[])]);
      views = r[0];
      resume = r[1];
      nextUp = r[2];
      latest.clear();
      final media = views.where((v) => v.collectionType != 'music' && v.collectionType != 'books' && v.collectionType != 'playlists').toList();
      final lists = await Future.wait(media.map((v) => api.latest(parentId: v.id).catchError((_) => <MediaItem>[])));
      for (var i = 0; i < media.length; i++) {
        latest[media[i].id] = lists[i];
      }
    } catch (e) {
      error = EmbyApi.describeError(e);
    }
    if (mounted) setState(() => loading = false);
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: Text(widget.server.name),
        actions: [
          IconButton(
            icon: const Icon(Icons.search),
            onPressed: () => Navigator.of(context).push(MaterialPageRoute(builder: (_) => SearchPage(servers: [widget.server]))),
          ),
        ],
      ),
      body: loading
          ? const Center(child: CircularProgressIndicator())
          : error != null
              ? ErrorView(error!, onRetry: _load)
              : RefreshIndicator(
                  onRefresh: _load,
                  child: ListView(children: [
                    if (views.isNotEmpty)
                      Shelf(
                        title: '媒体库',
                        height: 92,
                        children: [for (final v in views) _LibraryTile(v, onTap: () => openItem(context, v))],
                      ),
                    Shelf(
                      title: '继续观看',
                      height: 170,
                      children: [for (final it in resume) ThumbCard(it, onTap: () => openItem(context, it))],
                    ),
                    Shelf(
                      title: '下一集',
                      height: 170,
                      children: [for (final it in nextUp) ThumbCard(it, onTap: () => openItem(context, it))],
                    ),
                    for (final v in views)
                      if ((latest[v.id] ?? const []).isNotEmpty)
                        Shelf(
                          title: '最新 · ${v.name}',
                          height: 215,
                          onMore: () => openItem(context, v),
                          children: [for (final it in latest[v.id]!) PosterCard(it, onTap: () => openItem(context, it))],
                        ),
                    const SizedBox(height: 24),
                  ]),
                ),
    );
  }
}

class _LibraryTile extends StatelessWidget {
  final MediaItem view;
  final VoidCallback onTap;
  const _LibraryTile(this.view, {required this.onTap});

  @override
  Widget build(BuildContext context) {
    return SizedBox(
      width: 160,
      child: InkWell(
        onTap: onTap,
        borderRadius: BorderRadius.circular(10),
        child: ClipRRect(
          borderRadius: BorderRadius.circular(10),
          child: Stack(fit: StackFit.expand, children: [
            NetImage(view.thumbUrl(maxWidth: 400) ?? view.posterUrl(), view.server, placeholder: Icons.video_library_outlined),
            const DecoratedBox(decoration: BoxDecoration(color: Color(0x880F0F12))),
            Center(
              child: Text(view.name,
                  textAlign: TextAlign.center, style: const TextStyle(fontSize: 15, fontWeight: FontWeight.w600, color: NagiColors.text)),
            ),
          ]),
        ),
      ),
    );
  }
}

/// 媒体库 / 文件夹：分页网格，可排序
class LibraryPage extends StatefulWidget {
  final MediaItem parent;
  const LibraryPage(this.parent, {super.key});

  @override
  State<LibraryPage> createState() => _LibraryPageState();
}

class _LibraryPageState extends State<LibraryPage> {
  final items = <MediaItem>[];
  int total = 0;
  bool loading = false;
  String? error;
  String sortBy = 'SortName';
  String sortOrder = 'Ascending';
  final scroll = ScrollController();

  static const sorts = {
    'SortName': '名称',
    'DateCreated': '加入时间',
    'PremiereDate': '上映时间',
    'CommunityRating': '评分',
    'DatePlayed': '最近播放',
  };

  EmbyApi get api => EmbyApi(widget.parent.server);

  /// 电影库 / 剧集库只列电影和剧（不然会把每一集都列出来）；其它文件夹按文件夹浏览
  bool get _typed => widget.parent.collectionType == 'movies' || widget.parent.collectionType == 'tvshows';

  @override
  void initState() {
    super.initState();
    scroll.addListener(() {
      if (scroll.position.pixels > scroll.position.maxScrollExtent - 600) _more();
    });
    _more();
  }

  @override
  void dispose() {
    scroll.dispose();
    super.dispose();
  }

  Future<void> _reload() async {
    items.clear();
    total = 0;
    await _more(force: true);
  }

  Future<void> _more({bool force = false}) async {
    if (loading || (!force && items.isNotEmpty && items.length >= total)) return;
    setState(() {
      loading = true;
      error = null;
    });
    try {
      final (list, t) = await api.items(
        parentId: widget.parent.id,
        includeTypes: _typed ? (widget.parent.collectionType == 'movies' ? 'Movie' : 'Series') : '',
        recursive: _typed,
        sortBy: sortBy,
        sortOrder: sortOrder,
        start: items.length,
      );
      items.addAll(list);
      total = t;
    } catch (e) {
      error = EmbyApi.describeError(e);
    }
    if (mounted) setState(() => loading = false);
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: Text(widget.parent.name),
        actions: [
          PopupMenuButton<String>(
            icon: const Icon(Icons.sort),
            onSelected: (v) {
              if (v == '_order') {
                sortOrder = sortOrder == 'Ascending' ? 'Descending' : 'Ascending';
              } else {
                sortBy = v;
                sortOrder = v == 'SortName' ? 'Ascending' : 'Descending';
              }
              _reload();
            },
            itemBuilder: (_) => [
              for (final e in sorts.entries) CheckedPopupMenuItem(value: e.key, checked: sortBy == e.key, child: Text(e.value)),
              const PopupMenuDivider(),
              PopupMenuItem(value: '_order', child: Text(sortOrder == 'Ascending' ? '改为倒序' : '改为正序')),
            ],
          ),
        ],
      ),
      body: error != null && items.isEmpty
          ? ErrorView(error!, onRetry: _reload)
          : items.isEmpty && !loading
              ? const EmptyView('这里还没有内容')
              : RefreshIndicator(
                  onRefresh: _reload,
                  child: GridView.builder(
                    controller: scroll,
                    padding: const EdgeInsets.all(12),
                    gridDelegate: const SliverGridDelegateWithMaxCrossAxisExtent(
                      maxCrossAxisExtent: 130,
                      childAspectRatio: 0.52,
                      crossAxisSpacing: 10,
                      mainAxisSpacing: 6,
                    ),
                    itemCount: items.length + (loading ? 1 : 0),
                    itemBuilder: (_, i) => i >= items.length
                        ? const Center(child: CircularProgressIndicator())
                        : PosterCard(items[i], width: double.infinity, onTap: () => openItem(context, items[i])),
                  ),
                ),
    );
  }
}

/// 搜索（一个或多个服务器一起搜）
class SearchPage extends StatefulWidget {
  final List<MediaServer> servers;
  const SearchPage({super.key, required this.servers});

  @override
  State<SearchPage> createState() => _SearchPageState();
}

class _SearchPageState extends State<SearchPage> {
  final ctrl = TextEditingController();
  List<MediaItem> results = [];
  final errors = <String>[];
  bool loading = false;

  Future<void> _search() async {
    final q = ctrl.text.trim();
    if (q.isEmpty) return;
    setState(() {
      loading = true;
      errors.clear();
    });
    final lists = await Future.wait(widget.servers.map((s) async {
      try {
        final (l, _) = await EmbyApi(s).items(searchTerm: q, includeTypes: 'Movie,Series,Episode,Video', limit: 40);
        return l;
      } catch (e) {
        errors.add('${s.name}：${EmbyApi.describeError(e)}');
        return <MediaItem>[];
      }
    }));
    results = lists.expand((l) => l).toList();
    if (mounted) setState(() => loading = false);
  }

  @override
  void dispose() {
    ctrl.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final multi = widget.servers.length > 1;
    return Scaffold(
      appBar: AppBar(
        title: TextField(
          controller: ctrl,
          autofocus: true,
          textInputAction: TextInputAction.search,
          onSubmitted: (_) => _search(),
          decoration: InputDecoration(hintText: multi ? '在所有服务器里搜索' : '搜索', border: InputBorder.none),
        ),
        actions: [IconButton(onPressed: _search, icon: const Icon(Icons.search))],
      ),
      body: loading
          ? const Center(child: CircularProgressIndicator())
          : ListView(children: [
              for (final e in errors) Padding(padding: const EdgeInsets.fromLTRB(16, 8, 16, 0), child: Text(e, style: const TextStyle(color: NagiColors.sub))),
              if (results.isEmpty && ctrl.text.isNotEmpty && errors.isEmpty) const Padding(padding: EdgeInsets.only(top: 80), child: EmptyView('没有找到')),
              Padding(
                padding: const EdgeInsets.all(12),
                child: Wrap(spacing: 10, runSpacing: 8, children: [
                  for (final it in results) PosterCard(it, showServer: multi, onTap: () => openItem(context, it)),
                ]),
              ),
            ]),
    );
  }
}
