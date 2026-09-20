import 'dart:convert';
import 'dart:io';
import 'package:flutter_test/flutter_test.dart';
import 'package:meshx_flutter_probe/data/meshx_api.dart';

void main() {
  test(
    'terminal HTTP 401 refresh is bounded and a second node receives no old credentials',
    () async {
      final first = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
      final second = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
      final old = MeshXApi(Uri.parse('http://127.0.0.1:${first.port}'));
      final next = MeshXApi(Uri.parse('http://127.0.0.1:${second.port}'));
      var refreshes = 0, protectedRequests = 0, nodeRequests = 0;
      final leaked = <String>[];
      first.listen((request) async {
        final refresh = request.uri.path.endsWith('/auth/refresh');
        if (refresh) {
          refreshes++;
        } else {
          protectedRequests++;
        }
        request.response.statusCode = 401;
        request.response.write(jsonEncode({'code': 401, 'msg': 'expired'}));
        await request.response.close();
      });
      second.listen((request) async {
        nodeRequests++;
        for (final header in ['authorization', 'cookie']) {
          final value = request.headers.value(header);
          if (value != null) leaked.add(value);
        }
        request.response.write(
          jsonEncode({
            'code': 200,
            'data': {'protocolVersion': 1},
          }),
        );
        await request.response.close();
      });
      try {
        old.restoreCredentials({
          'origin': old.origin.toString(),
          'userId': 1,
          'nickname': 'test',
          'token': 'test-access',
          'refreshCookie': 'test-refresh',
          'apiPath': '/api/v1',
          'wsPath': '/ws/chat',
          'nodeName': 'test',
        });
        await expectLater(
          old.validateCurrentUser(),
          throwsA(isA<ApiException>()),
        );
        expect(refreshes, 1);
        expect(protectedRequests, 1);
        final oldCredentials = old.credentials();
        old.close();
        expect(
          () => next.restoreCredentials(oldCredentials),
          throwsFormatException,
        );
        await next.handshake();
        expect(nodeRequests, 1);
        expect(leaked, isEmpty);
      } finally {
        old.close();
        next.close();
        await first.close(force: true);
        await second.close(force: true);
      }
    },
  );
}
