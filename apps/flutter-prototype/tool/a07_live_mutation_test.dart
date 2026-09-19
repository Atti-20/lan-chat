// Explicit opt-in integration test. Run against a07_mutation_probe.mjs's owned fixture.
// No fake HTTP/WS; discovery and credential storage are test ports (not device evidence).
import 'dart:convert';
import 'dart:io';
import 'package:flutter_test/flutter_test.dart';
import 'package:meshx_flutter_probe/chat_controller.dart';
import 'package:meshx_flutter_probe/core/models.dart';
import 'package:meshx_flutter_probe/core/store.dart';
import 'package:meshx_flutter_probe/data/meshx_api.dart';
import 'package:meshx_flutter_probe/platform/storage.dart';
import '../test/mobile_reliability_test.dart' show TestCredentials;
import '../test/platform_fakes.dart';

void main() {
  test(
    'real Spring -> Dart disk cache -> offline mutation -> reconnect -> restart characterizes R02',
    () async {
      final directory = Platform.environment['PROBE_OUTPUT_DIR'];
      expect(
        directory,
        isNotNull,
        reason: 'An explicitly owned local fixture is required.',
      );
      final state =
          jsonDecode(await File('$directory/backend-state.json').readAsString())
              as Json;
      expect(state['mysql'], 'meshx-a07b1-mysql');
      final config =
          jsonDecode(
                await File('$directory/live-client-config.json').readAsString(),
              )
              as Json;
      final origin = Uri.parse(config['origin']);
      expect(origin.host, '127.0.0.1');
      expect(origin.port, state['port']);
      MeshXApi apiFor(Json user) => MeshXApi(origin)
        ..restoreCredentials({
          ...user,
          'origin': origin.toString(),
          'apiPath': '/api/v1',
          'wsPath': '/ws/chat',
          'nodeName': 'fixture',
        });
      final senderApi = apiFor(config['owner']),
          readerApi = apiFor(config['reader']);
      final sender = RealtimeConnection(senderApi);
      await sender.connect();
      addTearDown(() async {
        await sender.close();
        senderApi.close();
      });
      final cid = config['conversationId'] as String;
      Future<Json> send(String content, {bool burn = false}) async {
        final clientId = requestId();
        final ack = sender.events.stream.firstWhere(
          (e) => e['event'] == 'CHAT_ACK' && e['clientMsgId'] == clientId,
        );
        sender.send(
          'CHAT_SEND',
          {
            'groupId': config['groupId'],
            'contentType': 'text',
            'content': content,
            'isBurn': burn,
          },
          conversationId: cid,
          clientMsgId: clientId,
        );
        return await ack.timeout(const Duration(seconds: 10));
      }

      final recallAck = await send('synthetic live recall'),
          burnAck = await send('synthetic live burn', burn: true);
      for (var i = 0; i < 60; i++) {
        await send('synthetic live tail $i');
      }
      final storeDir = await Directory.systemTemp.createTemp('meshx-a07-live-');
      addTearDown(() => storeDir.delete(recursive: true));
      final store = FileChatStore(directory: storeDir),
          credentials = TestCredentials();
      await credentials.write(readerApi.credentials());
      final c = ChatController(discovery: FakeDiscovery(), store: store)
        ..api = readerApi;
      addTearDown(c.dispose);
      await c.reconnect();
      expect(c.online, isTrue);
      final conversation = c.conversations.firstWhere((item) => item.id == cid);
      c.active = conversation;
      final ids = [
        recallAck['payload']['messageId'],
        burnAck['payload']['messageId'],
      ];
      expect(c.messages.where((m) => ids.contains(m.messageId)).length, 2);
      final cursor = c.positions[cid]!;
      await c.pause();
      // Real authoritative mutation while this actual client's socket is closed.
      final http = HttpClient();
      addTearDown(() => http.close(force: true));
      for (final entry in {'recall': ids[0], 'burn': ids[1]}.entries) {
        final req = await http.postUrl(
          origin.replace(
            path: '/api/v1/chat/${entry.key}',
            queryParameters: {'messageId': entry.value},
          ),
        );
        req.headers.set(
          HttpHeaders.authorizationHeader,
          'Bearer ${senderApi.session!.token}',
        );
        final response = await req.close();
        expect(
          (jsonDecode(await utf8.decoder.bind(response).join())
              as Json)['code'],
          200,
        );
      }
      final truth = await sender.synchronize({cid: 0});
      expect(
        (truth['messages'] as List)
            .where((m) => ids.contains(m['messageId']))
            .length,
        2,
      );
      expect(
        (truth['messages'] as List)
            .where((m) => ids.contains(m['messageId']))
            .every((m) => m['content'] == ''),
        isTrue,
      );
      var notifications = 0;
      c.onLiveMessage = (_, _, _) => notifications++;
      await c.resume();
      await c.select(conversation);
      expect(c.online, isTrue);
      expect(c.positions[cid], cursor);
      expect(c.messages.where((m) => ids.contains(m.messageId)).length, 2);
      expect(
        c.messages
            .where((m) => ids.contains(m.messageId))
            .every((m) => m.content.startsWith('synthetic live')),
        isTrue,
      );
      await c.pause();
      final persisted = await store.load(
        accountScope(origin, readerApi.session!.userId),
      );
      expect(
        (persisted!['messages'][cid] as List)
            .where((m) => ids.contains(m['messageId']))
            .length,
        2,
      );
      expect(
        (persisted['messages'][cid] as List)
            .where((m) => ids.contains(m['messageId']))
            .every(
              (m) => (m['content'] as String).startsWith('synthetic live'),
            ),
        isTrue,
      );
      final restarted = ChatController(
        discovery: FakeDiscovery(),
        store: FileChatStore(directory: storeDir),
        credentials: credentials,
      );
      addTearDown(restarted.dispose);
      await restarted.restore();
      restarted.active = conversation;
      expect(restarted.online, isTrue);
      expect(
        restarted.messages.where((m) => ids.contains(m.messageId)).length,
        2,
      );
      expect(
        restarted.messages
            .where((m) => ids.contains(m.messageId))
            .every((m) => m.content.startsWith('synthetic live')),
        isTrue,
      );
      await File('$directory/dart-live-characterization.json').writeAsString(
        jsonEncode({
          'safety': 'FAIL',
          'classification': 'PROTOCOL_GAP',
          'actualHttpWebSocketAndFileStore': true,
          'cursor': cursor,
          'messageSequences': [
            recallAck['payload']['sequence'],
            burnAck['payload']['sequence'],
          ],
          'serverBodiesEmpty': true,
          'clientBodiesStaleAfterResumeAndRestart': true,
          'syncNotifications': notifications,
          'deviceEvidence': false,
        }),
      );
      expect(notifications, 0);
    },
    timeout: const Timeout(Duration(minutes: 2)),
  );
}
