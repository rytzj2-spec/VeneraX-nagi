// Nagisaland 统一主题：漫画（VeneraX）和番剧（Kazumi）共用，颜色照 Nagisaland 网页 / 原生底栏。
// 放在 kazumi 包里是因为主工程已经依赖它（import 'package:kazumi/nagi_theme.dart'）。
// 不想要统一主题时把 kNagiUnifiedTheme 改成 false，两边都回到各自原来的主题设置。
import 'package:flutter/material.dart';

const bool kNagiUnifiedTheme = true;

class NagiColors {
  static const bg = Color(0xFF0F0F12);
  static const panel = Color(0xFF16161B);
  static const card = Color(0xFF1C1C22);
  static const cardHigh = Color(0xFF23232B);
  static const cardHighest = Color(0xFF2A2A33);
  static const line = Color(0xFF2A2A36);
  static const outline = Color(0xFF3F3F4A);
  static const text = Color(0xFFFAFAF9);
  static const sub = Color(0xFFA1A1AA);
  static const muted = Color(0xFF6F6F82);
  static const pink = Color(0xFFE879A9);
  static const violet = Color(0xFFA78BFA);
  static const on = Color(0xFFF0ABFC);

  /// 选中状态的渐变（网页里的 .on 按钮、进度条等）
  static const selectedGradient = LinearGradient(colors: [pink, violet]);
}

class NagiTheme {
  static const ColorScheme scheme = ColorScheme(
    brightness: Brightness.dark,
    primary: NagiColors.pink,
    onPrimary: Color(0xFF18181B),
    primaryContainer: Color(0xFF4A2238),
    onPrimaryContainer: Color(0xFFFBCFE8),
    secondary: NagiColors.violet,
    onSecondary: Color(0xFF18181B),
    secondaryContainer: Color(0xFF2E2650),
    onSecondaryContainer: Color(0xFFDDD6FE),
    tertiary: NagiColors.on,
    onTertiary: Color(0xFF18181B),
    tertiaryContainer: Color(0xFF45204D),
    onTertiaryContainer: Color(0xFFF5D0FE),
    error: Color(0xFFF87171),
    onError: Color(0xFF18181B),
    errorContainer: Color(0xFF4C1D1D),
    onErrorContainer: Color(0xFFFECACA),
    surface: NagiColors.bg,
    onSurface: NagiColors.text,
    onSurfaceVariant: NagiColors.sub,
    surfaceDim: NagiColors.bg,
    surfaceBright: NagiColors.cardHighest,
    surfaceContainerLowest: Color(0xFF0A0A0D),
    surfaceContainerLow: NagiColors.panel,
    surfaceContainer: NagiColors.card,
    surfaceContainerHigh: NagiColors.cardHigh,
    surfaceContainerHighest: NagiColors.cardHighest,
    outline: NagiColors.outline,
    outlineVariant: NagiColors.line,
    shadow: Colors.black,
    scrim: Colors.black,
    inverseSurface: NagiColors.text,
    onInverseSurface: Color(0xFF18181B),
    inversePrimary: Color(0xFFBE185D),
    // M3 默认会给卡片 / 弹窗叠一层主色，统一主题里不要
    surfaceTint: Colors.transparent,
  );

  /// 在各自原来的 ThemeData 上换成 Nagisaland 配色（保留字体、页面切换动画等原有设置）
  static ThemeData apply(ThemeData base) {
    return base.copyWith(
      brightness: Brightness.dark,
      colorScheme: scheme,
      scaffoldBackgroundColor: NagiColors.bg,
      canvasColor: NagiColors.bg,
      cardColor: NagiColors.card,
      dividerColor: NagiColors.line,
      dialogTheme: const DialogThemeData(backgroundColor: NagiColors.panel),
      bottomSheetTheme: const BottomSheetThemeData(
        backgroundColor: NagiColors.panel,
        modalBackgroundColor: NagiColors.panel,
      ),
      appBarTheme: const AppBarTheme(
        backgroundColor: NagiColors.bg,
        foregroundColor: NagiColors.text,
        surfaceTintColor: Colors.transparent,
      ),
      navigationBarTheme: const NavigationBarThemeData(
        backgroundColor: NagiColors.bg,
        indicatorColor: Color(0xFF3A2245),
        surfaceTintColor: Colors.transparent,
      ),
      navigationRailTheme: const NavigationRailThemeData(
        backgroundColor: NagiColors.bg,
        indicatorColor: Color(0xFF3A2245),
      ),
      textTheme: base.textTheme.apply(bodyColor: NagiColors.text, displayColor: NagiColors.text),
    );
  }
}
