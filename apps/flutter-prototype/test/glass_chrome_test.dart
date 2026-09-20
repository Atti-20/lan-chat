import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:meshx_flutter_probe/ui/glass_chrome.dart';
import 'package:meshx_flutter_probe/ui/theme.dart';

void main() {
  testWidgets(
    'fallback tabs keep real destinations and remain operable at large text',
    (tester) async {
      tester.view.physicalSize = const Size(320, 640);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);
      var selected = 0;
      await tester.pumpWidget(
        MaterialApp(
          theme: meshXTheme(Brightness.light),
          home: MediaQuery(
            data: const MediaQueryData(
              size: Size(320, 640),
              textScaler: TextScaler.linear(2),
            ),
            child: StatefulBuilder(
              builder: (context, setState) => Scaffold(
                extendBody: true,
                body: const Center(child: Text('content')),
                bottomNavigationBar: MeshXNavigationBar(
                  selectedIndex: selected,
                  onDestinationSelected: (value) =>
                      setState(() => selected = value),
                  counts: const [12, 1, 0, null],
                  destinations: const [
                    NavigationDestination(icon: Icon(Icons.chat), label: '消息'),
                    NavigationDestination(
                      icon: Icon(Icons.people),
                      label: '联系人',
                    ),
                    NavigationDestination(
                      icon: Icon(Icons.groups),
                      label: '群聊',
                    ),
                    NavigationDestination(
                      icon: Icon(Icons.campaign),
                      label: '广播',
                    ),
                  ],
                ),
              ),
            ),
          ),
        ),
      );
      await tester.pumpAndSettle();
      await tester.tap(find.text('联系人'));
      await tester.pumpAndSettle();
      expect(selected, 1);
      expect(tester.takeException(), isNull);
      expect(find.byType(NavigationBar), findsOneWidget);
    },
  );

  testWidgets('latest message stays above measured floating composer', (
    tester,
  ) async {
    tester.view.physicalSize = const Size(320, 480);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    final composer = GlobalKey();
    await tester.pumpWidget(
      MaterialApp(
        theme: meshXTheme(Brightness.light),
        home: Scaffold(
          body: MeshXMessageLayout(
            composerKey: composer,
            threadBuilder: (_, inset) => ListView.builder(
              reverse: true,
              padding: EdgeInsets.only(bottom: inset + 12),
              itemCount: 30,
              itemBuilder: (_, i) =>
                  SizedBox(height: 48, child: Text('message-$i')),
            ),
            composer: const SizedBox(height: 120, child: Text('composer')),
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();
    final textRect = tester.getRect(find.text('message-0'));
    final chromeRect = tester.getRect(find.byKey(composer));
    expect(textRect.bottom, lessThanOrEqualTo(chromeRect.top));
    expect(tester.takeException(), isNull);
  });

  testWidgets('disabled glass button cannot submit', (tester) async {
    await tester.pumpWidget(
      MaterialApp(
        theme: meshXTheme(Brightness.dark),
        home: const Scaffold(
          body: MeshXGlassButton(
            tooltip: '发送消息',
            nativeSymbol: 'arrow.up',
            prominent: true,
            onPressed: null,
            icon: Icon(Icons.arrow_upward),
          ),
        ),
      ),
    );
    final button = tester.widget<IconButton>(find.byType(IconButton));
    expect(button.onPressed, isNull);
    expect(tester.getSize(find.byType(MeshXGlassButton)), const Size(48, 48));
  });
}
