import 'dart:async';
import 'dart:io';
import 'dart:typed_data';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:meshx_flutter_probe/application/broadcasts_controller.dart';
import 'package:meshx_flutter_probe/chat_controller.dart';
import 'package:meshx_flutter_probe/ui/broadcasts_page.dart';
import 'broadcasts_widget_test.dart' show WidgetBroadcastApi, widgetDetail;

const imageUrl = '/api/v1/file/content/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.png';

class ImageWidgetApi extends WidgetBroadcastApi {
  ImageWidgetApi() : super(widgetDetail(images: [imageUrl]));
  Completer<Uint8List> pending = Completer<Uint8List>();
  int reads = 0;
  @override
  Future<Uint8List> broadcastImageBytes(int id, String url) {
    expect(id, 8);
    expect(url, imageUrl);
    reads++;
    return pending.future;
  }
}

void main() {
  testWidgets('decoded image is cleared on account change and cache evicted', (
    tester,
  ) async {
    final api = ImageWidgetApi();
    final chat = ChatController()..api = api;
    final controller = BroadcastsController(
      chat: chat,
      api: api,
      changes: const Stream.empty(),
    )..detail = api.value;
    await tester.pumpWidget(
      MaterialApp(home: BroadcastDetailPage(controller: controller)),
    );
    final entry = find.byKey(const Key('broadcast-content-image-0'));
    await tester.ensureVisible(entry);
    await tester.tap(entry);
    await tester.pump();
    await tester.runAsync(() async {
      api.pending.complete(await File('assets/meshx.png').readAsBytes());
      await Future<void>.delayed(const Duration(milliseconds: 50));
    });
    await tester.pumpAndSettle();
    final provider = tester.widget<Image>(find.byType(Image)).image;
    await tester.runAsync(() async {
      final ready = Completer<void>();
      final stream = provider.resolve(ImageConfiguration.empty);
      final listener = ImageStreamListener((_, _) {
        if (!ready.isCompleted) ready.complete();
      });
      stream.addListener(listener);
      await ready.future;
      stream.removeListener(listener);
    });
    await tester.pumpAndSettle();
    expect(tester.widget<RawImage>(find.byType(RawImage)).image, isNotNull);
    api.session = null;
    chat.notifyListeners();
    await tester.pumpAndSettle();
    expect(find.byType(Image), findsNothing);
    expect(PaintingBinding.instance.imageCache.containsKey(provider), isFalse);
    expect(find.text('广播图片已不可用，请返回广播列表'), findsOneWidget);
    expect(tester.takeException(), isNull);
    await tester.pumpWidget(const SizedBox.shrink());
    controller.dispose();
    chat.dispose();
  });
  for (final close in [false, true]) {
    testWidgets(
      close
          ? 'closed viewer ignores late response'
          : 'viewer retries and revokes access',
      (tester) async {
        final api = ImageWidgetApi();
        final chat = ChatController()..api = api;
        final controller = BroadcastsController(
          chat: chat,
          api: api,
          changes: const Stream.empty(),
        )..detail = api.value;
        await tester.pumpWidget(
          MaterialApp(home: BroadcastDetailPage(controller: controller)),
        );
        final entry = find.byKey(const Key('broadcast-content-image-0'));
        await tester.ensureVisible(entry);
        await tester.tap(entry);
        await tester.pump();
        await tester.pump(const Duration(seconds: 1));
        expect(find.text('广播图片 1'), findsOneWidget);
        expect(find.byType(CircularProgressIndicator), findsOneWidget);
        expect(api.reads, 1);
        if (close) {
          await tester.tap(find.byTooltip('Back'));
          await tester.pumpAndSettle();
          api.pending.complete(Uint8List(1));
          await tester.pumpAndSettle();
          expect(find.text('广播图片 1'), findsNothing);
        } else {
          api.pending.completeError(StateError('offline'));
          await tester.pumpAndSettle();
          expect(find.text('重新加载'), findsOneWidget);
          api.pending = Completer<Uint8List>();
          await tester.tap(find.text('重新加载'));
          await tester.pump();
          expect(api.reads, 2);
          controller.detail = null;
          controller.notifyListeners();
          await tester.pump();
          expect(find.text('广播图片已不可用，请返回广播列表'), findsOneWidget);
          expect(find.text('重新加载'), findsNothing);
          api.pending.complete(Uint8List(1));
          await tester.pumpAndSettle();
          expect(find.byType(Image), findsNothing);
        }
        expect(tester.takeException(), isNull);
        await tester.pumpWidget(const SizedBox.shrink());
        controller.dispose();
        chat.dispose();
      },
    );
  }
}
