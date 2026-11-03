// Nagisaland「影视」分区（参考 Hills：服务器｜聚合视界｜设置）。
// 原生端 VideoActivity 用自己的 Flutter 引擎，dart 入口 nagiVideoMain（lib/main.dart 里转到这里）。
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:media_kit/media_kit.dart';

import '../nagi_suite_channel.dart';
import '../nagi_theme.dart';
import 'browse_pages.dart';
import 'emby_api.dart';
import 'video_store.dart';
import 'widgets.dart';

Future<void> nagiVideoMain() async {
  WidgetsFlutterBinding.ensureInitialized();
  // Nagisaland：左滑分区栏 = 回到影视首页并切到「设置」分页
  installNagiSuiteChannel(openSettings: () {
    _videoNavigatorKey.currentState?.popUntil((r) => r.isFirst);
    _settingsRequest.value++;
  });
  MediaKit.ensureInitialized();
  await VideoStore.instance.load();
  runApp(const NagiVideoApp());
}

final _videoNavigatorKey = GlobalKey<NavigatorState>();
final _settingsRequest = ValueNotifier<int>(0);

class NagiVideoApp extends StatelessWidget {
  const NagiVideoApp({super.key});

  @override
  Widget build(BuildContext context) {
    final theme = NagiTheme.apply(ThemeData(useMaterial3: true, brightness: Brightness.dark));
    return MaterialApp(
      navigatorKey: _videoNavigatorKey,
      title: '影视',
      debugShowCheckedModeBanner: false,
      theme: theme,
      darkTheme: theme,
      themeMode: ThemeMode.dark,
      localizationsDelegates: GlobalMaterialLocalizations.delegates,
      supportedLocales: const [Locale('zh', 'CN')],
      locale: const Locale('zh', 'CN'),
      home: const AnnotatedRegion<SystemUiOverlayStyle>(
        value: SystemUiOverlayStyle(statusBarColor: Colors.transparent, statusBarIconBrightness: Brightness.light),
        child: VideoHome(),
      ),
    );
  }
}

class VideoHome extends StatelessWidget {
  const VideoHome({super.key});

  @override
  Widget build(BuildContext context) {
    // 底部已经有原生的 Nagisaland 分区栏，这里的三个分页放在顶部
    return DefaultTabController(
      length: 3,
      child: _SettingsTabSwitch(child: Scaffold(
        appBar: AppBar(
          title: const Text('影视'),
          bottom: const TabBar(tabs: [Tab(text: '服务器'), Tab(text: '聚合视界'), Tab(text: '设置')]),
        ),
        body: const TabBarView(children: [ServersTab(), AggregateTab(), SettingsTab()]),
      )),
    );
  }
}

/// 收到「打开设置」时切到第 3 个分页（设置）
class _SettingsTabSwitch extends StatefulWidget {
  const _SettingsTabSwitch({required this.child});
  final Widget child;

  @override
  State<_SettingsTabSwitch> createState() => _SettingsTabSwitchState();
}

class _SettingsTabSwitchState extends State<_SettingsTabSwitch> {
  void _onRequest() {
    if (!mounted) return;
    DefaultTabController.maybeOf(context)?.animateTo(2);
  }

  @override
  void initState() {
    super.initState();
    _settingsRequest.addListener(_onRequest);
  }

  @override
  void dispose() {
    _settingsRequest.removeListener(_onRequest);
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => widget.child;
}

// ---------------------------------------------------------------- 服务器
class ServersTab extends StatelessWidget {
  const ServersTab({super.key});

  @override
  Widget build(BuildContext context) {
    final store = VideoStore.instance;
    return ListenableBuilder(
      listenable: store,
      builder: (context, _) => Scaffold(
        floatingActionButton: FloatingActionButton.extended(
          onPressed: () => showServerEditor(context),
          icon: const Icon(Icons.add),
          label: const Text('添加服务器'),
        ),
        body: store.servers.isEmpty
            ? const EmptyView('还没有服务器\n点右下角添加 Emby / Jellyfin 服务器\n（飞牛影视接口确认后再加）', icon: Icons.dns_outlined)
            : ReorderableListView.builder(
                padding: const EdgeInsets.fromLTRB(12, 12, 12, 96),
                itemCount: store.servers.length,
                onReorder: (a, b) => store.move(a, b > a ? b - 1 : b),
                itemBuilder: (_, i) {
                  final s = store.servers[i];
                  return Card(
                    key: ValueKey(s.id),
                    margin: const EdgeInsets.only(bottom: 10),
                    child: ListTile(
                      leading: CircleAvatar(
                        backgroundColor: s.kind == ServerKind.jellyfin ? const Color(0xFF2E2650) : const Color(0xFF4A2238),
                        child: Text(kindLabel(s.kind)[0], style: const TextStyle(color: NagiColors.text)),
                      ),
                      title: Text(s.name),
                      subtitle: Text('${kindLabel(s.kind)} · ${s.username} · ${s.url}', maxLines: 1, overflow: TextOverflow.ellipsis),
                      onTap: () => Navigator.of(context).push(MaterialPageRoute(builder: (_) => ServerHomePage(s))),
                      trailing: PopupMenuButton<String>(
                        onSelected: (v) async {
                          if (v == 'edit') {
                            showServerEditor(context, existing: s);
                          } else if (v == 'delete') {
                            final ok = await showDialog<bool>(
                              context: context,
                              builder: (c) => AlertDialog(
                                title: const Text('删除服务器'),
                                content: Text('删除「${s.name}」？（只删手机上的登录信息，服务器上的东西不动）'),
                                actions: [
                                  TextButton(onPressed: () => Navigator.pop(c, false), child: const Text('取消')),
                                  TextButton(onPressed: () => Navigator.pop(c, true), child: const Text('删除')),
                                ],
                              ),
                            );
                            if (ok == true) {
                              EmbyApi(s).logout();
                              store.remove(s);
                            }
                          }
                        },
                        itemBuilder: (_) => const [
                          PopupMenuItem(value: 'edit', child: Text('修改 / 重新登录')),
                          PopupMenuItem(value: 'delete', child: Text('删除')),
                        ],
                      ),
                    ),
                  );
                },
              ),
      ),
    );
  }
}

Future<void> showServerEditor(BuildContext context, {MediaServer? existing}) {
  return showModalBottomSheet(
    context: context,
    isScrollControlled: true,
    backgroundColor: NagiColors.panel,
    builder: (_) => Padding(
      padding: EdgeInsets.only(bottom: MediaQuery.of(context).viewInsets.bottom),
      child: _ServerEditor(existing: existing),
    ),
  );
}

class _ServerEditor extends StatefulWidget {
  final MediaServer? existing;
  const _ServerEditor({this.existing});

  @override
  State<_ServerEditor> createState() => _ServerEditorState();
}

/// 添加 / 修改服务器（参考 Hills）：① 类型、地址、端口 →「测试连接」看到服务器名和版本 ② 用户名、密码 → 登录
class _ServerEditorState extends State<_ServerEditor> {
  final address = TextEditingController();
  final port = TextEditingController();
  final user = TextEditingController();
  final pass = TextEditingController();
  final name = TextEditingController();
  ServerKind? kind; // null = 自动识别
  bool https = false;
  bool busy = false;
  bool showPass = false;
  String? error;
  ServerProbe? probed;

  @override
  void initState() {
    super.initState();
    final e = widget.existing;
    if (e != null) {
      kind = e.kind;
      final uri = Uri.tryParse(e.url);
      if (uri != null && uri.host.isNotEmpty) {
        https = uri.scheme == 'https';
        address.text = uri.host + (uri.path.isEmpty || uri.path == '/' ? '' : uri.path);
        if (uri.hasPort) port.text = '${uri.port}';
      } else {
        address.text = e.url;
      }
      user.text = e.username;
      name.text = e.name;
    }
    address.addListener(_syncScheme);
  }

  /// 地址里直接写了 http:// 或 https:// 就跟着切；写了 :端口 就挪到端口框
  void _syncScheme() {
    var t = address.text.trim();
    final lower = t.toLowerCase();
    if (lower.startsWith('https://') || lower.startsWith('http://')) {
      final h = lower.startsWith('https://');
      t = t.substring(h ? 8 : 7);
      final m = RegExp(r'^([^/:]+):(\d+)(.*)$').firstMatch(t);
      if (m != null) {
        port.text = m.group(2)!;
        t = '${m.group(1)}${m.group(3)}';
      }
      address.value = TextEditingValue(text: t, selection: TextSelection.collapsed(offset: t.length));
      setState(() {
        https = h;
        probed = null;
      });
    } else if (probed != null) {
      setState(() => probed = null);
    }
  }

  @override
  void dispose() {
    for (final c in [address, port, user, pass, name]) {
      c.dispose();
    }
    super.dispose();
  }

  String _base() {
    var a = address.text.trim();
    // 地址里写了 host:port（没写协议）也认
    final m = RegExp(r'^([^/:]+):(\d+)(.*)$').firstMatch(a);
    var p = port.text.trim();
    if (m != null) {
      if (p.isEmpty) p = m.group(2)!;
      a = '${m.group(1)}${m.group(3)}';
    }
    return EmbyApi.buildBase('${https ? 'https' : 'http'}://$a', p);
  }

  Future<ServerProbe?> _probe() async {
    if (kind == ServerKind.fnos) {
      setState(() => error = '飞牛影视的接口还没接入（确认接口后再加）');
      return null;
    }
    setState(() {
      busy = true;
      error = null;
      probed = null;
    });
    try {
      final r = await EmbyApi.probe(_base(), kind: kind, deviceId: widget.existing?.deviceId);
      if (mounted) {
        setState(() => probed = r);
        if (name.text.trim().isEmpty) name.text = r.name;
      }
      return r;
    } catch (e) {
      if (mounted) setState(() => error = EmbyApi.describeError(e));
      return null;
    } finally {
      if (mounted) setState(() => busy = false);
    }
  }

  Future<void> _login() async {
    if (user.text.trim().isEmpty) {
      setState(() => error = '用户名必填');
      return;
    }
    final p = probed ?? await _probe();
    if (p == null) return;
    setState(() {
      busy = true;
      error = null;
    });
    try {
      final s = await EmbyApi.login(
        probe: p,
        username: user.text,
        password: pass.text,
        localId: widget.existing?.id,
        displayName: name.text,
      );
      await VideoStore.instance.upsert(s);
      if (mounted) Navigator.pop(context);
    } catch (e) {
      if (mounted) setState(() => error = EmbyApi.describeError(e));
    }
    if (mounted) setState(() => busy = false);
  }

  @override
  Widget build(BuildContext context) {
    final p = probed;
    return SafeArea(
      child: SingleChildScrollView(
        padding: const EdgeInsets.fromLTRB(20, 16, 20, 16),
        child: Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.stretch, children: [
          Text(widget.existing == null ? '添加服务器' : '修改服务器', style: const TextStyle(fontSize: 18, fontWeight: FontWeight.w600)),
          const SizedBox(height: 12),
          SegmentedButton<ServerKind?>(
            segments: const [
              ButtonSegment(value: null, label: Text('自动')),
              ButtonSegment(value: ServerKind.emby, label: Text('Emby')),
              ButtonSegment(value: ServerKind.jellyfin, label: Text('Jellyfin')),
              ButtonSegment(value: ServerKind.fnos, label: Text('飞牛')),
            ],
            selected: {kind},
            onSelectionChanged: (v) => setState(() {
              kind = v.first;
              probed = null;
            }),
          ),
          const SizedBox(height: 12),
          Row(children: [
            SegmentedButton<bool>(
              showSelectedIcon: false,
              segments: const [ButtonSegment(value: false, label: Text('http')), ButtonSegment(value: true, label: Text('https'))],
              selected: {https},
              onSelectionChanged: (v) => setState(() {
                https = v.first;
                probed = null;
              }),
            ),
          ]),
          const SizedBox(height: 10),
          Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Expanded(
              flex: 3,
              child: TextField(
                controller: address,
                keyboardType: TextInputType.url,
                autocorrect: false,
                decoration: const InputDecoration(labelText: '地址', hintText: '192.168.1.10 或 emby.example.com', border: OutlineInputBorder()),
              ),
            ),
            const SizedBox(width: 8),
            Expanded(
              flex: 1,
              child: TextField(
                controller: port,
                keyboardType: TextInputType.number,
                onChanged: (_) => setState(() => probed = null),
                decoration: const InputDecoration(labelText: '端口', hintText: '8096', border: OutlineInputBorder()),
              ),
            ),
          ]),
          const Padding(
            padding: EdgeInsets.only(top: 4, left: 4),
            child: Text('端口留空 = http 80 / https 443；Emby / Jellyfin 默认是 8096（https 8920）',
                style: TextStyle(color: NagiColors.muted, fontSize: 11.5)),
          ),
          const SizedBox(height: 8),
          OutlinedButton.icon(
            onPressed: busy ? null : _probe,
            icon: const Icon(Icons.wifi_tethering, size: 18),
            label: const Text('测试连接'),
          ),
          if (p != null)
            Card(
              margin: const EdgeInsets.only(top: 8),
              child: ListTile(
                leading: const Icon(Icons.check_circle, color: NagiColors.violet),
                title: Text(p.name),
                subtitle: Text('${p.product} ${p.version}\n${p.base}'),
                isThreeLine: true,
              ),
            ),
          const SizedBox(height: 12),
          TextField(controller: user, autocorrect: false, decoration: const InputDecoration(labelText: '用户名（必填）', border: OutlineInputBorder())),
          const SizedBox(height: 10),
          TextField(
            controller: pass,
            obscureText: !showPass,
            onSubmitted: (_) => _login(),
            decoration: InputDecoration(
              labelText: '密码（没有就留空）',
              border: const OutlineInputBorder(),
              suffixIcon: IconButton(
                icon: Icon(showPass ? Icons.visibility_off : Icons.visibility),
                onPressed: () => setState(() => showPass = !showPass),
              ),
            ),
          ),
          const SizedBox(height: 10),
          TextField(controller: name, decoration: const InputDecoration(labelText: '显示名称（可选，默认用服务器名）', border: OutlineInputBorder())),
          if (error != null) ...[
            const SizedBox(height: 10),
            SelectableText(error!, style: const TextStyle(color: Color(0xFFF87171))),
          ],
          const SizedBox(height: 14),
          FilledButton(
            onPressed: busy ? null : _login,
            child: busy
                ? const SizedBox(width: 18, height: 18, child: CircularProgressIndicator(strokeWidth: 2))
                : const Text('登录'),
          ),
          const SizedBox(height: 6),
          const Text('密码只用来换登录令牌，不保存在手机上。', style: TextStyle(color: NagiColors.muted, fontSize: 12)),
        ]),
      ),
    );
  }
}

// ---------------------------------------------------------------- 聚合视界
/// 所有服务器的继续观看、下一集、最近添加合在一起，按时间排；顶部可以跨服务器搜索
class AggregateTab extends StatefulWidget {
  const AggregateTab({super.key});

  @override
  State<AggregateTab> createState() => _AggregateTabState();
}

class _AggregateTabState extends State<AggregateTab> with AutomaticKeepAliveClientMixin {
  bool loading = true;
  List<MediaItem> resume = [], nextUp = [], latest = [];
  final errors = <String>[];

  @override
  bool get wantKeepAlive => true;

  @override
  void initState() {
    super.initState();
    _load();
    VideoStore.instance.addListener(_onStore);
  }

  int _serverCount = VideoStore.instance.servers.length;
  void _onStore() {
    if (VideoStore.instance.servers.length != _serverCount) {
      _serverCount = VideoStore.instance.servers.length;
      _load();
    }
  }

  @override
  void dispose() {
    VideoStore.instance.removeListener(_onStore);
    super.dispose();
  }

  static DateTime _date(MediaItem it, String key) =>
      DateTime.tryParse('${it.userData[key] ?? it.raw[key] ?? ''}') ?? DateTime.fromMillisecondsSinceEpoch(0);

  Future<void> _load() async {
    setState(() {
      loading = true;
      errors.clear();
    });
    final servers = List.of(VideoStore.instance.servers);
    final r = <MediaItem>[], n = <MediaItem>[], l = <MediaItem>[];
    await Future.wait(servers.map((s) async {
      final api = EmbyApi(s);
      try {
        final res = await Future.wait([
          api.resume(),
          api.nextUp().catchError((_) => <MediaItem>[]),
          api.latest(limit: 16).catchError((_) => <MediaItem>[]),
        ]);
        r.addAll(res[0]);
        n.addAll(res[1]);
        l.addAll(res[2]);
      } catch (e) {
        errors.add('${s.name}：${EmbyApi.describeError(e)}');
      }
    }));
    r.sort((a, b) => _date(b, 'LastPlayedDate').compareTo(_date(a, 'LastPlayedDate')));
    l.sort((a, b) => _date(b, 'DateCreated').compareTo(_date(a, 'DateCreated')));
    resume = r;
    nextUp = n;
    latest = l;
    if (mounted) setState(() => loading = false);
  }

  @override
  Widget build(BuildContext context) {
    super.build(context);
    final servers = VideoStore.instance.servers;
    if (servers.isEmpty) return const EmptyView('先在「服务器」里添加服务器\n这里会把所有服务器的内容合在一起看', icon: Icons.hub_outlined);
    final multi = servers.length > 1;
    return RefreshIndicator(
      onRefresh: _load,
      child: ListView(children: [
        Padding(
          padding: const EdgeInsets.fromLTRB(16, 12, 16, 0),
          child: InkWell(
            borderRadius: BorderRadius.circular(24),
            onTap: () => Navigator.of(context).push(MaterialPageRoute(builder: (_) => SearchPage(servers: List.of(servers)))),
            child: Container(
              height: 44,
              padding: const EdgeInsets.symmetric(horizontal: 14),
              decoration: BoxDecoration(color: NagiColors.card, borderRadius: BorderRadius.circular(24)),
              child: Row(children: [
                const Icon(Icons.search, color: NagiColors.sub),
                const SizedBox(width: 8),
                Text(multi ? '在 ${servers.length} 个服务器里搜索' : '搜索', style: const TextStyle(color: NagiColors.sub)),
              ]),
            ),
          ),
        ),
        for (final e in errors) Padding(padding: const EdgeInsets.fromLTRB(16, 10, 16, 0), child: Text(e, style: const TextStyle(color: NagiColors.sub))),
        if (loading) const Padding(padding: EdgeInsets.all(40), child: Center(child: CircularProgressIndicator())),
        if (!loading) ...[
          Shelf(
            title: '继续观看',
            height: 170,
            children: [for (final it in resume) ThumbCard(it, showServer: multi, onTap: () => openItem(context, it))],
          ),
          Shelf(
            title: '下一集',
            height: 170,
            children: [for (final it in nextUp) ThumbCard(it, showServer: multi, onTap: () => openItem(context, it))],
          ),
          Shelf(
            title: '最近添加',
            height: 215,
            children: [for (final it in latest) PosterCard(it, showServer: multi, onTap: () => openItem(context, it))],
          ),
          if (resume.isEmpty && nextUp.isEmpty && latest.isEmpty && errors.isEmpty)
            const Padding(padding: EdgeInsets.only(top: 60), child: EmptyView('服务器上还没有内容')),
        ],
        const SizedBox(height: 24),
      ]),
    );
  }
}

// ---------------------------------------------------------------- 设置
class SettingsTab extends StatelessWidget {
  const SettingsTab({super.key});

  @override
  Widget build(BuildContext context) {
    final store = VideoStore.instance;
    return ListenableBuilder(
      listenable: store,
      builder: (context, _) {
        final s = store.settings;
        return ListView(children: [
          const _Header('播放'),
          SwitchListTile(
            title: const Text('硬件解码'),
            subtitle: const Text('关掉能解决个别片子花屏 / 黑屏，但更费电'),
            value: s.hardwareDecode,
            onChanged: (v) {
              s.hardwareDecode = v;
              store.save();
            },
          ),
          SwitchListTile(
            title: const Text('自动播放下一集'),
            value: s.autoNext,
            onChanged: (v) {
              s.autoNext = v;
              store.save();
            },
          ),
          SwitchListTile(
            title: const Text('播放时横屏'),
            value: s.landscape,
            onChanged: (v) {
              s.landscape = v;
              store.save();
            },
          ),
          ListTile(title: const Text('默认倍速'), trailing: Text('${s.speed}x'), subtitle: const Text('在播放器里改倍速会记住')),
          const _Header('关于'),
          const ListTile(
            title: Text('支持的服务器'),
            subtitle: Text('Emby、Jellyfin（直接播放原文件，不转码；进度会同步回服务器）。\n飞牛影视：接口确认后再加。'),
          ),
        ]);
      },
    );
  }
}

class _Header extends StatelessWidget {
  final String text;
  const _Header(this.text);

  @override
  Widget build(BuildContext context) => Padding(
        padding: const EdgeInsets.fromLTRB(16, 18, 16, 4),
        child: Text(text, style: const TextStyle(color: NagiColors.pink, fontSize: 13, fontWeight: FontWeight.w600)),
      );
}
