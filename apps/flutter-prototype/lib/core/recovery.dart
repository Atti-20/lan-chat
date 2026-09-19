import 'dart:convert';
import 'persistent_proof_map.dart';

Map<String, V> _freezeProofs<V>(Map<String, V> source) =>
    source is PersistentProofMap<V> ? source : Map.unmodifiable(source);

/// Body-free proofs. Store adapters commit them with existing messages/queues.
enum RecoveryPhase {
  quarantined,
  catchingUp,
  onlineSafe,
  storageBlocked,
  blockedUpgrade,
}

enum MessageRecoveryState { normal, recalled, burned, unavailable }

class RecoveryProtocolError implements Exception {
  const RecoveryProtocolError(this.reason);
  final String reason;
}

Never _fail([String reason = 'PROTOCOL_ERROR']) =>
    throw RecoveryProtocolError(reason);
final _uuid = RegExp(
  r'^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$',
);
BigInt recoveryDecimal(Object? value, {bool allowZero = true}) {
  if (value is! String || !RegExp(r'^(0|[1-9][0-9]{0,18})$').hasMatch(value)) {
    _fail();
  }
  final parsed = BigInt.parse(value);
  if (parsed > BigInt.parse('9223372036854775807') ||
      (!allowZero && parsed == BigInt.zero)) {
    _fail();
  }
  return parsed;
}

String _text(Object? value) {
  if (value is! String || value.trim().isEmpty || value.length > 128) _fail();
  return value;
}

void _keys(Map<String, Object?> value, List<String> keys) {
  if (value.length != keys.length ||
      keys.any((key) => !value.containsKey(key))) {
    _fail();
  }
}

class RecoveryContext {
  const RecoveryContext({
    required this.origin,
    required this.userId,
    required this.streamEpoch,
    required this.generation,
  });
  final String origin, userId, streamEpoch;
  final int generation;
  bool matches(RecoveryContext other) =>
      origin == other.origin &&
      userId == other.userId &&
      streamEpoch == other.streamEpoch &&
      generation == other.generation;
}

class MessageRecoveryProof {
  const MessageRecoveryProof(
    this.conversationId,
    this.objectVersion,
    this.state,
  );
  final String conversationId, objectVersion;
  final MessageRecoveryState state;
}

class AccessRecoveryProof {
  const AccessRecoveryProof(
    this.accessVersion,
    this.readAllowed,
    this.sendAllowed,
  );
  final String accessVersion;
  final bool readAllowed, sendAllowed;
}

class RecoveryState {
  RecoveryState({
    required this.context,
    this.phase = RecoveryPhase.quarantined,
    this.cursor = '0',
    Map<String, MessageRecoveryProof> messages = const {},
    Map<String, AccessRecoveryProof> access = const {},
    Map<String, String> seen = const {},
    Set<String> rebuild = const {},
    this.reason,
  }) : messages = _freezeProofs(messages),
       access = Map.unmodifiable(access),
       seen = Map.unmodifiable(seen),
       rebuild = Set.unmodifiable(rebuild);
  // Only immutable fields from an existing state or freshly frozen inputs enter here.
  RecoveryState._frozen({
    required this.context,
    required this.phase,
    required this.cursor,
    required this.messages,
    required this.access,
    required this.seen,
    required this.rebuild,
    this.reason,
  });
  final RecoveryContext context;
  final RecoveryPhase phase;
  final String cursor;
  final Map<String, MessageRecoveryProof> messages;
  final Map<String, AccessRecoveryProof> access;
  final Map<String, String> seen;
  final Set<String> rebuild;
  final String? reason;
  RecoveryState copyWith({
    RecoveryPhase? phase,
    String? cursor,
    Map<String, MessageRecoveryProof>? messages,
    Map<String, AccessRecoveryProof>? access,
    Map<String, String>? seen,
    Set<String>? rebuild,
    String? reason,
    bool clearReason = false,
  }) => RecoveryState._frozen(
    context: context,
    phase: phase ?? this.phase,
    cursor: cursor ?? this.cursor,
    messages: messages == null ? this.messages : _freezeProofs(messages),
    access: access == null ? this.access : Map.unmodifiable(access),
    seen: seen == null ? this.seen : Map.unmodifiable(seen),
    rebuild: rebuild == null ? this.rebuild : Set.unmodifiable(rebuild),
    reason: clearReason ? null : reason ?? this.reason,
  );
}

RecoveryState initialRecoveryState(RecoveryContext context) {
  if (context.origin.isEmpty ||
      !_uuid.hasMatch(context.streamEpoch) ||
      context.generation < 0 ||
      context.generation > 9007199254740991) {
    _fail();
  }
  recoveryDecimal(context.userId, allowZero: false);
  return RecoveryState(context: context);
}

class RecoveryMutation {
  const RecoveryMutation._({
    required this.eventId,
    required this.streamEpoch,
    required this.cursor,
    required this.type,
    required this.conversationId,
    required this.committedAt,
    this.messageId,
    this.objectVersion,
    this.accessVersion,
    this.readAllowed,
    this.sendAllowed,
    this.rebuildConversation,
    this.reason,
  });
  final String eventId, streamEpoch, cursor, type, conversationId, committedAt;
  final String? messageId, objectVersion, accessVersion, reason;
  final bool? readAllowed, sendAllowed, rebuildConversation;
  bool get isMessage => messageId != null;
  String get fingerprint => jsonEncode([
    eventId,
    streamEpoch,
    cursor,
    type,
    conversationId,
    messageId,
    objectVersion,
    accessVersion,
    readAllowed,
    sendAllowed,
    rebuildConversation,
    reason,
    committedAt,
  ]);

  factory RecoveryMutation.parse(Object? raw) {
    if (raw is! Map || raw.keys.any((key) => key is! String)) _fail();
    final value = Map<String, Object?>.from(raw);
    if (value['recordVersion'] != 1) _fail('UNSUPPORTED_SECURE_STATE');
    final eventId = _text(value['eventId']),
        epoch = _text(value['streamEpoch']),
        cursor = _text(value['cursor']);
    final cid = _text(value['conversationId']),
        committed = _text(value['committedAt']);
    if (!_uuid.hasMatch(eventId) || !_uuid.hasMatch(epoch)) _fail();
    recoveryDecimal(cursor, allowZero: false);
    if (!RegExp(
          r'^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z$',
        ).hasMatch(committed) ||
        DateTime.tryParse(committed)?.toUtc().toIso8601String() != committed) {
      _fail();
    }
    const common = [
      'recordVersion',
      'eventId',
      'streamEpoch',
      'cursor',
      'conversationId',
      'committedAt',
      'type',
    ];
    final type = value['type'];
    if (const [
      'MESSAGE_RECALLED',
      'MESSAGE_BURNED',
      'MESSAGE_UNAVAILABLE',
    ].contains(type)) {
      _keys(value, [...common, 'messageId', 'objectVersion']);
      final version = _text(value['objectVersion']);
      recoveryDecimal(version, allowZero: false);
      return RecoveryMutation._(
        eventId: eventId,
        streamEpoch: epoch,
        cursor: cursor,
        type: type as String,
        conversationId: cid,
        committedAt: committed,
        messageId: _text(value['messageId']),
        objectVersion: version,
      );
    }
    if (type != 'CONVERSATION_ACCESS_REVOKED' &&
        type != 'CONVERSATION_ACCESS_CHANGED') {
      _fail('UNSUPPORTED_SECURE_STATE');
    }
    final version = _text(value['accessVersion']),
        reason = _text(value['reason']);
    recoveryDecimal(version, allowZero: false);
    if (type == 'CONVERSATION_ACCESS_REVOKED') {
      _keys(value, [
        ...common,
        'accessVersion',
        'readAllowed',
        'sendAllowed',
        'reason',
      ]);
      if (value['readAllowed'] != false ||
          value['sendAllowed'] != false ||
          !const [
            'REMOVED',
            'GROUP_REMOVED',
            'DESTROYED',
            'ACCESS_REVOKED',
          ].contains(reason)) {
        _fail();
      }
      return RecoveryMutation._(
        eventId: eventId,
        streamEpoch: epoch,
        cursor: cursor,
        type: type as String,
        conversationId: cid,
        committedAt: committed,
        accessVersion: version,
        readAllowed: false,
        sendAllowed: false,
        reason: reason,
      );
    }
    _keys(value, [
      ...common,
      'accessVersion',
      'readAllowed',
      'sendAllowed',
      'rebuildConversation',
      'reason',
    ]);
    final send = value['sendAllowed'], rebuild = value['rebuildConversation'];
    if (value['readAllowed'] != true ||
        send is! bool ||
        rebuild is! bool ||
        !const [
          'FRIEND_DELETED',
          'SEND_DENIED',
          'GRANTED',
          'UPDATED',
        ].contains(reason) ||
        (const ['FRIEND_DELETED', 'SEND_DENIED'].contains(reason) &&
            (send || rebuild)) ||
        (reason == 'GRANTED' && !rebuild)) {
      _fail();
    }
    return RecoveryMutation._(
      eventId: eventId,
      streamEpoch: epoch,
      cursor: cursor,
      type: type as String,
      conversationId: cid,
      committedAt: committed,
      accessVersion: version,
      readAllowed: true,
      sendAllowed: send,
      rebuildConversation: rebuild,
      reason: reason,
    );
  }
}

class RecoveryEffects {
  RecoveryEffects({
    Set<String> eraseMessages = const {},
    Set<String> revokeConversations = const {},
    Set<String> stopAutomaticSend = const {},
    Set<String> rebuildConversations = const {},
  }) : eraseMessages = Set.unmodifiable(eraseMessages),
       revokeConversations = Set.unmodifiable(revokeConversations),
       stopAutomaticSend = Set.unmodifiable(stopAutomaticSend),
       rebuildConversations = Set.unmodifiable(rebuildConversations);
  final Set<String> eraseMessages,
      revokeConversations,
      stopAutomaticSend,
      rebuildConversations;
}

class RecoveryPlan {
  const RecoveryPlan(
    this.next,
    this.effects, {
    required this.changed,
    this.ignored = false,
  });
  final RecoveryState next;
  final RecoveryEffects effects;
  final bool changed, ignored;
}

RecoveryPlan planRecoveryMutations(
  RecoveryState current,
  RecoveryContext source,
  List<Object?> raw,
) {
  if (!current.context.matches(source) ||
      current.phase == RecoveryPhase.storageBlocked ||
      current.phase == RecoveryPhase.blockedUpgrade) {
    return RecoveryPlan(
      current,
      RecoveryEffects(),
      changed: false,
      ignored: true,
    );
  }
  final messages = ProofMapDraft<MessageRecoveryProof>.from(current.messages),
      access = Map<String, AccessRecoveryProof>.from(current.access);
  final seen = Map<String, String>.from(current.seen),
      rebuild = Set<String>.from(current.rebuild);
  final erase = <String>{},
      revoke = <String>{},
      stop = <String>{},
      rebuildEffects = <String>{};
  var cursor = current.cursor, changed = false;
  try {
    if (raw.length > 200) _fail();
    for (final value in raw) {
      final record = RecoveryMutation.parse(value);
      if (record.streamEpoch != current.context.streamEpoch) {
        _fail('STREAM_RESET');
      }
      final position = recoveryDecimal(record.cursor, allowZero: false),
          last = recoveryDecimal(cursor);
      final eventPosition = seen['event:${record.eventId}'];
      if (eventPosition != null && eventPosition != record.cursor) _fail();
      if (position <= last) {
        if (seen.containsKey(record.cursor) &&
            seen[record.cursor] != record.fingerprint) {
          _fail();
        }
        continue;
      }
      if (position != last + BigInt.one) _fail('CURSOR_GAP');
      if (record.isMessage) {
        final previous = messages[record.messageId];
        final state = switch (record.type) {
          'MESSAGE_RECALLED' => MessageRecoveryState.recalled,
          'MESSAGE_BURNED' => MessageRecoveryState.burned,
          _ => MessageRecoveryState.unavailable,
        };
        if (previous != null &&
            previous.conversationId != record.conversationId) {
          _fail();
        }
        final version = recoveryDecimal(record.objectVersion, allowZero: false);
        final before = previous == null
            ? BigInt.zero
            : recoveryDecimal(previous.objectVersion, allowZero: false);
        if (version == before && previous?.state != state) _fail();
        if (version > before) {
          if (previous != null &&
              previous.state != MessageRecoveryState.normal &&
              previous.state != state &&
              state != MessageRecoveryState.unavailable) {
            _fail();
          }
          messages[record.messageId!] = MessageRecoveryProof(
            record.conversationId,
            record.objectVersion!,
            state,
          );
          erase.add(record.messageId!);
        }
      } else {
        final previous = access[record.conversationId],
            version = recoveryDecimal(record.accessVersion, allowZero: false);
        final before = previous == null
            ? BigInt.zero
            : recoveryDecimal(previous.accessVersion, allowZero: false);
        if (version == before &&
            (previous?.readAllowed != record.readAllowed ||
                previous?.sendAllowed != record.sendAllowed)) {
          _fail();
        }
        if (version >= before) {
          access[record.conversationId] = AccessRecoveryProof(
            record.accessVersion!,
            record.readAllowed!,
            record.sendAllowed!,
          );
          if (!record.readAllowed!) {
            revoke.add(record.conversationId);
            stop.add(record.conversationId);
            rebuild.remove(record.conversationId);
          } else {
            if (!record.sendAllowed!) stop.add(record.conversationId);
            if (record.rebuildConversation!) {
              rebuild.add(record.conversationId);
              rebuildEffects.add(record.conversationId);
            }
          }
        }
      }
      seen[record.cursor] = record.fingerprint;
      seen['event:${record.eventId}'] = record.cursor;
      cursor = record.cursor;
      changed = true;
    }
    final effects = RecoveryEffects(
      eraseMessages: erase,
      revokeConversations: revoke,
      stopAutomaticSend: stop,
      rebuildConversations: rebuildEffects,
    );
    if (!changed) return RecoveryPlan(current, effects, changed: false);
    return RecoveryPlan(
      current.copyWith(
        cursor: cursor,
        messages: messages.freeze(),
        access: access,
        seen: seen,
        rebuild: rebuild,
        clearReason: true,
        phase: current.phase == RecoveryPhase.quarantined || rebuild.isNotEmpty
            ? RecoveryPhase.catchingUp
            : current.phase,
      ),
      effects,
      changed: true,
    );
  } on RecoveryProtocolError catch (error) {
    return RecoveryPlan(
      current.copyWith(phase: RecoveryPhase.quarantined, reason: error.reason),
      RecoveryEffects(),
      changed: false,
    );
  }
}

bool recoveryMessageVisible(RecoveryState state, String id) {
  final proof = state.messages[id];
  return state.phase == RecoveryPhase.onlineSafe &&
      proof?.state == MessageRecoveryState.normal &&
      state.access[proof!.conversationId]?.readAllowed == true &&
      !state.rebuild.contains(proof.conversationId);
}

RecoveryState quarantineRecovery(RecoveryState state, String reason) =>
    state.copyWith(phase: RecoveryPhase.quarantined, reason: reason);

/// Check the envelope against the exact HTTP request before applying any fact.
RecoveryPlan planRecoveryPage(
  RecoveryState current,
  RecoveryContext source,
  String after,
  String cut,
  Object? raw, {
  int limit = 100,
}) {
  if (!current.context.matches(source) ||
      current.phase == RecoveryPhase.storageBlocked ||
      current.phase == RecoveryPhase.blockedUpgrade) {
    return RecoveryPlan(
      current,
      RecoveryEffects(),
      changed: false,
      ignored: true,
    );
  }
  try {
    if (raw is! Map) _fail();
    const keys = {
      'records',
      'fromExclusive',
      'through',
      'nextCursor',
      'hasMore',
      'floor',
      'latest',
      'streamEpoch',
    };
    if (raw.length != keys.length ||
        raw.keys.any((key) => !keys.contains(key))) {
      _fail();
    }
    if (raw['streamEpoch'] != source.streamEpoch) _fail('STREAM_RESET');
    final start = recoveryDecimal(after), end = recoveryDecimal(cut);
    final records = raw['records'];
    if (raw['fromExclusive'] != after ||
        raw['through'] != cut ||
        start > end ||
        start > recoveryDecimal(current.cursor) ||
        limit < 1 ||
        limit > 200 ||
        records is! List ||
        records.length > limit) {
      _fail();
    }
    final floor = recoveryDecimal(raw['floor']),
        latest = recoveryDecimal(raw['latest']);
    final next = recoveryDecimal(raw['nextCursor']);
    if (floor > start) _fail('CURSOR_EXPIRED');
    if (latest < end ||
        next < start ||
        next > end ||
        raw['hasMore'] != (next < end) ||
        next != start + BigInt.from(records.length) ||
        (next < end && records.length != limit)) {
      _fail();
    }
    for (var index = 0; index < records.length; index++) {
      final record = RecoveryMutation.parse(records[index]);
      if (recoveryDecimal(record.cursor) !=
          start + BigInt.from(index) + BigInt.one) {
        _fail('CURSOR_GAP');
      }
    }
    return planRecoveryMutations(current, source, records.cast<Object?>());
  } on RecoveryProtocolError catch (error) {
    return RecoveryPlan(
      quarantineRecovery(current, error.reason),
      RecoveryEffects(),
      changed: false,
    );
  }
}

RecoveryState storageBlockedRecovery(RecoveryState state) => state.copyWith(
  phase: RecoveryPhase.storageBlocked,
  reason: 'PERSISTENCE_FAILED',
);

/// Supply the durably committed generation, never an uncommitted page plan.
RecoveryState acceptRecoveryReady(
  RecoveryState current,
  RecoveryContext source,
  String cut,
  bool snapshotComplete,
  Object? raw,
) {
  if (!current.context.matches(source) ||
      (current.phase != RecoveryPhase.catchingUp &&
          current.phase != RecoveryPhase.onlineSafe)) {
    return current;
  }
  try {
    if (raw is! Map) _fail();
    const allowed = {
      'ready',
      'acceptedCursor',
      'latest',
      'streamEpoch',
      'receiptId',
    };
    if (raw.keys.any((key) => !allowed.contains(key)) ||
        raw['ready'] is! bool) {
      _fail();
    }
    if (raw['streamEpoch'] != current.context.streamEpoch) {
      _fail('STREAM_RESET');
    }
    final accepted = recoveryDecimal(raw['acceptedCursor']);
    final latest = recoveryDecimal(raw['latest']);
    if (recoveryDecimal(cut) != accepted ||
        recoveryDecimal(current.cursor) != accepted ||
        latest < accepted ||
        raw['ready'] != (latest == accepted)) {
      _fail();
    }
    if (raw['receiptId'] != null) _text(raw['receiptId']);
    if (!snapshotComplete || current.rebuild.isNotEmpty) {
      return current.copyWith(phase: RecoveryPhase.catchingUp);
    }
    return current.copyWith(
      phase: raw['ready'] == true
          ? RecoveryPhase.onlineSafe
          : RecoveryPhase.catchingUp,
      clearReason: true,
    );
  } on RecoveryProtocolError catch (error) {
    return quarantineRecovery(current, error.reason);
  }
}
