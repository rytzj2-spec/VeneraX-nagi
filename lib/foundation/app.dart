import 'dart:ui';

import 'package:flutter/foundation.dart'
    show kIsWeb, TargetPlatform, defaultTargetPlatform;
import 'package:flutter/material.dart';
import 'package:path_provider/path_provider.dart';
import 'package:venera/foundation/download_network_guard.dart';
import 'package:venera/foundation/history.dart';
import 'package:venera/foundation/log.dart';
import 'package:venera/foundation/image_translation/translation_store.dart';
import 'package:venera/foundation/read_later.dart';

import 'appdata.dart';
import 'domain_database.dart';
import 'favorites.dart';
import 'local.dart';

export "widget_utils.dart";
export "context.dart";

/// Nagisaland 统一应用：安卓系统里的版本号是 Nagisaland 的（android/nagisaland.properties），
/// 漫画部分（漫画源的 minAppVersion 检查等）要用 VeneraX 自己的版本号。跟上游同步时和 pubspec.yaml 的 version 一起改。
const veneraXVersion = "2.3.6";

class _App {
  String _version = veneraXVersion;

  String get version => _version;

  bool get isWeb => kIsWeb;

  bool get isAndroid =>
      !kIsWeb && defaultTargetPlatform == TargetPlatform.android;

  bool get isIOS => !kIsWeb && defaultTargetPlatform == TargetPlatform.iOS;

  bool get isWindows =>
      !kIsWeb && defaultTargetPlatform == TargetPlatform.windows;

  bool get isLinux => !kIsWeb && defaultTargetPlatform == TargetPlatform.linux;

  bool get isMacOS => !kIsWeb && defaultTargetPlatform == TargetPlatform.macOS;

  bool get isDesktop => !kIsWeb && (isWindows || isLinux || isMacOS);

  bool get isMobile => !kIsWeb && (isAndroid || isIOS);

  // Whether the app has been initialized.
  // If current Isolate is main Isolate, this value is always true.
  bool isInitialized = false;

  Locale get locale {
    Locale deviceLocale = PlatformDispatcher.instance.locale;
    if (deviceLocale.languageCode == "zh" &&
        deviceLocale.scriptCode == "Hant") {
      deviceLocale = const Locale("zh", "TW");
    }
    if (appdata.settings['language'] != 'system') {
      return Locale(
        appdata.settings['language'].split('-')[0],
        appdata.settings['language'].split('-')[1],
      );
    }
    return deviceLocale;
  }

  String dataPath = '/venera/data';
  String cachePath = '/venera/cache';
  String? externalStoragePath;

  final rootNavigatorKey = GlobalKey<NavigatorState>();

  final rootRouteObserver = RouteObserver<PageRoute<dynamic>>();

  GlobalKey<NavigatorState>? mainNavigatorKey;

  BuildContext get rootContext => rootNavigatorKey.currentContext!;

  final Appdata data = appdata;

  final HistoryManager history = HistoryManager();

  final ReadLaterManager readLater = ReadLaterManager();

  final LocalFavoritesManager favorites = LocalFavoritesManager();

  final LocalManager local = LocalManager();

  final DomainDatabase domain = DomainDatabase();

  void rootPop() {
    rootNavigatorKey.currentState?.maybePop();
  }

  void pop() {
    if (rootNavigatorKey.currentState?.canPop() ?? false) {
      rootNavigatorKey.currentState?.pop();
    } else if (mainNavigatorKey?.currentState?.canPop() ?? false) {
      mainNavigatorKey?.currentState?.pop();
    }
  }

  Future<void> init() async {
    cachePath = (await getApplicationCacheDirectory()).path;
    dataPath = (await getApplicationSupportDirectory()).path;
    if (isAndroid) {
      externalStoragePath = (await getExternalStorageDirectory())!.path;
    }
    isInitialized = true;
  }

  Future<void> initComponents() async {
    final futures = <Future<void>>[
      data.init(),
      history.init(),
      readLater.init(),
      favorites.init(),
      domain.init(dataPath),
      local.init(),
      TranslationStore().init(),
    ];
    // One store failing must not abort its siblings: an unguarded Future.wait
    // rejected on the first error and left every later store uninitialized —
    // the "init failed but the app carried on" startup chain.
    await Future.wait(
      futures.map((future) async {
        try {
          await future;
        } catch (e, s) {
          Log.error("init", "$e\n$s");
        }
      }),
    );
    // Begin watching connectivity so "WiFi only" downloads pause on metered
    // networks. No-op while the setting is off (#15).
    DownloadNetworkGuard.instance.start();
  }

  Function? _forceRebuildHandler;

  void registerForceRebuild(Function handler) {
    _forceRebuildHandler = handler;
  }

  void forceRebuild() {
    _forceRebuildHandler?.call();
  }
}

// ignore: non_constant_identifier_names
final App = _App();
