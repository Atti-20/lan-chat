import 'package:meshx_flutter_probe/platform/discovery.dart';
import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:integration_test/integration_test.dart';
import 'package:meshx_flutter_probe/chat_controller.dart';
import 'package:meshx_flutter_probe/core/store.dart';
import 'package:meshx_flutter_probe/data/models.dart';
import 'package:meshx_flutter_probe/platform/storage.dart';
import 'package:meshx_flutter_probe/ui/app.dart';

class CountingCredentials extends NativeCredentialStore {
  int writes = 0;
  @override
  Future<void> write(Json value) async {
    await super.write(value);
    writes++;
  }
}

void main() {
  final binding = IntegrationTestWidgetsFlutterBinding.ensureInitialized();
  testWidgets(
    'A05 real Web/native interop, durable outbox and isolation',
    (tester) async {
      const origin = String.fromEnvironment('MESHX_NODE');
      const stage = String.fromEnvironment(
        'MESHX_STAGE',
        defaultValue: 'prepare',
      );
      const run = String.fromEnvironment('MESHX_RUN_ID');
      expect(origin, isNotEmpty);
      expect(run, isNotEmpty);
      final platform = Platform.isIOS ? 'ios' : 'android';
      final metrics = <String, dynamic>{
        'platform': platform,
        'stage': stage,
        'run': run,
        'environment':
            'native debug simulator; real Spring backend through fault proxy',
      };
      binding.reportData = {'metrics': metrics};
      final credentials = CountingCredentials(), storage = FileChatStore();
      final c = ChatController(
        discovery: NativeNodeDiscovery(),
        store: storage,
        credentials: credentials,
      );
      final bridge = Uri.parse(origin).replace(port: 18386);
      final groupId = 'group:${const String.fromEnvironment('PROBE_GROUP_ID')}';
      final restartText = 'A05 restart $run $platform';

      Future<Json> control(String path, [Json? body]) async {
        final client = HttpClient()
          ..connectionTimeout = const Duration(seconds: 10);
        try {
          final request = await client.openUrl(
            body == null ? 'GET' : 'POST',
            bridge.resolve(path),
          );
          if (body != null) {
            final bytes = utf8.encode(jsonEncode(body));
            request.contentLength = bytes.length;
            request.headers.contentType = ContentType.json;
            request.add(bytes);
          }
          final response = await request.close().timeout(
            const Duration(seconds: 10),
          );
          expect(response.statusCode, 200);
          return jsonDecode(await response.transform(utf8.decoder).join())
              as Json;
        } finally {
          client.close(force: true);
        }
      }

      Future<void> waitFor(
        bool Function() predicate,
        String label, {
        int seconds = 60,
      }) async {
        final end = DateTime.now().add(Duration(seconds: seconds));
        while (!predicate() && DateTime.now().isBefore(end)) {
          await tester.pump(const Duration(milliseconds: 100));
        }
        expect(predicate(), isTrue, reason: '$label; error=${c.error}');
      }

      Future<void> browser(String action, String text) async {
        final command = await control('/command', {
          'action': action,
          'text': text,
        });
        final end = DateTime.now().add(const Duration(seconds: 45));
        Json result = {};
        while (result.isEmpty && DateTime.now().isBefore(end)) {
          await tester.pump(const Duration(milliseconds: 100));
          result = await control('/result?id=${command['id']}');
        }
        expect(
          result['ok'],
          isTrue,
          reason: 'actual Web $action: ${result['error']}',
        );
      }

      Future<void> screenshot(String name) async {
        await tester.pump(const Duration(milliseconds: 300));
        final endpoint = Uri.parse(
          origin,
        ).replace(port: 18382, path: '/capture');
        final result = await control(endpoint.toString(), {
          'platform': platform,
          'name': '$stage-$name-$run',
        });
        expect(result['captured'], isTrue);
      }

      Future<void> compose(String text) async {
        await tester.tap(find.byKey(const Key('composer')));
        await tester.enterText(find.byKey(const Key('composer')), text);
        await tester.pump(const Duration(milliseconds: 300));
        expect(
          tester.getRect(find.byKey(const Key('send-message'))).bottom,
          lessThanOrEqualTo(
            tester.view.physicalSize.height / tester.view.devicePixelRatio,
          ),
        );
        await tester.tap(find.byKey(const Key('send-message')));
        await tester.pump();
      }

      Future<void> selectGroup() async {
        final group = c.conversations.firstWhere((item) => item.id == groupId);
        await c.select(group);
        await tester.pumpAndSettle();
      }

      await tester.pumpWidget(MeshXApp(controller: c));
      await tester.pumpAndSettle();
      await c.discovery.prepareConnection(Uri.parse(origin));
      await control('/network', {'online': true});

      if (stage == 'prepare') {
        // Only the independent test application's credentials are reset.
        await credentials.clear();
        await tester.enterText(find.byKey(const Key('node-origin')), origin);
        await tester.enterText(
          find.byKey(const Key('username')),
          const String.fromEnvironment('PROBE_USERNAME'),
        );
        await tester.enterText(
          find.byKey(const Key('password')),
          const String.fromEnvironment('PROBE_PASSWORD'),
        );
        FocusManager.instance.primaryFocus?.unfocus();
        await tester.pumpAndSettle();
        await tester.ensureVisible(find.byKey(const Key('login')));
        await tester.pumpAndSettle();
        await tester.tap(find.byKey(const Key('login')));
        await waitFor(
          () => c.online && c.conversations.isNotEmpty,
          'native login, AUTH and sync',
        );
        expect(c.positions[groupId], greaterThanOrEqualTo(220));
        metrics['authAndPagedSync'] = true;
        await selectGroup();
        await screenshot('chat-light');

        final inbound = 'Web to Flutter $run $platform';
        await browser('send', inbound);
        await waitFor(
          () => c.messages.any((m) => m.content == inbound),
          'actual Web -> Flutter',
        );
        metrics['webToFlutter'] = true;
        final outbound = 'Flutter to Web $run $platform';
        await compose(outbound);
        await waitFor(
          () => c.messages.any(
            (m) => m.content == outbound && m.delivery == Delivery.sent,
          ),
          'server ACK',
        );
        await browser('expect', outbound);
        metrics['flutterToWeb'] = true;
        await screenshot('keyboard-and-delivery');

        await control('/network', {'online': false});
        await waitFor(() => !c.online, 'actual TCP link closed');
        final queued = 'Offline queue $run $platform';
        await compose(queued);
        final queuedMessage = c.messages.singleWhere(
          (m) => m.content == queued,
        );
        expect(queuedMessage.delivery, Delivery.queued);
        final missed = 'Web during mobile outage $run $platform';
        await browser('send', missed);
        expect(c.messages.any((m) => m.content == missed), isFalse);
        await control('/network', {'online': true});
        await waitFor(
          () =>
              c.online &&
              c.messages.any(
                (m) =>
                    m.clientMsgId == queuedMessage.clientMsgId &&
                    m.delivery == Delivery.sent,
              ),
          'automatic retry after real network recovery',
        );
        expect(
          c.messages.where((m) => m.clientMsgId == queuedMessage.clientMsgId),
          hasLength(1),
        );
        await waitFor(
          () => c.messages.any((m) => m.content == missed),
          'WS gap fill',
        );
        await browser('expect', queued);
        final history = await c.api!.history(groupId);
        expect(
          history.where((m) => m.clientMsgId == queuedMessage.clientMsgId),
          hasLength(1),
        );
        metrics['realNetworkQueueNoLossNoDuplicate'] = true;

        // This fixture issues real signed access tokens with a 45s TTL.
        final before = c.session!.token, writes = credentials.writes;
        final claims =
            jsonDecode(
                  utf8.decode(
                    base64Url.decode(base64Url.normalize(before.split('.')[1])),
                  ),
                )
                as Json;
        final expiration = DateTime.fromMillisecondsSinceEpoch(
          (claims['exp'] as int) * 1000 + 1100,
        );
        expect(expiration.difference(DateTime.now()).inSeconds, lessThan(60));
        while (c.session?.token == before &&
            DateTime.now().isBefore(expiration)) {
          await tester.pump(const Duration(milliseconds: 100));
        }
        await Future.wait(
          List.generate(3, (_) => c.api!.validateCurrentUser()),
        );
        expect(c.session!.token, isNot(before));
        expect(credentials.writes - writes, 1);
        expect((await credentials.read())!['token'], c.session!.token);
        metrics['realExpiredTokenSingleFlightRefresh'] = true;

        FocusManager.instance.primaryFocus?.unfocus();
        tester.platformDispatcher.textScaleFactorTestValue = 2;
        addTearDown(tester.platformDispatcher.clearTextScaleFactorTestValue);
        await tester.tap(find.byKey(const Key('theme-toggle')));
        final longText =
            'Long text $run $platform\n${List.filled(12, '中文与 English 长消息输入仍可发送。').join()}';
        await compose(longText);
        await screenshot('large-font-dark-keyboard');
        await waitFor(
          () => c.messages.any(
            (m) => m.content == longText && m.delivery == Delivery.sent,
          ),
          'long text ACK at 2x text scale',
        );
        expect(tester.takeException(), isNull);
        metrics['longTextLargeFontKeyboard'] = true;

        await control('/network', {'online': false});
        await waitFor(() => !c.online, 'restart preparation offline');
        await compose(restartText);
        final pending = c.messages.singleWhere((m) => m.content == restartText);
        final saved = await storage.load(
          accountScope(c.api!.origin, c.session!.userId),
        );
        expect(
          (saved!['messages'][groupId] as List).any(
            (m) =>
                m['clientMsgId'] == pending.clientMsgId &&
                m['delivery'] == 'queued',
          ),
          isTrue,
        );
        metrics['restartClientMsgId'] = pending.clientMsgId;
        metrics['restartPositions'] = Map.from(c.positions);
        metrics['durableRestartPrepared'] = true;
        await screenshot('durable-offline');
        await c.pause();
      } else {
        expect(stage, 'resume');
        final savedCredentials = await credentials.read();
        expect(
          savedCredentials,
          isNotNull,
          reason: 'native secure credential survived process death',
        );
        final owner = accountScope(
          Uri.parse(origin),
          savedCredentials!['userId'] as int,
        );
        final saved = await storage.load(owner);
        final pending =
            (saved!['messages'][groupId] as List).singleWhere(
                  (m) => m['content'] == restartText,
                )
                as Json;
        final clientId = pending['clientMsgId'];
        expect(pending['delivery'], 'queued');
        expect((saved['positions'] as Map)[groupId], greaterThan(0));
        unawaited(c.restore());
        await waitFor(
          () => c.online,
          'secure credential and account snapshot restore',
        );
        await selectGroup();
        await waitFor(
          () => c.messages.any(
            (m) => m.clientMsgId == clientId && m.delivery == Delivery.sent,
          ),
          'same outbox ID committed after process restart',
        );
        await browser('expect', restartText);
        final history = await c.api!.history(groupId);
        expect(history.where((m) => m.clientMsgId == clientId), hasLength(1));
        metrics['restartClientMsgId'] = clientId;
        metrics['processRestartRestoreAndSingleCommit'] = true;
        await screenshot('restart-restored');

        final firstUser = c.session!.userId;
        await c.logout();
        expect(await credentials.read(), isNull);
        expect(c.messages, isEmpty);
        expect(c.positions, isEmpty);
        await c.login(
          origin,
          const String.fromEnvironment('PROBE_PEER_USERNAME'),
          const String.fromEnvironment('PROBE_PEER_PASSWORD'),
        );
        await waitFor(() => c.online, 'different account real login');
        expect(c.session!.userId, isNot(firstUser));
        expect((await credentials.read())!['userId'], c.session!.userId);
        metrics['logoutAndAccountSwitch'] = true;
        await c.logout();
        expect(await credentials.read(), isNull);
        final nextOrigin = Uri.parse(origin).replace(port: 18385).toString();
        await c.login(
          nextOrigin,
          const String.fromEnvironment('PROBE_USERNAME'),
          const String.fromEnvironment('PROBE_PASSWORD'),
        );
        await waitFor(() => c.online, 'explicit alternate server origin login');
        expect(c.api!.origin.toString(), nextOrigin);
        expect((await credentials.read())!['origin'], nextOrigin);
        metrics['explicitServerOriginSwitch'] = true;
        await c.logout();
        expect(await credentials.read(), isNull);
        metrics['credentialsCleared'] = true;
      }
      metrics['passed'] = true;
      await tester.pumpWidget(const SizedBox.shrink());
    },
    timeout: const Timeout(Duration(minutes: 8)),
  );
}
