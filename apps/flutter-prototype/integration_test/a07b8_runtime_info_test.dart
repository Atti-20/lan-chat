import 'package:flutter_test/flutter_test.dart';
import 'package:integration_test/integration_test.dart';
import 'package:meshx_flutter_probe/platform/system_capabilities.dart';

void main() {
  final binding = IntegrationTestWidgetsFlutterBinding.ensureInitialized();

  testWidgets('physical runtime exposes only bounded public version fields', (
    tester,
  ) async {
    final system = NativeSystemCapabilities();
    final result = await system.readRuntimeInfo();
    expect(result.ok, isTrue, reason: result.reason);
    final info = result.value!;
    final fields = [
      info.appVersion,
      info.buildNumber,
      info.osName,
      info.osVersion,
    ];
    expect(
      fields.every((field) => field.isNotEmpty && field.length <= 64),
      isTrue,
    );
    expect(fields.join('\n'), isNot(contains('/private/')));
    expect(fields.join('\n').toLowerCase(), isNot(contains('token')));
    binding.reportData = {
      'metrics': {
        'platform': 'ios-runtime-info',
        'appVersion': info.appVersion,
        'buildNumber': info.buildNumber,
        'osName': info.osName,
        'osVersion': info.osVersion,
        'fields': fields.length,
      },
    };
    await system.dispose();
  });
}
