import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:kazumi/nagi_section_header.dart';
import 'package:kazumi/nagi_theme.dart';

void main() {
  const channel = MethodChannel('nagisaland/navigation');
  Widget page({
    ValueChanged<int>? onSelected,
    List<NagiSectionAction> actions = const [],
    double scale = 1,
  }) {
    return MaterialApp(
      theme: NagiTheme.apply(ThemeData.dark()),
      home: MediaQuery(
        data: MediaQueryData(textScaler: TextScaler.linear(scale)),
        child: Scaffold(
          body: Column(
            children: [
              NagiSectionHeader(
                title: '漫画',
                destinations: const [
                  NagiSectionDestination('首页', Icons.home_outlined),
                  NagiSectionDestination('收藏', Icons.favorite_border),
                  NagiSectionDestination('探索', Icons.explore_outlined),
                  NagiSectionDestination('分类', Icons.category_outlined),
                ],
                selectedIndex: 0,
                onSelected: onSelected ?? (_) {},
                actions: actions,
              ),
              const Expanded(child: SizedBox()),
            ],
          ),
        ),
      ),
    );
  }

  testWidgets('menu opens native navigation without switching the local tab', (
    tester,
  ) async {
    String? method;
    int? selected;
    tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(channel, (
      call,
    ) async {
      method = call.method;
      return null;
    });
    addTearDown(
      () => tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(
        channel,
        null,
      ),
    );
    await tester.pumpWidget(page(onSelected: (i) => selected = i));
    await tester.tap(find.byTooltip('打开导航'));
    await tester.pump();
    expect(method, 'openDrawer');
    expect(selected, isNull);
    await tester.tap(find.text('收藏'));
    expect(selected, 1);
  });

  testWidgets('secondary actions remain reachable through overflow', (
    tester,
  ) async {
    var invoked = false;
    await tester.pumpWidget(
      page(
        actions: [
          NagiSectionAction('搜索', Icons.search, () {}),
          NagiSectionAction('设置', Icons.settings, () => invoked = true),
        ],
      ),
    );
    await tester.tap(find.byTooltip('漫画功能'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('设置'));
    await tester.pumpAndSettle();
    expect(invoked, isTrue);
  });

  testWidgets('narrow screen and large text do not overflow', (tester) async {
    await tester.binding.setSurfaceSize(const Size(320, 640));
    addTearDown(() => tester.binding.setSurfaceSize(null));
    await tester.pumpWidget(
      page(
        scale: 2,
        actions: [
          NagiSectionAction('搜索', Icons.search, () {}),
          NagiSectionAction('设置', Icons.settings, () {}),
        ],
      ),
    );
    await tester.pumpAndSettle();
    expect(tester.takeException(), isNull);
    await tester.drag(
      find.byType(SingleChildScrollView),
      const Offset(-500, 0),
    );
    await tester.pumpAndSettle();
    expect(find.text('分类').hitTestable(), findsOneWidget);
  });

  testWidgets('native failure is explained without crashing', (tester) async {
    tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(channel, (
      _,
    ) async {
      throw PlatformException(code: 'inactive');
    });
    addTearDown(
      () => tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(
        channel,
        null,
      ),
    );
    await tester.pumpWidget(page());
    await tester.tap(find.byTooltip('打开导航'));
    await tester.pumpAndSettle();
    expect(find.text('导航暂时无法打开，请重试'), findsOneWidget);
    expect(tester.takeException(), isNull);
  });
}
