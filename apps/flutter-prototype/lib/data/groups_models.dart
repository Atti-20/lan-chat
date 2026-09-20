import '../core/models.dart';

int _requiredId(Json value, String key) {
  final raw = value[key];
  final parsed = raw is int ? raw : int.tryParse('$raw');
  if (parsed == null || parsed <= 0) {
    throw FormatException('群组数据缺少有效的 $key');
  }
  return parsed;
}

int _requiredInt(Json value, String key, {int? minimum, int? maximum}) {
  final raw = value[key];
  final parsed = raw is int ? raw : int.tryParse('$raw');
  if (parsed == null ||
      (minimum != null && parsed < minimum) ||
      (maximum != null && parsed > maximum)) {
    throw FormatException('群组数据包含无效的 $key');
  }
  return parsed;
}

String _text(dynamic value) => value is String ? value.trim() : '';

class MeshXGroup {
  const MeshXGroup({
    required this.id,
    required this.name,
    required this.ownerId,
    required this.avatar,
    required this.announcement,
    required this.maxMembers,
    required this.joinMode,
    this.lastMessage = '',
    this.lastMessageType = '',
    this.createTime,
    this.updateTime,
  });

  final int id, ownerId, maxMembers, joinMode;
  final String name, avatar, announcement, lastMessage, lastMessageType;
  final DateTime? createTime, updateTime;

  factory MeshXGroup.fromJson(Json value) {
    final name = _text(value['groupName']);
    if (name.length < 2 || name.length > 20) {
      throw const FormatException('群名称长度需为2-20字符');
    }
    return MeshXGroup(
      id: _requiredId(value, 'id'),
      name: name,
      ownerId: _requiredId(value, 'ownerId'),
      avatar: _text(value['avatar']),
      announcement: _text(value['announcement']),
      maxMembers: _requiredInt(value, 'maxMembers', minimum: 1, maximum: 200),
      joinMode: _requiredInt(value, 'joinMode', minimum: 0, maximum: 2),
      lastMessage: _text(value['lastMessage']),
      lastMessageType: _text(value['lastMessageType']),
      createTime: DateTime.tryParse('${value['createTime'] ?? ''}'),
      updateTime: DateTime.tryParse('${value['updateTime'] ?? ''}'),
    );
  }
}

class GroupMemberInfo {
  const GroupMemberInfo({
    required this.userId,
    required this.nickname,
    required this.avatar,
    required this.role,
    required this.online,
    this.muteUntil,
    this.joinTime,
  });

  final int userId, role;
  final String nickname, avatar;
  final bool online;
  final DateTime? muteUntil, joinTime;

  String get displayName => nickname.isEmpty ? '成员 $userId' : nickname;
  String get roleLabel => switch (role) {
    2 => '群主',
    1 => '管理员',
    _ => '成员',
  };

  factory GroupMemberInfo.fromJson(Json value) => GroupMemberInfo(
    userId: _requiredId(value, 'userId'),
    nickname: _text(value['nickname']),
    avatar: _text(value['avatar']),
    role: _requiredInt(value, 'role', minimum: 0, maximum: 2),
    online: value['online'] == true || value['online'] == 1,
    muteUntil: DateTime.tryParse('${value['muteUntil'] ?? ''}'),
    joinTime: DateTime.tryParse('${value['joinTime'] ?? ''}'),
  );
}
