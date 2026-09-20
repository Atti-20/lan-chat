import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'dart:ui' show FrameTiming;
import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:integration_test/integration_test.dart';
import '../tool/recovery_capacity_probe.dart';

class _ForegroundProbe extends WidgetsBindingObserver {
  final states = <String>[];
  @override
  void didChangeAppLifecycleState(AppLifecycleState state) =>
      states.add(state.name);
}

void main() {
  final binding = IntegrationTestWidgetsFlutterBinding.ensureInitialized();
  binding.framePolicy = LiveTestWidgetsFlutterBindingFramePolicy.fullyLive;
  testWidgets(
    'Profile recovery capacity uses real file commit and restart',
    (tester) async {
      const runId = String.fromEnvironment(
        'PROBE_RUN_ID',
        defaultValue: 'driver-run',
      );
      final startedAt = DateTime.now().toUtc().toIso8601String();
      final evidence = File(
        '${Directory.systemTemp.path}/meshx-recovery-capacity.json',
      );
      await evidence.writeAsString(
        jsonEncode({
          'runId': runId,
          'startedAt': startedAt,
          'status': 'RUNNING',
        }),
        flush: true,
      );
      try {
        expect(
          kProfileMode,
          true,
          reason: 'Capacity device evidence requires a Profile build',
        );
        await tester.pumpWidget(
          const MaterialApp(
            home: Scaffold(
              body: Center(
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  children: [Text('MeshX 恢复容量验证'), CircularProgressIndicator()],
                ),
              ),
            ),
          ),
        );
        // iOS delivers the first resumed event after the initial Flutter frame.
        // Startup waiting is outside the measurement, with a bounded deadline.
        final foregroundDeadline = Stopwatch()..start();
        while (binding.lifecycleState != AppLifecycleState.resumed &&
            foregroundDeadline.elapsed < const Duration(seconds: 30)) {
          await tester.pump(const Duration(milliseconds: 100));
        }
        expect(
          binding.lifecycleState,
          AppLifecycleState.resumed,
          reason: 'Measure only a foreground device',
        );
        final foreground = _ForegroundProbe();
        binding.addObserver(foreground);
        final clock = Stopwatch()..start();
        var lastTick = 0, maxGapMs = 0, ticks = 0, stallsOver250Ms = 0;
        var frames = 0, maxBuildUs = 0, maxRasterUs = 0;
        void onFrames(List<FrameTiming> timings) {
          for (final timing in timings) {
            frames++;
            if (timing.buildDuration.inMicroseconds > maxBuildUs) {
              maxBuildUs = timing.buildDuration.inMicroseconds;
            }
            if (timing.rasterDuration.inMicroseconds > maxRasterUs) {
              maxRasterUs = timing.rasterDuration.inMicroseconds;
            }
          }
        }

        binding.addTimingsCallback(onFrames);
        final heartbeat = Timer.periodic(const Duration(milliseconds: 16), (_) {
          final now = clock.elapsedMilliseconds, gap = now - lastTick;
          if (gap > maxGapMs) maxGapMs = gap;
          if (gap > 250) stallsOver250Ms++;
          lastTick = now;
          ticks++;
        });
        late Map<String, Object> metrics;
        try {
          metrics = await runCapacityProbe(repeatRecovery: true);
        } finally {
          heartbeat.cancel();
          binding.removeTimingsCallback(onFrames);
          binding.removeObserver(foreground);
        }
        expect(
          frames,
          greaterThan(0),
          reason: 'The visible progress UI must render during recovery',
        );
        expect(
          foreground.states.where((state) => state != 'resumed'),
          isEmpty,
          reason:
              'Background or obscured intervals are not foreground performance evidence',
        );
        expect(binding.lifecycleState, AppLifecycleState.resumed);
        binding.reportData = {
          'metrics': {
            'platform': Platform.isIOS ? 'ios-device' : 'android-emulator',
            'buildMode': 'profile',
            'runId': runId,
            'foregroundThroughout': true,
            ...metrics,
            'heartbeatTicks': ticks,
            'maxEventLoopGapMs': maxGapMs,
            'eventLoopStallsOver250Ms': stallsOver250Ms,
            'frames': frames,
            'maxFrameBuildUs': maxBuildUs,
            'maxFrameRasterUs': maxRasterUs,
          },
        };
        await evidence.writeAsString(
          jsonEncode({
            'runId': runId,
            'startedAt': startedAt,
            'status': 'PASS',
            ...binding.reportData!,
          }),
          flush: true,
        );
        await tester.pumpWidget(
          const MaterialApp(
            home: Scaffold(body: Center(child: Text('容量验证完成'))),
          ),
        );
      } catch (error) {
        await evidence.writeAsString(
          jsonEncode({
            'runId': runId,
            'startedAt': startedAt,
            'status': 'FAIL',
            'error': '$error',
          }),
          flush: true,
        );
        rethrow;
      }
    },
    timeout: const Timeout(Duration(minutes: 10)),
  );
}
