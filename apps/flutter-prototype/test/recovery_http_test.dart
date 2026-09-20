import 'dart:convert';
import 'dart:io';
import 'package:flutter_test/flutter_test.dart';
import 'package:meshx_flutter_probe/data/meshx_api.dart';

void main() {
  test(
    'recovery HTTP preserves key through refresh, cursor precision and error data',
    () async {
      final server = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
      final origin = Uri.parse('http://127.0.0.1:${server.port}');
      final api = MeshXApi(origin);
      api.restoreCredentials({
        'origin': origin.toString(),
        'userId': 7,
        'nickname': 'probe',
        'token': 'old',
        'refreshCookie': 'test-refresh',
        'apiPath': '/custom/api',
        'wsPath': '/ws/chat',
        'nodeName': 'test',
      });
      final opens = <Map<String, Object?>>[];
      var refreshes = 0;
      Map<String, String>? query;
      final paths = <String>[];
      Map<String, String>? snapshotQuery;
      Object? readyBody;
      Future<void> reply(
        HttpRequest req,
        Object? data, {
        int status = 200,
        String? reason,
      }) async {
        req.response.statusCode = status;
        if (status != 204) {
          req.response.headers.contentType = ContentType.json;
          req.response.write(
            jsonEncode({'code': status, 'msg': reason, 'data': data}),
          );
        }
        await req.response.close();
      }

      server.listen((req) async {
        final path = req.uri.path;
        paths.add(path);
        if (path.endsWith('/auth/refresh')) {
          refreshes++;
          await reply(req, {'userId': 7, 'nickname': 'probe', 'token': 'new'});
        } else if (path.endsWith('/sessions')) {
          opens.add({
            'key': req.headers.value('Idempotency-Key'),
            'body': await utf8.decoder.bind(req).join(),
          });
          await reply(req, {
            'recoveryId': 'session-a',
          }, status: opens.length == 1 ? 401 : 200);
        } else if (path.endsWith('/mutations')) {
          query = req.uri.queryParameters;
          await reply(
            req,
            {
              'reason': 'CURSOR_EXPIRED',
              'floor': '9007199254740993',
              'rebuildRequired': true,
            },
            status: 409,
            reason: 'CURSOR_EXPIRED',
          );
        } else if (path.endsWith('/snapshot')) {
          snapshotQuery = req.uri.queryParameters;
          await reply(req, {'items': []});
        } else if (path.endsWith('/cut')) {
          await reply(req, {'cut': '5'});
        } else if (path.endsWith('/ready')) {
          readyBody = jsonDecode(await utf8.decoder.bind(req).join());
          await reply(req, {'ready': true});
        } else {
          await reply(req, null, status: 204);
        }
      });
      try {
        final input = <String, dynamic>{
          'protocolVersion': 1,
          'mode': 'rebuild',
        };
        final opening = api.openRecovery(
          input,
          idempotencyKey: 'same-logical-request',
        );
        input['mode'] = 'mutated-after-call';
        expect((await opening)['recoveryId'], 'session-a');
        expect(refreshes, 1);
        expect(opens.length, 2);
        expect(opens[0], opens[1]);
        expect(jsonDecode(opens[0]['body'] as String)['mode'], 'rebuild');
        await api.recoverySnapshot(
          'session-a',
          pageToken: 'opaque+token',
          limit: 200,
        );
        expect(snapshotQuery, {'pageToken': 'opaque+token', 'limit': '200'});
        expect((await api.recoveryCut('session-a'))['cut'], '5');
        await api.recoveryReady(
          'session-a',
          '9007199254740995',
          snapshotComplete: true,
        );
        expect(readyBody, {
          'appliedCursor': '9007199254740995',
          'snapshotComplete': true,
        });
        await expectLater(
          api.recoveryMutations(
            'session-a',
            '9007199254740992',
            '9007199254740995',
            limit: 200,
          ),
          throwsA(
            isA<ApiException>()
                .having((e) => e.code, 'status', 409)
                .having(
                  (e) => (e.data as Map)['reason'],
                  'reason',
                  'CURSOR_EXPIRED',
                ),
          ),
        );
        expect(query, {
          'after': '9007199254740992',
          'through': '9007199254740995',
          'limit': '200',
        });
        await api.releaseRecovery('session-a');
        await expectLater(
          api.recoveryCapabilities(),
          throwsA(isA<ApiException>()),
        );
        expect(paths.every((path) => path.startsWith('/custom/api/')), isTrue);
      } finally {
        api.close();
        await server.close(force: true);
      }
    },
  );
}
