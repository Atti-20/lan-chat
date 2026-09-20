import 'dart:io';
import 'dart:ui' as ui;
import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:meshx_flutter_probe/chat_controller.dart';
import 'package:meshx_flutter_probe/ui/app.dart';
import 'package:meshx_flutter_probe/ui/theme.dart';
import 'mobile_completion_test.dart' show CompletionApi;
import 'recovery_test.dart' show CapturingConnection;

void main() {
  for (final brightness in Brightness.values) {
    testWidgets(
      'iOS route supports cancelled and completed edge back in $brightness',
      (tester) async {
        final evidenceFont = Platform.environment['MESHX_EVIDENCE_FONT'];
        if (evidenceFont != null) {
          await tester.runAsync(() async {
            final loader = FontLoader('MeshXEvidence')
              ..addFont(
                File(evidenceFont).readAsBytes().then(ByteData.sublistView),
              );
            await loader.load();
            final cupertino =
                FontLoader('packages/cupertino_icons/CupertinoIcons')..addFont(
                  rootBundle.load(
                    'packages/cupertino_icons/assets/CupertinoIcons.ttf',
                  ),
                );
            await cupertino.load();
            final icons = Platform.environment['MESHX_EVIDENCE_ICONS'];
            if (icons != null) {
              await (FontLoader('MaterialIcons')..addFont(
                    File(icons).readAsBytes().then(ByteData.sublistView),
                  ))
                  .load();
            }
          });
        }
        tester.view.physicalSize = const Size(390, 844);
        tester.view.devicePixelRatio = 1;
        addTearDown(tester.view.reset);
        final api = CompletionApi(),
            wire = CapturingConnection(CompletionApi());
        final c = ChatController(connectionFactory: (_) => wire)..api = api;
        addTearDown(c.dispose);
        await c.reconnect();
        final boundary = GlobalKey();
        await tester.pumpWidget(
          RepaintBoundary(
            key: boundary,
            child: MaterialApp(
              theme: meshXTheme(brightness).copyWith(
                platform: TargetPlatform.iOS,
                textTheme: evidenceFont == null
                    ? null
                    : meshXTheme(
                        brightness,
                      ).textTheme.apply(fontFamily: 'MeshXEvidence'),
              ),
              navigatorObservers: [meshXRouteObserver],
              home: AnimatedBuilder(
                animation: c,
                builder: (_, _) =>
                    ChatPage(controller: c, onToggleTheme: () {}),
              ),
            ),
          ),
        );
        await tester.pumpAndSettle();
        await tester.tap(find.text('测试'));
        await tester.pumpAndSettle();
        expect(find.byType(MessagePane), findsOneWidget);
        await tester.enterText(find.byKey(const Key('composer')), '保留这份草稿');
        await tester.pumpAndSettle();
        final gesture = await tester.startGesture(const Offset(4, 300));
        await gesture.moveBy(const Offset(70, 0));
        await tester.pump(const Duration(milliseconds: 100));
        await gesture.moveBy(const Offset(-60, 0));
        await tester.pump(const Duration(milliseconds: 300));
        await gesture.up();
        await tester.pumpAndSettle();
        expect(c.active, isNotNull);
        expect(find.text('保留这份草稿'), findsOneWidget);
        final directory = Platform.environment['MESHX_UI_EVIDENCE'];
        if (directory != null) {
          final render =
              boundary.currentContext!.findRenderObject()!
                  as RenderRepaintBoundary;
          await tester.runAsync(() async {
            final image = await render.toImage(pixelRatio: 1);
            final bytes = await image.toByteData(
              format: ui.ImageByteFormat.png,
            );
            await Directory(directory).create(recursive: true);
            await File(
              '$directory/chat-${brightness.name}.png',
            ).writeAsBytes(bytes!.buffer.asUint8List());
            image.dispose();
          });
        }
        await tester.flingFrom(
          const Offset(4, 300),
          const Offset(330, 0),
          1000,
        );
        await tester.pumpAndSettle();
        expect(c.active, isNull);
        expect(c.drafts.values, contains('保留这份草稿'));
        expect(tester.takeException(), isNull);
        await tester.pumpWidget(const SizedBox.shrink());
        await c.pause();
      },
    );
  }
}
