/// Platform-neutral capability contracts. No OS paths or plugin DTOs.
enum CapabilityStatus {
  available,
  success,
  permissionRequired,
  permissionDenied,
  permissionPermanentlyDenied,
  unsupported,
  temporarilyUnavailable,
  cancelled,
  timeout,
  failed,
}

class CapabilityResult<T> {
  const CapabilityResult(this.status, {this.value, this.reason = ''});
  final CapabilityStatus status;
  final T? value;

  /// A stable public reason code, never a raw exception or local file path.
  final String reason;
  bool get ok =>
      status == CapabilityStatus.success ||
      status == CapabilityStatus.available;
}

class DiscoveredNode {
  const DiscoveredNode(this.id, this.name, this.origins);
  final String id, name;
  final List<String> origins;
}

class DiscoveryUpdate {
  const DiscoveryUpdate(this.session, this.result, {this.complete = false});
  final int session;
  final CapabilityResult<List<DiscoveredNode>> result;
  final bool complete;
}

abstract interface class DiscoveryPort {
  Stream<DiscoveryUpdate> get updates;
  Future<CapabilityResult<void>> start({
    Duration window = const Duration(seconds: 8),
    bool requestPermission = false,
  });
  Future<CapabilityResult<void>> stop({bool cancelled = false});
  Future<CapabilityResult<void>> prepareConnection(
    Uri origin, {
    bool requestPermission = false,
  });
  Future<void> dispose();
}

/// Explicit unsupported dependency for non-mobile hosts; production injects adapters.
class UnsupportedDiscovery implements DiscoveryPort {
  const UnsupportedDiscovery();
  @override
  Stream<DiscoveryUpdate> get updates => const Stream.empty();
  @override
  Future<CapabilityResult<void>> start({
    Duration window = const Duration(seconds: 8),
    bool requestPermission = false,
  }) async => const CapabilityResult(CapabilityStatus.unsupported);
  @override
  Future<CapabilityResult<void>> stop({bool cancelled = false}) async =>
      const CapabilityResult(CapabilityStatus.unsupported);
  @override
  Future<CapabilityResult<void>> prepareConnection(
    Uri origin, {
    bool requestPermission = false,
  }) async => const CapabilityResult(CapabilityStatus.unsupported);
  @override
  Future<void> dispose() async {}
}

String capabilityMessage(CapabilityStatus status) => switch (status) {
  CapabilityStatus.available => '系统能力可用',
  CapabilityStatus.success => '操作完成',
  CapabilityStatus.permissionRequired => '需要授权，请点击相应功能后允许权限',
  CapabilityStatus.permissionDenied => '未获得权限，可重试或到系统设置中恢复',
  CapabilityStatus.permissionPermanentlyDenied => '权限已被关闭，请到系统设置中恢复',
  CapabilityStatus.unsupported => '此平台不支持该能力',
  CapabilityStatus.temporarilyUnavailable => '系统或网络暂不可用，请恢复后重试',
  CapabilityStatus.cancelled => '操作已取消',
  CapabilityStatus.timeout => '操作超时，请重试',
  CapabilityStatus.failed => '操作失败，请重试',
};

enum AppVisibility { foreground, inactive, background, detached }

abstract interface class LifecyclePort {
  Stream<AppVisibility> get changes;
  AppVisibility get current;
  void dispose();
}

class NotificationRoute {
  const NotificationRoute(
    this.owner,
    this.conversationId, {
    this.broadcastId,
    this.messageId,
  });
  final String? messageId;
  final String owner, conversationId;
  final int? broadcastId;
}

abstract interface class NotificationPort {
  Stream<NotificationRoute> get taps;
  Future<CapabilityResult<void>> permission({bool request = false});

  /// Changes native ownership too, so an old pending show cannot survive logout.
  Future<CapabilityResult<void>> setOwner(String? owner);

  /// The platform supplies generic, privacy-preserving text, never message content.
  Future<CapabilityResult<void>> show(String id, NotificationRoute route);
  Future<CapabilityResult<void>> cancel(String id);
  Future<CapabilityResult<void>> cancelAll({String? owner});
}

class SelectedFile {
  const SelectedFile({
    required this.handle,
    required this.name,
    required this.mime,
    required this.size,
  });

  /// Opaque app-owned copy identifier, not a filesystem path or platform URI.
  final String handle, name, mime;
  final int size;
}

abstract interface class FilePickerPort {
  Future<CapabilityResult<SelectedFile>> pick({
    int maxBytes = 25 * 1024 * 1024,
  });

  /// Reads only a previously selected app-owned copy and enforces [maxBytes].
  Future<CapabilityResult<List<int>>> read(
    SelectedFile file, {
    required int maxBytes,
  });

  /// Reads a bounded slice of the opaque copy; never accepts a path or URI.
  Future<CapabilityResult<List<int>>> readChunk(
    SelectedFile file, {
    required int offset,
    required int length,
  });

  /// Stores verified downloaded bytes as another opaque app-owned copy.
  Future<CapabilityResult<SelectedFile>> cache({
    required String name,
    required String mime,
    required List<int> bytes,
  });
  Future<CapabilityResult<void>> release(SelectedFile file);
  Future<CapabilityResult<void>> releaseAll();
}

abstract interface class SharePort {
  /// SUCCESS means the OS accepted/presented the share UI, not remote delivery.
  Future<CapabilityResult<void>> share(SelectedFile file);
}

abstract interface class NetworkChangePort {
  Stream<CapabilityResult<void>> get changes;
}

abstract interface class PermissionSettingsPort {
  Future<CapabilityResult<void>> openSettings();
}

class RuntimeInfo {
  const RuntimeInfo({
    required this.appVersion,
    required this.buildNumber,
    required this.osName,
    required this.osVersion,
  });

  final String appVersion, buildNumber, osName, osVersion;
}

abstract interface class RuntimeInfoPort {
  /// Returns only public version fields. Device names, paths and identifiers are
  /// intentionally outside this contract.
  Future<CapabilityResult<RuntimeInfo>> readRuntimeInfo();
}

/// New live events only; synchronization and already-rendered messages stay quiet.
class NotificationPolicy {
  final Set<String> _seen = {};
  String? _owner;
  void reset(String? owner) {
    if (_owner != owner) {
      _owner = owner;
      _seen.clear();
    }
  }

  bool shouldShow({
    required String owner,
    required String id,
    required bool live,
    required bool ownMessage,
    required bool alreadyStored,
    required bool viewingConversation,
  }) {
    reset(owner);
    if (id.isEmpty || _seen.contains(id)) return false;
    _seen.add(id);
    if (_seen.length > 512) _seen.remove(_seen.first);
    return live && !ownMessage && !alreadyStored && !viewingConversation;
  }
}
