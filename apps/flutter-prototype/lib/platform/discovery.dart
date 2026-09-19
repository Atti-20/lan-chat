import 'dart:async';
import 'dart:io';
import 'package:flutter/services.dart';
import '../core/platform_ports.dart';
import 'capability_codec.dart';

export '../core/platform_ports.dart';

/// Native scan ownership is echoed with every event; stale scans cannot publish.
class NativeNodeDiscovery implements DiscoveryPort {
  static const channel = MethodChannel('com.meshx.prototype/discovery');
  static const events = EventChannel('com.meshx.prototype/discovery/events');
  final _updates = StreamController<DiscoveryUpdate>.broadcast(sync: true);
  StreamSubscription<dynamic>? _subscription;
  int _session = 0;
  bool _disposed = false, _running = false;
  Timer? _deadline;
  @override
  Stream<DiscoveryUpdate> get updates => _updates.stream;

  void _event(dynamic value) {
    if (_disposed ||
        !_running ||
        value is! Map ||
        value['session'] != _session) {
      return;
    }
    if (value['nodes'] != null && value['nodes'] is! List) {
      _event({
        'session': _session,
        'status': 'FAILED',
        'reason': 'malformedSnapshot',
        'nodes': <Object?>[],
        'complete': true,
      });
      unawaited(invokeCapability(channel, 'stop', {'session': _session}));
      return;
    }
    final status = decodeStatus(value['status']);
    // Every native update is a full bounded snapshot; removals replace the list.
    final byIdentity = <String, (String, Set<String>)>{};
    final originOwners = <String, String>{};
    for (final raw in (value['nodes'] as List? ?? const []).take(128)) {
      if (raw is! Map) continue;
      final uri = Uri.tryParse(raw['origin']?.toString() ?? '');
      if (uri == null ||
          !{'http', 'https'}.contains(uri.scheme) ||
          uri.host.isEmpty ||
          uri.userInfo.isNotEmpty ||
          uri.hasQuery ||
          uri.hasFragment ||
          (uri.path.isNotEmpty && uri.path != '/')) {
        continue;
      }
      final origin = uri.origin;
      final id = raw['id']?.toString() ?? origin;
      if (id.isEmpty || id.length > 160) continue;
      final owner = originOwners[origin] ?? id;
      originOwners[origin] = owner;
      final name = (raw['name']?.toString() ?? 'MeshX 节点');
      final entry = byIdentity.putIfAbsent(
        owner,
        () => (name.substring(0, name.length.clamp(0, 80)), <String>{}),
      );
      entry.$2.add(origin);
    }
    final complete = value['complete'] == true;
    if (complete) {
      _running = false;
      _deadline?.cancel();
    }
    _updates.add(
      DiscoveryUpdate(
        _session,
        CapabilityResult(
          status,
          value: byIdentity.entries
              .map(
                (e) => DiscoveredNode(
                  e.key,
                  e.value.$1,
                  List.unmodifiable(e.value.$2),
                ),
              )
              .toList(growable: false),
          reason: value['reason'] is String ? value['reason'] as String : '',
        ),
        complete: complete,
      ),
    );
  }

  @override
  Future<CapabilityResult<void>> start({
    Duration window = const Duration(seconds: 8),
    bool requestPermission = false,
  }) async {
    if (_disposed) {
      return const CapabilityResult(
        CapabilityStatus.cancelled,
        reason: 'disposed',
      );
    }
    await stop();
    if (_disposed) {
      return const CapabilityResult(
        CapabilityStatus.cancelled,
        reason: 'disposed',
      );
    }
    final session = ++_session;
    _running = true;
    _subscription ??= events.receiveBroadcastStream().listen(
      _event,
      onError: (Object error) {
        if (!_disposed && _running) {
          _event({
            'session': _session,
            'status': error is MissingPluginException
                ? 'UNSUPPORTED'
                : 'FAILED',
            'complete': true,
          });
        }
      },
    );
    final bounded = window.inMilliseconds.clamp(1000, 30000);
    final result = await invokeCapability(channel, 'start', {
      'session': session,
      'windowMs': bounded,
      'requestPermission': requestPermission,
    });
    if (_disposed || session != _session) {
      return const CapabilityResult(CapabilityStatus.cancelled);
    }
    if (!result.ok) {
      _running = false;
      _updates.add(
        DiscoveryUpdate(
          session,
          CapabilityResult(
            result.status,
            value: const [],
            reason: result.reason,
          ),
          complete: true,
        ),
      );
    } else if (_running) {
      _deadline = Timer(Duration(milliseconds: bounded + 3000), () async {
        if (!_disposed && _running && session == _session) {
          _event({'session': session, 'status': 'TIMEOUT', 'complete': true});
          await invokeCapability(channel, 'stop', {'session': session});
        }
      });
    }
    return withoutValue(result);
  }

  @override
  Future<CapabilityResult<void>> stop({bool cancelled = false}) async {
    final session = _session;
    final wasRunning = _running;
    _running = false;
    _deadline?.cancel();
    ++_session;
    if (!_disposed && wasRunning) {
      _updates.add(
        DiscoveryUpdate(
          session,
          CapabilityResult(
            cancelled ? CapabilityStatus.cancelled : CapabilityStatus.success,
            value: const [],
          ),
          complete: true,
        ),
      );
    }
    if (!wasRunning) return const CapabilityResult(CapabilityStatus.success);
    return withoutValue(
      await invokeCapability(channel, 'stop', {
        'session': session,
        'cancelled': cancelled,
      }, const Duration(seconds: 5)),
    );
  }

  @override
  Future<CapabilityResult<void>> prepareConnection(
    Uri origin, {
    bool requestPermission = false,
  }) async {
    if (_disposed) {
      return const CapabilityResult(
        CapabilityStatus.cancelled,
        reason: 'disposed',
      );
    }
    if (!Platform.isAndroid) {
      return const CapabilityResult(CapabilityStatus.available);
    }
    bool local(InternetAddress address) {
      final bytes = address.rawAddress;
      if (address.isLoopback || address.isLinkLocal) return true;
      if (address.type == InternetAddressType.IPv6) {
        return (bytes[0] & 0xfe) == 0xfc;
      }
      return bytes[0] == 10 ||
          (bytes[0] == 192 && bytes[1] == 168) ||
          (bytes[0] == 172 && bytes[1] >= 16 && bytes[1] <= 31);
    }

    try {
      final literal = InternetAddress.tryParse(origin.host);
      final needsPermission =
          origin.host == 'localhost' ||
          origin.host.endsWith('.local') ||
          (literal != null
              ? local(literal)
              : (await InternetAddress.lookup(
                  origin.host,
                ).timeout(const Duration(seconds: 8))).any(local));
      if (!needsPermission) {
        return const CapabilityResult(CapabilityStatus.available);
      }
      return withoutValue(
        await invokeCapability(channel, 'prepareConnection', {
          'requestPermission': requestPermission,
        }),
      );
    } on TimeoutException {
      return const CapabilityResult(CapabilityStatus.timeout);
    } on SocketException {
      return const CapabilityResult(
        CapabilityStatus.temporarilyUnavailable,
        reason: 'networkUnavailable',
      );
    }
  }

  @override
  Future<void> dispose() async {
    if (_disposed) return;
    _disposed = true;
    await stop(cancelled: true);
    await _subscription?.cancel();
    await _updates.close();
  }
}
