import 'dart:convert';
import 'dart:io';
import 'package:flutter_test/flutter_test.dart';
import 'package:meshx_flutter_probe/data/ws_contract.g.dart';
import 'package:meshx_flutter_probe/ui/tokens.g.dart';

void main() {
  test('generated Dart models round-trip shared ACK and sync fixtures', () {
    final fixtures =
        jsonDecode(
              File(
                '../../contracts/websocket/fixtures/core.json',
              ).readAsStringSync(),
            )
            as List;
    Map<String, dynamic> payload(String name) => Map<String, dynamic>.from(
      (fixtures.singleWhere((f) => f['name'] == name)['frame']['payload'])
          as Map,
    );
    final ack = ChatAckPayload.fromJson(payload('duplicate-ack'));
    expect(ack.duplicated, isTrue);
    expect(ack.sequence, 42);
    expect(ack.mentionUserIds, isNull);
    expect(ack.toJson(), payload('duplicate-ack'));
    final sync = SyncResponsePayload.fromJson(payload('sync-empty-denied'));
    expect(sync.deniedConversationIds, ['group:99']);
    expect(sync.toJson(), payload('sync-empty-denied'));
    expect(
      () => ChatAckPayload.fromJson({
        ...payload('duplicate-ack'),
        'sequence': '42',
      }),
      throwsA(isA<TypeError>()),
    );
  });

  test(
    'native token conversion preserves dimensions and semantic contrast colors',
    () {
      expect(meshXSizes['font-body'], 14);
      expect(meshXSizes['control-height'], 44);
      expect(meshXLight['action-bg'], meshXDark['action-bg']);
      expect(meshXLight['focus-ring']!.toARGB32(), 0x8c006fe8);
      expect(meshXDurations['duration-fast']!.inMilliseconds, 160);
    },
  );
}
