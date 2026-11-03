// Nagisaland：原生分区栏和 Dart 之间的小通道（nagisaland/suite）。
// 目前只有一个方法：openSettings —— 在分区栏上左滑时，原生端（SuiteNav.openSettings）让当前分区打开自己的设置页。
// 漫画（VeneraX，lib/main.dart）、番剧（kazumi main.dart）、影视（nagi_video/video_app.dart）各自在入口里装一次。
import 'package:flutter/services.dart';

const MethodChannel _nagiSuiteChannel = MethodChannel('nagisaland/suite');

/// [openSettings] 在 UI 线程上调用；抛异常会把错误回给原生端（弹「打不开设置」提示），不会让 App 崩
void installNagiSuiteChannel({required void Function() openSettings}) {
  _nagiSuiteChannel.setMethodCallHandler((call) async {
    switch (call.method) {
      case 'openSettings':
        openSettings();
        return true;
      default:
        throw MissingPluginException();
    }
  });
}
