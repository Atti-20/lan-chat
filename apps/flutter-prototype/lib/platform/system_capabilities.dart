import 'dart:async';
import 'dart:math';
import 'package:flutter/services.dart';
import 'package:flutter/widgets.dart';
import '../core/platform_ports.dart';
import 'capability_codec.dart';

class FlutterLifecyclePort
    with WidgetsBindingObserver
    implements LifecyclePort {
  FlutterLifecyclePort() {
    _current = _map(
      WidgetsBinding.instance.lifecycleState ?? AppLifecycleState.resumed,
    );
    WidgetsBinding.instance.addObserver(this);
  }
  final _events = StreamController<AppVisibility>.broadcast(sync: true);
  late AppVisibility _current;
  @override
  AppVisibility get current => _current;
  @override
  Stream<AppVisibility> get changes => _events.stream;
  AppVisibility _map(AppLifecycleState value) => switch (value) {
    AppLifecycleState.resumed => AppVisibility.foreground,
    AppLifecycleState.inactive => AppVisibility.inactive,
    AppLifecycleState.paused ||
    AppLifecycleState.hidden => AppVisibility.background,
    AppLifecycleState.detached => AppVisibility.detached,
  };
  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    final next = _map(state);
    if (next == _current) return;
    _current = next;
    _events.add(next);
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    unawaited(_events.close());
  }
}

class NativeSystemCapabilities
    implements
        NotificationPort,
        FilePickerPort,
        SharePort,
        NetworkChangePort,
        PermissionSettingsPort,
        RuntimeInfoPort {
  static const _events = EventChannel('com.meshx.mobile/capabilities/events');
  final _tapEvents = StreamController<NotificationRoute>.broadcast(sync: true);
  final _networkEvents = StreamController<CapabilityResult<void>>.broadcast(
    sync: true,
  );
  StreamSubscription<dynamic>? _subscription;
  void start() {
    _subscription ??= _events.receiveBroadcastStream().listen(
      (dynamic value) {
        if (value is! Map) return;
        if (value['type'] == 'networkChanged') {
          _networkEvents.add(const CapabilityResult(CapabilityStatus.success));
        }
        if (value['type'] == 'notificationTap' &&
            value['owner'] is String &&
            value['conversationId'] is String) {
          final rawBroadcastId = value['broadcastId'];
          final broadcastId = rawBroadcastId is int
              ? rawBroadcastId
              : int.tryParse('${rawBroadcastId ?? ''}');
          _tapEvents.add(
            NotificationRoute(
              value['owner'] as String,
              value['conversationId'] as String,
              messageId: value['messageId'] is String
                  ? value['messageId'] as String
                  : null,
              broadcastId: broadcastId != null && broadcastId > 0
                  ? broadcastId
                  : null,
            ),
          );
        }
      },
      onError: (Object error) {
        _networkEvents.add(
          CapabilityResult(
            error is MissingPluginException
                ? CapabilityStatus.unsupported
                : CapabilityStatus.failed,
            reason: 'eventStreamUnavailable',
          ),
        );
      },
    );
  }

  @override
  Stream<NotificationRoute> get taps => _tapEvents.stream;
  @override
  Stream<CapabilityResult<void>> get changes => _networkEvents.stream;
  @override
  Future<CapabilityResult<void>> permission({bool request = false}) async =>
      withoutValue(
        await invokeCapability(capabilityChannel, 'notificationPermission', {
          'request': request,
        }),
      );
  @override
  Future<CapabilityResult<void>> setOwner(String? owner) async => withoutValue(
    await invokeCapability(capabilityChannel, 'notificationOwner', {
      'owner': owner,
    }),
  );
  @override
  Future<CapabilityResult<void>> show(
    String id,
    NotificationRoute route,
  ) async => withoutValue(
    await invokeCapability(capabilityChannel, 'notificationShow', {
      'id': id,
      'owner': route.owner,
      'conversationId': route.conversationId,
      if (route.messageId != null) 'messageId': route.messageId,
      if (route.broadcastId != null) 'broadcastId': route.broadcastId,
    }),
  );
  @override
  Future<CapabilityResult<void>> cancel(String id) async => withoutValue(
    await invokeCapability(capabilityChannel, 'notificationCancel', {'id': id}),
  );
  @override
  Future<CapabilityResult<void>> cancelAll({String? owner}) async =>
      withoutValue(
        await invokeCapability(capabilityChannel, 'notificationCancelAll', {
          'owner': ?owner,
        }),
      );
  @override
  Future<CapabilityResult<SelectedFile>> pick({
    int maxBytes = 25 * 1024 * 1024,
  }) async {
    final result = await invokeCapability(capabilityChannel, 'pickFile', {
      'maxBytes': maxBytes,
    });
    if (!result.ok) {
      if (result.status == CapabilityStatus.timeout) await releaseAll();
      return CapabilityResult(result.status, reason: result.reason);
    }
    final value = result.value!;
    if (value['handle'] is! String ||
        !RegExp(r'^[0-9a-fA-F-]{36}$').hasMatch(value['handle'] as String) ||
        value['name'] is! String ||
        value['mime'] is! String ||
        value['size'] is! int ||
        (value['size'] as int) < 0 ||
        (value['size'] as int) > maxBytes) {
      return const CapabilityResult(
        CapabilityStatus.failed,
        reason: 'invalidSelection',
      );
    }
    return CapabilityResult(
      CapabilityStatus.success,
      value: SelectedFile(
        handle: value['handle'] as String,
        name: value['name'] as String,
        mime: value['mime'] as String,
        size: value['size'] as int,
      ),
    );
  }

  @override
  Future<CapabilityResult<List<int>>> read(
    SelectedFile file, {
    required int maxBytes,
  }) async {
    if (maxBytes <= 0 || file.size < 0 || file.size > maxBytes) {
      return const CapabilityResult(
        CapabilityStatus.failed,
        reason: 'fileTooLarge',
      );
    }
    final result = await invokeCapability(capabilityChannel, 'readFile', {
      'handle': file.handle,
      'maxBytes': maxBytes,
    });
    if (!result.ok) {
      return CapabilityResult(result.status, reason: result.reason);
    }
    final bytes = result.value?['bytes'];
    if (bytes is! Uint8List ||
        bytes.length != file.size ||
        bytes.length > maxBytes) {
      return const CapabilityResult(
        CapabilityStatus.failed,
        reason: 'invalidFileContent',
      );
    }
    return CapabilityResult(CapabilityStatus.success, value: bytes);
  }

  @override
  Future<CapabilityResult<List<int>>> readChunk(
    SelectedFile file, {
    required int offset,
    required int length,
  }) async {
    if (offset < 0 ||
        offset > file.size ||
        length <= 0 ||
        length > 1024 * 1024) {
      return const CapabilityResult(
        CapabilityStatus.failed,
        reason: 'invalidFileRange',
      );
    }
    final expected = min(length, file.size - offset);
    final result = await invokeCapability(capabilityChannel, 'readFileChunk', {
      'handle': file.handle,
      'offset': offset,
      'length': length,
    });
    if (!result.ok) {
      return CapabilityResult(result.status, reason: result.reason);
    }
    final bytes = result.value?['bytes'];
    if (bytes is! Uint8List || bytes.length != expected) {
      return const CapabilityResult(
        CapabilityStatus.failed,
        reason: 'invalidFileContent',
      );
    }
    return CapabilityResult(CapabilityStatus.success, value: bytes);
  }

  @override
  Future<CapabilityResult<SelectedFile>> cache({
    required String name,
    required String mime,
    required List<int> bytes,
  }) async {
    if (bytes.isEmpty || bytes.length > 25 * 1024 * 1024) {
      return const CapabilityResult(
        CapabilityStatus.failed,
        reason: 'fileTooLarge',
      );
    }
    final result = await invokeCapability(capabilityChannel, 'cacheFile', {
      'name': name,
      'mime': mime,
      'bytes': Uint8List.fromList(bytes),
    });
    if (!result.ok) {
      return CapabilityResult(result.status, reason: result.reason);
    }
    final value = result.value!;
    if (value['handle'] is! String ||
        !RegExp(r'^[0-9a-fA-F-]{36}$').hasMatch(value['handle'] as String) ||
        value['name'] is! String ||
        value['mime'] is! String ||
        value['size'] != bytes.length) {
      return const CapabilityResult(
        CapabilityStatus.failed,
        reason: 'invalidCachedFile',
      );
    }
    return CapabilityResult(
      CapabilityStatus.success,
      value: SelectedFile(
        handle: value['handle'] as String,
        name: value['name'] as String,
        mime: value['mime'] as String,
        size: value['size'] as int,
      ),
    );
  }

  @override
  Future<CapabilityResult<void>> release(SelectedFile file) async =>
      withoutValue(
        await invokeCapability(capabilityChannel, 'releaseFile', {
          'handle': file.handle,
        }),
      );
  @override
  Future<CapabilityResult<void>> releaseAll() async => withoutValue(
    await invokeCapability(capabilityChannel, 'releaseAllFiles'),
  );
  @override
  Future<CapabilityResult<void>> share(SelectedFile file) async {
    final result = await invokeCapability(capabilityChannel, 'shareFile', {
      'handle': file.handle,
    });
    if (result.status == CapabilityStatus.timeout) await releaseAll();
    return withoutValue(result);
  }

  @override
  Future<CapabilityResult<void>> openSettings() async =>
      withoutValue(await invokeCapability(capabilityChannel, 'openSettings'));
  @override
  Future<CapabilityResult<RuntimeInfo>> readRuntimeInfo() async {
    final result = await invokeCapability(capabilityChannel, 'runtimeInfo');
    if (!result.ok) {
      return CapabilityResult(result.status, reason: result.reason);
    }
    final value = result.value!;
    final fields = <String>[
      for (final key in ['appVersion', 'buildNumber', 'osName', 'osVersion'])
        if (value[key] is String) value[key] as String else '',
    ];
    final safe = RegExp(r'^[A-Za-z0-9 ._+()-]{1,64}$');
    if (fields.any((field) => !safe.hasMatch(field))) {
      return const CapabilityResult(
        CapabilityStatus.failed,
        reason: 'invalidRuntimeInfo',
      );
    }
    return CapabilityResult(
      CapabilityStatus.success,
      value: RuntimeInfo(
        appVersion: fields[0],
        buildNumber: fields[1],
        osName: fields[2],
        osVersion: fields[3],
      ),
    );
  }

  Future<void> dispose() async {
    await _subscription?.cancel();
    await _tapEvents.close();
    await _networkEvents.close();
  }
}
