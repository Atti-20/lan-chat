import 'dart:async';
import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:meshx_flutter_probe/platform/glass_bridge.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  const channel = MethodChannel('meshx.test/glass');
  late MeshXGlassRuntime runtime;
  setUp(() {
    debugDefaultTargetPlatformOverride = TargetPlatform.iOS;
    runtime = MeshXGlassRuntime(channel: channel);
  });
  tearDown(() {
    runtime.dispose();
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, null);
    debugDefaultTargetPlatformOverride = null;
  });

  test('capabilities are negotiated once, not inferred from platform name', () async {
    var calls = 0;
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
      calls++;
      return {'version': 1, 'available': true, 'liquidGlass': false,
        'photoPicker': true, 'reduceTransparency': true};
    });
    await Future.wait([runtime.initialize(), runtime.initialize()]);
    expect(calls, 1);
    expect(runtime.available, isTrue);
    expect(runtime.liquidGlass, isFalse);
    expect(runtime.reduceTransparency, isTrue);
  });

  test('unknown bridge version never enables native views', () async {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (_) async => {'version': 99, 'available': true});
    await runtime.initialize();
    expect(runtime.available, isFalse);
    expect(runtime.unavailableReason, 'incompatibleNativeBridge');
  });

  test('Android does not call iOS platform channel', () async {
    debugDefaultTargetPlatformOverride = TargetPlatform.android;
    var calls = 0;
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (_) async { calls++; return null; });
    await runtime.initialize();
    expect(calls, 0);
    expect(runtime.available, isFalse);
  });

  test('native presentation hides reads until every nested operation finishes', () async {
    final first = Completer<void>(), second = Completer<void>();
    final a = runtime.guardPresentation(() => first.future);
    final b = runtime.guardPresentation(() => second.future);
    expect(runtime.obscuresConversation, isTrue);
    first.complete(); await a;
    expect(runtime.obscuresConversation, isTrue);
    second.complete(); await b;
    expect(runtime.obscuresConversation, isFalse);
  });

  test('presentation error restores visibility instead of permanently blocking reads', () async {
    await expectLater(runtime.guardPresentation<void>(() async => throw StateError('failed')),
        throwsA(isA<StateError>()));
    expect(runtime.obscuresConversation, isFalse);
  });

  test('native menu forwards allowed source and clears its guard', () async {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
      expect(call.method, 'attachmentMenu');
      expect((call.arguments as Map)['photos'], isTrue);
      expect(runtime.obscuresConversation, isTrue);
      return 'photos';
    });
    expect(await runtime.attachmentMenu(photos: true, dark: true), 'photos');
    expect(runtime.obscuresConversation, isFalse);
  });

  test('busy native sheet is not treated as a successful file selection', () async {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (_) async => throw PlatformException(code: 'PRESENTATION_BUSY'));
    await expectLater(runtime.attachmentMenu(photos: true, dark: false), throwsA(isA<PlatformException>()));
    expect(runtime.obscuresConversation, isFalse);
  });
}
