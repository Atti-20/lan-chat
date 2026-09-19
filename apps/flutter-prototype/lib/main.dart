import 'package:flutter/material.dart';
import 'package:flutter/foundation.dart';
import 'chat_controller.dart';
import 'ui/app.dart';
import 'platform/storage.dart';
import 'platform/discovery.dart';
import 'platform/system_capabilities.dart';
import 'application/platform_coordinator.dart';

void main() => runMeshX();

void runMeshX({bool allowLocalHttp = kDebugMode}) {
  WidgetsFlutterBinding.ensureInitialized();
  final controller = ChatController(
    discovery: NativeNodeDiscovery(),
    allowLocalHttp: allowLocalHttp,
    store: FileChatStore(),
    credentials: NativeCredentialStore(),
  );
  final system = NativeSystemCapabilities();
  final platform = PlatformCoordinator(
    chat: controller,
    lifecycle: FlutterLifecyclePort(),
    notifications: system,
    files: system,
    sharePort: system,
    network: system,
    settings: system,
    runtimeInfo: system,
    disposeAdapters: system.dispose,
  );
  platform.start();
  system.start();
  runApp(
    MeshXApp(
      controller: controller,
      platform: platform,
      preferences: FilePreferenceStore(),
    ),
  );
  controller.restore();
}
