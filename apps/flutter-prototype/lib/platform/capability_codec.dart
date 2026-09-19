import 'dart:async';
import 'package:flutter/services.dart';
import '../core/platform_ports.dart';

const capabilityChannel = MethodChannel('com.meshx.mobile/capabilities');

CapabilityStatus decodeStatus(Object? value) => switch (value) {
  'AVAILABLE' => CapabilityStatus.available,
  'SUCCESS' => CapabilityStatus.success,
  'PERMISSION_REQUIRED' => CapabilityStatus.permissionRequired,
  'PERMISSION_DENIED' => CapabilityStatus.permissionDenied,
  'PERMISSION_PERMANENTLY_DENIED' =>
    CapabilityStatus.permissionPermanentlyDenied,
  'UNSUPPORTED' => CapabilityStatus.unsupported,
  'TEMPORARILY_UNAVAILABLE' => CapabilityStatus.temporarilyUnavailable,
  'CANCELLED' => CapabilityStatus.cancelled,
  'TIMEOUT' => CapabilityStatus.timeout,
  _ => CapabilityStatus.failed,
};

Future<CapabilityResult<Map<Object?, Object?>>> invokeCapability(
  MethodChannel channel,
  String method, [
  Object? arguments,
  Duration timeout = const Duration(seconds: 90),
]) async {
  try {
    final raw = await channel
        .invokeMapMethod<Object?, Object?>(method, arguments)
        .timeout(timeout);
    if (raw == null || raw['status'] is! String) {
      return const CapabilityResult(
        CapabilityStatus.failed,
        reason: 'invalidPlatformResponse',
      );
    }
    return CapabilityResult(
      decodeStatus(raw['status']),
      value: raw,
      reason: raw['reason'] is String ? raw['reason'] as String : '',
    );
  } on MissingPluginException {
    return const CapabilityResult(CapabilityStatus.unsupported);
  } on TimeoutException {
    return const CapabilityResult(CapabilityStatus.timeout);
  } on PlatformException {
    return const CapabilityResult(
      CapabilityStatus.failed,
      reason: 'platformOperationFailed',
    );
  }
}

CapabilityResult<void> withoutValue(CapabilityResult<Object?> result) =>
    CapabilityResult(result.status, reason: result.reason);
