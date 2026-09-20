import '../core/models.dart';

String _string(Json value, String key, {bool required = false}) {
  final raw = value[key];
  if (raw == null && !required) return '';
  if (raw is! String) throw FormatException('用户资料字段 $key 无效');
  final text = raw.trim();
  if (required && text.isEmpty) throw FormatException('用户资料字段 $key 缺失');
  return text;
}

class UserProfile {
  const UserProfile({
    required this.userId,
    required this.username,
    required this.nickname,
    required this.avatar,
    required this.signature,
  });

  final int userId;
  final String username, nickname, avatar, signature;

  factory UserProfile.fromJson(Json value) {
    final id = integer(value['id'] ?? value['userId']);
    if (id <= 0) throw const FormatException('用户资料 ID 无效');
    return UserProfile(
      userId: id,
      username: _string(value, 'username', required: true),
      nickname: _string(value, 'nickname', required: true),
      avatar: _string(value, 'avatar'),
      signature: _string(value, 'signature'),
    );
  }
}

class AvatarUpload {
  const AvatarUpload({required this.url, required this.thumbnailUrl});

  final String url, thumbnailUrl;
  String get profileValue => thumbnailUrl.isNotEmpty ? thumbnailUrl : url;

  factory AvatarUpload.fromJson(Json value) {
    final result = AvatarUpload(
      url: _string(value, 'url'),
      thumbnailUrl: _string(value, 'thumbnailUrl'),
    );
    if (result.profileValue.isEmpty) {
      throw const FormatException('头像上传结果缺少地址');
    }
    return result;
  }
}

const textAvatarColors = [
  '#5AC8FA',
  '#007AFF',
  '#5856D6',
  '#AF52DE',
  '#FF2D55',
  '#FF3B30',
  '#FF9500',
  '#FFCC00',
  '#34C759',
  '#30D158',
  '#00C7BE',
  '#64748B',
];

String textAvatar(String name, [String color = '#5856D6']) {
  final trimmed = name.trim();
  final initial = trimmed.isEmpty
      ? '?'
      : String.fromCharCode(trimmed.runes.first).toUpperCase();
  final normalized = textAvatarColors.contains(color) ? color : '#5856D6';
  return 'letter:$initial:$normalized';
}

bool isTextAvatar(String avatar) =>
    avatar.isEmpty || avatar == 'text' || avatar.startsWith('letter:');
