import 'package:flutter/material.dart';

import '../nagi_theme.dart';
import 'emby_api.dart';
import 'player_page.dart';
import 'widgets.dart';

String _fmt(int ticks) {
  final d = Duration(microseconds: ticks ~/ 10);
  final h = d.inHours, m = d.inMinutes % 60, s = d.inSeconds % 60;
  return h > 0 ? '$h:${m.toString().padLeft(2, '0')}:${s.toString().padLeft(2, '0')}' : '$m:${s.toString().padLeft(2, '0')}';
}

/// 电影 / 剧 / 季 / 单集的详情。剧：选季、选集；电影和单集：播放 / 继续播放
class DetailPage extends StatefulWidget {
  final MediaItem item;
  const DetailPage(this.item, {super.key});

  static void open(BuildContext context, MediaItem item) {
    Navigator.of(context).push(MaterialPageRoute(builder: (_) => DetailPage(item)));
  }

  @override
  State<DetailPage> createState() => _DetailPageState();
}

class _DetailPageState extends State<DetailPage> {
  MediaItem? item;
  String? error;
  List<MediaItem> seasons = [];
  String? seasonId;
  List<MediaItem> episodes = [];
  bool loadingEpisodes = false;

  EmbyApi get api => EmbyApi(widget.item.server);

  /// 剧的 id：本身是剧就是自己，季 / 集取 SeriesId
  String? get seriesId {
    final it = item ?? widget.item;
    if (it.type == 'Series') return it.id;
    return it.seriesId;
  }

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    setState(() => error = null);
    try {
      final it = await api.item(widget.item.id);
      item = it;
      final sid = seriesId;
      if (sid != null && (it.type == 'Series' || it.type == 'Season')) {
        seasons = await api.seasons(sid);
        seasonId = it.type == 'Season' ? it.id : (seasons.isNotEmpty ? seasons.first.id : null);
        // 有看到一半的季就默认选它
        if (it.type == 'Series') {
          final next = await api.nextUp(limit: 50).then((l) => l.where((e) => e.seriesId == sid).toList()).catchError((_) => <MediaItem>[]);
          if (next.isNotEmpty && next.first.seasonId != null && seasons.any((s) => s.id == next.first.seasonId)) {
            seasonId = next.first.seasonId;
          }
        }
        await _loadEpisodes();
      } else if (sid != null && it.type == 'Episode') {
        // 单集（从「继续观看」进来）：取同一季的集，用来播完自动下一集
        seasonId = it.seasonId;
        await _loadEpisodes();
      }
      if (mounted) setState(() {});
    } catch (e) {
      if (mounted) setState(() => error = EmbyApi.describeError(e));
    }
  }

  Future<void> _loadEpisodes() async {
    final sid = seriesId;
    if (sid == null) return;
    setState(() => loadingEpisodes = true);
    try {
      episodes = await api.episodes(sid, seasonId: seasonId);
    } catch (e) {
      episodes = [];
    }
    if (mounted) setState(() => loadingEpisodes = false);
  }

  Future<void> _play(MediaItem target, {bool fromStart = false}) async {
    final list = target.type == 'Episode' ? (episodes.isNotEmpty ? episodes : <MediaItem>[]) : <MediaItem>[];
    await Navigator.of(context).push(MaterialPageRoute(
      builder: (_) => VideoPlayerPage(item: target, playlist: list, fromStart: fromStart),
    ));
    // 回来后刷新进度
    _load();
  }

  /// 剧：继续看的那一集（第一集没看完的），没有就第一集
  MediaItem? get _resumeEpisode {
    if (episodes.isEmpty) return null;
    return episodes.firstWhere((e) => e.progress > 0 && !e.played, orElse: () => episodes.firstWhere((e) => !e.played, orElse: () => episodes.first));
  }

  @override
  Widget build(BuildContext context) {
    final it = item;
    if (error != null) {
      return Scaffold(appBar: AppBar(title: Text(widget.item.name)), body: ErrorView(error!, onRetry: _load));
    }
    if (it == null) {
      return Scaffold(appBar: AppBar(title: Text(widget.item.name)), body: const Center(child: CircularProgressIndicator()));
    }
    final isShow = it.type == 'Series' || it.type == 'Season';
    final target = isShow ? _resumeEpisode : it;
    final meta = [
      if (it.year != null) '${it.year}',
      if (it.rating != null) '★ ${it.rating!.toStringAsFixed(1)}',
      if (!isShow && it.runTimeTicks > 0) _fmt(it.runTimeTicks),
      kindLabel(it.server.kind),
      it.server.name,
    ].join('  ·  ');
    return Scaffold(
      body: CustomScrollView(slivers: [
        SliverAppBar(
          pinned: true,
          expandedHeight: 230,
          title: Text(it.type == 'Episode' ? (it.seriesName ?? it.name) : it.name, maxLines: 1, overflow: TextOverflow.ellipsis),
          flexibleSpace: FlexibleSpaceBar(
            background: Stack(fit: StackFit.expand, children: [
              NetImage(it.backdropUrl() ?? it.thumbUrl(), it.server),
              const DecoratedBox(
                decoration: BoxDecoration(
                  gradient: LinearGradient(
                    begin: Alignment.topCenter,
                    end: Alignment.bottomCenter,
                    colors: [Color(0x660F0F12), Color(0x000F0F12), NagiColors.bg],
                    stops: [0, 0.45, 1],
                  ),
                ),
              ),
            ]),
          ),
        ),
        SliverToBoxAdapter(
          child: Padding(
            padding: const EdgeInsets.fromLTRB(16, 4, 16, 8),
            child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              if (it.type == 'Episode') Text(it.displayTitle, style: const TextStyle(fontSize: 17, fontWeight: FontWeight.w600)),
              Text(meta, style: const TextStyle(color: NagiColors.sub, fontSize: 12.5)),
              const SizedBox(height: 12),
              if (target != null)
                Row(children: [
                  Expanded(
                    child: _GradientButton(
                      icon: Icons.play_arrow_rounded,
                      label: target.progress > 0 && !target.played
                          ? '继续 ${isShow ? target.displayTitle : ''}（${_fmt(target.positionTicks)}）'
                          : '播放${isShow ? ' ${target.displayTitle}' : ''}',
                      onTap: () => _play(target),
                    ),
                  ),
                  if (target.progress > 0) ...[
                    const SizedBox(width: 8),
                    OutlinedButton(onPressed: () => _play(target, fromStart: true), child: const Text('从头')),
                  ],
                ]),
              if (it.overview.isNotEmpty) ...[
                const SizedBox(height: 14),
                Text(it.overview, style: const TextStyle(color: NagiColors.sub, height: 1.6, fontSize: 13.5)),
              ],
            ]),
          ),
        ),
        if (isShow && seasons.length > 1)
          SliverToBoxAdapter(
            child: SizedBox(
              height: 44,
              child: ListView(
                scrollDirection: Axis.horizontal,
                padding: const EdgeInsets.symmetric(horizontal: 12),
                children: [
                  for (final s in seasons)
                    Padding(
                      padding: const EdgeInsets.symmetric(horizontal: 4),
                      child: ChoiceChip(
                        label: Text(s.name),
                        selected: s.id == seasonId,
                        onSelected: (_) {
                          setState(() => seasonId = s.id);
                          _loadEpisodes();
                        },
                      ),
                    ),
                ],
              ),
            ),
          ),
        if (isShow && loadingEpisodes)
          const SliverToBoxAdapter(child: Padding(padding: EdgeInsets.all(24), child: Center(child: CircularProgressIndicator()))),
        if (isShow && !loadingEpisodes)
          SliverList.builder(
            itemCount: episodes.length,
            itemBuilder: (_, i) => _EpisodeTile(episodes[i], onTap: () => _play(episodes[i]), onToggle: () async {
              await api.setPlayed(episodes[i].id, !episodes[i].played);
              _loadEpisodes();
            }),
          ),
        const SliverToBoxAdapter(child: SizedBox(height: 24)),
      ]),
    );
  }
}

class _EpisodeTile extends StatelessWidget {
  final MediaItem ep;
  final VoidCallback onTap;
  final VoidCallback onToggle;
  const _EpisodeTile(this.ep, {required this.onTap, required this.onToggle});

  @override
  Widget build(BuildContext context) {
    return InkWell(
      onTap: onTap,
      onLongPress: onToggle,
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 6),
        child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
          SizedBox(
            width: 150,
            child: AspectRatio(
              aspectRatio: 16 / 9,
              child: ClipRRect(
                borderRadius: BorderRadius.circular(8),
                child: Stack(fit: StackFit.expand, children: [
                  NetImage(ep.thumbUrl(maxWidth: 400), ep.server),
                  if (ep.progress > 0 && !ep.played) Positioned(left: 0, right: 0, bottom: 0, child: ProgressStripe(ep.progress)),
                ]),
              ),
            ),
          ),
          const SizedBox(width: 12),
          Expanded(
            child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              Row(children: [
                Expanded(child: Text(ep.displayTitle, maxLines: 2, overflow: TextOverflow.ellipsis, style: const TextStyle(fontSize: 14))),
                if (ep.played) const Icon(Icons.check_circle, size: 16, color: NagiColors.violet),
              ]),
              if (ep.runTimeTicks > 0)
                Text(_fmt(ep.runTimeTicks), style: const TextStyle(color: NagiColors.muted, fontSize: 12)),
              if (ep.overview.isNotEmpty)
                Text(ep.overview, maxLines: 2, overflow: TextOverflow.ellipsis, style: const TextStyle(color: NagiColors.sub, fontSize: 12)),
            ]),
          ),
        ]),
      ),
    );
  }
}

class _GradientButton extends StatelessWidget {
  final IconData icon;
  final String label;
  final VoidCallback onTap;
  const _GradientButton({required this.icon, required this.label, required this.onTap});

  @override
  Widget build(BuildContext context) {
    return DecoratedBox(
      decoration: BoxDecoration(gradient: NagiColors.selectedGradient, borderRadius: BorderRadius.circular(10)),
      child: Material(
        color: Colors.transparent,
        child: InkWell(
          borderRadius: BorderRadius.circular(10),
          onTap: onTap,
          child: Padding(
            padding: const EdgeInsets.symmetric(vertical: 11, horizontal: 12),
            child: Row(mainAxisAlignment: MainAxisAlignment.center, children: [
              Icon(icon, color: const Color(0xFF18181B)),
              const SizedBox(width: 6),
              Flexible(
                child: Text(label,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: const TextStyle(color: Color(0xFF18181B), fontWeight: FontWeight.w600)),
              ),
            ]),
          ),
        ),
      ),
    );
  }
}
