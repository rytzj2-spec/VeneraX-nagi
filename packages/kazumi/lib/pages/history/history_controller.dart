import 'package:kazumi/modules/bangumi/bangumi_item.dart';
import 'package:kazumi/modules/history/history_module.dart';
import 'package:kazumi/repositories/history_repository.dart';
import 'package:mobx/mobx.dart';

part 'history_controller.g.dart';

class HistoryController = _HistoryController with _$HistoryController;

abstract class _HistoryController with Store {
  _HistoryController(this._historyRepository);

  final IHistoryRepository _historyRepository;

  @observable
  ObservableList<History> histories = ObservableList<History>();

  bool _mergedOnce = false;

  void init() {
    final temp = _historyRepository.getAllHistories();
    histories.clear();
    histories.addAll(temp);
    // Nagisaland：第一次加载时把旧数据里同一部番的多条记录合并（之后每次保存进度时也会合并）
    if (!_mergedOnce) {
      _mergedOnce = true;
      _historyRepository.mergeDuplicates().then((n) {
        if (n > 0) init();
      });
    }
  }

  Future<void> updateHistory(
    PlaybackHistoryIdentity identity,
    Duration progress, {
    Duration duration = Duration.zero,
  }) async {
    await _historyRepository.updateHistory(
      identity: identity,
      progress: progress,
      duration: duration,
    );
    init();
  }

  Progress? lastWatching(
    BangumiItem bangumiItem,
    String adapterName, {
    String entryKind = HistoryEntryKind.online,
  }) {
    return _historyRepository.getLastWatchingProgress(
      bangumiItem,
      adapterName,
      entryKind: entryKind,
    );
  }

  bool _sameSeries(History h, BangumiItem b) {
    if (b.id != 0 || h.bangumiItem.id != 0) return h.bangumiItem.id == b.id;
    String t(BangumiItem x) => (x.nameCn.isNotEmpty ? x.nameCn : x.name).toLowerCase().replaceAll(RegExp(r'\s+'), '');
    return t(h.bangumiItem) == t(b);
  }

  /// Nagisaland：这部番在所有源里最近看的一条记录
  History? latestFor(BangumiItem bangumiItem, {String entryKind = HistoryEntryKind.online}) {
    History? best;
    for (final h in histories) {
      if (!_sameSeries(h, bangumiItem)) continue;
      if (HistoryEntryKind.normalize(h.entryKind) != entryKind) continue;
      if (best == null || h.lastWatchTime.isAfter(best.lastWatchTime)) best = h;
    }
    return best;
  }

  /// Nagisaland：所有源里「最后更新时间」最新的那条进度（集数 + 时间），不论用哪个源播
  ({History history, Progress progress})? latestProgressFor(BangumiItem bangumiItem,
      {String entryKind = HistoryEntryKind.online}) {
    ({History history, Progress progress})? best;
    var bestAt = -1;
    for (final h in histories) {
      if (!_sameSeries(h, bangumiItem)) continue;
      if (HistoryEntryKind.normalize(h.entryKind) != entryKind) continue;
      for (final p in h.progresses.values) {
        final at = p.effectiveUpdatedAtMs(h.lastWatchTime);
        if (at > bestAt) {
          bestAt = at;
          best = (history: h, progress: p);
        }
      }
    }
    return best;
  }

  /// Nagisaland：某一集在所有源里最新的进度
  Progress? findProgressAnySource(BangumiItem bangumiItem, int episode,
      {String entryKind = HistoryEntryKind.online}) {
    Progress? best;
    var bestAt = -1;
    for (final h in histories) {
      if (!_sameSeries(h, bangumiItem)) continue;
      if (HistoryEntryKind.normalize(h.entryKind) != entryKind) continue;
      final p = h.progresses[episode];
      if (p == null) continue;
      final at = p.effectiveUpdatedAtMs(h.lastWatchTime);
      if (at > bestAt) {
        bestAt = at;
        best = p;
      }
    }
    return best;
  }

  Progress? findProgress(
    BangumiItem bangumiItem,
    String adapterName,
    int episode, {
    String entryKind = HistoryEntryKind.online,
  }) {
    return _historyRepository.findProgress(
      bangumiItem,
      adapterName,
      episode,
      entryKind: entryKind,
    );
  }

  Future<void> deleteHistory(History history) async {
    await _historyRepository.deleteHistory(history);
    init();
  }

  Future<void> clearAll() async {
    await _historyRepository.clearAllHistories();
    histories.clear();
  }
}
