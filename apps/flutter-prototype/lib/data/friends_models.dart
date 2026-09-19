import '../core/models.dart';

int _requiredId(Json value, String key) {
  final raw = value[key];
  final parsed = raw is int ? raw : int.tryParse('$raw');
  if (parsed == null || parsed <= 0) {
    throw FormatException('好友数据缺少有效的 $key');
  }
  return parsed;
}

String _text(dynamic value) => value is String ? value.trim() : '';

class FriendContact {
  const FriendContact({
    required this.userId,
    required this.username,
    required this.nickname,
    required this.remark,
    required this.signature,
    required this.online,
  });

  final int userId;
  final String username, nickname, remark, signature;
  final bool online;

  String get displayName => remark.isNotEmpty
      ? remark
      : nickname.isNotEmpty
      ? nickname
      : username;

  factory FriendContact.fromJson(Json value) => FriendContact(
    userId: _requiredId(value, 'friendId'),
    username: _text(value['username']),
    nickname: _text(value['nickname']),
    remark: _text(value['remark']),
    signature: _text(value['signature']),
    online: value['online'] == true || value['online'] == 1,
  );
}

class FriendRequestItem {
  const FriendRequestItem({
    required this.id,
    required this.fromUserId,
    required this.toUserId,
    required this.message,
    required this.status,
    this.senderName = '',
    this.createdAt,
  });

  final int id, fromUserId, toUserId, status;
  final String message, senderName;
  final DateTime? createdAt;

  factory FriendRequestItem.fromJson(Json value) {
    final status = value['status'] is int
        ? value['status'] as int
        : int.tryParse('${value['status']}');
    if (status == null || status < 0 || status > 2) {
      throw const FormatException('好友申请状态无效');
    }
    return FriendRequestItem(
      id: _requiredId(value, 'id'),
      fromUserId: _requiredId(value, 'fromUserId'),
      toUserId: _requiredId(value, 'toUserId'),
      message: _text(value['message']),
      status: status,
      createdAt: DateTime.tryParse('${value['createTime'] ?? ''}'),
    );
  }

  FriendRequestItem withSenderName(String value) => FriendRequestItem(
    id: id,
    fromUserId: fromUserId,
    toUserId: toUserId,
    message: message,
    status: status,
    senderName: value,
    createdAt: createdAt,
  );
}

class UserSearchResult {
  const UserSearchResult({
    required this.userId,
    required this.username,
    required this.nickname,
    required this.signature,
  });

  final int userId;
  final String username, nickname, signature;
  String get displayName => nickname.isNotEmpty ? nickname : username;

  factory UserSearchResult.fromJson(Json value) => UserSearchResult(
    userId: _requiredId(value, 'id'),
    username: _text(value['username']),
    nickname: _text(value['nickname']),
    signature: _text(value['signature']),
  );
}
