import 'dart:convert';
import 'dart:io';
import 'package:flutter_test/flutter_test.dart';
import 'package:meshx_flutter_probe/data/rest_contract.g.dart';
import 'package:meshx_flutter_probe/data/ws_contract.g.dart';
import 'package:meshx_flutter_probe/data/wire_validation.dart';
import 'package:meshx_flutter_probe/data/models.dart';

void main() {
  final vectors =
      jsonDecode(
            File('../../contracts/fixtures/core-v1.json').readAsStringSync(),
          )
          as Map;
  final frames = vectors['frames'] as List;
  Map<String, dynamic> frame(String id) => Map<String, dynamic>.from(
    frames.singleWhere((v) => v['id'] == id)['frame'] as Map,
  );

  test(
    'generated Dart envelope parser runs the same positive and negative vectors',
    () {
      for (final v in frames) {
        expect(
          () => WsFrame.fromJson(Map<String, dynamic>.from(v['frame'] as Map)),
          v['baseValid'] == true ? returnsNormally : throwsFormatException,
          reason: '${v['id']}: base envelope',
        );
        void parse() => WsFrame.fromJson(
          Map<String, dynamic>.from(v['frame'] as Map),
        ).validateKnownEvent(v['direction'] as String);
        if (v['valid'] == true) {
          expect(parse, returnsNormally, reason: v['id'] as String);
        } else {
          expect(parse, throwsFormatException, reason: v['id'] as String);
        }
      }
      final unknown = WsFrame.fromJson(frame('unknown-server'));
      expect(unknown.isKnown('server'), isFalse);
      expect(unknown.toJson(), frame('unknown-server'));
      final sync = SyncResponsePayload.fromJson(
        WsFrame.fromJson(frame('reconnect-sync-response')).payload,
      );
      expect(sync.messages.single.sequence, 42);
      expect(sync.latestPositions['private:7:8'], 42);
    },
  );

  test(
    'generated Dart REST validation preserves required, null and missing semantics',
    () {
      for (final v in vectors['rest'] as List) {
        void parse() => validateWire(
          v['value'],
          Map<String, dynamic>.from(restModelSchemas[v['model']] as Map),
          restModelSchemas,
        );
        expect(
          parse,
          v['valid'] == true ? returnsNormally : throwsFormatException,
          reason: v['id'] as String,
        );
      }
      final omitted = RestResultLoginVO.fromJson({
        'code': 200,
        'msg': 'success',
      });
      final explicit = RestResultLoginVO.fromJson({
        'code': 200,
        'msg': 'success',
        'data': null,
      });
      expect(omitted.toJson().containsKey('data'), isFalse);
      expect(explicit.toJson().containsKey('data'), isTrue);
      expect(
        RestLoginDTO(username: 'alice', password: 'fixture-password').toJson(),
        {'username': 'alice', 'password': 'fixture-password'},
      );
      expect(
        RestOperations.login.atBase('https://node.example/custom/api/'),
        'https://node.example/custom/api/auth/login',
      );
      expect(
        restDeviceTypes,
        unorderedEquals(['web', 'desktop', 'android', 'ios']),
      );
    },
  );

  test(
    'current Dart message adapter merges duplicate delivery and reads REST integer flags',
    () {
      final message = ChatMessage.fromJson(
        frame('event-after-ack')['payload'] as Map<String, dynamic>,
      );
      final merged = mergeMessages([message], [message]);
      expect(merged.length, 1);
      expect(merged.single.sequence, 42);
      final rest = (vectors['rest'] as List).singleWhere(
        (v) => v['id'] == 'rest-message-integer-flags',
      );
      final recalled = ChatMessage.fromJson(
        Map<String, dynamic>.from(rest['value'] as Map),
      );
      expect(recalled.recalled, isTrue);
      expect(recalled.contentType, 'image');
    },
  );
}
