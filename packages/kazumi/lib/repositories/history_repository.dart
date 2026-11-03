import 'package:hive_ce/hive.dart';
import 'package:kazumi/services/storage/storage.dart';
import 'package:kazumi/utils/constants.dart';
import 'package:kazumi/modules/bangumi/bangumi_item.dart';
import 'package:kazumi/modules/history/history_module.dart';
import 'package:kazumi/services/sync/history_sync_service.dart';
import 'package:kazumi/services/logging/logger.dart';
import 'package:kazumi/services/storage/history_storage_coordinator.dart';

typedef HistoryProgressSyncAppender = Future<void> Function({
  required History history,
  required int episode,
  required int road,
  required int progressMs,
  required int updatedAt,
});

typedef HistoryDeleteSyncAppender = Future<void> Function(History history);

typedef HistoryClearSyncAppender = Future<void> Function();

/// 历史记录数据访问接口
///
/// 提供观看历史相关的数据访问抽象
abstract class IHistoryRepository {
  /// 历史记录变更事件流
  ///
  /// 写入、删除、清空各推送一次。所有历史写入都经过本仓库。
  Stream<void> get changes;

  /// 获取所有历史记录（按时间倒序）
  List<History> getAllHistories();

  /// 获取特定番剧的历史记录
  ///
  /// [adapterName] 适配器名称
  /// [bangumiItem] 番剧信息
  /// 返回历史记录，不存在返回null
  History? getHistory(
    String adapterName,
    BangumiItem bangumiItem, {
    String entryKind = HistoryEntryKind.online,
  });

  /// 更新或创建历史记录
  ///
  /// [identity] 播放历史身份
  /// [progress] 观看进度
  /// [duration] 视频总时长；距结尾 [nearEndWatchedThreshold] 以内的进度视为
  /// 已看完，归零保存。传 [Duration.zero] 表示时长未知，不做该判断
  Future<void> updateHistory({
    required PlaybackHistoryIdentity identity,
    required Duration progress,
    Duration duration = Duration.zero,
  });

  /// 获取上次观看的进度
  ///
  /// [bangumiItem] 番剧信息
  /// [adapterName] 适配器名称
  /// 返回观看进度，不存在返回null
  Progress? getLastWatchingProgress(
    BangumiItem bangumiItem,
    String adapterName, {
    String entryKind = HistoryEntryKind.online,
  });

  /// 查找特定集数的观看进度
  ///
  /// [bangumiItem] 番剧信息
  /// [adapterName] 适配器名称
  /// [episode] 集数
  /// 返回观看进度，不存在返回null
  Progress? findProgress(
    BangumiItem bangumiItem,
    String adapterName,
    int episode, {
    String entryKind = HistoryEntryKind.online,
  });

  /// 删除历史记录
  ///
  /// [history] 要删除的历史记录
  Future<void> deleteHistory(History history);

  /// 清空所有历史记录
  Future<void> clearAllHistories();

  /// Nagisaland：同一部番在不同源的记录合并成一条（旧数据迁移，可重复执行）
  Future<int> mergeDuplicates();

  /// 获取隐私模式设置
  bool getPrivateMode();
}

/// 历史记录数据访问实现类
///
/// 基于Hive实现的历史记录数据访问层
class HistoryRepository implements IHistoryRepository {
  HistoryRepository({
    Box<History>? historiesBox,
    bool Function()? privateModeReader,
    HistoryProgressSyncAppender? progressSyncAppender,
    HistoryDeleteSyncAppender? deleteSyncAppender,
    HistoryClearSyncAppender? clearSyncAppender,
  })  : _historiesBox = historiesBox ?? GStorage.histories,
        _privateModeReader = privateModeReader ??
            (() => GStorage.getSetting(SettingsKeys.privateMode)),
        _progressSyncAppender = progressSyncAppender ?? _appendProgressSync,
        _deleteSyncAppender = deleteSyncAppender ?? _appendDeleteSync,
        _clearSyncAppender = clearSyncAppender ?? _appendClearSync;

  final Box<History> _historiesBox;
  final bool Function() _privateModeReader;
  final HistoryProgressSyncAppender _progressSyncAppender;
  final HistoryDeleteSyncAppender _deleteSyncAppender;
  final HistoryClearSyncAppender _clearSyncAppender;
  final HistoryStorageCoordinator _storageCoordinator =
      HistoryStorageCoordinator();

  static Future<void> _appendProgressSync({
    required History history,
    required int episode,
    required int road,
    required int progressMs,
    required int updatedAt,
  }) async {
    final historySyncService = HistorySyncService();
    await historySyncService.appendSafely(
      () => historySyncService.appendUpsertProgress(
        history: history,
        episode: episode,
        road: road,
        progressMs: progressMs,
        updatedAt: updatedAt,
      ),
    );
  }

  static Future<void> _appendDeleteSync(History history) async {
    final historySyncService = HistorySyncService();
    await historySyncService.appendSafely(
      () => historySyncService.appendDeleteHistory(history),
    );
  }

  static Future<void> _appendClearSync() async {
    final historySyncService = HistorySyncService();
    await historySyncService.appendSafely(
      () => historySyncService.appendClearAll(),
    );
  }

  @override
  Stream<void> get changes => _historiesBox.watch();

  @override
  List<History> getAllHistories() {
    try {
      final byKey = <String, History>{};
      for (final history in _historiesBox.values) {
        history.entryKind = HistoryEntryKind.normalize(history.entryKind);
        final existing = byKey[history.key];
        if (existing == null ||
            existing.lastWatchTime.isBefore(history.lastWatchTime)) {
          byKey[history.key] = history;
        }
      }
      var histories = byKey.values.toList();
      histories.sort(
        (a, b) =>
            b.lastWatchTime.millisecondsSinceEpoch -
            a.lastWatchTime.millisecondsSinceEpoch,
      );
      return histories;
    } catch (e, stackTrace) {
      KazumiLogger().e(
        'GStorage: get all histories failed',
        error: e,
        stackTrace: stackTrace,
      );
      return [];
    }
  }

  @override
  History? getHistory(
    String adapterName,
    BangumiItem bangumiItem, {
    String entryKind = HistoryEntryKind.online,
  }) {
    try {
      return _findHistory(adapterName, bangumiItem, entryKind);
    } catch (e, stackTrace) {
      KazumiLogger().e(
        'GStorage: get history failed. bangumi=${bangumiItem.name}',
        error: e,
        stackTrace: stackTrace,
      );
      return null;
    }
  }

  @override
  Future<void> updateHistory({
    required PlaybackHistoryIdentity identity,
    required Duration progress,
    Duration duration = Duration.zero,
  }) async {
    // 距结尾过近视为已看完，归零保存
    if (duration > Duration.zero &&
        progress >= duration - nearEndWatchedThreshold) {
      progress = Duration.zero;
    }
    await _storageCoordinator.run(() async {
      try {
        if (!identity.canRecord) {
          return;
        }
        // 检查隐私模式
        if (getPrivateMode()) {
          return;
        }

        final episode = identity.episodeNumber;
        final adapterName = identity.pluginName;
        final bangumiItem = identity.bangumiItem;

        final now = DateTime.now();
        final nowMs = now.millisecondsSinceEpoch;
        final legacyKey = History.legacyKey(adapterName, bangumiItem);
        final shouldMigrateLegacy =
            HistoryEntryKind.normalize(identity.entryKind) ==
                HistoryEntryKind.online;

        // 获取或创建历史记录
        var history = _findHistory(
              adapterName,
              bangumiItem,
              identity.entryKind,
            ) ??
            History(
              bangumiItem,
              episode,
              adapterName,
              now,
              identity.onlineBangumiSrc,
              identity.episodeTitle,
              entryKind: identity.entryKind,
              episodePageUrl: identity.episodePageUrl,
            );

        // 更新历史记录
        history.lastWatchEpisode = episode;
        history.lastWatchTime = now;
        history.entryKind = HistoryEntryKind.normalize(identity.entryKind);
        if (identity.onlineBangumiSrc.isNotEmpty) {
          history.lastSrc = identity.onlineBangumiSrc;
        }
        if (identity.episodeTitle.isNotEmpty) {
          history.lastWatchEpisodeName = identity.episodeTitle;
        }
        if (identity.episodePageUrl.isNotEmpty) {
          history.episodePageUrl = identity.episodePageUrl;
        }

        // 更新观看进度
        var prog = history.progresses[episode];
        if (prog == null) {
          history.progresses[episode] = Progress(
            episode,
            identity.road,
            progress.inMilliseconds,
            updatedAtMs: nowMs,
          );
        } else {
          prog.road = identity.road;
          prog.progress = progress;
          prog.updatedAtMs = nowMs;
        }

        // 保存到存储
        await _historiesBox.put(history.key, history);
        if (shouldMigrateLegacy && legacyKey != history.key) {
          await _historiesBox.delete(legacyKey);
        }
        // Nagisaland：这部番在别的源上的记录并进来（每集保留最新的进度），只留这一条
        if (HistoryEntryKind.normalize(history.entryKind) == HistoryEntryKind.online) {
          await _absorbOthers(history);
        }
        await _progressSyncAppender(
          history: history,
          episode: episode,
          road: identity.road,
          progressMs: progress.inMilliseconds,
          updatedAt: nowMs,
        );
      } catch (e, stackTrace) {
        KazumiLogger().e(
          'GStorage: update history failed. bangumi=${identity.bangumiItem.name}, episode=${identity.episodeNumber}',
          error: e,
          stackTrace: stackTrace,
        );
      }
    });
  }

  @override
  Progress? getLastWatchingProgress(
    BangumiItem bangumiItem,
    String adapterName, {
    String entryKind = HistoryEntryKind.online,
  }) {
    try {
      var history = _findHistory(adapterName, bangumiItem, entryKind);
      return history?.progresses[history.lastWatchEpisode];
    } catch (e, stackTrace) {
      KazumiLogger().e(
        'GStorage: get last watching progress failed. bangumi=${bangumiItem.name}',
        error: e,
        stackTrace: stackTrace,
      );
      return null;
    }
  }

  @override
  Progress? findProgress(
    BangumiItem bangumiItem,
    String adapterName,
    int episode, {
    String entryKind = HistoryEntryKind.online,
  }) {
    try {
      var history = _findHistory(adapterName, bangumiItem, entryKind);
      return history?.progresses[episode];
    } catch (e, stackTrace) {
      KazumiLogger().e(
        'GStorage: find progress failed. bangumi=${bangumiItem.name}, episode=$episode',
        error: e,
        stackTrace: stackTrace,
      );
      return null;
    }
  }

  @override
  Future<void> deleteHistory(History history) async {
    await _storageCoordinator.run(() async {
      try {
        await _historiesBox.delete(history.key);
        if (HistoryEntryKind.normalize(history.entryKind) ==
            HistoryEntryKind.online) {
          await _historiesBox.delete(
            History.legacyKey(history.adapterName, history.bangumiItem),
          );
        }
        await _deleteSyncAppender(history);
      } catch (e, stackTrace) {
        KazumiLogger().e(
          'GStorage: delete history failed. bangumi=${history.bangumiItem.name}',
          error: e,
          stackTrace: stackTrace,
        );
      }
    });
  }

  @override
  Future<void> clearAllHistories() async {
    await _storageCoordinator.run(() async {
      try {
        await _historiesBox.clear();
        await _clearSyncAppender();
      } catch (e, stackTrace) {
        KazumiLogger().e(
          'GStorage: clear all histories failed',
          error: e,
          stackTrace: stackTrace,
        );
      }
    });
  }

  // ---------------------------------------------------------------- Nagisaland：按番合并
  /// 同一部番：有 Bangumi id 按 id，没有按标题
  static String seriesKey(History h) {
    final b = h.bangumiItem;
    if (b.id != 0) return 'id:${b.id}';
    final t = (b.nameCn.isNotEmpty ? b.nameCn : b.name).toLowerCase().replaceAll(RegExp(r'\s+'), '');
    return 'title:$t';
  }

  /// [others] 的每集进度并进 [target]：同一集取更新时间新的
  static void _mergeProgresses(History target, Iterable<History> others) {
    for (final o in others) {
      for (final e in o.progresses.entries) {
        final mine = target.progresses[e.key];
        final theirs = e.value;
        if (mine == null ||
            theirs.effectiveUpdatedAtMs(o.lastWatchTime) > mine.effectiveUpdatedAtMs(target.lastWatchTime)) {
          target.progresses[e.key] = Progress(
            theirs.episode,
            theirs.road,
            theirs.progress.inMilliseconds,
            updatedAtMs: theirs.effectiveUpdatedAtMs(o.lastWatchTime),
          );
        }
      }
    }
  }

  Future<void> _deleteEntry(History h) async {
    await _historiesBox.delete(h.key);
    await _historiesBox.delete(History.legacyKey(h.adapterName, h.bangumiItem));
    await _deleteSyncAppender(h);
  }

  Future<void> _absorbOthers(History target) async {
    final sk = seriesKey(target);
    final others = _historiesBox.values
        .where((h) =>
            h.key != target.key &&
            HistoryEntryKind.normalize(h.entryKind) == HistoryEntryKind.online &&
            seriesKey(h) == sk)
        .toList();
    if (others.isEmpty) return;
    _mergeProgresses(target, others);
    for (final o in others) {
      await _deleteEntry(o);
    }
    await _historiesBox.put(target.key, target);
  }

  @override
  Future<int> mergeDuplicates() async {
    var merged = 0;
    await _storageCoordinator.run(() async {
      try {
        final groups = <String, List<History>>{};
        for (final h in _historiesBox.values) {
          if (HistoryEntryKind.normalize(h.entryKind) != HistoryEntryKind.online) continue;
          groups.putIfAbsent(seriesKey(h), () => []).add(h);
        }
        for (final g in groups.values) {
          if (g.length < 2) continue;
          // 最后观看的那条留下（它的源、集数就是「最后观看」），其它的进度并进来后删掉
          g.sort((a, b) => b.lastWatchTime.compareTo(a.lastWatchTime));
          final target = g.first;
          final rest = g.sublist(1);
          _mergeProgresses(target, rest);
          for (final o in rest) {
            await _deleteEntry(o);
          }
          await _historiesBox.put(target.key, target);
          merged += rest.length;
        }
      } catch (e, stackTrace) {
        KazumiLogger().e('GStorage: merge duplicate histories failed', error: e, stackTrace: stackTrace);
      }
    });
    return merged;
  }

  @override
  bool getPrivateMode() {
    try {
      return _privateModeReader();
    } catch (e, stackTrace) {
      KazumiLogger().e(
        'GStorage: get private mode setting failed, using default false',
        error: e,
        stackTrace: stackTrace,
      );
      return false;
    }
  }

  History? _findHistory(
    String adapterName,
    BangumiItem bangumiItem,
    String entryKind,
  ) {
    final normalizedEntryKind = HistoryEntryKind.normalize(entryKind);
    return _historiesBox.get(
          History.getKey(
            adapterName,
            bangumiItem,
            entryKind: normalizedEntryKind,
          ),
        ) ??
        (normalizedEntryKind == HistoryEntryKind.online
            ? _historiesBox.get(History.legacyKey(adapterName, bangumiItem))
            : null);
  }
}
