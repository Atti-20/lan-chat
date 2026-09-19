import 'dart:convert';
import 'dart:collection';
import 'recovery.dart';
import 'persistent_proof_map.dart';

typedef SnapshotItem = Map<String, Object?>;
Never _bad() => throw const RecoveryProtocolError('PROTOCOL_ERROR');
SnapshotItem _object(Object? raw) {
  if (raw is! Map || raw.keys.any((key) => key is! String)) _bad();
  return Map<String, Object?>.from(raw);
}

void _fields(
  SnapshotItem raw,
  List<String> required, [
  List<String> optional = const [],
]) {
  if (required.any((key) => !raw.containsKey(key)) ||
      raw.keys.any(
        (key) => !required.contains(key) && !optional.contains(key),
      )) {
    _bad();
  }
}

String _id(Object? value) {
  if (value is! String || value.trim().isEmpty || value.length > 128) _bad();
  return value;
}

bool _integer(Object? value) =>
    value is num &&
    value.isFinite &&
    value == value.truncateToDouble() &&
    value.abs() <= 9007199254740991;

SnapshotItem parseSnapshotItem(Object? raw) {
  final value = _object(raw), cid = _id(_object(raw)['conversationId']);
  if (value['kind'] == 'CONVERSATION') {
    final read = value['readAllowed'], send = value['sendAllowed'];
    if (read is! bool || send is! bool || (!read && send)) _bad();
    _fields(value, [
      'kind',
      'conversationId',
      'accessVersion',
      'readAllowed',
      'sendAllowed',
      if (read) 'messageSequenceAtH',
    ]);
    recoveryDecimal(value['accessVersion'], allowZero: false);
    if (read) recoveryDecimal(value['messageSequenceAtH']);
    return {...value, 'conversationId': cid};
  }
  if (value['kind'] != 'MESSAGE') _bad();
  final state = value['state'];
  if (!{'NORMAL', 'RECALLED', 'BURNED', 'UNAVAILABLE'}.contains(state)) {
    throw const RecoveryProtocolError('UNSUPPORTED_SECURE_STATE');
  }
  _fields(value, [
    'kind',
    'conversationId',
    'messageId',
    'objectVersion',
    'state',
    'messageSequence',
    if (state == 'NORMAL') ...['content', 'details'],
  ], state == 'NORMAL' ? [] : ['content', 'details']);
  _id(value['messageId']);
  recoveryDecimal(value['objectVersion'], allowZero: false);
  recoveryDecimal(value['messageSequence'], allowZero: false);
  if (state != 'NORMAL') {
    if (value['content'] != null || value['details'] != null) _bad();
    return {...value}
      ..remove('content')
      ..remove('details');
  }
  if (value['content'] is! String) _bad();
  final details = _object(value['details']);
  _fields(
    details,
    ['fromUserId', 'contentType', 'createTime', 'isBurn'],
    ['clientMsgId', 'burnDuration', 'replyToId', 'mentionUserIds'],
  );
  if (!_integer(details['fromUserId']) ||
      (details['fromUserId'] as num) <= 0 ||
      !{0, 1}.contains(details['isBurn'])) {
    _bad();
  }
  _id(details['contentType']);
  final time = details['createTime'];
  if (time is! String ||
      !RegExp(
        r'^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d{1,9})?$',
      ).hasMatch(time)) {
    _bad();
  }
  final date = DateTime.tryParse('${time}Z');
  if (date == null ||
      date.toIso8601String().substring(0, 19) != time.substring(0, 19)) {
    _bad();
  }
  for (final key in ['clientMsgId', 'replyToId', 'mentionUserIds']) {
    if (details.containsKey(key) && details[key] is! String) _bad();
  }
  if (details.containsKey('burnDuration') &&
      (!_integer(details['burnDuration']) ||
          (details['burnDuration'] as num) < 0)) {
    _bad();
  }
  return {...value, 'details': details};
}

class RecoverySnapshotStage {
  RecoverySnapshotStage({
    required this.state,
    required this.snapshotId,
    required this.boundary,
    this.nextToken,
    this.complete = false,
    Set<String?> consumedTokens = const {},
    Map<String, String> positions = const {},
    this.count = 0,
    Map<String, String> sequences = const {},
  }) : consumedTokens = Set.unmodifiable(consumedTokens),
       positions = Map.unmodifiable(positions),
       sequences = Map.unmodifiable(sequences);
  // Page validation owns these fresh containers exclusively. Read-only views avoid
  // copying the entire sequence index a second time while preserving old stages.
  RecoverySnapshotStage._owned({
    required this.state,
    required this.snapshotId,
    required this.boundary,
    required this.nextToken,
    required this.complete,
    required Set<String?> consumedTokens,
    required Map<String, String> positions,
    required Map<String, String> sequences,
    required this.count,
  }) : consumedTokens = UnmodifiableSetView(consumedTokens),
       positions = UnmodifiableMapView(positions),
       sequences = sequences is PersistentProofMap<String>
           ? sequences
           : UnmodifiableMapView(sequences);
  final RecoveryState state;
  final String snapshotId, boundary;
  final String? nextToken;
  final bool complete;
  final Set<String?> consumedTokens;
  final Map<String, String> positions, sequences;
  final int count;
}

RecoverySnapshotStage beginFullRecoverySnapshot(
  RecoveryContext context,
  String snapshotId,
  String boundary,
) {
  _id(snapshotId);
  recoveryDecimal(boundary);
  return RecoverySnapshotStage(
    state: initialRecoveryState(
      context,
    ).copyWith(cursor: boundary, phase: RecoveryPhase.catchingUp),
    snapshotId: snapshotId,
    boundary: boundary,
  );
}

({RecoverySnapshotStage stage, List<SnapshotItem> items, bool ignored})
stageRecoverySnapshotPage(
  RecoverySnapshotStage current,
  RecoveryContext source,
  String? requestedToken,
  Object? raw, {
  int limit = 100,
}) {
  if (!current.state.context.matches(source) ||
      current.state.phase != RecoveryPhase.catchingUp ||
      current.consumedTokens.contains(requestedToken)) {
    return (stage: current, items: [], ignored: true);
  }
  if (current.complete || requestedToken != current.nextToken) _bad();
  final page = _object(raw);
  _fields(page, [
    'snapshotId',
    'boundary',
    'items',
    'nextPageToken',
    'snapshotComplete',
  ]);
  final items = page['items'], complete = page['snapshotComplete'];
  if (page['snapshotId'] != current.snapshotId ||
      page['boundary'] != current.boundary ||
      complete is! bool ||
      items is! List ||
      limit < 1 ||
      limit > 200 ||
      items.length > limit ||
      (requestedToken != null && items.isEmpty)) {
    _bad();
  }
  final next = complete ? null : _id(page['nextPageToken']);
  if ((complete && page['nextPageToken'] != null) ||
      (!complete &&
          (items.length != limit ||
              next == requestedToken ||
              current.consumedTokens.contains(next)))) {
    _bad();
  }
  final messages = ProofMapDraft<MessageRecoveryProof>.from(
        current.state.messages,
      ),
      access = {...current.state.access},
      positions = {...current.positions},
      sequences = ProofMapDraft<String>.from(current.sequences);
  final accepted = <SnapshotItem>[];
  for (final rawItem in items) {
    final item = parseSnapshotItem(rawItem),
        cid = _id(_object(rawItem)['conversationId']);
    if (item['kind'] == 'CONVERSATION') {
      final old = access[cid],
          version = recoveryDecimal(item['accessVersion'], allowZero: false);
      final previous = old == null
          ? BigInt.zero
          : recoveryDecimal(old.accessVersion, allowZero: false);
      if (version < previous) continue;
      if (version == previous &&
          (old?.readAllowed != item['readAllowed'] ||
              old?.sendAllowed != item['sendAllowed'])) {
        _bad();
      }
      access[cid] = AccessRecoveryProof(
        item['accessVersion'] as String,
        item['readAllowed'] as bool,
        item['sendAllowed'] as bool,
      );
      if (item['readAllowed'] == true) {
        if (positions.containsKey(cid) &&
            positions[cid] != item['messageSequenceAtH']) {
          _bad();
        }
        positions[cid] = item['messageSequenceAtH'] as String;
      } else {
        positions.remove(cid);
      }
    } else {
      if (!access.containsKey(cid)) _bad();
      if (!access[cid]!.readAllowed) continue;
      if (recoveryDecimal(item['messageSequence']) >
          recoveryDecimal(positions[cid])) {
        _bad();
      }
      final key = jsonEncode([cid, item['messageSequence']]),
          messageId = item['messageId'] as String;
      if (sequences.containsKey(key) && sequences[key] != messageId) _bad();
      final identityKey = 'id:$messageId';
      if (sequences.containsKey(identityKey) && sequences[identityKey] != key) {
        _bad();
      }
      sequences[identityKey] = key;
      sequences[key] = messageId;
      final old = messages[messageId],
          version = recoveryDecimal(item['objectVersion'], allowZero: false);
      final previous = old == null
          ? BigInt.zero
          : recoveryDecimal(old.objectVersion, allowZero: false);
      final state = MessageRecoveryState.values.byName(
        (item['state'] as String).toLowerCase(),
      );
      if (old != null && old.conversationId != cid) _bad();
      if (version < previous) continue;
      if (old != null &&
          ((version == previous && old.state != state) ||
              (version > previous &&
                  old.state != MessageRecoveryState.normal &&
                  old.state != state &&
                  state != MessageRecoveryState.unavailable))) {
        _bad();
      }
      messages[messageId] = MessageRecoveryProof(
        cid,
        item['objectVersion'] as String,
        state,
      );
    }
    accepted.add(item);
  }
  final count = current.count + items.length;
  if (count > 200100 || access.length > 100 || messages.length > 200000) {
    throw const RecoveryProtocolError('RECOVERY_CAPACITY');
  }
  return (
    stage: RecoverySnapshotStage._owned(
      state: current.state.copyWith(
        messages: messages.freeze(),
        access: access,
      ),
      snapshotId: current.snapshotId,
      boundary: current.boundary,
      nextToken: next,
      complete: complete,
      consumedTokens: {...current.consumedTokens, requestedToken},
      positions: positions,
      sequences: sequences.freeze(),
      count: count,
    ),
    items: accepted
        .where(
          (item) =>
              item['kind'] == 'CONVERSATION' ||
              (access[item['conversationId']]?.readAllowed == true &&
                  messages[item['messageId']]?.state.name.toUpperCase() ==
                      item['state'] &&
                  messages[item['messageId']]?.objectVersion ==
                      item['objectVersion']),
        )
        .toList(),
    ignored: false,
  );
}
