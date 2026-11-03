// Nagisaland「影视」分区：飞牛影视（fnOS 影视 / trim.media）接口。
//
// 飞牛没有公开 API 文档，下面按社区逆向整理的协议自己写（参考：fntv-electron、momokind/plugin.video.fnmedia、
// JellyPlex-Watched 的 TrimMedia 适配；代码没有照抄）：
//   - 路径都以 /v/api/v1 开头；响应外层 {code, msg, data}，code == 0 才算成功
//   - 每个请求带 Authx 头：nonce=<6 位>&timestamp=<毫秒>&sign=MD5(apiKey_路径_nonce_timestamp_MD5(body)_apiSecret)
//     （apiKey / apiSecret 是官方网页客户端里的固定值）；POST 的 body 里也要放一个 nonce，签名对的是实际发送的 body
//   - 登录：POST /login {app_name, username, password} → token；之后 Authorization: <token>
//   - 图片 /sys/img<路径>?w=… 和视频 /media/range/<media_guid> 也要 Authorization（图片还要 Authx）
// 飞牛升级后接口可能变：出错时提示里带上飞牛返回的 msg。远程访问（FN ID 中转）不支持，只能用局域网 / 直连地址。
//
// 返回的数据都转成 Emby 的字段（Id / Name / Type / UserData…），界面那边不用分两套。
import 'dart:convert';
import 'dart:math';

import 'package:crypto/crypto.dart';
import 'package:dio/dio.dart';

import 'emby_api.dart';

class FnosApi {
  static const _apiKey = 'NDzZTVxnRKP8Z0jXg1VAMonaG8akvh';
  static const _apiSecret = '16CCEB3D-AB42-077D-36A1-F355324E4237';
  static const _prefix = '/v/api/v1';

  /// 图片路径（条目 id → 各类图片在飞牛上的路径），转条目时记下，取图片地址时用
  static final Map<String, Map<String, String>> _images = {};

  final MediaServer server;
  FnosApi(this.server);

  static final _rand = Random();
  static String _md5(String s) => md5.convert(utf8.encode(s)).toString();
  static String _nonce() => (100000 + _rand.nextInt(900000)).toString();

  static String authx(String pathWithQuery, [String body = '']) {
    final nonce = _nonce();
    final ts = DateTime.now().millisecondsSinceEpoch.toString();
    final sign = _md5([_apiKey, pathWithQuery, nonce, ts, _md5(body), _apiSecret].join('_'));
    return 'nonce=$nonce&timestamp=$ts&sign=$sign';
  }

  static Dio _dio(String base) => Dio(BaseOptions(
        baseUrl: base,
        connectTimeout: const Duration(seconds: 12),
        receiveTimeout: const Duration(seconds: 60),
        responseType: ResponseType.plain,
        validateStatus: (_) => true,
      ));

  /// 发请求；返回 data。[path] 不带 /v/api/v1 前缀
  static Future<dynamic> call(String base, String? token, String method, String path, [Map<String, dynamic>? data]) async {
    final full = '$_prefix$path';
    String body = '';
    if (method == 'POST') {
      final payload = <String, dynamic>{...?data, 'nonce': _nonce()};
      body = jsonEncode(payload);   // 和 JS JSON.stringify 一样：紧凑、保持顺序
    }
    final headers = <String, String>{
      'Content-Type': 'application/json',
      'Cookie': 'mode=relay',
      'Authx': authx(full, body),
      if (token != null && token.isNotEmpty) 'Authorization': token,
    };
    final r = await _dio(base).request<String>(full,
        data: method == 'POST' ? body : null, options: Options(method: method, headers: headers));
    final text = r.data ?? '';
    dynamic env;
    try {
      env = jsonDecode(text);
    } catch (_) {
      throw EmbyException(r.statusCode == 404
          ? '找不到飞牛影视的接口（404）：地址要填飞牛系统的地址（例如 http://192.168.1.2:5666）'
          : '飞牛返回的不是 JSON（HTTP ${r.statusCode}）：地址对吗？');
    }
    if (env is! Map) throw EmbyException('飞牛返回的格式不对');
    final code = env['code'];
    if (code == 0) return env['data'];
    final msg = '${env['msg'] ?? '错误码 $code'}';
    if (r.statusCode == 401 || r.statusCode == 403 || msg.contains('token') || msg.contains('登录')) {
      throw EmbyException('飞牛：登录过期或没有权限（$msg），请在服务器上点「修改 / 重新登录」');
    }
    throw EmbyException('飞牛：$msg');
  }

  Future<dynamic> _get(String path) => call(server.url, server.token, 'GET', path);
  Future<dynamic> _post(String path, Map<String, dynamic> data) => call(server.url, server.token, 'POST', path, data);

  // ---------------------------------------------------------------- 测试连接 / 登录

  static Future<ServerProbe> probe(String base, {String? deviceId}) async {
    if (base.isEmpty) throw EmbyException('请填服务器地址');
    // 不登录也能调的接口不多：随便调一个，只要回的是飞牛的 {code, msg} 格式就算连上了
    try {
      await call(base, null, 'GET', '/user/info');
    } on EmbyException catch (e) {
      if (e.message.startsWith('找不到') || e.message.startsWith('飞牛返回的')) rethrow;
      // 「没登录」之类的错误 = 服务器是对的
    }
    final host = Uri.tryParse(base)?.host ?? base;
    return ServerProbe(base: base, kind: ServerKind.fnos, name: '飞牛影视 $host', version: '', product: '飞牛影视',
        deviceId: deviceId ?? EmbyApi.newDeviceId());
  }

  static Future<MediaServer> login({
    required ServerProbe probe,
    required String username,
    required String password,
    String? localId,
    String? displayName,
  }) async {
    final user = username.trim();
    if (user.isEmpty) throw EmbyException('用户名必填（飞牛系统的账号）');
    final d = await call(probe.base, null, 'POST', '/login', {
      'app_name': 'trimemedia-web',
      'username': user,
      'password': password,
    });
    final token = d is Map ? d['token'] as String? : null;
    if (token == null || token.isEmpty) throw EmbyException('飞牛登录成功但没有返回令牌');
    return MediaServer(
      id: localId ?? EmbyApi.newDeviceId(),
      kind: ServerKind.fnos,
      name: (displayName?.trim().isNotEmpty ?? false) ? displayName!.trim() : probe.name,
      url: probe.base,
      username: user,
      userId: user,
      token: token,
      deviceId: probe.deviceId,
    );
  }

  // ---------------------------------------------------------------- 图片

  String imageUrl(String itemId, String type, {int? maxHeight, int? maxWidth}) {
    final imgs = _images[itemId] ?? const {};
    final path = switch (type) {
      'Backdrop' => imgs['backdrop'] ?? imgs['still'] ?? imgs['poster'],
      'Thumb' => imgs['still'] ?? imgs['backdrop'] ?? imgs['poster'],
      _ => imgs['poster'] ?? imgs['still'],
    };
    if (path == null || path.isEmpty) return '';
    if (path.startsWith('http')) return path;
    final w = maxWidth ?? ((maxHeight ?? 450) * 2 ~/ 3);
    return '${server.url}$_prefix/sys/img${path.startsWith('/') ? '' : '/'}$path?w=$w';
  }

  Map<String, String> imageHeaders(String url) {
    final i = url.indexOf(_prefix);
    return {
      'Authorization': server.token,
      'Cookie': 'mode=relay',
      if (i >= 0) 'Authx': authx(url.substring(i)),
    };
  }

  // ---------------------------------------------------------------- 条目：飞牛 → Emby 的字段

  static String _type(String t) => switch (t) {
        'TV' => 'Series',
        'Directory' || 'Folder' => 'Folder',
        '' => 'Video',
        _ => t, // Movie / Season / Episode / Video
      };

  static int? _int(dynamic v) => v is num ? v.toInt() : int.tryParse('${v ?? ''}');

  MediaItem _map(Map m, {String? fallbackType}) {
    final id = '${m['guid'] ?? m['item_guid'] ?? ''}';
    final type = _type('${m['type'] ?? fallbackType ?? ''}');
    String? str(List<String> keys) {
      for (final k in keys) {
        final v = m[k];
        if (v is String && v.isNotEmpty) return v;
        if (v is List && v.isNotEmpty && v.first is String) return v.first as String;
      }
      return null;
    }

    final poster = str(['poster', 'posters', 'poster_path']);
    final still = str(['still_path', 'still']);
    final backdrop = str(['backdrop', 'backdrops', 'backdrop_path', 'fanart']);
    _images[id] = {
      if (poster != null) 'poster': poster,
      if (still != null) 'still': still,
      if (backdrop != null) 'backdrop': backdrop,
    };
    final airDate = '${m['air_date'] ?? m['release_date'] ?? ''}';
    final year = _int(m['year']) ?? (airDate.length >= 4 ? int.tryParse(airDate.substring(0, 4)) : null);
    final duration = _int(m['duration']) ?? _int(m['runtime']) ?? 0;   // 秒
    final ts = _int(m['ts']) ?? _int(m['watched_ts']) ?? 0;            // 秒
    final watched = m['watched'] == 1 || m['watched'] == true;
    final raw = <String, dynamic>{
      'Id': id,
      'Name': '${m['title'] ?? m['name'] ?? m['tv_title'] ?? ''}',
      'Type': type,
      'IsFolder': type == 'Folder' || type == 'Series' || type == 'Season' || type == 'CollectionFolder',
      if (m['overview'] != null || m['plot'] != null) 'Overview': '${m['overview'] ?? m['plot']}',
      if (year != null) 'ProductionYear': year,
      if (m['tv_title'] != null) 'SeriesName': '${m['tv_title']}',
      if (m['ancestor_guid'] != null) 'SeriesId': '${m['ancestor_guid']}',
      if (m['parent_guid'] != null && type == 'Episode') 'SeasonId': '${m['parent_guid']}',
      if (_int(m['episode_number']) != null) 'IndexNumber': _int(m['episode_number']),
      if (_int(m['season_number']) != null) 'ParentIndexNumber': _int(m['season_number']),
      if (m['vote_average'] is num) 'CommunityRating': (m['vote_average'] as num).toDouble(),
      'RunTimeTicks': duration * 10000000,
      'UserData': {'PlaybackPositionTicks': ts * 10000000, 'Played': watched},
      'ImageTags': {
        if (poster != null) 'Primary': 'p',
        if (still != null) 'Thumb': 't',
      },
      if (backdrop != null) 'BackdropImageTags': ['b'],
      'nagiFnos': m,
    };
    if (type == 'Episode' && still != null) {
      // 单集：竖图没有就用截图
      (raw['ImageTags'] as Map)['Primary'] = 'p';
      _images[id]!.putIfAbsent('poster', () => still);
    }
    return MediaItem(server, raw);
  }

  List<MediaItem> _list(dynamic d, {String? fallbackType}) {
    final List list = d is List ? d : (d is Map ? ((d['list'] ?? d['items']) as List? ?? const []) : const []);
    return list.whereType<Map>().map((m) => _map(m, fallbackType: fallbackType)).toList();
  }

  // ---------------------------------------------------------------- 浏览

  Future<List<MediaItem>> views() async {
    final d = await _get('/mediadb/list');
    final List list = d is List ? d : (d is Map ? (d['list'] as List? ?? const []) : const []);
    return list.whereType<Map>().map((m) {
      final id = '${m['guid'] ?? ''}';
      final poster = (m['posters'] is List && (m['posters'] as List).isNotEmpty) ? '${(m['posters'] as List).first}' : m['poster'] as String?;
      _images[id] = {if (poster != null) 'poster': poster};
      return MediaItem(server, {
        'Id': id,
        'Name': '${m['title'] ?? m['name'] ?? m['mdb_name'] ?? '媒体库'}',
        'Type': 'CollectionFolder',
        'CollectionType': '${m['category'] ?? ''}',
        'IsFolder': true,
        'ImageTags': {if (poster != null) 'Primary': 'p'},
      });
    }).toList();
  }

  Future<(List<MediaItem>, int)> items({String? parentId, String? searchTerm, int start = 0, int limit = 60}) async {
    final body = <String, dynamic>{
      'parent_guid': '',
      'exclude_folder': 1,
      'sort_column': 'sort_title',
      'sort_type': 'ASC',
      if (parentId != null && parentId.isNotEmpty) 'ancestor_guid': parentId,
      'page': start ~/ max(1, limit) + 1,
      'page_size': limit,
    };
    if (searchTerm != null && searchTerm.isNotEmpty) {
      // 飞牛的搜索接口没整理到：取一大页按标题过滤
      body['page'] = 1;
      body['page_size'] = 500;
    }
    final d = await _post('/item/list', body);
    var list = _list(d);
    var total = d is Map ? (_int(d['total']) ?? list.length) : list.length;
    if (searchTerm != null && searchTerm.isNotEmpty) {
      final q = searchTerm.toLowerCase();
      list = list.where((e) => e.name.toLowerCase().contains(q)).toList();
      total = list.length;
    }
    return (list, total);
  }

  Future<List<MediaItem>> latest({String? parentId, int limit = 20}) async {
    try {
      final d = await _post('/item/list', {
        'parent_guid': '',
        'exclude_folder': 1,
        'sort_column': 'create_time',
        'sort_type': 'DESC',
        if (parentId != null) 'ancestor_guid': parentId,
        'page': 1,
        'page_size': limit,
      });
      return _list(d);
    } catch (_) {
      return const [];
    }
  }

  /// 继续观看 / 下一集：飞牛的这两个接口没整理到，先不显示（聚合视界里只是少了这两栏）
  Future<List<MediaItem>> resume() async => const [];

  Future<MediaItem> item(String id) async => _map(await _get('/item/$id') as Map);

  Future<List<MediaItem>> seasons(String seriesId) async => _list(await _get('/season/list/$seriesId'), fallbackType: 'Season');

  Future<List<MediaItem>> episodes(String seriesId, {String? seasonId}) async =>
      _list(await _get('/episode/list/${seasonId ?? seriesId}'), fallbackType: 'Episode');

  // ---------------------------------------------------------------- 播放

  Future<PlaybackSource> playback(String itemId) async {
    final d = await _post('/play/info', {'item_guid': itemId});
    if (d is! Map) throw EmbyException('飞牛没有返回播放信息');
    final mediaGuid = '${d['media_guid'] ?? ''}';
    if (mediaGuid.isEmpty) throw EmbyException('飞牛没有返回这个视频的文件（可能是网盘 / 原盘，暂不支持）');
    final path = '$_prefix/media/range/$mediaGuid';
    final item = d['item'] is Map ? d['item'] as Map : const {};
    final extra = jsonEncode({
      'media_guid': mediaGuid,
      'video_guid': d['video_guid'] ?? '',
      'audio_guid': d['audio_guid'] ?? '',
      'subtitle_guid': d['subtitle_guid'] ?? '',
      'duration': _int(item['duration']) ?? _int(d['duration']) ?? 0,
      'play_link': path,
    });
    return PlaybackSource('${server.url}$path', mediaGuid, extra, const [],
        headers: {'Authorization': server.token, 'Cookie': 'mode=relay', 'Authx': authx(path)});
  }

  Future<void> record(String itemId, PlaybackSource src, Duration pos) async {
    try {
      final e = jsonDecode(src.playSessionId ?? '{}') as Map;
      await _post('/play/record', {
        'item_guid': itemId,
        'media_guid': e['media_guid'] ?? src.mediaSourceId,
        'video_guid': e['video_guid'] ?? '',
        'audio_guid': e['audio_guid'] ?? '',
        'subtitle_guid': e['subtitle_guid'] ?? '',
        'play_link': e['play_link'] ?? '',
        'ts': pos.inSeconds,
        'duration': e['duration'] ?? 0,
      });
    } catch (_) {}
  }

  Future<void> setPlayed(String itemId, bool played) async {
    if (!played) return; // 「标为未看」的接口没整理到
    try {
      await _post('/item/watched', {'item_guid': itemId});
    } catch (_) {}
  }
}
