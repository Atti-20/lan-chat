import 'models.dart';

/// Takes only verified history and body-free terminal headers from one committed image.
({String preview, int unread, DateTime? timestamp})
recoveredConversationSummary(
  Iterable<ChatMessage> messages,
  Iterable<Json> terminals,
  int userId,
  int? readPosition,
) {
  var preview = '', unread = 0, sequence = 0;
  DateTime? timestamp;
  const labels = {
    'image': '图片',
    'file': '文件',
    'voice': '语音',
    'video': '视频',
    'broadcast': '广播通知',
  };
  for (final message in messages) {
    if (message.delivery != Delivery.sent || message.sequence <= 0) continue;
    if (readPosition != null &&
        message.fromUserId != userId &&
        message.sequence > readPosition) {
      unread++;
    }
    if (message.sequence < sequence) continue;
    sequence = message.sequence;
    timestamp = message.createdAt;
    preview = message.isBurn
        ? '阅后即焚消息'
        : message.contentType == 'text'
        ? (message.content.isEmpty ? '还没有消息' : message.content)
        : labels[message.contentType] ?? '暂不预览此类消息';
  }
  for (final item in terminals) {
    final position = int.tryParse('${item['messageSequence']}');
    if (position == null || position <= 0 || position < sequence) continue;
    sequence = position;
    timestamp = null;
    preview = switch (item['state']) {
      'RECALLED' => '这条消息已撤回',
      'BURNED' => '这条消息已焚毁',
      _ => '这条消息已不可用',
    };
  }
  return (preview: preview, unread: unread, timestamp: timestamp);
}
