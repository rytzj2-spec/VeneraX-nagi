// Nagisaland：影视分区的「一起看」（SyncPlay 同步播放，复用番剧的 SyncplayClient）。
// 大家填同一个房间名：暂停 / 播放 / 拖进度互相同步；有人换了视频，连着同一个服务器的人自动跟着换。
// 房间一直保持（换集、退出播放页都不断开），在播放页的「一起看」里点「退出」才断开。
import 'dart:async';

import 'package:flutter/material.dart';
import 'package:media_kit/media_kit.dart';

import '../services/player/syncplay_client.dart';
import '../services/player/syncplay_endpoint.dart';
import 'emby_api.dart';
import 'video_store.dart';

/// 影视 App 全局的提示（MaterialApp.scaffoldMessengerKey）
final videoMessengerKey = GlobalKey<ScaffoldMessengerState>();
void videoToast(String msg) {
  videoMessengerKey.currentState
    ?..hideCurrentSnackBar()
    ..showSnackBar(SnackBar(content: Text(msg), duration: const Duration(seconds: 3)));
}

class VideoTogether {
  VideoTogether._();
  static final VideoTogether instance = VideoTogether._();

  static const _filePrefix = 'nagivideo|';

  SyncplayClient? _client;
  String room = '';
  String user = '';
  final _subs = <StreamSubscription>[];

  // 绑定的播放器（当前播放页）
  Player? _player;
  MediaItem? _item;
  Future<void> Function(MediaItem item)? _switchTo;
  final _playerSubs = <StreamSubscription>[];

  DateTime _suppressUntil = DateTime.fromMillisecondsSinceEpoch(0);
  Duration _lastPos = Duration.zero;
  DateTime _lastPosAt = DateTime.now();
  bool _hasLastPos = false;

  bool get active => _client != null;
  bool get _suppressed => DateTime.now().isBefore(_suppressUntil);
  void _suppress([int ms = 1500]) => _suppressUntil = DateTime.now().add(Duration(milliseconds: ms));

  // ---------------------------------------------------------------- 房间

  Future<void> join({required String room, required String user, required String server}) async {
    await leave();
    final ep = parseSyncPlayEndPoint(server);
    if (ep == null) {
      videoToast('同步服务器地址不对：$server');
      return;
    }
    final client = SyncplayClient(host: ep.host, port: ep.port);
    _client = client;
    this.room = room;
    this.user = user;
    try {
      await client.connect(enableTLS: isOfficialSyncPlayEndPoint(ep));
      if (!identical(_client, client)) return;
      _subs.add(client.onGeneralMessage.listen((_) {}, onError: (e) {
        if (!identical(_client, client)) return;
        if (e is SyncplayConnectionException) {
          videoToast('一起看：连接断了（${e.message}）');
          leave();
        }
      }));
      _subs.add(client.onRoomMessage.listen((m) {
        if (!identical(_client, client)) return;
        switch (m['type']) {
          case 'init':
            if ((m['username'] ?? '') == '') {
              videoToast('房间里只有你，别人进来会跟你的进度');
              _announceFile();
            } else {
              videoToast('以 ${m['username']} 的进度为准');
            }
          case 'joined':
            videoToast('${m['username']} 进来了');
          case 'left':
            videoToast('${m['username']} 走了');
        }
      }));
      _subs.add(client.onFileChangedMessage.listen((m) {
        if (!identical(_client, client)) return;
        _onRemoteFile('${m['name'] ?? ''}', '${m['setBy'] ?? ''}');
      }));
      _subs.add(client.onChatMessage.listen((m) {
        if (!identical(_client, client)) return;
        videoToast('${m['username']}：${m['message']}');
      }, onError: (_) {}));
      _subs.add(client.onPositionChangedMessage.listen((m) {
        if (!identical(_client, client)) return;
        _applyRemote(m);
      }));
      _updateLocal();
      await client.joinRoom(room, user);
      videoToast('已进入房间「$room」');
    } catch (e) {
      if (identical(_client, client)) {
        _client = null;
        await client.disconnect();
      }
      videoToast('一起看连接失败：${e is SyncplayException ? e.message : e}');
    }
  }

  Future<void> leave() async {
    for (final s in _subs) {
      await s.cancel();
    }
    _subs.clear();
    final c = _client;
    _client = null;
    await c?.disconnect();
  }

  // ---------------------------------------------------------------- 播放器

  /// 播放页打开 / 换了一集时调用
  void bind(Player player, MediaItem item, Future<void> Function(MediaItem item) switchTo) {
    final samePlayer = identical(_player, player);
    _player = player;
    _item = item;
    _switchTo = switchTo;
    _hasLastPos = false;
    if (!samePlayer) {
      for (final s in _playerSubs) {
        s.cancel();
      }
      _playerSubs
        ..clear()
        ..add(player.stream.playing.listen((_) => _onLocalPlayState()))
        ..add(player.stream.position.listen(_onLocalPosition));
    }
    if (active) _announceFile();
  }

  /// 播放页关掉时调用（房间不断开）
  void unbind(Player player) {
    if (!identical(_player, player)) return;
    for (final s in _playerSubs) {
      s.cancel();
    }
    _playerSubs.clear();
    _player = null;
    _item = null;
    _switchTo = null;
  }

  void _announceFile() {
    final c = _client;
    final item = _item;
    if (c == null || item == null) return;
    final title = item.type == 'Episode' ? '${item.seriesName ?? ''} ${item.name}' : item.name;
    c.setSyncPlayPlaying('$_filePrefix${item.server.url}|${item.id}|$title', 10800, 220514438).catchError((_) {});
  }

  void _updateLocal() {
    final c = _client;
    final p = _player;
    if (c == null || p == null) return;
    c.setPaused(!p.state.playing);
    c.setPosition(p.state.position.inMilliseconds / 1000.0);
  }

  void _onLocalPlayState() {
    if (_client == null) return;
    _updateLocal();
    if (!_suppressed) _client?.sendSyncPlaySyncRequest().catchError((_) {});
  }

  void _onLocalPosition(Duration pos) {
    final c = _client;
    final p = _player;
    if (c == null || p == null) return;
    c.setPosition(pos.inMilliseconds / 1000.0);
    final now = DateTime.now();
    if (_hasLastPos) {
      final elapsed = p.state.playing ? now.difference(_lastPosAt) : Duration.zero;
      final expected = _lastPos + elapsed;
      // 进度突然跳了 = 自己拖了进度条
      if ((pos - expected).inMilliseconds.abs() > 2500 && !_suppressed) {
        c.setPaused(!p.state.playing);
        c.sendSyncPlaySyncRequest(doSeek: true).catchError((_) {});
      }
    }
    _lastPos = pos;
    _lastPosAt = now;
    _hasLastPos = true;
  }

  void _applyRemote(Map<String, dynamic> m) {
    final p = _player;
    if (p == null) return;
    final paused = m['paused'] == true;
    final position = (m['position'] as num?)?.toDouble() ?? 0;
    final calc = (m['calculatedPositon'] as num?)?.toDouble() ?? position;
    final setBy = '${m['setBy'] ?? ''}';
    if (paused == p.state.playing && position != 0) {
      _suppress();
      if (paused) {
        p.pause();
        if (setBy.isNotEmpty) videoToast('$setBy 暂停了');
      } else {
        p.play();
        if (setBy.isNotEmpty) videoToast('$setBy 开始播放');
      }
    }
    final target = Duration(milliseconds: (calc * 1000).round());
    if (((p.state.position - target).inMilliseconds.abs() > 1500 || m['doSeek'] == true) &&
        p.state.duration > Duration.zero) {
      _suppress();
      _hasLastPos = false;
      p.seek(target);
    }
  }

  Future<void> _onRemoteFile(String name, String setBy) async {
    if (!name.startsWith(_filePrefix)) {
      videoToast('$setBy 在看别的分区的内容');
      return;
    }
    final parts = name.substring(_filePrefix.length).split('|');
    if (parts.length < 2) return;
    final url = parts[0];
    final id = parts[1];
    final title = parts.length > 2 ? parts.sublist(2).join('|') : '';
    if (_item?.id == id) return;
    // 只有连着同一个服务器（并且已登录）才能跟着换
    final server = VideoStore.instance.servers.where((s) => s.url == url && s.token.isNotEmpty).firstOrNull;
    final switchTo = _switchTo;
    if (server == null) {
      videoToast('$setBy 在看「$title」（你的影视里没有登录这个服务器）');
      return;
    }
    if (switchTo == null) {
      videoToast('$setBy 在看「$title」，打开任意视频的播放页会自动跟上');
      return;
    }
    try {
      final item = await EmbyApi(server).item(id);
      videoToast('$setBy 换到了「$title」');
      await switchTo(item);
    } catch (e) {
      videoToast('跟着换视频失败：${EmbyApi.describeError(e)}');
    }
  }
}

/// 播放页的「一起看」对话框
Future<void> showVideoTogetherSheet(BuildContext context) async {
  final t = VideoTogether.instance;
  final settings = VideoStore.instance.settings;
  if (t.active) {
    await showDialog(
      context: context,
      builder: (c) => AlertDialog(
        title: const Text('一起看'),
        content: Text('房间：${t.room}\n昵称：${t.user}\n暂停、拖进度、换集都会同步给房间里的人。'),
        actions: [
          TextButton(
            onPressed: () {
              Navigator.pop(c);
              t.leave();
              videoToast('已退出一起看');
            },
            child: const Text('退出一起看'),
          ),
          TextButton(onPressed: () => Navigator.pop(c), child: const Text('好')),
        ],
      ),
    );
    return;
  }
  final room = TextEditingController(text: settings.syncRoom);
  final name = TextEditingController(text: settings.syncName);
  final server = TextEditingController(text: settings.syncServer);
  final ok = await showDialog<bool>(
    context: context,
    builder: (c) => AlertDialog(
      title: const Text('一起看'),
      content: SingleChildScrollView(
        child: Column(mainAxisSize: MainAxisSize.min, children: [
          TextField(controller: room, decoration: const InputDecoration(labelText: '房间名（大家填一样的）')),
          TextField(controller: name, decoration: const InputDecoration(labelText: '你的昵称')),
          TextField(controller: server, decoration: const InputDecoration(labelText: '同步服务器')),
          const SizedBox(height: 8),
          const Text('默认用公共的 SyncPlay 服务器，不用自己搭。只同步播放进度，画面各自播；大家要连同一个 Emby / Jellyfin 服务器。',
              style: TextStyle(fontSize: 12)),
        ]),
      ),
      actions: [
        TextButton(onPressed: () => Navigator.pop(c, false), child: const Text('取消')),
        TextButton(onPressed: () => Navigator.pop(c, true), child: const Text('进房间')),
      ],
    ),
  );
  if (ok != true) return;
  final r = room.text.trim();
  if (r.isEmpty) {
    videoToast('房间名不能为空');
    return;
  }
  settings
    ..syncRoom = r
    ..syncName = name.text.trim().isEmpty ? '影视用户' : name.text.trim()
    ..syncServer = server.text.trim().isEmpty ? defaultSyncPlayEndPoint : server.text.trim();
  await VideoStore.instance.save();
  await t.join(room: settings.syncRoom, user: settings.syncName, server: settings.syncServer);
}
