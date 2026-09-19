import 'dart:io';
import 'package:flutter_test/flutter_test.dart';
import 'package:meshx_flutter_probe/core/models.dart';
import 'package:meshx_flutter_probe/core/recovery.dart';
import 'package:meshx_flutter_probe/core/recovery_data.dart';
import 'package:meshx_flutter_probe/platform/storage.dart';

void main() {
  final context = RecoveryContext(
    origin: 'https://node.example',
    userId: '7',
    streamEpoch: '11111111-1111-4111-8111-111111111111',
    generation: 1,
  );
  final pending = ChatMessage(
    messageId: 'm1',
    clientMsgId: 'c1',
    conversationId: 'group:21',
    fromUserId: 7,
    content: 'private attachment',
    contentType: 'file',
    sequence: 1,
    createdAt: DateTime.utc(2026),
    delivery: Delivery.sending,
  );
  RecoveryChatData data(ChatMessage message) => RecoveryChatData(
    {
      'group:21': [message],
    },
    [
      const Conversation(
        id: 'group:21',
        targetId: 21,
        kind: 'group',
        title: 'Group',
        preview: 'private preview',
      ),
    ],
    {'group:21': 1},
  );
  RecoveryPlan plan({bool revoke = false, bool stop = false}) =>
      planRecoveryMutations(RecoveryState(context: context), context, [
        if (revoke || stop)
          {
            'recordVersion': 1,
            'eventId': '22222222-2222-4222-8222-222222222222',
            'streamEpoch': context.streamEpoch,
            'cursor': '1',
            'conversationId': 'group:21',
            'committedAt': '2026-09-14T00:00:00.000Z',
            'accessVersion': '2',
            'type': revoke
                ? 'CONVERSATION_ACCESS_REVOKED'
                : 'CONVERSATION_ACCESS_CHANGED',
            'readAllowed': !revoke,
            'sendAllowed': false,
            'reason': revoke ? 'REMOVED' : 'SEND_DENIED',
            if (!revoke) 'rebuildConversation': false,
          },
      ]);

  test(
    'revoke clears body/preview/position and persisted queue cannot revive',
    () async {
      final mutation = plan(revoke: true);
      expect(mutation.changed, isTrue);
      final result = applyRecoveryChatData(mutation, data(pending));
      final held = result.messages['group:21']!.single;
      expect(held.content, '');
      expect(held.contentType, 'text');
      expect(held.recoveryDisposition, RecoveryDisposition.dropBodyRevoked);
      expect(result.conversations.single.preview, '');
      expect(result.positions, isEmpty);
      final dir = await Directory.systemTemp.createTemp('meshx-recovery-data-');
      try {
        final store = FileChatStore(directory: dir);
        await store.commitRecovery('https://node.example|7', 0, {
          'messages': [held.toJson()],
          'cursor': mutation.next.cursor,
        });
        final image = await store.loadRecovery('https://node.example|7');
        expect(image!.snapshot['cursor'], '1');
        final restored = ChatMessage.restore(
          (image.snapshot['messages'] as List).single as Json,
        );
        expect(
          restored.withDelivery(Delivery.queued).delivery,
          Delivery.failed,
        );
        final merged = mergeMessages([restored], [pending]);
        expect(merged.single.content, '');
        expect(merged.single.delivery, Delivery.failed);
      } finally {
        await dir.delete(recursive: true);
      }
      expect(pending.content, 'private attachment');
    },
  );
  test(
    'send denied retains draft and history; grant does not restore old queue',
    () {
      final result = applyRecoveryChatData(plan(stop: true), data(pending));
      final held = result.messages['group:21']!.single;
      expect(held.content, pending.content);
      expect(held.recoveryDisposition, RecoveryDisposition.needsUserAction);
      final revoked = mergeMessages(
        [held],
        [pending.withRecoveryDisposition(RecoveryDisposition.dropBodyRevoked)],
      ).single;
      expect(revoked.content, '');
      expect(revoked.recoveryDisposition, RecoveryDisposition.dropBodyRevoked);
      expect(
        applyRecoveryChatData(
          plan(),
          result,
        ).messages['group:21']!.single.delivery,
        Delivery.failed,
      );
      final history = applyRecoveryChatData(
        plan(stop: true),
        data(pending.withDelivery(Delivery.sent)),
      );
      expect(history.messages['group:21']!.single.recoveryDisposition, isNull);
      expect(history.messages['group:21']!.single.content, pending.content);
    },
  );
}
