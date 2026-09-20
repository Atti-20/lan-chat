typedef Json = Map<String, dynamic>;

String privateConversationId(int firstUserId, int secondUserId) {
  if (firstUserId <= 0 || secondUserId <= 0 || firstUserId == secondUserId) {
    throw const FormatException('无法生成私聊会话标识');
  }
  final lower = firstUserId < secondUserId ? firstUserId : secondUserId;
  final upper = firstUserId < secondUserId ? secondUserId : firstUserId;
  return 'private:$lower:$upper';
}

int? privateConversationPeer(String conversationId, int currentUserId) {
  final parts = conversationId.split(':');
  if (parts.length != 3 || parts.first != 'private') return null;
  final first = int.tryParse(parts[1]), second = int.tryParse(parts[2]);
  if (first == null || second == null || first <= 0 || second <= 0) return null;
  if (first == currentUserId) return second;
  if (second == currentUserId) return first;
  return null;
}

int integer(dynamic value) =>
    value is int ? value : int.tryParse('$value') ?? 0;

class NodeInfo {
  const NodeInfo(
    this.origin,
    this.name,
    this.organization,
    this.apiPath,
    this.wsPath,
  );
  final Uri origin;
  final String name, organization, apiPath, wsPath;

  factory NodeInfo.fromJson(Uri origin, Json json) {
    if (integer(json['protocolVersion']) != 1) {
      throw const FormatException('节点协议版本不兼容');
    }
    String path(String key, String fallback) {
      final value = json[key] as String? ?? fallback;
      final parsed = Uri.tryParse(value);
      if (parsed == null ||
          !value.startsWith('/') ||
          value.startsWith('//') ||
          parsed.hasAuthority ||
          parsed.hasScheme ||
          parsed.hasQuery ||
          parsed.hasFragment ||
          value.contains('\\') ||
          Uri.decodeComponent(value).split('/').contains('..')) {
        throw const FormatException('节点接口地址无效');
      }
      return value.replaceFirst(RegExp(r'/$'), '');
    }

    return NodeInfo(
      origin,
      json['nodeName'] as String? ?? 'MeshX 节点',
      json['organizationName'] as String? ?? '',
      path('apiBasePath', '/api/v1'),
      path('webSocketPath', '/ws/chat'),
    );
  }
}

class Session {
  const Session(
    this.userId,
    this.nickname,
    this.token, {
    this.username = '',
    this.avatar = '',
  });
  final int userId;
  final String nickname, token, username, avatar;
  factory Session.fromJson(Json j) => Session(
    integer(j['userId']),
    j['nickname'] ?? j['username'] ?? '',
    j['token'] as String,
    username: j['username'] as String? ?? '',
    avatar: j['avatar'] as String? ?? '',
  );
}

class Conversation {
  const Conversation({
    required this.id,
    required this.targetId,
    required this.kind,
    required this.title,
    this.preview = '',
    this.unread = 0,
    this.lastSequence = 0,
    this.lastReadSequence = 0,
    this.avatar = '',
  });
  final String id, kind, title, preview, avatar;
  final int targetId, unread, lastSequence, lastReadSequence;
  Conversation withReadState({
    required int lastSequence,
    required int lastReadSequence,
    required int unread,
  }) => Conversation(
    id: id,
    targetId: targetId,
    kind: kind,
    title: title,
    preview: preview,
    avatar: avatar,
    lastSequence: lastSequence,
    lastReadSequence: lastReadSequence,
    unread: unread,
  );
  Json toJson() => {
    'id': id,
    'targetId': targetId,
    'kind': kind,
    'title': title,
    'preview': preview,
    'unread': unread,
    'lastSequence': lastSequence,
    'lastReadSequence': lastReadSequence,
    'avatar': avatar,
  };
  factory Conversation.restore(Json j) => Conversation(
    id: j['id'],
    targetId: j['targetId'],
    kind: j['kind'],
    title: j['title'],
    preview: j['preview'],
    unread: j['unread'],
    lastSequence: integer(j['lastSequence']),
    lastReadSequence: integer(j['lastReadSequence']),
    avatar: j['avatar'] as String? ?? '',
  );
}

enum Delivery { queued, sending, sent, failed }

enum RecoveryDisposition { needsUserAction, dropBodyRevoked }

class ChatMessage {
  const ChatMessage({
    required this.messageId,
    required this.clientMsgId,
    required this.conversationId,
    required this.fromUserId,
    required this.content,
    required this.sequence,
    required this.createdAt,
    this.nickname = '',
    this.contentType = 'text',
    this.delivery = Delivery.sent,
    this.recalled = false,
    this.burned = false,
    this.recoveryDisposition,
    this.isBurn = false,
    this.burnDuration,
    this.replyToId,
    this.mentionUserIds,
  });
  final String messageId,
      clientMsgId,
      conversationId,
      content,
      nickname,
      contentType;
  final int fromUserId, sequence;
  final DateTime createdAt;
  final Delivery delivery;
  final bool recalled, burned;
  final bool isBurn;
  final int? burnDuration;
  final String? replyToId, mentionUserIds;
  final RecoveryDisposition? recoveryDisposition;
  String get key => messageId.isNotEmpty ? messageId : clientMsgId;
  String get displayContent => burned
      ? '这条消息已焚毁'
      : recalled
      ? '这条消息已撤回'
      : contentType == 'text'
      ? content
      : '[暂不预览此类消息]';

  factory ChatMessage.fromJson(Json j) => ChatMessage(
    messageId: j['messageId']?.toString() ?? '',
    clientMsgId: j['clientMsgId']?.toString() ?? '',
    conversationId: j['conversationId'] as String,
    fromUserId: integer(j['fromUserId']),
    nickname: j['fromNickname'] as String? ?? '',
    content:
        j.containsKey('status') && !{0, 1, 2}.contains(integer(j['status']))
        ? ''
        : j['content'] as String? ?? '',
    contentType:
        j.containsKey('status') && !{0, 1, 2}.contains(integer(j['status']))
        ? 'unsupported'
        : j['contentType'] as String? ?? j['type'] as String? ?? 'text',
    // REST entity and WS payload use integer flags; keep legacy bool tolerance.
    recalled: j['isRecalled'] == true || integer(j['isRecalled']) == 1,
    burned: j['burned'] == true || integer(j['status']) == 2,
    isBurn: j['isBurn'] == true || integer(j['isBurn']) == 1,
    burnDuration: j['burnDuration'] == null ? null : integer(j['burnDuration']),
    replyToId: j['replyToId'] as String?,
    mentionUserIds: j['mentionUserIds'] as String?,
    sequence: integer(j['sequence']),
    createdAt:
        DateTime.tryParse(j['createTime']?.toString() ?? '') ?? DateTime.now(),
  );

  Json toJson() => {
    'messageId': messageId,
    'clientMsgId': clientMsgId,
    'conversationId': conversationId,
    'fromUserId': fromUserId,
    'fromNickname': nickname,
    'content': content,
    'contentType': contentType,
    'sequence': sequence,
    'createTime': createdAt.toIso8601String(),
    'isRecalled': recalled,
    'burned': burned,
    'delivery': delivery.name,
    if (isBurn) 'isBurn': 1,
    if (burnDuration != null) 'burnDuration': burnDuration,
    if (replyToId != null) 'replyToId': replyToId,
    if (mentionUserIds != null) 'mentionUserIds': mentionUserIds,
    if (recoveryDisposition != null)
      'recoveryDisposition': recoveryDisposition!.name,
  };
  factory ChatMessage.restore(Json j) {
    final state = Delivery.values.byName(j['delivery'] as String);
    final message = ChatMessage.fromJson(
      j,
    ).withDelivery(state == Delivery.sending ? Delivery.queued : state);
    final hold = j['recoveryDisposition'];
    return hold == null
        ? message
        : message.withRecoveryDisposition(
            RecoveryDisposition.values.byName(hold as String),
          );
  }

  ChatMessage withDelivery(Delivery value, {String? id, int? serverSequence}) =>
      ChatMessage(
        messageId: id ?? messageId,
        clientMsgId: clientMsgId,
        conversationId: conversationId,
        fromUserId: fromUserId,
        nickname: nickname,
        content: content,
        contentType: contentType,
        isBurn: isBurn,
        burnDuration: burnDuration,
        replyToId: replyToId,
        mentionUserIds: mentionUserIds,
        recalled: recalled,
        burned: burned,
        sequence: serverSequence ?? sequence,
        createdAt: createdAt,
        delivery: recoveryDisposition == null ? value : Delivery.failed,
        recoveryDisposition: recoveryDisposition,
      );

  ChatMessage withRecoveryDisposition(RecoveryDisposition value) {
    final effective = recoveryDisposition == RecoveryDisposition.dropBodyRevoked
        ? RecoveryDisposition.dropBodyRevoked
        : value;
    return ChatMessage(
      messageId: messageId,
      clientMsgId: clientMsgId,
      conversationId: conversationId,
      fromUserId: fromUserId,
      content: effective == RecoveryDisposition.dropBodyRevoked ? '' : content,
      contentType: effective == RecoveryDisposition.dropBodyRevoked
          ? 'text'
          : contentType,
      sequence: sequence,
      createdAt: createdAt,
      nickname: nickname,
      recalled: recalled,
      burned: burned,
      delivery: Delivery.failed,
      recoveryDisposition: effective,
      isBurn: effective == RecoveryDisposition.dropBodyRevoked ? false : isBurn,
      burnDuration: effective == RecoveryDisposition.dropBodyRevoked
          ? null
          : burnDuration,
      replyToId: effective == RecoveryDisposition.dropBodyRevoked
          ? null
          : replyToId,
      mentionUserIds: effective == RecoveryDisposition.dropBodyRevoked
          ? null
          : mentionUserIds,
    );
  }

  ChatMessage terminal({required bool burned}) => ChatMessage(
    messageId: messageId,
    clientMsgId: clientMsgId,
    conversationId: conversationId,
    fromUserId: fromUserId,
    content: '',
    sequence: sequence,
    createdAt: createdAt,
    nickname: nickname,
    delivery: Delivery.sent,
    recalled: !burned,
    burned: burned,
  );

  ChatMessage withoutRecoveryBody({required bool isRecalled}) => ChatMessage(
    messageId: messageId,
    clientMsgId: clientMsgId,
    conversationId: conversationId,
    fromUserId: fromUserId,
    content: '',
    sequence: sequence,
    createdAt: createdAt,
    delivery: delivery,
    recalled: isRecalled,
    recoveryDisposition: recoveryDisposition,
  );
}

/// A clientMsgId is scoped to its sender, never globally unique.
List<ChatMessage> mergeMessages(
  List<ChatMessage> current,
  Iterable<ChatMessage> incoming,
) {
  final result = [...current];
  for (final message in incoming) {
    final index = result.indexWhere(
      (old) =>
          old.conversationId == message.conversationId &&
          ((message.messageId.isNotEmpty &&
                  old.messageId == message.messageId) ||
              (message.clientMsgId.isNotEmpty &&
                  old.clientMsgId == message.clientMsgId &&
                  old.fromUserId == message.fromUserId)),
    );
    if (index < 0) {
      result.add(message);
    } else {
      final previous = result[index];
      result[index] = previous.burned || previous.recalled
          ? previous
          : previous.recoveryDisposition == null
          ? message
          : message.recoveryDisposition == RecoveryDisposition.dropBodyRevoked
          ? previous.withRecoveryDisposition(
              RecoveryDisposition.dropBodyRevoked,
            )
          : previous;
    }
  }
  result.sort((a, b) {
    if (a.sequence > 0 && b.sequence > 0) {
      return a.sequence.compareTo(b.sequence);
    }
    if (a.sequence > 0) return -1;
    if (b.sequence > 0) return 1;
    return a.createdAt.compareTo(b.createdAt);
  });
  return result;
}

/// A body-free terminal row with no invented sender or timestamp.
class RecoveryTerminal {
  const RecoveryTerminal(
    this.messageId,
    this.conversationId,
    this.sequence,
    this.state,
  );
  final String messageId, conversationId, state;
  final int sequence;
  String get label => state == 'RECALLED'
      ? '这条消息已撤回'
      : state == 'BURNED'
      ? '这条消息已焚毁'
      : '这条消息已不可用';
}
