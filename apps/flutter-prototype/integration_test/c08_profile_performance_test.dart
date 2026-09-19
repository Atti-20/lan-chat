import 'dart:async';
import 'dart:io';
import 'dart:ui' show FrameTiming;

import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:integration_test/integration_test.dart';
import 'package:meshx_flutter_probe/application/platform_coordinator.dart';
import 'package:meshx_flutter_probe/chat_controller.dart';
import 'package:meshx_flutter_probe/core/store.dart';
import 'package:meshx_flutter_probe/data/models.dart';
import 'package:meshx_flutter_probe/platform/discovery.dart';
import 'package:meshx_flutter_probe/platform/system_capabilities.dart';
import 'package:meshx_flutter_probe/ui/app.dart';

class _MemoryCredentials implements CredentialStore {
  Json? value;

  @override
  Future<void> clear() async => value = null;

  @override
  Future<Json?> read() async => value == null ? null : Json.from(value!);

  @override
  Future<void> write(Json credentials) async {
    value = Json.from(credentials);
  }
}

class _MemoryStore implements ChatStore {
  final values = <String, Json>{};

  @override
  Future<Json?> load(String owner) async => values[owner];

  @override
  Future<void> save(String owner, Json snapshot) async {
    values[owner] = snapshot;
  }
}

class _FrameWindow {
  _FrameWindow(this.name, this.refreshRate);

  final String name;
  final double refreshRate;
  final workloadFrames = <int>[];
  final totalSpanFrames = <int>[];
  final rssSamples = <int>[];
  final stopwatch = Stopwatch();

  void onFrames(List<FrameTiming> timings) {
    workloadFrames.addAll(
      timings.map(
        (frame) =>
            frame.buildDuration.inMicroseconds +
            frame.rasterDuration.inMicroseconds,
      ),
    );
    totalSpanFrames.addAll(
      timings.map((frame) => frame.totalSpan.inMicroseconds),
    );
  }

  void sampleMemory() => rssSamples.add(ProcessInfo.currentRss);

  Map<String, Object> finish() {
    stopwatch.stop();
    final sorted = workloadFrames.toList()..sort();
    final totalSorted = totalSpanFrames.toList()..sort();
    final refreshMicros = (1000000 / refreshRate).round();
    final p95 = sorted.isEmpty
        ? 0
        : sorted[((sorted.length - 1) * .95).floor()];
    final minimumRss = rssSamples.isEmpty
        ? 0
        : rssSamples.reduce((a, b) => a < b ? a : b);
    final maximumRss = rssSamples.isEmpty
        ? 0
        : rssSamples.reduce((a, b) => a > b ? a : b);
    return {
      'name': name,
      'durationMs': stopwatch.elapsedMilliseconds,
      'refreshRateHz': refreshRate,
      'refreshPeriodMicros': refreshMicros,
      'frameCount': workloadFrames.length,
      'p95WorkloadMicros': p95,
      'maxWorkloadMicros': sorted.isEmpty ? 0 : sorted.last,
      'workloadFramesOverTwoRefreshPeriods': workloadFrames
          .where((value) => value > refreshMicros * 2)
          .length,
      'workloadFramesOver250ms': workloadFrames
          .where((value) => value > 250000)
          .length,
      'p95TotalSpanMicros': totalSorted.isEmpty
          ? 0
          : totalSorted[((totalSorted.length - 1) * .95).floor()],
      'maxTotalSpanMicros': totalSorted.isEmpty ? 0 : totalSorted.last,
      'rssSampleCount': rssSamples.length,
      'rssMinBytes': minimumRss,
      'rssMaxBytes': maximumRss,
      'rssDeltaBytes': rssSamples.length < 2
          ? 0
          : rssSamples.last - rssSamples.first,
    };
  }
}

void main() {
  final binding = IntegrationTestWidgetsFlutterBinding.ensureInitialized();

  testWidgets(
    'C08 Profile list, input and reconnect performance slice',
    (tester) async {
      const origin = String.fromEnvironment('MESHX_NODE');
      const username = String.fromEnvironment('PROBE_USERNAME');
      const password = String.fromEnvironment('PROBE_PASSWORD');
      const groupId = String.fromEnvironment('PROBE_GROUP_ID');
      const smallGroupId = String.fromEnvironment('PROBE_SMALL_GROUP_ID');
      const expectedMessages = int.fromEnvironment(
        'PROBE_MESSAGE_COUNT',
        defaultValue: 2000,
      );
      const physical = bool.fromEnvironment('PROBE_PHYSICAL');
      expect(origin, isNotEmpty, reason: 'Use the ignored C08 fixture config');
      expect(username, isNotEmpty);
      expect(password, isNotEmpty);
      expect(groupId, isNotEmpty);
      expect(smallGroupId, isNotEmpty);
      expect(kProfileMode, isTrue, reason: 'C08 must run in Profile mode');
      expect(expectedMessages, greaterThanOrEqualTo(2000));

      final system = NativeSystemCapabilities()..start();
      final lifecycle = FlutterLifecyclePort();
      final controller = ChatController(
        discovery: NativeNodeDiscovery(),
        store: _MemoryStore(),
        credentials: _MemoryCredentials(),
        // The isolated C08 fixture is HTTP. This test-only entrypoint opts in;
        // production main.dart and release candidates remain HTTPS-only.
        allowLocalHttp: true,
      );
      final coordinator = PlatformCoordinator(
        chat: controller,
        lifecycle: lifecycle,
        notifications: system,
        files: system,
        sharePort: system,
        network: system,
        settings: system,
        runtimeInfo: system,
        disposeAdapters: system.dispose,
      );
      final runtime = await system.readRuntimeInfo();
      final refreshRate = tester.view.display.refreshRate;
      final metrics = <String, dynamic>{
        'schema': 'meshx.c08-profile-performance/1',
        'platform': Platform.operatingSystem,
        'mode': 'profile',
        'physicalDevice': physical,
        'entrypoint': 'integration_test/c08_profile_performance_test.dart',
        'formalMainEntrypoint': false,
        'transport': 'isolated-test-http',
        'productionTransportChanged': false,
        'deviceOs': runtime.value == null
            ? null
            : '${runtime.value!.osName} ${runtime.value!.osVersion}',
        'refreshRateHz': refreshRate,
        'requiredMessageCount': expectedMessages,
        'windows': <Map<String, Object>>[],
        'limitations': [
          'Integration entrypoint exercises production controller, UI and native adapters but is not lib/main.dart.',
          'Cold launch, real OS resume, screen reader and Android physical-device evidence are separate C08 rows.',
          'Local HTTP is enabled only in this fixture entrypoint; it is not production transport evidence.',
        ],
      };
      binding.reportData = {'metrics': metrics};

      Future<void> waitFor(
        bool Function() predicate,
        String label, {
        int seconds = 30,
      }) async {
        final deadline = DateTime.now().add(Duration(seconds: seconds));
        while (!predicate() && DateTime.now().isBefore(deadline)) {
          await tester.pump(const Duration(milliseconds: 100));
        }
        expect(
          predicate(),
          isTrue,
          reason: '$label; error=${controller.error}',
        );
      }

      Future<Map<String, Object>> measure(
        String name,
        Future<void> Function(_FrameWindow window) action,
      ) async {
        final window = _FrameWindow(name, refreshRate)..sampleMemory();
        binding.addTimingsCallback(window.onFrames);
        window.stopwatch.start();
        try {
          await action(window);
        } finally {
          binding.removeTimingsCallback(window.onFrames);
          window.sampleMemory();
        }
        final result = window.finish();
        (metrics['windows'] as List<Map<String, Object>>).add(result);
        return result;
      }

      Future<Map<String, Object>> scrollFor(String name, Duration duration) =>
          measure(name, (window) async {
            final deadline = DateTime.now().add(duration);
            var direction = 1.0;
            while (DateTime.now().isBefore(deadline)) {
              await tester.fling(
                find.byKey(const Key('message-list')),
                Offset(0, 560 * direction),
                1800,
              );
              await Future<void>.delayed(const Duration(milliseconds: 250));
              await tester.pump();
              direction = -direction;
              window.sampleMemory();
            }
          });

      await tester.pumpWidget(
        MeshXApp(controller: controller, platform: coordinator),
      );
      await tester.pumpAndSettle();
      final login = Stopwatch()..start();
      await controller.discovery.prepareConnection(Uri.parse(origin));
      await controller.login(origin, username, password);
      await waitFor(
        () => controller.online && controller.conversations.isNotEmpty,
        'real fixture login and sync',
        seconds: 60,
      );
      metrics['loginToOnlineMs'] = login.elapsedMilliseconds;
      final smallRoom = controller.conversations.firstWhere(
        (value) => value.id == 'group:$smallGroupId',
      );
      await controller.select(smallRoom);
      await tester.pump();
      while (controller.messages.length < 220 && controller.hasOlder) {
        await controller.loadOlder();
        await tester.pump();
      }
      expect(controller.messages.length, greaterThanOrEqualTo(220));
      metrics['firstListMessageCount'] = controller.messages.length;
      expect(controller.messages.length, lessThan(300));
      final list220 = await scrollFor(
        'scroll-220',
        const Duration(seconds: 30),
      );
      expect(list220['frameCount'] as int, greaterThan(0));

      final loadAll = Stopwatch()..start();
      final room = controller.conversations.firstWhere(
        (value) => value.id == 'group:$groupId',
      );
      await controller.select(room);
      await tester.pump();
      while (controller.messages.length < expectedMessages &&
          controller.hasOlder) {
        await controller.loadOlder();
        await tester.pump();
      }
      metrics['loadToTargetMs'] = loadAll.elapsedMilliseconds;
      metrics['loadedMessageCount'] = controller.messages.length;
      expect(
        controller.messages.length,
        greaterThanOrEqualTo(expectedMessages),
      );
      final list2000 = await scrollFor(
        'scroll-2000',
        const Duration(seconds: 30),
      );
      expect(list2000['frameCount'] as int, greaterThan(0));

      final inputResult = await measure('input-1000', (window) async {
        await tester.tap(find.byKey(const Key('composer')));
        await tester.showKeyboard(find.byKey(const Key('composer')));
        final source = List.filled(
          80,
          '中文输入 MeshX 123。',
        ).join().substring(0, 1000);
        for (var length = 20; length <= source.length; length += 20) {
          await tester.enterText(
            find.byKey(const Key('composer')),
            source.substring(0, length),
          );
          await Future<void>.delayed(const Duration(milliseconds: 30));
          await tester.pump();
          window.sampleMemory();
        }
        metrics['inputCharacters'] = source.length;
        for (var index = 0; index < 5; index++) {
          FocusManager.instance.primaryFocus?.unfocus();
          await Future<void>.delayed(const Duration(milliseconds: 180));
          await tester.pump();
          await tester.showKeyboard(find.byKey(const Key('composer')));
          await Future<void>.delayed(const Duration(milliseconds: 180));
          await tester.pump();
        }
      });
      expect(inputResult['frameCount'] as int, greaterThan(0));
      expect(metrics['inputCharacters'], 1000);
      FocusManager.instance.primaryFocus?.unfocus();
      await tester.pumpAndSettle();

      tester.platformDispatcher.textScaleFactorTestValue = 2;
      addTearDown(tester.platformDispatcher.clearTextScaleFactorTestValue);
      await tester.tap(find.byKey(const Key('theme-toggle')));
      await tester.pumpAndSettle();
      expect(tester.takeException(), isNull);
      metrics['largeTextAndThemeNoFlutterException'] = true;
      expect(find.byTooltip('发送消息'), findsOneWidget);
      metrics['primarySendAccessibilityLabelPresent'] = true;

      final concurrency = Stopwatch()..start();
      unawaited(controller.scan());
      await tester.pump(const Duration(milliseconds: 250));
      await controller.pause();
      await tester.pump(const Duration(milliseconds: 500));
      await controller.resume();
      await waitFor(() => controller.online, 'reconnect during discovery');
      await controller.stopScan();
      metrics['discoveryReconnectMs'] = concurrency.elapsedMilliseconds;
      metrics['discoveryReconnectRecoveredOnline'] = controller.online;
      metrics['discoveryFinalStatus'] = controller.discoveryStatus.name;
      final recoveryCycles = <int>[];
      for (var cycle = 0; cycle < 5; cycle++) {
        await controller.pause();
        await tester.pump();
        final recovery = Stopwatch()..start();
        await controller.resume();
        await waitFor(
          () => controller.online,
          'controller recovery cycle ${cycle + 1}',
        );
        recoveryCycles.add(recovery.elapsedMilliseconds);
      }
      metrics['controllerRecoveryMs'] = recoveryCycles;
      metrics['controllerRecoveryAllWithin5s'] = recoveryCycles.every(
        (value) => value <= 5000,
      );
      metrics['flutterException'] = tester
          .takeException()
          ?.runtimeType
          .toString();
      expect(metrics['flutterException'], isNull);

      metrics['finalRssBytes'] = ProcessInfo.currentRss;
      metrics['passedMeasuredSlice'] = true;
      metrics['c08GateClosed'] = false;
      await controller.pause();
      await tester.pumpWidget(const SizedBox.shrink());
    },
    timeout: const Timeout(Duration(minutes: 12)),
  );
}
