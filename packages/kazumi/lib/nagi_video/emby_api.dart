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

/// 「测试连接」的结果
class ServerProbe {
  final String base; // 实际能用的基础地址（可能自动加了 /emby）
  final ServerKind kind;
  final String name;
  final String version;
  final String product;
  final String deviceId;
  ServerProbe({required this.base, required this.kind, required this.name, required this.version, required this.product, required this.deviceId});
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

  /// 地址 + 端口 → 基础地址。端口框留空：用地址里写的端口（没写就是 http 80 / https 443）
  static String buildBase(String address, String port) {
    final u = normalizeUrl(address);
    if (u.isEmpty) return u;
    final p = port.trim();
    final uri = Uri.tryParse(u);
    if (uri == null || uri.host.isEmpty) throw EmbyException('地址格式不对：$address');
    if (p.isEmpty) return u;
    final n = int.tryParse(p);
    if (n == null || n <= 0 || n > 65535) throw EmbyException('端口要是 1~65535 的数字');
    final b = normalizeUrl(uri.replace(port: n).toString());
    return b;
  }

  /// Emby 用 `Emby …`，Jellyfin 用 `MediaBrowser …`（Jellyfin 新版只认 Authorization 头，Emby 认 X-Emby-Authorization）
  static String _authValue(ServerKind kind, String deviceId, String? token) {
    final scheme = kind == ServerKind.jellyfin ? 'MediaBrowser' : 'Emby';
    final b = StringBuffer('$scheme Client="$clientName", Device="Android", DeviceId="$deviceId", Version="$clientVersion"');
    if (token != null && token.isNotEmpty) b.write(', Token="$token"');
    return b.toString();
  }

  static Map<String, String> _headers(ServerKind kind, String deviceId, String? token) {
    final v = _authValue(kind, deviceId, token);
    return {
      // Emby 不发 Authorization 头：前面有反代（nginx / Cloudflare）时，不认识的 Authorization 可能被直接打回 400
      if (kind == ServerKind.jellyfin) 'Authorization': v,
      'X-Emby-Authorization': v,
      if (token != null && token.isNotEmpty) 'X-Emby-Token': token,
      if (token != null && token.isNotEmpty && kind == ServerKind.jellyfin) 'X-MediaBrowser-Token': token,
      'Accept': 'application/json',
    };
  }

  /// Emby 网页版的做法：客户端信息同时放在地址参数里（有的反代会去掉自定义请求头）
  static Map<String, String> _authQuery(String deviceId) => {
        'X-Emby-Client': clientName,
        'X-Emby-Device-Name': 'Android',
        'X-Emby-Device-Id': deviceId,
        'X-Emby-Client-Version': clientVersion,
      };

  static Dio _dio(String baseUrl, Map<String, String> headers) => Dio(BaseOptions(
        baseUrl: baseUrl,
        headers: headers,
        connectTimeout: const Duration(seconds: 12),
        receiveTimeout: const Duration(seconds: 30),
        responseType: ResponseType.json,
      ));

  Dio get _client => _dio(server.url, _headers(server.kind, server.deviceId, server.token));

  /// 服务器返回的出错内容（JSON 的 Message / error，或者网页正文前 200 字）
  static String _body(Response? r) {
    if (r == null) return '';
    final d = r.data;
    String text;
    if (d is Map) {
      text = '${d['Message'] ?? d['message'] ?? d['error'] ?? d['title'] ?? d['detail'] ?? jsonEncode(d)}';
    } else {
      text = '${d ?? ''}';
      text = text.replaceAll(RegExp(r'<[^>]+>'), ' ').replaceAll(RegExp(r'\s+'), ' ').trim();
    }
    if (text.length > 200) text = '${text.substring(0, 200)}…';
    return text;
  }

  static String describeError(Object e) {
    if (e is EmbyException) return e.message;
    if (e is DioException) {
      final code = e.response?.statusCode;
      final body = _body(e.response);
      final detail = body.isEmpty ? '' : '\n服务器说：$body';
      if (code == 401) return '用户名或密码不对（或登录已过期，请重新登录）$detail';
      if (code == 404) return '找不到接口（404）：地址对吗？Emby 有时要在地址后面加 /emby$detail';
      if (code != null) return '服务器返回 $code$detail';
      return switch (e.type) {
        DioExceptionType.connectionTimeout => '连接超时：地址 / 端口对吗？手机能访问到服务器吗？',
        DioExceptionType.receiveTimeout => '服务器响应太慢',
        DioExceptionType.badCertificate => 'HTTPS 证书不受信任（自签名证书？试试用 http 地址）',
        DioExceptionType.connectionError => '连不上服务器：${e.message ?? e.error ?? ''}\n（http / https 选对了吗？端口对吗？）',
        _ => e.message ?? e.error?.toString() ?? e.toString(),
      };
    }
    return e.toString();
  }

  /// 测试连接：读 /System/Info/Public（不用登录），认出是 Emby 还是 Jellyfin；
  /// 直接访问不行时再试加 /emby 前缀（Emby 的老路径）
  static Future<ServerProbe> probe(String base, {ServerKind? kind, String? deviceId}) async {
    if (base.isEmpty) throw EmbyException('请填服务器地址');
    final dev = deviceId ?? newDeviceId();
    final candidates = [base, if (!base.toLowerCase().endsWith('/emby')) '$base/emby'];
    Object? firstError;
    for (final c in candidates) {
      try {
        final r = await _dio(c, _headers(kind ?? ServerKind.emby, dev, null))
            .get('/System/Info/Public', options: Options(responseType: ResponseType.json));
        final info = r.data;
        if (info is! Map || (info['Id'] == null && info['ServerName'] == null)) {
          throw EmbyException('这个地址不像 Emby / Jellyfin 服务器（/System/Info/Public 返回的不是服务器信息）');
        }
        final product = '${info['ProductName'] ?? ''}';
        final k = kind ?? (product.toLowerCase().contains('jellyfin') ? ServerKind.jellyfin : ServerKind.emby);
        final name = '${info['ServerName'] ?? ''}'.trim();
        return ServerProbe(
          base: c,
          kind: k,
          name: name.isEmpty ? Uri.parse(c).host : name,
          version: '${info['Version'] ?? ''}',
          product: product.isEmpty ? kindLabel(k) : product,
          deviceId: dev,
        );
      } catch (e) {
        firstError ??= e;
        // 连接层面的错误（连不上 / 超时 / 证书）换前缀也没用
        if (e is DioException && e.response == null) break;
      }
    }
    throw EmbyException('连不上服务器：${describeError(firstError!)}');
  }

  /// 用户名密码换令牌（密码不保存）
  static Future<MediaServer> login({
    required ServerProbe probe,
    required String username,
    required String password,
    String? localId,
    String? displayName,
  }) async {
    final user = username.trim();
    if (user.isEmpty) throw EmbyException('用户名必填（Emby / Jellyfin 都不能空着用户名登录）');
    final dio = _dio(probe.base, _headers(probe.kind, probe.deviceId, null));
    final r = await dio.post(
      '/Users/AuthenticateByName',
      queryParameters: probe.kind == ServerKind.emby ? _authQuery(probe.deviceId) : null,
      data: jsonEncode({'Username': user, 'Pw': password}),
      options: Options(contentType: Headers.jsonContentType),
    );
    final d = r.data is String ? jsonDecode(r.data as String) : r.data;
    if (d is! Map) throw EmbyException('登录失败：服务器返回的不是 JSON');
    final token = d['AccessToken'] as String?;
    final u = d['User'] as Map?;
    if (token == null || u == null) throw EmbyException('登录失败：服务器没有返回令牌');
    return MediaServer(
      id: localId ?? newDeviceId(),
      kind: probe.kind,
      name: (displayName?.trim().isNotEmpty ?? false) ? displayName!.trim() : probe.name,
      url: probe.base,
      username: user,
      userId: '${u['Id']}',
      token: token,
      deviceId: probe.deviceId,
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
