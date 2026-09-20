// Persistent C08 process/lifecycle evidence entrypoint. This is not production
// main.dart and never writes credentials, message bodies or user paths to output.
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

class _MeasuredConnection extends RealtimeConnection {
  _MeasuredConnection(super.api, this.onState);

  final void Function(String state) onState;
  bool _active = false;

  void _ended() {
    if (!_active) return;
    _active = false;
    onState('closed');
  }

  @override
  Future<void> connect() async {
    _active = true;
    onState('opening');
    disconnected.stream.listen((_) => _ended());
    try {
      await super.connect();
      onState('authenticated');
    } catch (_) {
      _ended();
      rethrow;
    }
  }

  @override
  Future<Map<String, dynamic>> synchronize(Map<String, int> positions) {
    onState('sync');
    return super.synchronize(positions);
  }

  @override
  Future<void> close() async {
    _ended();
    await super.close();
  }
}

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  const origin = String.fromEnvironment('MESHX_NODE');
  const username = String.fromEnvironment('PROBE_USERNAME');
  const password = String.fromEnvironment('PROBE_PASSWORD');
  const groupId = String.fromEnvironment('PROBE_GROUP_ID');
  const runId = String.fromEnvironment('PROBE_RUN_ID');
  const expectedMessages = int.fromEnvironment(
    'PROBE_MESSAGE_COUNT',
    defaultValue: 2000,
  );
  if (origin.isEmpty ||
      username.isEmpty ||
      password.isEmpty ||
      groupId.isEmpty ||
      runId.isEmpty ||
      expectedMessages < 2000) {
    throw StateError('Dedicated C08 fixture configuration required');
  }

  final launchClock = Stopwatch()..start();
  final root = await const MethodChannel(
    'com.meshx.mobile/storage',
  ).invokeMethod<String>('dataDirectory');
  if (root == null || root.isEmpty) {
    throw StateError('C08 evidence directory unavailable');
  }
  final evidence = File('$root/c08-process-probe.json');
  Map<String, dynamic> metrics = {};
  if (await evidence.exists()) {
    final decoded = jsonDecode(await evidence.readAsString());
    if (decoded is Map<String, dynamic> && decoded['runId'] == runId) {
      metrics = decoded;
    }
  }
  final runs =
      (metrics['runs'] as List?)?.cast<Map<String, dynamic>>() ??
      <Map<String, dynamic>>[];
  final recoveries =
      (metrics['osRecoveries'] as List?)?.cast<Map<String, dynamic>>() ??
      <Map<String, dynamic>>[];
  metrics = {
    'schema': 'meshx.c08-process-lifecycle/1',
    'runId': runId,
    'platform': Platform.operatingSystem,
    'mode': kProfileMode ? 'profile' : (kDebugMode ? 'debug' : 'release'),
    'entrypoint': 'integration_test/c08_process_probe.dart',
    'formalMainEntrypoint': false,
    'transport': 'isolated-test-http',
    'productionTransportChanged': false,
    'requiredMessageCount': expectedMessages,
    'runs': runs,
    'osRecoveries': recoveries,
    'c08GateClosed': false,
  };
  final currentRun = <String, dynamic>{
    'index': runs.length + 1,
    'startedAt': DateTime.now().toUtc().toIso8601String(),
    'stage': 'starting',
    'firstFrameMs': null,
    'cacheInteractiveMs': null,
    'onlineMs': null,
    'loadedMessageCount': 0,
    'rssAtStartBytes': ProcessInfo.currentRss,
    'activeConnections': 0,
    'peakActiveConnections': 0,
    'connectionOpens': 0,
    'connectionAuths': 0,
    'syncRequests': 0,
  };
  runs.add(currentRun);
  Future<void> writes = Future.value();
  void save() {
    final encoded = jsonEncode(metrics);
    writes = writes.then((_) async {
      final temporary = File('${evidence.path}.tmp');
      await temporary.writeAsString(encoded, flush: true);
      await temporary.rename(evidence.path);
    });
  }

  void connectionState(String state) {
    switch (state) {
      case 'opening':
        currentRun['connectionOpens'] =
            (currentRun['connectionOpens'] as int) + 1;
        currentRun['activeConnections'] =
            (currentRun['activeConnections'] as int) + 1;
        if ((currentRun['activeConnections'] as int) >
            (currentRun['peakActiveConnections'] as int)) {
          currentRun['peakActiveConnections'] = currentRun['activeConnections'];
        }
      case 'closed':
        currentRun['activeConnections'] =
            ((currentRun['activeConnections'] as int) - 1).clamp(0, 1000);
      case 'authenticated':
        currentRun['connectionAuths'] =
            (currentRun['connectionAuths'] as int) + 1;
      case 'sync':
        currentRun['syncRequests'] = (currentRun['syncRequests'] as int) + 1;
    }
    save();
  }

  final discovery = NativeNodeDiscovery();
  final system = NativeSystemCapabilities()..start();
  final lifecycle = FlutterLifecyclePort();
  final controller = ChatController(
    discovery: discovery,
    allowLocalHttp: true,
    store: FileChatStore(),
    credentials: NativeCredentialStore(),
    connectionFactory: (api) => _MeasuredConnection(api, connectionState),
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

  Map<String, dynamic>? pendingRecovery;
  bool restoring = true;
  controller.addListener(() {
    if (restoring &&
        currentRun['cacheInteractiveMs'] == null &&
        controller.session != null &&
        controller.conversations.isNotEmpty) {
      currentRun['cacheInteractiveMs'] = launchClock.elapsedMilliseconds;
    }
    if (controller.online && currentRun['onlineMs'] == null) {
      currentRun['onlineMs'] = launchClock.elapsedMilliseconds;
    }
    final recovery = pendingRecovery;
    if (recovery != null &&
        recovery['foregroundAt'] != null &&
        controller.online &&
        recovery['onlineAfterForegroundMs'] == null) {
      recovery['onlineAfterForegroundMs'] = DateTime.now()
          .difference(DateTime.parse(recovery['foregroundAt'] as String))
          .inMilliseconds;
      recovery['completedAt'] = DateTime.now().toUtc().toIso8601String();
      pendingRecovery = null;
    }
    if (recovery != null &&
        recovery['backgroundAt'] != null &&
        !controller.online &&
        recovery['offlineObservedAt'] == null) {
      recovery['offlineObservedAt'] = DateTime.now().toUtc().toIso8601String();
    }
    currentRun['online'] = controller.online;
    currentRun['conversationCount'] = controller.conversations.length;
    currentRun['rssLatestBytes'] = ProcessInfo.currentRss;
    save();
  });
  coordinator.start();
  lifecycle.changes.listen((state) {
    final now = DateTime.now().toUtc().toIso8601String();
    if (state == AppVisibility.background) {
      pendingRecovery = <String, dynamic>{
        'index': recoveries.length + 1,
        'processRun': currentRun['index'],
        'backgroundAt': now,
        'offlineObservedAt': controller.online ? null : now,
        'foregroundAt': null,
        'onlineAfterForegroundMs': null,
      };
      recoveries.add(pendingRecovery!);
    } else if (state == AppVisibility.foreground && pendingRecovery != null) {
      pendingRecovery!['foregroundAt'] = now;
    }
    currentRun['visibility'] = state.name;
    save();
  });
  WidgetsBinding.instance.addPostFrameCallback((_) {
    currentRun['firstFrameMs'] = launchClock.elapsedMilliseconds;
    save();
  });
  runApp(
    MeshXApp(
      controller: controller,
      platform: coordinator,
      preferences: FilePreferenceStore(),
    ),
  );
  save();

  try {
    await controller.restore();
    restoring = false;
    final restored = controller.session != null;
    currentRun['restoredCredentials'] = restored;
    if (restored && controller.api!.origin.toString() != origin) {
      await controller.logout(revokeRemote: false);
      currentRun['discardedForeignTestOrigin'] = true;
    }
    if (controller.session == null) {
      await controller.discovery.prepareConnection(Uri.parse(origin));
      await controller.login(origin, username, password);
      currentRun['loggedInFixture'] = true;
    }
    final onlineDeadline = DateTime.now().add(const Duration(seconds: 30));
    while (!controller.online && DateTime.now().isBefore(onlineDeadline)) {
      await Future<void>.delayed(const Duration(milliseconds: 50));
    }
    if (!controller.online) throw StateError('onlineTimeout');
    final room = controller.conversations.firstWhere(
      (value) => value.id == 'group:$groupId',
    );
    await controller.select(room);
    while (controller.messages.length < expectedMessages &&
        controller.hasOlder) {
      await controller.loadOlder();
    }
    if (controller.messages.length < expectedMessages) {
      throw StateError('messageTargetMissing');
    }
    final runtime = await system.readRuntimeInfo();
    currentRun['deviceOs'] = runtime.value == null
        ? null
        : '${runtime.value!.osName} ${runtime.value!.osVersion}';
    currentRun['loadedMessageCount'] = controller.messages.length;
    currentRun['uniqueMessages'] =
        controller.messages
            .map((message) => message.messageId)
            .toSet()
            .length ==
        controller.messages.length;
    currentRun['rssReadyBytes'] = ProcessInfo.currentRss;
    currentRun['stage'] = 'ready';
    currentRun['readyMs'] = launchClock.elapsedMilliseconds;
    save();
  } catch (_) {
    restoring = false;
    currentRun['stage'] = 'failed';
    currentRun['failureCode'] = 'processProbeStartupFailed';
    currentRun['rssReadyBytes'] = ProcessInfo.currentRss;
    save();
  }
}
