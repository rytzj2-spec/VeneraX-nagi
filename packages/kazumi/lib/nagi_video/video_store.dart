// 影视分区的本地数据：服务器列表 + 设置，存成一个 JSON 文件（不用番剧的 Hive，两个引擎同时开同一个库会冲突）。
import 'dart:convert';
import 'dart:io';

import 'package:flutter/foundation.dart';
import 'package:path_provider/path_provider.dart';

import 'emby_api.dart';

class VideoSettings {
  bool hardwareDecode;
  bool autoNext; // 播完自动下一集
  bool landscape; // 播放时横屏
  double speed;

  VideoSettings({this.hardwareDecode = true, this.autoNext = true, this.landscape = true, this.speed = 1.0});

  factory VideoSettings.fromJson(Map<String, dynamic> j) => VideoSettings(
        hardwareDecode: j['hardwareDecode'] ?? true,
        autoNext: j['autoNext'] ?? true,
        landscape: j['landscape'] ?? true,
        speed: (j['speed'] as num?)?.toDouble() ?? 1.0,
      );

  Map<String, dynamic> toJson() => {'hardwareDecode': hardwareDecode, 'autoNext': autoNext, 'landscape': landscape, 'speed': speed};
}

class VideoStore extends ChangeNotifier {
  VideoStore._();
  static final VideoStore instance = VideoStore._();

  final List<MediaServer> servers = [];
  VideoSettings settings = VideoSettings();
  File? _file;

  Future<void> load() async {
    final dir = await getApplicationSupportDirectory();
    _file = File('${dir.path}/nagi_video.json');
    try {
      if (await _file!.exists()) {
        final j = jsonDecode(await _file!.readAsString()) as Map<String, dynamic>;
        servers
          ..clear()
          ..addAll((j['servers'] as List? ?? const []).whereType<Map>().map((m) => MediaServer.fromJson(m.cast<String, dynamic>())));
        settings = VideoSettings.fromJson((j['settings'] as Map?)?.cast<String, dynamic>() ?? const {});
      }
    } catch (e) {
      debugPrint('nagi_video: 读取设置失败 $e');
    }
    notifyListeners();
  }

  Future<void> save() async {
    final f = _file;
    if (f == null) return;
    final tmp = File('${f.path}.tmp');
    await tmp.writeAsString(jsonEncode({'servers': servers.map((s) => s.toJson()).toList(), 'settings': settings.toJson()}));
    await tmp.rename(f.path);
    notifyListeners();
  }

  Future<void> upsert(MediaServer s) async {
    final i = servers.indexWhere((x) => x.id == s.id);
    if (i >= 0) {
      servers[i] = s;
    } else {
      servers.add(s);
    }
    await save();
  }

  Future<void> remove(MediaServer s) async {
    servers.removeWhere((x) => x.id == s.id);
    await save();
  }

  Future<void> move(int from, int to) async {
    final s = servers.removeAt(from);
    servers.insert(to.clamp(0, servers.length), s);
    await save();
  }
}
