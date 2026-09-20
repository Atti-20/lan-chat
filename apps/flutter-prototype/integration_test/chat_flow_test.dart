import 'package:meshx_flutter_probe/platform/discovery.dart';
import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'dart:ui' show FrameTiming;
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:integration_test/integration_test.dart';
import 'package:meshx_flutter_probe/chat_controller.dart';
import 'package:meshx_flutter_probe/data/meshx_api.dart';
import 'package:meshx_flutter_probe/data/models.dart';
import 'package:meshx_flutter_probe/ui/app.dart';

void main() {
  final binding = IntegrationTestWidgetsFlutterBinding.ensureInitialized();
  testWidgets(
    'real node discovery, chat, keyboard and reconnect',
    (tester) async {
      const origin = String.fromEnvironment('MESHX_NODE');
      const username = String.fromEnvironment('PROBE_USERNAME');
      const password = String.fromEnvironment('PROBE_PASSWORD');
      const groupId = String.fromEnvironment('PROBE_GROUP_ID');
      expect(
        origin,
        isNotEmpty,
        reason: 'Supply the ignored local fixture config',
      );
      expect(username, isNotEmpty);
      final platform = Platform.isIOS ? 'ios' : 'android';
      final c = ChatController(
        discovery: NativeNodeDiscovery(),
        allowLocalHttp: true,
      );
      final metrics = <String, dynamic>{
        'platform': platform,
        'mode': 'debug',
        'performanceConclusion':
            'Functional simulator evidence only; not release-device timing.',
      };
      binding.reportData = {'metrics': metrics};

      Future<void> waitFor(
        bool Function() predicate,
        String reason, {
        int seconds = 30,
      }) async {
        final end = DateTime.now().add(Duration(seconds: seconds));
        while (!predicate() && DateTime.now().isBefore(end)) {
          await tester.pump(const Duration(milliseconds: 100));
        }
        expect(
          predicate(),
          isTrue,
          reason: reason + (c.error == null ? '' : ': ${c.error!}'),
        );
      }

      Future<void> screenshot(String name) async {
        await tester.pumpAndSettle(const Duration(milliseconds: 100));
        await binding.takeScreenshot('$platform-$name');
        if (const bool.fromEnvironment('PROBE_NATIVE_SCREENSHOT')) {
          final client = HttpClient();
          try {
            final request = await client.postUrl(
              Uri.parse(origin).replace(port: 18382, path: '/capture'),
            );
            request.headers.contentType = ContentType.json;
            final body = utf8.encode(
              jsonEncode({'platform': platform, 'name': name}),
            );
            request.contentLength = body.length;
            request.add(body);
            final response = await request.close();
            expect(response.statusCode, 200);
            await response.drain<void>();
          } finally {
            client.close(force: true);
          }
        }
      }

      await tester.pumpWidget(
        MeshXApp(controller: c, initialThemeMode: ThemeMode.light),
      );
      await tester.pumpAndSettle();
      if (Platform.isAndroid) {
        await binding.convertFlutterSurfaceToImage();
        await tester.pump();
      }
      await screenshot('login-light');
      await tester.tap(find.byKey(const Key('discover')));
      await waitFor(
        () => !c.scanning,
        'native scan did not complete',
        seconds: 20,
      );
      metrics['nativeDiscoveryCandidates'] = c.candidates.length;
      metrics['nativeDiscoveryFoundFixture'] = c.candidates.any(
        (address) => address.endsWith(':18381'),
      );
      metrics['nativeDiscoveryError'] = c.error;
      if (const bool.fromEnvironment('PROBE_REQUIRE_DISCOVERY')) {
        expect(metrics['nativeDiscoveryFoundFixture'], isTrue);
      }
      await screenshot('native-discovery');
      // Discovery never auto-submits credentials to an advertised candidate.
      await tester.scrollUntilVisible(
        find.byKey(const Key('node-origin')),
        160,
        scrollable: find.byType(Scrollable).first,
      );
      await tester.enterText(find.byKey(const Key('node-origin')), origin);
      await tester.ensureVisible(find.byKey(const Key('username')));
      await tester.enterText(find.byKey(const Key('username')), username);
      await tester.ensureVisible(find.byKey(const Key('password')));
      await tester.enterText(find.byKey(const Key('password')), password);
      await tester.ensureVisible(find.byKey(const Key('login')));
      await tester.tap(find.byKey(const Key('login')));
      await waitFor(
        () => c.online && c.conversations.isNotEmpty,
        'real login and conversation snapshot',
      );
      metrics['conversationCount'] = c.conversations.length;
      await screenshot('conversations-light');
      final group = c.conversations.firstWhere(
        (conversation) => conversation.id == 'group:$groupId',
      );
      await tester.tap(find.byKey(ValueKey('conversation-${group.id}')));
      await waitFor(
        () => c.messages.isNotEmpty && !c.loadingHistory,
        'real message history',
      );
      metrics['initialHistoryCount'] = c.messages.length;
      await screenshot('chat-light');

      // Walk multiple history pages; all data is read through the actual server endpoint.
      final loadWatch = Stopwatch()..start();
      for (var page = 0; page < 5; page++) {
        await c.loadOlder();
        await tester.pump();
      }
      metrics['loadedHistoryCount'] = c.messages.length;
      metrics['historyLoadMs'] = loadWatch.elapsedMilliseconds;
      final frames = <Duration>[];
      void onFrames(List<FrameTiming> timings) {
        frames.addAll(timings.map((timing) => timing.totalSpan));
      }

      // Screenshot conversion changes Android surface behavior. This is explicitly
      // debug/simulator diagnostic data, never a release FPS comparison.
      binding.addTimingsCallback(onFrames);
      for (var swipe = 0; swipe < 8; swipe++) {
        await tester.fling(
          find.byKey(const Key('message-list')),
          const Offset(0, 540),
          1600,
        );
        await tester.pumpAndSettle();
      }
      binding.removeTimingsCallback(onFrames);
      metrics['scrollFrameCount'] = frames.length;
      metrics['scrollFramesOver16ms'] = frames
          .where((frame) => frame.inMicroseconds > 16667)
          .length;
      await screenshot('history-scroll');
      // Return to the latest row before testing input.
      final scrollable = tester.state<ScrollableState>(
        find.descendant(
          of: find.byKey(const Key('message-list')),
          matching: find.byType(Scrollable),
        ),
      );
      scrollable.position.jumpTo(0);
      await tester.pump();
      final marker = 'Flutter 验证 ${DateTime.now().millisecondsSinceEpoch}';
      await tester.tap(find.byKey(const Key('composer')));
      await tester.enterText(
        find.byKey(const Key('composer')),
        '$marker\n第二行：中文与 English 输入',
      );
      await tester.pump(const Duration(milliseconds: 500));
      await screenshot('keyboard');
      await tester.tap(find.byKey(const Key('send-message')));
      await waitFor(
        () => c.messages.any(
          (message) =>
              message.content.startsWith(marker) &&
              message.delivery == Delivery.sent,
        ),
        'server commit ACK',
      );
      final sent = c.messages.firstWhere(
        (message) => message.content.startsWith(marker),
      );
      expect(sent.sequence, greaterThan(0));
      metrics['sentServerSequence'] = sent.sequence;
      final history = await c.api!.history(group.id);
      expect(
        history.where((message) => message.clientMsgId == sent.clientMsgId),
        hasLength(1),
      );
      FocusManager.instance.primaryFocus?.unfocus();
      await tester.pumpAndSettle();

      final peer = MeshXApi(Uri.parse(origin));
      await peer.handshake();
      await peer.login(
        const String.fromEnvironment('PROBE_PEER_USERNAME'),
        const String.fromEnvironment('PROBE_PEER_PASSWORD'),
      );
      final peerRealtime = RealtimeConnection(peer);
      await peerRealtime.connect();
      final receivedMarker = '原生客户端已接收到同一节点的实时消息 ${requestId()}';
      peerRealtime.send(
        'CHAT_SEND',
        {
          'groupId': int.parse(groupId),
          'contentType': 'text',
          'content': receivedMarker,
          'isBurn': false,
        },
        clientMsgId: requestId(),
        conversationId: group.id,
      );
      await waitFor(
        () => c.messages.any((message) => message.content == receivedMarker),
        'live CHAT_DELIVER',
      );
      metrics['realtimeReceiveVerified'] = true;

      // Pause closes the socket. A message committed during this gap must be
      // recovered by the real history overlap when the app resumes.
      await c.pause();
      final offlineMarker = '重新连接后补回的消息 ${requestId()}';
      final committed = Completer<void>(), clientId = requestId();
      final subscription = peerRealtime.events.stream.listen((event) {
        if (event['event'] == 'CHAT_ACK' &&
            event['clientMsgId'] == clientId &&
            !committed.isCompleted) {
          committed.complete();
        }
      });
      peerRealtime.send(
        'CHAT_SEND',
        {
          'groupId': int.parse(groupId),
          'contentType': 'text',
          'content': offlineMarker,
          'isBurn': false,
        },
        clientMsgId: clientId,
        conversationId: group.id,
      );
      await committed.future.timeout(const Duration(seconds: 10));
      await c.resume();
      await waitFor(
        () =>
            c.online &&
            c.messages.any((message) => message.content == offlineMarker),
        'reconnect history overlap',
      );
      metrics['reconnectRecoveryVerified'] = true;
      await subscription.cancel();
      await peerRealtime.close();
      await peer.logout();
      peer.close();

      await tester.tap(find.byKey(const Key('theme-toggle')));
      await screenshot('chat-dark');
      expect(tester.takeException(), isNull);
      metrics['messageFlowPassed'] = true;
      await c.pause();
      await tester.pumpWidget(const SizedBox.shrink());
    },
    timeout: const Timeout(Duration(minutes: 5)),
  );
}
