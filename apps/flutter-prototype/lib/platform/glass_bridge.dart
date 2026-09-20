import 'dart:async';
import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

/// UI capabilities only. No credentials, message bodies or file paths cross it.
/// System UI can cover Flutter without creating a Flutter route, so visibility
/// is tracked separately from ModalRoute.isCurrent.
class MeshXGlassRuntime extends ChangeNotifier {
  MeshXGlassRuntime({MethodChannel? channel})
    : _channel = channel ?? const MethodChannel('com.meshx.mobile/glass');

  static final instance = MeshXGlassRuntime();
  final MethodChannel _channel;
  Future<void>? _initialization;
  bool _disposed = false;
  bool available = false;
  bool liquidGlass = false;
  bool photoPicker = false;
  bool reduceTransparency = false;
  bool reduceMotion = false;
  bool increaseContrast = false;
  String? unavailableReason;
  int _overlayDepth = 0;
  bool get obscuresConversation => _overlayDepth > 0;

  Future<void> initialize() => _initialization ??= _initialize();

  Future<void> _initialize() async {
    if (kIsWeb || defaultTargetPlatform != TargetPlatform.iOS) return;
    _channel.setMethodCallHandler((call) async {
      if (call.method == 'accessibilityChanged' && call.arguments is Map) {
        _apply(Map<Object?, Object?>.from(call.arguments as Map));
      }
    });
    try {
      final value = await _channel
          .invokeMapMethod<Object?, Object?>('capabilities', {'version': 1})
          .timeout(const Duration(seconds: 3));
      if (_disposed) return;
      if (value == null || value['version'] != 1) {
        unavailableReason = 'incompatibleNativeBridge';
      } else {
        _apply(value);
      }
    } on MissingPluginException {
      unavailableReason = 'nativeBridgeNotRegistered';
    } on PlatformException {
      unavailableReason = 'nativeBridgeUnavailable';
    } on TimeoutException {
      unavailableReason = 'nativeBridgeTimeout';
    }
    if (!_disposed) notifyListeners();
  }

  void _apply(Map<Object?, Object?> value) {
    if (_disposed || value['version'] != 1) return;
    available = value['available'] == true;
    liquidGlass = value['liquidGlass'] == true;
    photoPicker = value['photoPicker'] == true;
    reduceTransparency = value['reduceTransparency'] == true;
    reduceMotion = value['reduceMotion'] == true;
    increaseContrast = value['increaseContrast'] == true;
    notifyListeners();
  }

  /// A nested guard remains closed until *all* native presentations finish.
  Future<T> guardPresentation<T>(Future<T> Function() operation) async {
    ++_overlayDepth;
    if (!_disposed) notifyListeners();
    try {
      return await operation();
    } finally {
      --_overlayDepth;
      if (!_disposed) notifyListeners();
    }
  }

  Future<String?> attachmentMenu({required bool photos, required bool dark}) {
    return guardPresentation(() async {
      try {
        return await _channel
            .invokeMethod<String>('attachmentMenu', {
              'version': 1,
              'photos': photos,
              'dark': dark,
            })
            .timeout(const Duration(seconds: 95));
      } on TimeoutException {
        // Do not allow the native sheet to outlive the visibility guard.
        await _channel
            .invokeMethod<void>('dismissMenu')
            .timeout(const Duration(seconds: 3), onTimeout: () {});
        rethrow;
      }
    });
  }

  @override
  void dispose() {
    _disposed = true;
    _channel.setMethodCallHandler(null);
    super.dispose();
  }
}
