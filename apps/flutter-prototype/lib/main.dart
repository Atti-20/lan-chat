import 'platform/transfer_storage.dart';
import 'platform/webrtc_peer.dart';
import 'application/direct_transfer_controller.dart';
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
    draftStore: FileChatStore(),
    credentials: NativeCredentialStore(),
  );
  final system = NativeSystemCapabilities();
  final transfers = FileTransferStore();
  final direct = DirectTransferController(
    chat: controller,
    peers: WebRtcPeerPort(),
    store: transfers,
  );
  final platform = PlatformCoordinator(
    chat: controller,
    lifecycle: FlutterLifecyclePort(),
    notifications: system,
    files: system,
    sharePort: system,
    network: system,
    settings: system,
    runtimeInfo: system,
    location: system,
    transfers: transfers,
    direct: direct,
    push: system,
    disposeAdapters: () async {
      direct.dispose();
      await system.dispose();
    },
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
