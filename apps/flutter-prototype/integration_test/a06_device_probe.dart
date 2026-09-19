// Dedicated device evidence entrypoint, never production main.dart. Results
// contain counters only: no credentials, message bodies or user file paths.
import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:meshx_flutter_probe/application/platform_coordinator.dart';
import 'package:meshx_flutter_probe/chat_controller.dart';
import 'package:meshx_flutter_probe/data/meshx_api.dart';
import 'package:meshx_flutter_probe/platform/discovery.dart';
import 'package:meshx_flutter_probe/platform/storage.dart';
import 'package:meshx_flutter_probe/platform/system_capabilities.dart';
import 'package:meshx_flutter_probe/ui/app.dart';

class MeasuredConnection extends RealtimeConnection {
  MeasuredConnection(super.api, this.measure);
  final void Function(String) measure;
  bool counted = false;
  void ended() {
    if (counted) {
      counted = false;
      measure('closed');
    }
  }

  @override
  Future<void> connect() async {
    counted = true;
    measure('opening');
    disconnected.stream.listen((_) => ended());
    try {
      await super.connect();
      measure('authenticated');
    } catch (_) {
      ended();
      rethrow;
    }
  }

  @override
  Future<Map<String, dynamic>> synchronize(Map<String, int> positions) {
    measure('sync');
    return super.synchronize(positions);
  }

  @override
  Future<void> close() async {
    ended();
    await super.close();
  }
}

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  const origin = String.fromEnvironment('MESHX_NODE');
  if (origin.isEmpty) {
    throw StateError('Dedicated fixture configuration required');
  }
  final root = await const MethodChannel(
    'com.meshx.mobile/storage',
  ).invokeMethod<String>('dataDirectory');
  final evidence = File('$root/a06-device-probe.json');
  final command = File('$root/a06-device-command.json');
  final prior = await evidence.exists()
      ? jsonDecode(await evidence.readAsString()) as Map<String, dynamic>
      : <String, dynamic>{};
  final metrics = <String, dynamic>{
    'platform': Platform.operatingSystem,
    'mode': kProfileMode ? 'profile' : (kDebugMode ? 'debug' : 'release'),
    'processRuns': ((prior['processRuns'] as int?) ?? 0) + 1,
    'previousCheckpoint': prior.isEmpty
        ? null
        : {
            'online': prior['online'],
            'messageCount': prior['messageCount'],
            'positions': prior['positions'],
            'backgroundEvents': prior['backgroundEvents'],
            'foregroundEvents': prior['foregroundEvents'],
            'commands': prior['commands'],
          },
    'stage': 'starting',
    'events': <Map<String, dynamic>>[],
    'commands': <Map<String, dynamic>>[],
    'backgroundEvents': 0,
    'foregroundEvents': 0,
    'networkEvents': 0,
    'connectionOpens': 0,
    'connectionAuths': 0,
    'syncRequests': 0,
    'activeConnections': 0,
    'peakActiveConnections': 0,
  };
  Future<void> writes = Future.value();
  void save() {
    final data = jsonEncode(metrics);
    writes = writes.then((_) async {
      final temporary = File('${evidence.path}.tmp');
      await temporary.writeAsString(data, flush: true);
      await temporary.rename(evidence.path);
    });
  }

  void event(String type) {
    final events = metrics['events'] as List<Map<String, dynamic>>;
    events.add({'type': type, 'at': DateTime.now().toUtc().toIso8601String()});
    if (events.length > 100) events.removeAt(0);
    save();
  }

  final discovery = NativeNodeDiscovery();
  final system = NativeSystemCapabilities();
  final lifecycle = FlutterLifecyclePort();
  final c = ChatController(
    discovery: discovery,
    allowLocalHttp: true,
    store: FileChatStore(),
    credentials: NativeCredentialStore(),
    connectionFactory: (api) => MeasuredConnection(api, (kind) {
      if (kind == 'opening') {
        metrics['connectionOpens'] = (metrics['connectionOpens'] as int) + 1;
        metrics['activeConnections'] =
            (metrics['activeConnections'] as int) + 1;
        if ((metrics['activeConnections'] as int) >
            (metrics['peakActiveConnections'] as int)) {
          metrics['peakActiveConnections'] = metrics['activeConnections'];
        }
      }
      if (kind == 'closed') {
        metrics['activeConnections'] =
            (metrics['activeConnections'] as int) - 1;
      }
      if (kind == 'authenticated') {
        metrics['connectionAuths'] = (metrics['connectionAuths'] as int) + 1;
      }
      if (kind == 'sync') {
        metrics['syncRequests'] = (metrics['syncRequests'] as int) + 1;
      }
      event('connection:$kind');
    }),
  );
  final coordinator = PlatformCoordinator(
    chat: c,
    lifecycle: lifecycle,
    notifications: system,
    files: system,
    sharePort: system,
    network: system,
    settings: system,
    disposeAdapters: system.dispose,
  );
  c.addListener(() {
    metrics['online'] = c.online;
    metrics['scanning'] = c.scanning;
    metrics['candidateCount'] = c.candidates.length;
    metrics['discoveryStatus'] = c.discoveryStatus.name;
    metrics['messageCount'] = c.messages.length;
    metrics['positions'] = c.positions;
    metrics['uniqueMessages'] =
        c.messages.map((m) => m.messageId).toSet().length == c.messages.length;
    metrics['backgroundGapMarkers'] = c.messages
        .where((m) => m.content.startsWith('A06-background-gap-'))
        .length;
    metrics['restoredOutboxMarkers'] = c.messages
        .where((m) => m.content.startsWith('A06-outbox-'))
        .map((m) => m.delivery.name)
        .toList();
    save();
  });
  lifecycle.changes.listen((state) {
    if (state == AppVisibility.background) {
      metrics['backgroundEvents'] = (metrics['backgroundEvents'] as int) + 1;
    }
    if (state == AppVisibility.foreground) {
      metrics['foregroundEvents'] = (metrics['foregroundEvents'] as int) + 1;
    }
    metrics['visibility'] = state.name;
    event('os:${state.name}');
  });
  system.changes.listen((result) {
    metrics['networkEvents'] = (metrics['networkEvents'] as int) + 1;
    event('network:${result.status.name}');
  });
  discovery.updates.listen((update) {
    metrics['nativeDiscoveryNodeCount'] = update.result.value?.length ?? 0;
    metrics['nativeDiscoveryReason'] = update.result.reason;
    event('discovery:${update.result.status.name}:${update.complete}');
  });
  final frames = <int>[];
  WidgetsBinding.instance.addTimingsCallback((timings) {
    frames.addAll(timings.map((v) => v.totalSpan.inMicroseconds));
    if (frames.length > 2000) frames.removeRange(0, frames.length - 2000);
  });
  coordinator.start();
  system.start();
  runApp(MeshXApp(controller: c, platform: coordinator));
  Future<void> scan() async {
    await c.scan();
    metrics['discoveryStatus'] = c.discoveryStatus.name;
    metrics['candidateCount'] = c.candidates.length;
    metrics['fixtureDiscovered'] = c.candidates.any(
      (v) => Uri.parse(v).port == Uri.parse(origin).port,
    );
    save();
  }

  final start = Stopwatch()..start();
  try {
    await c.restore();
    if (c.session != null && c.api!.origin.toString() != origin) {
      await c.logout(revokeRemote: false);
    }
    metrics['restoredCredentials'] = c.session != null;
    if (c.session == null) {
      await c.login(
        origin,
        const String.fromEnvironment('PROBE_USERNAME'),
        const String.fromEnvironment('PROBE_PASSWORD'),
      );
    }
    final deadline = DateTime.now().add(const Duration(seconds: 60));
    while (!c.online && DateTime.now().isBefore(deadline)) {
      await Future<void>.delayed(const Duration(milliseconds: 100));
    }
    if (!c.online) throw StateError('onlineTimeout');
    final room = c.conversations.firstWhere(
      (r) => r.id == 'group:${const String.fromEnvironment('PROBE_GROUP_ID')}',
    );
    await c.select(room);
    metrics['restoreOrLoginToOnlineMs'] = start.elapsedMilliseconds;
    metrics['stage'] = 'online';
    save();
    await scan();
    metrics['stage'] = 'ready';
    save();
  } catch (_) {
    metrics['stage'] = 'failed';
    metrics['error'] = 'startupFailed';
    save();
  }
  var handling = false;
  Timer.periodic(const Duration(milliseconds: 500), (_) async {
    if (handling || !await command.exists()) return;
    handling = true;
    final result = <String, dynamic>{};
    try {
      final input =
          jsonDecode(await command.readAsString()) as Map<String, dynamic>;
      await command.delete();
      result['id'] = input['id'];
      result['op'] = input['op'];
      switch (input['op']) {
        case 'scan':
          await scan();
          result['status'] = c.discoveryStatus.name;
          result['fixtureDiscovered'] = metrics['fixtureDiscovered'];
        case 'startScan':
          unawaited(c.scan());
          result['status'] = 'started';
        case 'stopScan':
          await c.stopScan();
          result['status'] = c.discoveryStatus.name;
          result['candidateCount'] = c.candidates.length;
        case 'openSettings':
          final settings = await system.openSettings();
          result['status'] = settings.status.name;
          result['reason'] = settings.reason;
        case 'notificationQuery':
          result['status'] = (await system.permission()).status.name;
        case 'notificationRequest':
          result['status'] = (await system.permission(
            request: true,
          )).status.name;
        case 'notify':
          result['status'] = (await system.show(
            'a06-local-capability',
            NotificationRoute(coordinator.owner!, c.active!.id),
          )).status.name;
        case 'cancelNotifications':
          result['status'] = (await system.cancelAll()).status.name;
        case 'filePickTooSmall':
        case 'filePick':
          result['status'] = 'pending';
          unawaited(
            coordinator
                .pickFile(
                  maxBytes: input['op'] == 'filePickTooSmall'
                      ? 1
                      : 25 * 1024 * 1024,
                )
                .then((_) {
                  result['status'] = coordinator.fileStatus.status.name;
                  result['reason'] = coordinator.fileStatus.reason;
                  result['bytes'] = coordinator.selectedFile?.size;
                  save();
                }),
          );
        case 'shareReleasedFile':
          final file = coordinator.selectedFile!;
          await system.release(file);
          final outcome = await system.share(file);
          result['status'] = outcome.status.name;
          result['reason'] = outcome.reason;
        case 'fileShare':
          await coordinator.shareFile();
          result['status'] = coordinator.fileStatus.status.name;
        case 'clearFile':
          await coordinator.clearFile();
          result['status'] = coordinator.fileStatus.status.name;
        case 'sendOutbox':
          result['accepted'] = c.send('A06-outbox-${input['id']}');
          result['status'] = 'submitted';
        case 'snapshot':
          final sorted = frames.toList()..sort();
          result['frameCount'] = frames.length;
          if (sorted.isNotEmpty) {
            result['p95FrameMicros'] =
                sorted[((sorted.length - 1) * .95).floor()];
            result['maxFrameMicros'] = sorted.last;
          }
          result['status'] = 'success';
        default:
          result['status'] = 'unsupported';
      }
    } catch (_) {
      result['status'] = 'failed';
      result['reason'] = 'probeOperationFailed';
    }
    (metrics['commands'] as List<Map<String, dynamic>>).add(result);
    save();
    handling = false;
  });
}
