import '../core/models.dart';

class TemporaryRoom {
  const TemporaryRoom({
    required this.id,
    required this.name,
    required this.code,
    required this.status,
    required this.expiresAt,
    required this.ownerId,
    this.allowFileUpload = false,
    this.allowFileDownload = false,
  });
  final int id, ownerId;
  final bool allowFileUpload, allowFileDownload;
  final String name, code, status;
  final DateTime expiresAt;
  String get conversationId => 'temporary:$id';
  bool get available => status == 'ACTIVE' && expiresAt.isAfter(DateTime.now());
  factory TemporaryRoom.fromJson(Json json) {
    final id = integer(json['id']), owner = integer(json['ownerId']);
    final expiry = DateTime.tryParse('${json['expiresAt']}');
    if (id <= 0 || owner <= 0 || expiry == null) {
      throw const FormatException('临时房间信息无效');
    }
    return TemporaryRoom(
      id: id,
      allowFileUpload: json['allowFileUpload'] == true,
      allowFileDownload: json['allowFileDownload'] == true,
      ownerId: owner,
      name: json['roomName'] as String? ?? '临时房间',
      code: json['roomCode'] as String? ?? '',
      status: '${json['status']}'.toUpperCase(),
      expiresAt: expiry,
    );
  }
}
