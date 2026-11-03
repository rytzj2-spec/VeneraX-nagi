// Nagisaland：自动选源。所有规则（源）同时测：能搜到这部番 → 能拿到播放列表 → 列表里有要看的那一集，
// 按总耗时从快到慢排。点「开始观看」和历史记录里原来的源不可用时都用它；手动换源仍然保留。
//
// 说明：这里不在后台逐个解析出最终视频地址（要为每个源各开一个 WebView 跑解析，太重），
// 只测到「播放列表」这一步；真正播放时解析失败，可以在播放页手动换源。
import 'dart:async';

import 'package:kazumi/modules/bangumi/bangumi_item.dart';
import 'package:kazumi/modules/roads/road_module.dart';
import 'package:kazumi/modules/search/plugin_search_module.dart';
import 'package:kazumi/plugins/plugins.dart';
import 'package:kazumi/services/logging/logger.dart';
import 'package:kazumi/services/plugin/rule_engine_models.dart' show RuleCancelToken;

class AutoSourceCandidate {
  AutoSourceCandidate({required this.plugin, required this.item, required this.roads, required this.elapsed});

  final Plugin plugin;
  final SearchItem item;
  final List<Road> roads;
  final Duration elapsed;
}

class AutoSourceService {
  /// [needEpisode]：要播的集数（从 1 开始），没有这一集的源不要
  /// [exclude]：不测的源（例如历史里已经确认不可用的那个）
  /// 返回能用的源，最快的在前；一个都没有就是空列表
  static Future<List<AutoSourceCandidate>> rank(
    BangumiItem bangumi,
    List<Plugin> plugins, {
    int needEpisode = 1,
    Set<String> exclude = const {},
    Duration perSourceTimeout = const Duration(seconds: 15),
    RuleCancelToken? cancelToken,
    void Function(AutoSourceCandidate first)? onFirst,
  }) async {
    final keywords = <String>{
      if (bangumi.nameCn.trim().isNotEmpty) bangumi.nameCn.trim(),
      if (bangumi.name.trim().isNotEmpty) bangumi.name.trim(),
    }.toList();
    if (keywords.isEmpty) return [];
    final found = <AutoSourceCandidate>[];
    var firstReported = false;

    Future<void> test(Plugin plugin) async {
      final sw = Stopwatch()..start();
      try {
        SearchItem? best;
        for (final kw in keywords) {
          final resp = await plugin.queryBangumi(kw, cancelToken: cancelToken).timeout(perSourceTimeout);
          best = _bestMatch(resp.data, keywords);
          if (best != null) break;
        }
        if (best == null || cancelToken?.isCancelled == true) return;
        final left = perSourceTimeout - sw.elapsed;
        if (left <= Duration.zero) return;
        final roads = await plugin.queryChapterRoads(best.src, cancelToken: cancelToken).timeout(left);
        if (!roads.any((r) => r.data.length >= needEpisode)) return;
        final c = AutoSourceCandidate(plugin: plugin, item: best, roads: roads, elapsed: sw.elapsed);
        found.add(c);
        if (!firstReported) {
          firstReported = true;
          onFirst?.call(c);
        }
      } catch (e) {
        // 搜不到 / 要验证码 / 超时 / 出错：这个源不算
        KazumiLogger().d('AutoSource: ${plugin.name} 不可用：$e');
      }
    }

    await Future.wait(plugins.where((p) => !exclude.contains(p.name)).map(test));
    found.sort((a, b) => a.elapsed.compareTo(b.elapsed));
    return found;
  }

  static String _norm(String s) =>
      s.toLowerCase().replaceAll(RegExp(r'[\s\-_:：·・,，.。!！?？~～()（）\[\]【】「」『』]'), '');

  /// 标题完全一样 > 互相包含 > 第一个结果
  static SearchItem? _bestMatch(List<SearchItem> items, List<String> keywords) {
    if (items.isEmpty) return null;
    final ks = keywords.map(_norm).where((k) => k.isNotEmpty).toList();
    SearchItem? contains;
    for (final it in items) {
      final n = _norm(it.name);
      if (ks.contains(n)) return it;
      if (contains == null && ks.any((k) => n.contains(k) || (n.isNotEmpty && k.contains(n)))) contains = it;
    }
    return contains ?? items.first;
  }
}
