// 影视播放器：复用番剧的 media_kit（mpv）。直接播放原文件，不让服务器转码。
import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:media_kit/media_kit.dart';
import 'package:media_kit_video/media_kit_video.dart';
import 'package:path_provider/path_provider.dart';

import '../nagi_theme.dart';
import 'emby_api.dart';
import 'video_store.dart';

class VideoPlayerPage extends StatefulWidget {
  final MediaItem item;

  /// 同一季的所有集（按顺序），用来自动下一集；电影传空
  final List<MediaItem> playlist;

  /// 从头播放（忽略服务器记录的进度）
  final bool fromStart;

  const VideoPlayerPage({super.key, required this.item, this.playlist = const [], this.fromStart = false});

  @override
  State<VideoPlayerPage> createState() => _VideoPlayerPageState();
}

class _VideoPlayerPageState extends State<VideoPlayerPage> {
  late final Player player;
  late final VideoController controller;
  late MediaItem item;
  PlaybackSource? source;
  String? error;
  Timer? _reportTimer;
  final _subs = <StreamSubscription>[];
  bool _completedHandled = false;

  EmbyApi get api => EmbyApi(item.server);
  VideoSettings get settings => VideoStore.instance.settings;

  @override
  void initState() {
    super.initState();
    item = widget.item;
    player = Player(configuration: const PlayerConfiguration(osc: false));
    final hw = settings.hardwareDecode;
    controller = VideoController(
      player,
      configuration: VideoControllerConfiguration(
        vo: 'gpu',
        enableHardwareAcceleration: hw,
        enableAndroidSurfaceProducer: false,
        hwdec: hw ? 'auto-safe' : 'no',
        androidAttachSurfaceAfterVideoParameters: false,
      ),
    );
    if (settings.landscape) {
      SystemChrome.setPreferredOrientations([DeviceOrientation.landscapeLeft, DeviceOrientation.landscapeRight]);
    }
    SystemChrome.setEnabledSystemUIMode(SystemUiMode.immersiveSticky);
    _subs.add(player.stream.completed.listen((done) {
      if (done) _onCompleted();
    }));
    _subs.add(player.stream.error.listen((e) => debugPrint('nagi_video player: $e')));
    _start(fromStart: widget.fromStart);
  }

  Future<void> _start({bool fromStart = false}) async {
    setState(() {
      error = null;
      source = null;
    });
    _completedHandled = false;
    try {
      // 详情页传进来的条目可能没有最新进度：重新取一次
      final fresh = await api.item(item.id).catchError((_) => item);
      item = fresh;
      final src = await api.playback(item.id);
      if (!mounted) return;
      source = src;
      final native = player.platform;
      if (native is NativePlayer) {
        await native.setProperty('demuxer-cache-dir', (await getTemporaryDirectory()).path);
        await native.setProperty('network-timeout', '60');
      }
      await player.open(Media(src.url), play: false);
      final start = fromStart ? Duration.zero : Duration(microseconds: item.positionTicks ~/ 10);
      if (start > const Duration(seconds: 5)) {
        // 等拿到时长再跳，否则有的文件跳不过去
        await player.stream.duration.firstWhere((d) => d > Duration.zero).timeout(const Duration(seconds: 15), onTimeout: () => Duration.zero);
        await player.seek(start);
      }
      await player.setRate(settings.speed);
      await player.play();
      if (src.subtitles.isNotEmpty) {
        // 外挂字幕（服务器上和视频放在一起的 .srt/.ass）：默认选第一条；内封字幕 mpv 自己会读
        final s = src.subtitles.first;
        await player.setSubtitleTrack(SubtitleTrack.uri(s.url, title: s.title, language: s.language));
      }
      await api.reportStart(item.id, src, player.state.position);
      _reportTimer?.cancel();
      _reportTimer = Timer.periodic(const Duration(seconds: 10), (_) => _report());
      if (mounted) setState(() {});
    } catch (e) {
      if (mounted) setState(() => error = EmbyApi.describeError(e));
    }
  }

  void _report() {
    final s = source;
    if (s == null) return;
    api.reportProgress(item.id, s, player.state.position, !player.state.playing);
  }

  Future<void> _stopReport() async {
    _reportTimer?.cancel();
    final s = source;
    if (s != null) await api.reportStopped(item.id, s, player.state.position);
  }

  int get _index => widget.playlist.indexWhere((e) => e.id == item.id);
  MediaItem? get _next {
    final i = _index;
    return (i >= 0 && i + 1 < widget.playlist.length) ? widget.playlist[i + 1] : null;
  }

  Future<void> _onCompleted() async {
    if (_completedHandled) return;
    _completedHandled = true;
    await _stopReport();
    final next = _next;
    if (settings.autoNext && next != null && mounted) {
      item = next;
      await _start(fromStart: true);
    }
  }

  Future<void> _playNext() async {
    final next = _next;
    if (next == null) return;
    await _stopReport();
    item = next;
    await _start(fromStart: true);
  }

  @override
  void dispose() {
    _stopReport();
    for (final s in _subs) {
      s.cancel();
    }
    player.dispose();
    SystemChrome.setPreferredOrientations([]);
    SystemChrome.setEnabledSystemUIMode(SystemUiMode.edgeToEdge);
    super.dispose();
  }

  // ---------- 轨道 / 倍速 ----------
  Future<void> _pickTrack({required bool audio}) async {
    final tracks = audio ? player.state.tracks.audio : player.state.tracks.subtitle;
    final current = audio ? player.state.track.audio : player.state.track.subtitle;
    final extra = audio ? const <ExternalSubtitle>[] : (source?.subtitles ?? const <ExternalSubtitle>[]);
    String label(dynamic t) {
      if (t.id == 'auto') return '自动';
      if (t.id == 'no') return audio ? '关闭' : '不显示字幕';
      final parts = [t.title, t.language].whereType<String>().where((s) => s.isNotEmpty).toList();
      return parts.isEmpty ? '轨道 ${t.id}' : parts.join(' · ');
    }

    await showModalBottomSheet(
      context: context,
      backgroundColor: NagiColors.panel,
      builder: (ctx) => SafeArea(
        child: ListView(shrinkWrap: true, children: [
          ListTile(title: Text(audio ? '音轨' : '字幕', style: const TextStyle(fontWeight: FontWeight.w600))),
          for (final t in tracks)
            ListTile(
              title: Text(label(t)),
              trailing: t == current ? const Icon(Icons.check, color: NagiColors.pink) : null,
              onTap: () {
                audio ? player.setAudioTrack(t as AudioTrack) : player.setSubtitleTrack(t as SubtitleTrack);
                Navigator.pop(ctx);
              },
            ),
          for (final s in extra)
            ListTile(
              title: Text('${s.title}（外挂）'),
              onTap: () {
                player.setSubtitleTrack(SubtitleTrack.uri(s.url, title: s.title, language: s.language));
                Navigator.pop(ctx);
              },
            ),
        ]),
      ),
    );
  }

  Future<void> _pickSpeed() async {
    const speeds = [0.5, 0.75, 1.0, 1.25, 1.5, 2.0, 3.0];
    await showModalBottomSheet(
      context: context,
      backgroundColor: NagiColors.panel,
      builder: (ctx) => SafeArea(
        child: ListView(shrinkWrap: true, children: [
          const ListTile(title: Text('倍速', style: TextStyle(fontWeight: FontWeight.w600))),
          for (final s in speeds)
            ListTile(
              title: Text('${s}x'),
              trailing: player.state.rate == s ? const Icon(Icons.check, color: NagiColors.pink) : null,
              onTap: () {
                player.setRate(s);
                settings.speed = s;
                VideoStore.instance.save();
                Navigator.pop(ctx);
              },
            ),
        ]),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final title = item.type == 'Episode' ? '${item.seriesName ?? ''}  ${item.displayTitle}' : item.name;
    final top = [
      MaterialCustomButton(onPressed: () => Navigator.maybePop(context), icon: const Icon(Icons.arrow_back)),
      const SizedBox(width: 4),
      Expanded(
        child: Text(title, maxLines: 1, overflow: TextOverflow.ellipsis, style: const TextStyle(color: Colors.white, fontSize: 15)),
      ),
      MaterialCustomButton(onPressed: () => _pickTrack(audio: false), icon: const Icon(Icons.subtitles_outlined)),
      MaterialCustomButton(onPressed: () => _pickTrack(audio: true), icon: const Icon(Icons.audiotrack_outlined)),
      MaterialCustomButton(onPressed: _pickSpeed, icon: const Icon(Icons.speed)),
      if (_next != null) MaterialCustomButton(onPressed: _playNext, icon: const Icon(Icons.skip_next)),
    ];
    final theme = MaterialVideoControlsThemeData(
      topButtonBar: top,
      seekBarPositionColor: NagiColors.pink,
      seekBarThumbColor: NagiColors.pink,
      seekGesture: true,
      speedUpOnLongPress: true,
    );
    return Scaffold(
      backgroundColor: Colors.black,
      body: error != null
          ? SafeArea(
              child: Column(children: [
                Align(alignment: Alignment.centerLeft, child: BackButton(onPressed: () => Navigator.maybePop(context))),
                Expanded(
                  child: Center(
                    child: Column(mainAxisSize: MainAxisSize.min, children: [
                      Text('播放失败：$error', textAlign: TextAlign.center, style: const TextStyle(color: Colors.white70)),
                      const SizedBox(height: 12),
                      OutlinedButton(onPressed: () => _start(), child: const Text('重试')),
                    ]),
                  ),
                ),
              ]),
            )
          : MaterialVideoControlsTheme(
              normal: theme,
              fullscreen: theme,
              child: Video(
                controller: controller,
                controls: MaterialVideoControls,
                pauseUponEnteringBackgroundMode: true,
              ),
            ),
    );
  }
}
