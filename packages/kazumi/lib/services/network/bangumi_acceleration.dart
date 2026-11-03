import 'package:kazumi/services/network/acceleration_mode.dart';
import 'package:kazumi/services/storage/storage.dart';
import 'package:kazumi/utils/bangumi_mirror_credentials.dart';

enum BangumiAcceleration implements AccelerationMode {
  direct,
  ech,
  mirror;

  /// 镜像接口要官方 CI 注入的 KAZUMI_APPID / KAZUMI_KEY（--dart-define）。
  static bool get mirrorAvailable =>
      (bangumiMirrorCredentials['id'] ?? '').isNotEmpty &&
      (bangumiMirrorCredentials['value'] ?? '').isNotEmpty;

  static BangumiAcceleration get current {
    final selected = values.asNameMap()[GStorage.getSetting(
          SettingsKeys.bangumiAcceleration,
        )] ??
        (GStorage.getSetting(SettingsKeys.enableBangumiProxy) ? mirror : direct);
    // Nagisaland suite：没有镜像密钥时自动改走 Bangumi 官方接口（否则搜索 / 时间表全部签名失败）
    if (selected == mirror && !mirrorAvailable) return direct;
    return selected;
  }

  @override
  String get label => switch (this) {
    direct => '直连',
    ech => 'ECH',
    mirror => '镜像',
  };

  @override
  String get description => switch (this) {
    direct => '访问官方接口',
    ech => '启用加密握手',
    mirror => mirrorAvailable ? '镜像接口（默认）' : '镜像接口（本版本没有镜像密钥，会改走官方接口）',
  };
}
