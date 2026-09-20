import 'models.dart';
import 'recovery.dart';

class RecoveryChatData {
  RecoveryChatData(this.messages, this.conversations, this.positions);
  final Map<String, List<ChatMessage>> messages;
  final List<Conversation> conversations;
  final Map<String, int> positions;
}

/// The existing message list also owns Flutter's pending send queue.
RecoveryChatData applyRecoveryChatData(
  RecoveryPlan plan,
  RecoveryChatData data,
) {
  final cleared = {...plan.effects.revokeConversations};
  final messages = <String, List<ChatMessage>>{};
  for (final entry in data.messages.entries) {
    messages[entry.key] = entry.value.map((message) {
      final erase =
          plan.effects.revokeConversations.contains(message.conversationId) ||
          plan.effects.eraseMessages.contains(message.messageId);
      var next = message;
      if (erase) {
        cleared.add(message.conversationId);
        next = message.withoutRecoveryBody(
          isRecalled:
              plan.next.messages[message.messageId]?.state ==
              MessageRecoveryState.recalled,
        );
      }
      final pending =
          message.fromUserId.toString() == plan.next.context.userId &&
          (message.delivery != Delivery.sent ||
              message.recoveryDisposition != null);
      if (pending && erase) {
        next = next.withRecoveryDisposition(
          RecoveryDisposition.dropBodyRevoked,
        );
      } else if (pending &&
          plan.effects.stopAutomaticSend.contains(message.conversationId)) {
        next = next.withRecoveryDisposition(
          RecoveryDisposition.needsUserAction,
        );
      }
      return next;
    }).toList();
  }
  return RecoveryChatData(
    messages,
    data.conversations
        .map(
          (conversation) => !cleared.contains(conversation.id)
              ? conversation
              : Conversation(
                  id: conversation.id,
                  targetId: conversation.targetId,
                  kind: conversation.kind,
                  title: conversation.title,
                  unread: conversation.unread,
                ),
        )
        .toList(),
    {...data.positions}
      ..removeWhere((cid, _) => plan.effects.revokeConversations.contains(cid)),
  );
}
