// Dedicated local-LAN acceptance entrypoint. Never use for candidate artifacts.
import 'package:flutter/foundation.dart';
import 'package:meshx_flutter_probe/main.dart' as application;

void main() {
  if (!kProfileMode) {
    throw StateError(
      'LAN acceptance preview requires an explicit Profile build',
    );
  }
  // parseNodeOrigin still restricts HTTP to local addresses. Production main
  // keeps its original kDebugMode policy; no certificate checks are bypassed.
  application.runMeshX(allowLocalHttp: true);
}
