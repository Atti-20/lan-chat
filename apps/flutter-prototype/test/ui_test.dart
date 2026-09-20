import 'platform_fakes.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:meshx_flutter_probe/chat_controller.dart';
import 'package:meshx_flutter_probe/data/meshx_api.dart';
import 'package:meshx_flutter_probe/data/models.dart';
import 'package:meshx_flutter_probe/ui/app.dart';
import 'package:meshx_flutter_probe/ui/theme.dart';

class NoDiscovery extends FakeDiscovery {
  NoDiscovery() {
    scanStatus = CapabilityStatus.permissionDenied;
  }
}

class FakeApi extends MeshXApi {
  FakeApi() : super(Uri.parse('https://test.invalid')) {
    session = const Session(1, '测试用户', 'not-a-real-token');
    node = NodeInfo(origin, '测试节点', '隔离测试', '/api/v1', '/ws/chat');
  }
  @override
  Future<List<ChatMessage>> history(
    String id, {
    int? before,
    int limit = 50,
  }) async => List.generate(
    1200,
    (i) => ChatMessage(
      messageId: 'm$i',
      clientMsgId: 'c$i',
      conversationId: id,
      fromUserId: i.isEven ? 1 : 2,
      content: '第 $i 条消息：中英文 mixed text，用于检查列表与输入框。',
      sequence: i + 1,
      createdAt: DateTime(2026, 9, 8, 12, i % 60),
    ),
  );
}

void main() {
  testWidgets('discovery permission denial stays actionable', (tester) async {
    final c = ChatController(discovery: NoDiscovery());
    await tester.pumpWidget(
      MaterialApp(
        theme: meshXTheme(Brightness.light),
        home: AnimatedBuilder(
          animation: c,
          builder: (_, _) => LoginPage(controller: c, onToggleTheme: () {}),
        ),
      ),
    );
    await tester.tap(find.byKey(const Key('discover')));
    await tester.pumpAndSettle();
    expect(
      find.text(capabilityMessage(CapabilityStatus.permissionDenied)),
      findsOneWidget,
    );
    expect(find.byKey(const Key('node-origin')), findsOneWidget);
    c.dispose();
  });

  testWidgets('320px login remains scrollable in dark mode with the keyboard', (
    tester,
  ) async {
    tester.view.physicalSize = const Size(320, 568);
    tester.view.devicePixelRatio = 1;
    tester.view.viewInsets = const FakeViewPadding(bottom: 240);
    addTearDown(tester.view.reset);
    final c = ChatController();
    await tester.pumpWidget(
      MaterialApp(
        theme: meshXTheme(Brightness.dark),
        home: LoginPage(controller: c, onToggleTheme: () {}),
      ),
    );
    await tester.scrollUntilVisible(
      find.byKey(const Key('password')),
      150,
      scrollable: find.byType(Scrollable).first,
    );
    await tester.enterText(find.byKey(const Key('password')), 'placeholder');
    await tester.ensureVisible(find.byKey(const Key('login')));
    expect(tester.takeException(), isNull);
    expect(
      tester.getRect(find.byKey(const Key('login'))).bottom,
      lessThanOrEqualTo(328),
    );
    c.dispose();
  });

  testWidgets(
    'long conversation uses lazy rows and keeps multiline composer reachable',
    (tester) async {
      tester.view.physicalSize = const Size(390, 844);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.reset);
      final c = ChatController()
        ..api = FakeApi()
        ..online = true;
      const conversation = Conversation(
        id: 'private:1:2',
        targetId: 2,
        kind: 'private',
        title: '许澄',
      );
      c.conversations = [conversation];
      await c.select(conversation);
      await tester.pumpWidget(
        MaterialApp(
          theme: meshXTheme(Brightness.light),
          home: ChatPage(controller: c, onToggleTheme: () {}),
        ),
      );
      await tester.pumpAndSettle();
      expect(c.messages, hasLength(1200));
      expect(find.byType(MessageBubble).evaluate().length, lessThan(30));
      tester.view.viewInsets = const FakeViewPadding(bottom: 300);
      await tester.pump();
      await tester.enterText(find.byKey(const Key('composer')), '第一行\n第二行');
      await tester.pump();
      expect(
        tester.getRect(find.byKey(const Key('send-message'))).bottom,
        lessThanOrEqualTo(544),
      );
      expect(tester.takeException(), isNull);
      c.dispose();
    },
  );
}
