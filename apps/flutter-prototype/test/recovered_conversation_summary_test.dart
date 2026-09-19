import 'package:flutter_test/flutter_test.dart';
import 'package:meshx_flutter_probe/core/models.dart';
import 'package:meshx_flutter_probe/core/recovered_conversation_summary.dart';

ChatMessage message(
  int sequence, {
  int sender = 8,
  String content = 'verified',
  String type = 'text',
  bool burn = false,
}) => ChatMessage(
  messageId: 'm$sequence',
  clientMsgId: '',
  conversationId: 'group:21',
  fromUserId: sender,
  content: content,
  contentType: type,
  sequence: sequence,
  createdAt: DateTime(2026),
  isBurn: burn,
);
void main() {
  test(
    'verified summary counts peers after own read position and orders by sequence',
    () {
      final summary = recoveredConversationSummary(
        [message(3, sender: 7, content: 'own'), message(1), message(2)],
        [],
        7,
        1,
      );
      expect(summary, (preview: 'own', unread: 1, timestamp: DateTime(2026)));
    },
  );
  test('terminal latest summary never exposes stale body', () {
    for (final entry in {
      'RECALLED': '这条消息已撤回',
      'BURNED': '这条消息已焚毁',
      'UNAVAILABLE': '这条消息已不可用',
    }.entries) {
      final summary = recoveredConversationSummary(
        [message(1)],
        [
          {'state': entry.key, 'messageSequence': '2'},
        ],
        7,
        0,
      );
      expect(summary, (preview: entry.value, unread: 1, timestamp: null));
    }
  });
  test(
    'burn and unknown media have safe labels; missing baseline does not invent unread',
    () {
      expect(
        recoveredConversationSummary([message(1, burn: true)], [], 7, null),
        (preview: '阅后即焚消息', unread: 0, timestamp: DateTime(2026)),
      );
      expect(
        recoveredConversationSummary(
          [message(1, type: 'image')],
          [],
          7,
          null,
        ).preview,
        '图片',
      );
      expect(
        recoveredConversationSummary(
          [message(1, type: 'unknown')],
          [],
          7,
          null,
        ).preview,
        '暂不预览此类消息',
      );
      expect(recoveredConversationSummary([], [], 7, 0), (
        preview: '',
        unread: 0,
        timestamp: null,
      ));
    },
  );
}
