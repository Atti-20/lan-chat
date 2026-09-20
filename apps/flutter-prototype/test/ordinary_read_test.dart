import 'dart:async';
import 'package:flutter_test/flutter_test.dart';
import 'package:meshx_flutter_probe/chat_controller.dart';
import 'package:meshx_flutter_probe/data/models.dart';
import 'package:meshx_flutter_probe/core/store.dart';
import 'recovery_test.dart'
    show ControlledApi, CapturingConnection, room, eventually;

class ReadApi extends ControlledApi {
  Conversation summary = room.withReadState(
    lastSequence: 2,
    lastReadSequence: 0,
    unread: 2,
  );
  Completer<List<Conversation>>? snapshot;
  @override
  Future<List<Conversation>> conversations() async =>
      snapshot == null ? [summary] : snapshot!.future;
}

class ReadStore implements ChatStore {
  final snapshots = <String, Json>{};
  @override
  Future<Json?> load(String owner) async => snapshots[owner];
  @override
  Future<void> save(String owner, Json snapshot) async {
    snapshots[owner] = snapshot;
  }
}

void main() {
  late ReadApi api;
  late CapturingConnection wire;
  late ChatController c;
  late ReadStore store;
  setUp(() async {
    api = ReadApi();
    wire = CapturingConnection(api);
    store = ReadStore();
    c = ChatController(store: store, connectionFactory: (_) => wire)..api = api;
    await c.reconnect();
    c.active = c.conversations.single;
  });
  tearDown(() => c.dispose());
  void sample(Set<int> visible, {bool foreground = true, int start = 0}) {
    final scope = c.recoveryReadScope;
    for (var t = start; t <= start + 300; t += 100) {
      c.observeRecoveryRead(scope, visible, t, foreground: foreground);
    }
  }

  void receipt(int last, int read, int unread, {int user = 1}) {
    wire.events.add({
      'event': 'CHAT_READ',
      'conversationId': room.id,
      'payload': {
        'userId': user,
        'lastSequence': last,
        'lastReadSequence': read,
        'unreadCount': unread,
      },
    });
  }

  test('ordinary reading waits for visibility and server authority', () async {
    expect(c.usesMutationRecovery, false);
    sample({2});
    await Future<void>.delayed(Duration.zero);
    expect(wire.frames, isEmpty);
    sample({1, 2}, start: 400);
    await eventually(() => wire.frames.isNotEmpty);
    expect(wire.frames.single['payload']['lastReadSequence'], 2);
    expect(c.conversations.single.unread, 2);
    receipt(2, 2, 0);
    await eventually(() => c.conversations.single.unread == 0);
    expect(c.conversations.single.lastReadSequence, 2);
  });
  test('covered route and stale scope never qualify', () async {
    sample({1, 2}, foreground: false);
    final oldScope = c.recoveryReadScope;
    c.leaveConversation();
    c.observeRecoveryRead(oldScope, {1, 2}, 500, foreground: true);
    await Future<void>.delayed(Duration.zero);
    expect(wire.frames, isEmpty);
  });
  test('other readers and old receipts cannot clear newer messages', () async {
    receipt(2, 2, 0, user: 9);
    await Future<void>.delayed(Duration.zero);
    expect(c.conversations.single.unread, 2);
    api.snapshot = Completer<List<Conversation>>();
    c.conversations = [
      room.withReadState(lastSequence: 3, lastReadSequence: 0, unread: 3),
    ];
    receipt(2, 2, 0);
    await Future<void>.delayed(const Duration(milliseconds: 10));
    expect(c.conversations.single.unread, 3);
    api.snapshot!.complete(c.conversations);
  });
  test('late REST snapshot cannot overwrite committed read event', () async {
    api.snapshot = Completer<List<Conversation>>();
    final refresh = c.refreshConversations();
    receipt(2, 2, 0);
    await eventually(() => c.conversations.single.unread == 0);
    api.snapshot!.complete([api.summary]);
    await refresh;
    expect(c.conversations.single.unread, 0);
  });
  test(
    'unconfirmed visible position retries after reconnect even outside chat',
    () async {
      sample({1, 2});
      await eventually(() => wire.frames.isNotEmpty);
      c.leaveConversation();
      await c.reconnect();
      expect(wire.frames.where((f) => f['event'] == 'CHAT_READ').length, 2);
    },
  );
  test(
    'pending read is durable under its owner before transport confirmation',
    () async {
      sample({1, 2});
      await eventually(() => wire.frames.isNotEmpty);
      expect(store.snapshots['http://127.0.0.1|1']!['pendingReads'], {
        room.id: 2,
      });
      receipt(2, 2, 0);
      await eventually(
        () => (store.snapshots['http://127.0.0.1|1']!['pendingReads'] as Map)
            .isEmpty,
      );
    },
  );
  test(
    'logout discards delayed read events and pending transport work',
    () async {
      sample({1, 2});
      await eventually(() => wire.frames.isNotEmpty);
      receipt(2, 2, 0);
      await c.logout(revokeRemote: false);
      await Future<void>.delayed(Duration.zero);
      expect(c.conversations, isEmpty);
      expect(c.recoveryReadScope, isEmpty);
    },
  );
  test('old cached conversation shape remains readable', () {
    final restored = Conversation.restore({
      'id': room.id,
      'targetId': 2,
      'kind': 'private',
      'title': '测试',
      'preview': '',
      'unread': 2,
    });
    expect(restored.lastReadSequence, 0);
    expect(Conversation.restore(api.summary.toJson()).lastSequence, 2);
  });
}
