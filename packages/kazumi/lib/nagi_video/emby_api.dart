// Nagisaland「影视」分区：Emby / Jellyfin 接口（两者 API 基本相同，Jellyfin 是 Emby 的分支）。
// 参考 Hills 的功能自己写；飞牛影视的接口确认后再加（ServerKind.fnos 先占位）。
import 'dart:convert';
import 'dart:math';

import 'package:dio/dio.dart';

enum ServerKind { emby, jellyfin, fnos }

String kindLabel(ServerKind k) => switch (k) {
      ServerKind.emby => 'Emby',
      ServerKind.jellyfin => 'Jellyfin',
      ServerKind.fnos => '飞牛影视',
    };

class MediaServer {
  final String id; // 本地编号
  ServerKind kind;
  String name;
  String url; // 不带末尾斜杠，例 http://192.168.1.2:8096
  String username;
  String userId;
  String token;
  String deviceId;

  MediaServer({
    required this.id,
    required this.kind,
    required this.name,
    required this.url,
    required this.username,
    required this.userId,
    required this.token,
    required this.deviceId,
  });

  factory MediaServer.fromJson(Map<String, dynamic> j) => MediaServer(
        id: j['id'],
        kind: ServerKind.values.firstWhere((k) => k.name == j['kind'], orElse: () => ServerKind.emby),
        name: j['name'] ?? '',
        url: j['url'] ?? '',
        username: j['username'] ?? '',
        userId: j['userId'] ?? '',
        token: j['token'] ?? '',
        deviceId: j['deviceId'] ?? '',
      );

  Map<String, dynamic> toJson() => {
        'id': id,
        'kind': kind.name,
        'name': name,
        'url': url,
        'username': username,
        'userId': userId,
        'token': token,
        'deviceId': deviceId,
      };
}

/// 一个条目（电影 / 剧集 / 季 / 集 / 媒体库）
class MediaItem {
  final MediaServer server;
  final Map<String, dynamic> raw;
  MediaItem(this.server, this.raw);

  String get id => raw['Id'] ?? '';
  String get name => raw['Name'] ?? '';
  String get type => raw['Type'] ?? ''; // Movie / Series / Season / Episode / CollectionFolder / Video …
  String get collectionType => raw['CollectionType'] ?? '';
  String get overview => raw['Overview'] ?? '';
  int? get year => raw['ProductionYear'];
  String? get seriesId => raw['SeriesId'];
  String? get seriesName => raw['SeriesName'];
  String? get seasonId => raw['SeasonId'];
  int? get indexNumber => raw['IndexNumber'];
  int? get parentIndexNumber => raw['ParentIndexNumber'];
  double? get rating => (raw['CommunityRating'] as num?)?.toDouble();
  int get runTimeTicks => (raw['RunTimeTicks'] as num?)?.toInt() ?? 0;
  Map<String, dynamic> get userData => (raw['UserData'] as Map?)?.cast<String, dynamic>() ?? const {};
  int get positionTicks => (userData['PlaybackPositionTicks'] as num?)?.toInt() ?? 0;
  bool get played => userData['Played'] == true;
  bool get isFolder => raw['IsFolder'] == true;
  bool get isPlayable => type == 'Movie' || type == 'Episode' || type == 'Video' || type == 'MusicVideo';

  /// 进度 0~1（继续观看用）
  double get progress => runTimeTicks > 0 ? (positionTicks / runTimeTicks).clamp(0.0, 1.0) : 0;

  /// 「S1E3 标题」或者标题
  String get displayTitle {
    if (type == 'Episode') {
      final s = parentIndexNumber, e = indexNumber;
      final tag = (s != null && e != null) ? 'S${s}E$e ' : (e != null ? '第 $e 集 ' : '');
      return '$tag$name';
    }
    return name;
  }

  /// 海报图；剧集里的单集没有竖图时用剧的
  String? posterUrl({int maxHeight = 450}) {
    final tags = (raw['ImageTags'] as Map?) ?? const {};
    if (tags['Primary'] != null) return EmbyApi(server).imageUrl(id, 'Primary', maxHeight: maxHeight);
    final sid = raw['SeriesId'];
    if (sid != null && raw['SeriesPrimaryImageTag'] != null) return EmbyApi(server).imageUrl(sid, 'Primary', maxHeight: maxHeight);
    if (type == 'Episode' || type == 'Season') {
      if (sid != null) return EmbyApi(server).imageUrl(sid, 'Primary', maxHeight: maxHeight);
    }
    return null;
  }

  /// 横图（单集截图 / 背景）
  String? thumbUrl({int maxWidth = 640}) {
    final tags = (raw['ImageTags'] as Map?) ?? const {};
    if (type == 'Episode' && tags['Primary'] != null) return EmbyApi(server).imageUrl(id, 'Primary', maxWidth: maxWidth);
    if (tags['Thumb'] != null) return EmbyApi(server).imageUrl(id, 'Thumb', maxWidth: maxWidth);
    final bd = raw['BackdropImageTags'] as List?;
    if (bd != null && bd.isNotEmpty) return EmbyApi(server).imageUrl(id, 'Backdrop', maxWidth: maxWidth);
    final pbd = raw['ParentBackdropItemId'];
    if (pbd != null) return EmbyApi(server).imageUrl(pbd, 'Backdrop', maxWidth: maxWidth);
    return posterUrl();
  }

  String? backdropUrl({int maxWidth = 1280}) {
    final bd = raw['BackdropImageTags'] as List?;
    if (bd != null && bd.isNotEmpty) return EmbyApi(server).imageUrl(id, 'Backdrop', maxWidth: maxWidth);
    final pbd = raw['ParentBackdropItemId'] ?? raw['SeriesId'];
    if (pbd != null) return EmbyApi(server).imageUrl(pbd, 'Backdrop', maxWidth: maxWidth);
    return null;
  }
}

/// 播放需要的信息
class PlaybackSource {
  final String url;
  final String mediaSourceId;
  final String? playSessionId;
  final List<ExternalSubtitle> subtitles;
  PlaybackSource(this.url, this.mediaSourceId, this.playSessionId, this.subtitles);
}

class ExternalSubtitle {
  final String url;
  final String title;
  final String? language;
  ExternalSubtitle(this.url, this.title, this.language);
}

class EmbyException implements Exception {
  final String message;
  EmbyException(this.message);
  @override
  String toString() => message;
}

class EmbyApi {
  static const clientName = 'Nagisaland';
  static const clientVersion = '0.19.0';
  final MediaServer server;
  EmbyApi(this.server);

  static String newDeviceId() {
    final r = Random.secure();
    return List.generate(16, (_) => r.nextInt(256).toRadixString(16).padLeft(2, '0')).join();
  }

  static String normalizeUrl(String input) {
    var u = input.trim();
    if (u.isEmpty) return u;
    if (!u.startsWith('http://') && !u.startsWith('https://')) u = 'http://$u';
    while (u.endsWith('/')) {
      u = u.substring(0, u.length - 1);
    }
    return u;
  }

  static String _authValue(String deviceId, String? token) {
    final b = StringBuffer('MediaBrowser Client="$clientName", Device="Android", DeviceId="$deviceId", Version="$clientVersion"');
    if (token != null && token.isNotEmpty) b.write(', Token="$token"');
    return b.toString();
  }

  static Map<String, String> _headers(String deviceId, String? token) {
    final v = _authValue(deviceId, token);
    // Jellyfin 新版只认 Authorization，Emby 认 X-Emby-Authorization；两个都带
    return {
      'Authorization': v,
      'X-Emby-Authorization': v,
      if (token != null && token.isNotEmpty) 'X-Emby-Token': token,
      if (token != null && token.isNotEmpty) 'X-MediaBrowser-Token': token,
      'Accept': 'application/json',
    };
  }

  static Dio _dio(String baseUrl, Map<String, String> headers) => Dio(BaseOptions(
        baseUrl: baseUrl,
        headers: headers,
        connectTimeout: const Duration(seconds: 12),
        receiveTimeout: const Duration(seconds: 30),
        responseType: ResponseType.json,
      ));

  Dio get _client => _dio(server.url, _headers(server.deviceId, server.token));

  static String describeError(Object e) {
    if (e is EmbyException) return e.message;
    if (e is DioException) {
      final code = e.response?.statusCode;
      if (code == 401) return '用户名或密码不对（或登录已过期，请重新登录）';
      if (code != null) return '服务器返回 $code';
      return switch (e.type) {
        DioExceptionType.connectionTimeout => '连接超时：地址 / 端口对吗？手机能访问到服务器吗？',
        DioExceptionType.receiveTimeout => '服务器响应太慢',
        DioExceptionType.connectionError => '连不上服务器：${e.message ?? ''}',
        _ => e.message ?? e.toString(),
      };
    }
    return e.toString();
  }

  /// 登录：先读 /System/Info/Public 认是 Emby 还是 Jellyfin，再用户名密码换令牌
  static Future<MediaServer> login({
    required String url,
    required String username,
    required String password,
    ServerKind? kind,
    String? localId,
    String? deviceId,
  }) async {
    final base = normalizeUrl(url);
    if (base.isEmpty) throw EmbyException('请填服务器地址');
    final dev = deviceId ?? newDeviceId();
    final anon = _dio(base, _headers(dev, null));
    String name = base;
    var k = kind ?? ServerKind.emby;
    try {
      final info = (await anon.get('/System/Info/Public')).data;
      if (info is Map) {
        name = (info['ServerName'] as String?)?.trim().isNotEmpty == true ? info['ServerName'] : base;
        final product = '${info['ProductName'] ?? ''}'.toLowerCase();
        if (kind == null) k = product.contains('jellyfin') ? ServerKind.jellyfin : ServerKind.emby;
      }
    } catch (e) {
      throw EmbyException('连不上服务器：${describeError(e)}');
    }
    final r = await anon.post('/Users/AuthenticateByName', data: {'Username': username, 'Pw': password, 'Password': password});
    final d = r.data as Map;
    final token = d['AccessToken'] as String?;
    final user = d['User'] as Map?;
    if (token == null || user == null) throw EmbyException('登录失败：服务器没有返回令牌');
    return MediaServer(
      id: localId ?? newDeviceId(),
      kind: k,
      name: name,
      url: base,
      username: username,
      userId: user['Id'],
      token: token,
      deviceId: dev,
    );
  }

  String imageUrl(String itemId, String type, {int? maxHeight, int? maxWidth}) {
    final q = <String>['quality=90'];
    if (maxHeight != null) q.add('maxHeight=$maxHeight');
    if (maxWidth != null) q.add('maxWidth=$maxWidth');
    return '${server.url}/Items/$itemId/Images/$type?${q.join('&')}';
  }

  /// 图片请求头（有的服务器图片也要令牌）
  Map<String, String> get imageHeaders => {'X-Emby-Token': server.token};

  List<MediaItem> _items(dynamic data) {
    final list = (data is Map ? data['Items'] : data) as List? ?? const [];
    return list.whereType<Map>().map((m) => MediaItem(server, m.cast<String, dynamic>())).toList();
  }

  static const _fields = 'PrimaryImageAspectRatio,ProductionYear,Overview,BasicSyncInfo,UserData,SeriesPrimaryImage,ParentBackdropItemId';

  Future<List<MediaItem>> views() async =>
      _items((await _client.get('/Users/${server.userId}/Views')).data);

  Future<List<MediaItem>> resume({int limit = 20}) async => _items((await _client.get('/Users/${server.userId}/Items/Resume',
          queryParameters: {'Limit': limit, 'MediaTypes': 'Video', 'Fields': _fields, 'EnableImageTypes': 'Primary,Backdrop,Thumb'}))
      .data);

  Future<List<MediaItem>> nextUp({int limit = 20}) async => _items((await _client.get('/Shows/NextUp',
          queryParameters: {'UserId': server.userId, 'Limit': limit, 'Fields': _fields}))
      .data);

  Future<List<MediaItem>> latest({String? parentId, int limit = 20}) async => _items((await _client.get(
          '/Users/${server.userId}/Items/Latest',
          queryParameters: {'Limit': limit, 'Fields': _fields, if (parentId != null) 'ParentId': parentId}))
      .data);

  /// 媒体库里的条目（分页）；返回 (条目, 总数)
  Future<(List<MediaItem>, int)> items({
    String? parentId,
    String? searchTerm,
    String includeTypes = 'Movie,Series,Video,MusicVideo',
    String sortBy = 'SortName',
    String sortOrder = 'Ascending',
    int start = 0,
    int limit = 60,
    bool recursive = true,
  }) async {
    final r = await _client.get('/Users/${server.userId}/Items', queryParameters: {
      if (parentId != null) 'ParentId': parentId,
      if (searchTerm != null) 'SearchTerm': searchTerm,
      if (includeTypes.isNotEmpty) 'IncludeItemTypes': includeTypes,
      'Recursive': recursive,
      'SortBy': sortBy,
      'SortOrder': sortOrder,
      'StartIndex': start,
      'Limit': limit,
      'Fields': _fields,
      'EnableTotalRecordCount': true,
    });
    final total = (r.data is Map ? (r.data['TotalRecordCount'] as num?)?.toInt() : null) ?? 0;
    return (_items(r.data), total);
  }

  Future<MediaItem> item(String id) async {
    final r = await _client.get('/Users/${server.userId}/Items/$id');
    return MediaItem(server, (r.data as Map).cast<String, dynamic>());
  }

  Future<List<MediaItem>> seasons(String seriesId) async => _items((await _client.get('/Shows/$seriesId/Seasons',
          queryParameters: {'UserId': server.userId, 'Fields': _fields}))
      .data);

  Future<List<MediaItem>> episodes(String seriesId, {String? seasonId}) async => _items((await _client.get(
          '/Shows/$seriesId/Episodes',
          queryParameters: {'UserId': server.userId, 'Fields': _fields, if (seasonId != null) 'SeasonId': seasonId}))
      .data);

  /// 取播放地址：直接播放原文件（static=true，mpv 什么格式都能放，不让服务器转码）
  Future<PlaybackSource> playback(String itemId) async {
    String msId = itemId;
    String? session;
    String container = '';
    final subs = <ExternalSubtitle>[];
    try {
      final r = await _client.post('/Items/$itemId/PlaybackInfo',
          queryParameters: {'UserId': server.userId},
          data: jsonEncode({
            'UserId': server.userId,
            'DeviceProfile': {'MaxStreamingBitrate': 1000000000, 'DirectPlayProfiles': [{'Type': 'Video'}]},
          }),
          options: Options(contentType: 'application/json'));
      final d = r.data as Map;
      session = d['PlaySessionId'] as String?;
      final sources = d['MediaSources'] as List?;
      if (sources != null && sources.isNotEmpty) {
        final s = sources.first as Map;
        msId = (s['Id'] as String?) ?? itemId;
        container = (s['Container'] as String?) ?? '';
        for (final st in (s['MediaStreams'] as List? ?? const [])) {
          if (st is! Map || st['Type'] != 'Subtitle' || st['IsExternal'] != true) continue;
          final idx = st['Index'];
          final codec = '${st['Codec'] ?? 'srt'}'.toLowerCase();
          final ext = switch (codec) { 'subrip' => 'srt', 'ass' || 'ssa' => 'ass', 'webvtt' => 'vtt', _ => codec };
          subs.add(ExternalSubtitle(
            '${server.url}/Videos/$itemId/$msId/Subtitles/$idx/Stream.$ext?api_key=${server.token}',
            (st['DisplayTitle'] ?? st['Title'] ?? st['Language'] ?? '外挂字幕 $idx').toString(),
            st['Language'] as String?,
          ));
        }
      }
    } catch (_) {
      // 有的服务器不支持 PlaybackInfo 的 POST：直接用条目 id
    }
    final q = {
      'static': 'true',
      'MediaSourceId': msId,
      'DeviceId': server.deviceId,
      'api_key': server.token,
      if (session != null) 'PlaySessionId': session,
    };
    final ext = container.isNotEmpty && !container.contains(',') ? '.$container' : '';
    final url = '${server.url}/Videos/$itemId/stream$ext?${Uri(queryParameters: q).query}';
    return PlaybackSource(url, msId, session, subs);
  }

  Map<String, dynamic> _report(String itemId, PlaybackSource src, Duration pos, {bool paused = false}) => {
        'ItemId': itemId,
        'MediaSourceId': src.mediaSourceId,
        if (src.playSessionId != null) 'PlaySessionId': src.playSessionId,
        'PositionTicks': pos.inMicroseconds * 10,
        'CanSeek': true,
        'IsPaused': paused,
        'PlayMethod': 'DirectPlay',
      };

  /// 播放进度回报（服务器上「继续观看」用）；失败不影响播放
  Future<void> reportStart(String itemId, PlaybackSource src, Duration pos) =>
      _quiet(() => _client.post('/Sessions/Playing', data: jsonEncode(_report(itemId, src, pos)), options: Options(contentType: 'application/json')));

  Future<void> reportProgress(String itemId, PlaybackSource src, Duration pos, bool paused) => _quiet(() => _client.post(
      '/Sessions/Playing/Progress',
      data: jsonEncode(_report(itemId, src, pos, paused: paused)),
      options: Options(contentType: 'application/json')));

  Future<void> reportStopped(String itemId, PlaybackSource src, Duration pos) => _quiet(() => _client.post(
      '/Sessions/Playing/Stopped',
      data: jsonEncode(_report(itemId, src, pos)),
      options: Options(contentType: 'application/json')));

  Future<void> setPlayed(String itemId, bool played) => _quiet(() => played
      ? _client.post('/Users/${server.userId}/PlayedItems/$itemId')
      : _client.delete('/Users/${server.userId}/PlayedItems/$itemId'));

  Future<void> logout() => _quiet(() => _client.post('/Sessions/Logout'));

  static Future<void> _quiet(Future<dynamic> Function() f) async {
    try {
      await f();
    } catch (_) {}
  }
}
