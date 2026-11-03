import 'package:kazumi/utils/bangumi_mirror_credentials.dart';
import 'package:kazumi/services/network/acceleration_mode.dart';
import 'package:kazumi/services/storage/storage.dart';

enum BangumiAcceleration implements AccelerationMode {
  direct,
  ech,
  mirror;

  static BangumiAcceleration get current {
    final mode = values.asNameMap()[GStorage.getSetting(
          SettingsKeys.bangumiAcceleration,
        )] ??
        (GStorage.getSetting(SettingsKeys.enableBangumiProxy) ? mirror : direct);
    // Nagisaland suite：镜像接口的搜索/评论要官方 CI 注入的密钥（KAZUMI_APPID/KEY），自己编译的没有，改走官方接口
    if (mode == mirror && (bangumiMirrorCredentials['id'] ?? '').isEmpty) {
      return direct;
    }
    return mode;
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
    mirror => '镜像接口（默认）',
  };
}
