import '../core/models.dart';

int _id(Json value, String key) {
  final raw = value[key];
  final parsed = raw is int ? raw : int.tryParse('$raw');
  if (parsed == null || parsed <= 0) {
    throw FormatException('广播数据缺少有效的 $key');
  }
  return parsed;
}

String _text(dynamic value) => value is String ? value.trim() : '';

class BroadcastSummary {
  const BroadcastSummary({
    required this.id,
    required this.senderId,
    required this.title,
    required this.content,
    required this.status,
    required this.priority,
    required this.confirmationRequired,
    required this.requireImageProof,
    required this.requireLocationProof,
    this.currentUserConfirmStatus = '',
    this.deadlineAt,
  });

  final int id, senderId;
  final String title, content, status, priority, currentUserConfirmStatus;
  final bool confirmationRequired, requireImageProof, requireLocationProof;
  final DateTime? deadlineAt;

  bool get active => status == 'ACTIVE';
  bool get expired => deadlineAt?.isBefore(DateTime.now()) == true;

  factory BroadcastSummary.fromJson(Json value) {
    final status = _text(value['status']).toUpperCase();
    final priority = _text(value['priority']).toUpperCase();
    if (!{'ACTIVE', 'COMPLETED', 'CANCELLED'}.contains(status) ||
        !{'NORMAL', 'IMPORTANT', 'EMERGENCY'}.contains(priority)) {
      throw const FormatException('广播状态无效');
    }
    final title = _text(value['title']);
    if (title.isEmpty) throw const FormatException('广播标题为空');
    return BroadcastSummary(
      id: _id(value, 'id'),
      senderId: _id(value, 'senderId'),
      title: title,
      content: _text(value['content']),
      status: status,
      priority: priority,
      confirmationRequired: value['confirmationRequired'] == true,
      requireImageProof: value['requireImageProof'] == true,
      requireLocationProof: value['requireLocationProof'] == true,
      currentUserConfirmStatus: _text(
        value['currentUserConfirmStatus'],
      ).toUpperCase(),
      deadlineAt: DateTime.tryParse('${value['deadlineAt'] ?? ''}'),
    );
  }
}

class BroadcastReceiverState {
  const BroadcastReceiverState({
    required this.id,
    required this.broadcastId,
    required this.userId,
    required this.confirmStatus,
    required this.targetStatus,
    this.viewedAt,
    this.confirmedAt,
    this.completedAt,
  });
  final int id, broadcastId, userId;
  final String confirmStatus, targetStatus;
  final DateTime? viewedAt, confirmedAt, completedAt;
  bool get targetActive => targetStatus == 'ACTIVE';

  factory BroadcastReceiverState.fromJson(Json value) {
    final target = _text(value['targetStatus']).toUpperCase();
    final confirmation = _text(value['confirmStatus']).toUpperCase();
    if (!{'ACTIVE', 'REMOVED'}.contains(target) || confirmation.isEmpty) {
      throw const FormatException('广播接收状态无效');
    }
    return BroadcastReceiverState(
      id: _id(value, 'id'),
      broadcastId: _id(value, 'broadcastId'),
      userId: _id(value, 'userId'),
      confirmStatus: confirmation,
      targetStatus: target,
      viewedAt: DateTime.tryParse('${value['viewedAt'] ?? ''}'),
      confirmedAt: DateTime.tryParse('${value['confirmedAt'] ?? ''}'),
      completedAt: DateTime.tryParse('${value['completedAt'] ?? ''}'),
    );
  }
}

class BroadcastDetail {
  const BroadcastDetail({
    required this.broadcast,
    required this.receiver,
    required this.senderName,
    required this.confirmationOptions,
    required this.contentImageUrls,
  });
  final BroadcastSummary broadcast;
  final BroadcastReceiverState? receiver;
  final String senderName;
  final List<String> confirmationOptions, contentImageUrls;

  bool get canSubmit =>
      receiver?.targetActive == true && broadcast.active && !broadcast.expired;
  bool get locationReadOnly => broadcast.requireLocationProof;

  factory BroadcastDetail.fromJson(Json value) {
    final rawBroadcast = value['broadcast'];
    if (rawBroadcast is! Json) throw const FormatException('广播详情格式无效');
    final rawReceiver = value['receiver'];
    final rawSender = value['sender'];
    final rawEvidence = value['contentEvidence'];
    final rawOptions = value['confirmationOptions'];
    if (rawOptions is! List) throw const FormatException('广播确认选项无效');
    final options = rawOptions
        .map((item) => _text(item).toUpperCase())
        .toList();
    if (options.any((item) => item.isEmpty) ||
        options.toSet().length != options.length) {
      throw const FormatException('广播确认选项无效');
    }
    final images = rawEvidence is Json && rawEvidence['imageUrls'] is List
        ? (rawEvidence['imageUrls'] as List)
              .map(_text)
              .where((item) => item.isNotEmpty)
              .toList()
        : <String>[];
    return BroadcastDetail(
      broadcast: BroadcastSummary.fromJson(rawBroadcast),
      receiver: rawReceiver is Json
          ? BroadcastReceiverState.fromJson(rawReceiver)
          : null,
      senderName: rawSender is Json ? _text(rawSender['nickname']) : '',
      confirmationOptions: List.unmodifiable(options),
      contentImageUrls: List.unmodifiable(images),
    );
  }
}

class BroadcastImageUpload {
  const BroadcastImageUpload(this.id);
  final int id;
  factory BroadcastImageUpload.fromJson(Json value) =>
      BroadcastImageUpload(_id(value, 'id'));
}
