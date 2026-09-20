import 'dart:async';
import 'package:meshx_flutter_probe/core/platform_ports.dart';
export 'package:meshx_flutter_probe/core/platform_ports.dart';

class FakeDiscovery implements DiscoveryPort {
  final events = StreamController<DiscoveryUpdate>.broadcast(sync: true);
  CapabilityStatus scanStatus = CapabilityStatus.success;
  CapabilityStatus accessStatus = CapabilityStatus.available;
  int starts = 0, stops = 0;
  bool autoComplete = true;
  @override
  Stream<DiscoveryUpdate> get updates => events.stream;
  @override
  Future<CapabilityResult<void>> start({
    Duration window = const Duration(seconds: 8),
    bool requestPermission = false,
  }) async {
    starts++;
    if (autoComplete) {
      events.add(
        DiscoveryUpdate(
          starts,
          CapabilityResult(scanStatus, value: const []),
          complete: true,
        ),
      );
    }
    return CapabilityResult(scanStatus);
  }

  @override
  Future<CapabilityResult<void>> stop({bool cancelled = false}) async {
    stops++;
    return const CapabilityResult(CapabilityStatus.success);
  }

  @override
  Future<CapabilityResult<void>> prepareConnection(
    Uri origin, {
    bool requestPermission = false,
  }) async => CapabilityResult(accessStatus);
  @override
  Future<void> dispose() async {
    await events.close();
  }
}

class FakeLifecycle implements LifecyclePort {
  final events = StreamController<AppVisibility>.broadcast(sync: true);
  @override
  AppVisibility current = AppVisibility.foreground;
  @override
  Stream<AppVisibility> get changes => events.stream;
  void emit(AppVisibility value) {
    current = value;
    events.add(value);
  }

  @override
  void dispose() {
    events.close();
  }
}

class FakeSystem
    implements
        NotificationPort,
        FilePickerPort,
        SharePort,
        NetworkChangePort,
        PermissionSettingsPort,
        RuntimeInfoPort {
  final tapEvents = StreamController<NotificationRoute>.broadcast(sync: true);
  final networkEvents = StreamController<CapabilityResult<void>>.broadcast(
    sync: true,
  );
  final visible = <String>[];
  final shownRoutes = <NotificationRoute>[];
  String? owner;
  Completer<void>? showGate;
  Completer<CapabilityResult<SelectedFile>>? pickGate;
  int releaseCount = 0, pickCount = 0, shareCount = 0;
  List<int> selectedBytes = const [];
  CapabilityStatus readStatus = CapabilityStatus.success;
  String readReason = 'fileUnavailable';
  CapabilityStatus permissionStatus = CapabilityStatus.permissionRequired;
  CapabilityResult<void> settingsResult = const CapabilityResult(
    CapabilityStatus.success,
    reason: 'settingsOpenAccepted',
  );
  Completer<void>? settingsGate;
  int settingsCount = 0;
  CapabilityResult<RuntimeInfo> runtimeInfo = const CapabilityResult(
    CapabilityStatus.success,
    value: RuntimeInfo(
      appVersion: '0.1.0',
      buildNumber: '1',
      osName: 'TestOS',
      osVersion: '1.0',
    ),
  );
  @override
  Stream<NotificationRoute> get taps => tapEvents.stream;
  @override
  Stream<CapabilityResult<void>> get changes => networkEvents.stream;
  @override
  Future<CapabilityResult<void>> permission({bool request = false}) async =>
      CapabilityResult(permissionStatus);
  @override
  Future<CapabilityResult<void>> setOwner(String? value) async {
    owner = value;
    visible.clear();
    shownRoutes.clear();
    return const CapabilityResult(CapabilityStatus.success);
  }

  @override
  Future<CapabilityResult<void>> show(
    String id,
    NotificationRoute route,
  ) async {
    await showGate?.future;
    if (route.owner != owner) {
      return const CapabilityResult(CapabilityStatus.cancelled);
    }
    visible.add(id);
    shownRoutes.add(route);
    return const CapabilityResult(CapabilityStatus.success);
  }

  @override
  Future<CapabilityResult<void>> cancel(String id) async {
    visible.remove(id);
    return const CapabilityResult(CapabilityStatus.success);
  }

  @override
  Future<CapabilityResult<void>> cancelAll({String? owner}) async {
    if (owner != null && owner != this.owner) {
      return const CapabilityResult(CapabilityStatus.cancelled);
    }
    visible.clear();
    return const CapabilityResult(CapabilityStatus.success);
  }

  @override
  Future<CapabilityResult<SelectedFile>> pick({
    int maxBytes = 25 * 1024 * 1024,
  }) async {
    pickCount++;
    return pickGate == null
        ? const CapabilityResult(CapabilityStatus.cancelled)
        : pickGate!.future;
  }

  @override
  Future<CapabilityResult<void>> release(SelectedFile file) async {
    releaseCount++;
    return const CapabilityResult(CapabilityStatus.success);
  }

  @override
  Future<CapabilityResult<List<int>>> read(
    SelectedFile file, {
    required int maxBytes,
  }) async {
    if (readStatus != CapabilityStatus.success) {
      return CapabilityResult(readStatus, reason: readReason);
    }
    return selectedBytes.length <= maxBytes
        ? CapabilityResult(CapabilityStatus.success, value: selectedBytes)
        : const CapabilityResult(
            CapabilityStatus.failed,
            reason: 'fileTooLarge',
          );
  }

  @override
  Future<CapabilityResult<List<int>>> readChunk(
    SelectedFile file, {
    required int offset,
    required int length,
  }) async {
    if (readStatus != CapabilityStatus.success) {
      return CapabilityResult(readStatus, reason: 'fileUnavailable');
    }
    if (offset < 0 || offset > selectedBytes.length) {
      return const CapabilityResult(
        CapabilityStatus.failed,
        reason: 'invalidFileRange',
      );
    }
    final end = (offset + length).clamp(offset, selectedBytes.length);
    return CapabilityResult(
      CapabilityStatus.success,
      value: selectedBytes.sublist(offset, end),
    );
  }

  @override
  Future<CapabilityResult<SelectedFile>> cache({
    required String name,
    required String mime,
    required List<int> bytes,
  }) async => CapabilityResult(
    CapabilityStatus.success,
    value: SelectedFile(
      handle: '00000000-0000-0000-0000-000000000001',
      name: name,
      mime: mime,
      size: bytes.length,
    ),
  );

  @override
  Future<CapabilityResult<void>> releaseAll() async {
    releaseCount++;
    return const CapabilityResult(CapabilityStatus.success);
  }

  @override
  Future<CapabilityResult<void>> share(SelectedFile file) async {
    shareCount++;
    return const CapabilityResult(CapabilityStatus.success);
  }

  @override
  Future<CapabilityResult<void>> openSettings() async {
    settingsCount++;
    await settingsGate?.future;
    return settingsResult;
  }

  @override
  Future<CapabilityResult<RuntimeInfo>> readRuntimeInfo() async => runtimeInfo;

  Future<void> dispose() async {
    await tapEvents.close();
    await networkEvents.close();
  }
}
