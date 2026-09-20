import '../core/json_views.dart';
import '../core/models.dart';
import '../core/notification_recovery.dart';
import '../core/store.dart';
import '../core/recovery.dart';
import '../core/recovery_data.dart';
import '../core/recovery_snapshot.dart';
import 'recovery_coordinator.dart';

// Top-level serializers avoid sending controller closures with isolated storage.
Json _encodeChatMessage(ChatMessage message) => message.toJson();
Json _encodeMessageProof(MessageRecoveryProof proof) => {
  'conversationId': proof.conversationId,
  'objectVersion': proof.objectVersion,
  'state': proof.state.name.toUpperCase(),
};

/// Existing chat models plus body-free terminal headers; no invented sender/date for tombstones.
class RecoveredChatImage {
  RecoveredChatImage(
    this.chat,
    this.outbox,
    this.tombstones,
    this.notificationRecovery,
  );
  final Json notificationRecovery;
  final Map<String, List<ChatMessage>> outbox;
  final RecoveryChatData chat;
  final Map<String, SnapshotItem> tombstones;
}

class RecoveryChatSink implements RecoverySink {
  RecoveryChatSink({
    required this.store,
    required this.seed,
    required this.isCurrent,
    required this.onPublish,
    required this.onQuarantine,
    this.liveCandidates,
  });
  final List<String> Function()? liveCandidates;
  final RecoveryChatStore store;
  final RecoveryChatData Function() seed;
  final bool Function(RecoveryContext) isCurrent;
  final void Function(RecoveryState, RecoveredChatImage) onPublish;
  final void Function(String) onQuarantine;
  RecoveryContext? _context;
  int _revision = 0, _run = 0;
  bool _active = false;
  String? _committedCursor;
  RecoveryChatData? _chat;
  RecoveryNotificationJournal _notifications = RecoveryNotificationJournal();
  final Map<String, SnapshotItem> _tombstones = {};
  final Map<String, String> _titles = {};
  String _owner(RecoveryContext context) =>
      '${context.origin}|${context.userId}';
  void _check(RecoveryContext context) {
    if (!_active || _context?.matches(context) != true || !isCurrent(context)) {
      throw const RecoveryProtocolError('STALE_RECOVERY');
    }
  }

  int _sequence(Object? value) {
    final n = recoveryDecimal(value);
    if (n > BigInt.from(9007199254740991)) {
      throw const RecoveryProtocolError('UNSUPPORTED_SECURE_STATE');
    }
    return n.toInt();
  }

  Conversation _conversation(String cid, RecoveryContext context) {
    final parts = cid.split(':');
    String kind;
    int target;
    if (parts.length == 3 &&
        parts[0] == 'private' &&
        (parts[1] == context.userId || parts[2] == context.userId)) {
      kind = 'private';
      target = _sequence(parts[1] == context.userId ? parts[2] : parts[1]);
    } else if (parts.length == 2 && {'group', 'temporary'}.contains(parts[0])) {
      kind = parts[0];
      target = _sequence(parts[1]);
    } else {
      throw const RecoveryProtocolError('PROTOCOL_ERROR');
    }
    if (target <= 0) throw const RecoveryProtocolError('PROTOCOL_ERROR');
    return Conversation(
      id: cid,
      targetId: target,
      kind: kind,
      title: _titles[cid] ?? '会话 $target',
    );
  }

  @override
  Future<void> begin(RecoveryContext context) async {
    final run = ++_run;
    _context = context;
    _active = true;
    _committedCursor = null;
    _check(context);
    final old = seed();
    _titles.clear();
    _tombstones.clear();
    for (final conversation in old.conversations) {
      _titles[conversation.id] = conversation.title;
    }
    final pending = <String, List<ChatMessage>>{};
    for (final entry in old.messages.entries) {
      final held = entry.value
          .where(
            (message) =>
                message.fromUserId.toString() == context.userId &&
                (message.delivery != Delivery.sent ||
                    message.recoveryDisposition != null),
          )
          .map(
            (message) => message.withRecoveryDisposition(
              RecoveryDisposition.needsUserAction,
            ),
          )
          .toList();
      if (held.isNotEmpty) pending[entry.key] = held;
    }
    _chat = RecoveryChatData(pending, [], {});
    final existing = await store.loadRecovery(_owner(context));
    _check(context);
    if (run != _run) {
      throw const RecoveryProtocolError('STALE_RECOVERY');
    }
    _revision = existing?.revision ?? 0;
    _notifications = RecoveryNotificationJournal(
      existing?.snapshot['notificationRecovery'],
    );
    if (existing != null) {
      final saved = existing.snapshot['outbox'];
      if (saved is! Map) {
        throw const RecoveryProtocolError('UNSUPPORTED_SECURE_STATE');
      }
      for (final entry in saved.entries) {
        if (entry.key is! String || entry.value is! List) {
          throw const RecoveryProtocolError('UNSUPPORTED_SECURE_STATE');
        }
        final rows = (entry.value as List).map((raw) {
          final message = ChatMessage.restore(
            Map<String, dynamic>.from(raw as Map),
          );
          if (message.conversationId != entry.key ||
              message.fromUserId.toString() != context.userId ||
              message.delivery == Delivery.sent) {
            throw const RecoveryProtocolError('UNSUPPORTED_SECURE_STATE');
          }
          return message.withRecoveryDisposition(
            RecoveryDisposition.needsUserAction,
          );
        }).toList();
        pending[entry.key as String] = mergeMessages(
          rows,
          pending[entry.key] ?? [],
        );
      }
    }
  }

  @override
  Future<void> resume(RecoveryContext context, RecoveryState state) async {
    if (_context?.matches(context) != true ||
        !state.context.matches(context) ||
        _chat == null ||
        !isCurrent(context)) {
      throw const RecoveryProtocolError('STALE_RECOVERY');
    }
    ++_run;
    _active = true;
    _committedCursor = null;
  }

  @override
  Future<void> snapshot(
    RecoveryContext context,
    List<SnapshotItem> items,
  ) async {
    _check(context);
    _committedCursor = null;
    var chat = _chat!;
    for (final item in items) {
      final cid = item['conversationId'] as String;
      if (item['kind'] == 'CONVERSATION') {
        if (item['readAllowed'] == false) {
          final plan = RecoveryPlan(
            RecoveryState(context: context),
            RecoveryEffects(
              revokeConversations: {cid},
              stopAutomaticSend: {cid},
            ),
            changed: true,
          );
          chat = applyRecoveryChatData(plan, chat);
          chat = RecoveryChatData(
            chat.messages,
            chat.conversations.where((c) => c.id != cid).toList(),
            chat.positions,
          );
          _tombstones.removeWhere((_, value) => value['conversationId'] == cid);
        } else {
          final conversations = [
            ...chat.conversations.where((c) => c.id != cid),
            _conversation(cid, context),
          ];
          chat = RecoveryChatData(chat.messages, conversations, {
            ...chat.positions,
            cid: _sequence(item['messageSequenceAtH']),
          });
        }
      } else if (item['state'] == 'NORMAL') {
        final details = Map<String, dynamic>.from(item['details'] as Map);
        final message = ChatMessage.fromJson({
          ...details,
          'messageId': item['messageId'],
          'conversationId': cid,
          'content': item['content'],
          'sequence': _sequence(item['messageSequence']),
        });
        final rows = [...chat.messages[cid] ?? <ChatMessage>[]];
        rows.removeWhere(
          (old) =>
              old.delivery == Delivery.sent &&
              old.messageId == message.messageId,
        );
        rows.add(message);
        chat = RecoveryChatData(
          {...chat.messages, cid: rows},
          chat.conversations,
          chat.positions,
        );
      } else {
        final id = item['messageId'] as String;
        _tombstones[id] = {...item};
        final proof = MessageRecoveryProof(
          cid,
          item['objectVersion'] as String,
          MessageRecoveryState.values.byName(
            (item['state'] as String).toLowerCase(),
          ),
        );
        chat = applyRecoveryChatData(
          RecoveryPlan(
            RecoveryState(context: context, messages: {id: proof}),
            RecoveryEffects(eraseMessages: {id}),
            changed: true,
          ),
          chat,
        );
        chat = RecoveryChatData(
          {
            ...chat.messages,
            cid: (chat.messages[cid] ?? [])
                .where((m) => m.messageId != id || m.delivery != Delivery.sent)
                .toList(),
          },
          chat.conversations,
          chat.positions,
        );
      }
    }
    _chat = chat;
  }

  @override
  Future<void> mutations(RecoveryContext context, RecoveryPlan plan) async {
    _check(context);
    _committedCursor = null;
    _chat = applyRecoveryChatData(plan, _chat!);
    for (final cid in plan.effects.revokeConversations) {
      _chat = RecoveryChatData(
        _chat!.messages,
        _chat!.conversations.where((c) => c.id != cid).toList(),
        _chat!.positions,
      );
      _tombstones.removeWhere((_, value) => value['conversationId'] == cid);
    }
    for (final id in plan.effects.eraseMessages) {
      final proof = plan.next.messages[id]!;
      final rows = _chat!.messages[proof.conversationId] ?? [];
      final matches = rows.where((m) => m.messageId == id && m.sequence > 0);
      final old = _tombstones[id];
      _tombstones[id] = {
        'kind': 'MESSAGE',
        'conversationId': proof.conversationId,
        'messageId': id,
        'objectVersion': proof.objectVersion,
        'state': proof.state.name.toUpperCase(),
        if (matches.isNotEmpty)
          'messageSequence': '${matches.first.sequence}'
        else if (old?['messageSequence'] != null)
          'messageSequence': old!['messageSequence'],
      };
    }
  }

  void planLiveNotifications(
    RecoveryContext context,
    RecoveryState state,
    List<String> ids,
  ) {
    _check(context);
    _committedCursor = null;
    _notifications.reconcile(state, ids);
  }

  void acknowledgeNotification(RecoveryContext context, String key) {
    _check(context);
    _committedCursor = null;
    _notifications.acknowledge(key);
  }

  /// User-created pending rows are persisted with the already verified history.
  /// Incoming WS/history bodies must never enter through this method.
  void replacePending(
    RecoveryContext context,
    Map<String, List<ChatMessage>> rows,
  ) {
    _check(context);
    _committedCursor = null;
    final combined = <String, List<ChatMessage>>{
      for (final entry in _chat!.messages.entries)
        entry.key: entry.value
            .where((m) => m.delivery == Delivery.sent)
            .toList(),
    };
    for (final entry in rows.entries) {
      for (final message in entry.value) {
        if (message.delivery == Delivery.sent) continue;
        if (message.fromUserId.toString() != context.userId ||
            message.conversationId != entry.key) {
          throw const RecoveryProtocolError('STALE_RECOVERY');
        }
        combined.putIfAbsent(entry.key, () => []).add(message);
      }
    }
    _chat = RecoveryChatData(combined, _chat!.conversations, _chat!.positions);
  }

  @override
  Future<void> commit(RecoveryContext context, RecoveryState state) async {
    _check(context);
    if (!state.context.matches(context)) {
      throw const RecoveryProtocolError('STALE_RECOVERY');
    }
    _committedCursor = null;
    final absent = _chat!.messages.keys
        .where((cid) => state.access[cid]?.readAllowed != true)
        .toSet();
    _chat = applyRecoveryChatData(
      RecoveryPlan(
        state,
        RecoveryEffects(revokeConversations: absent, stopAutomaticSend: absent),
        changed: absent.isNotEmpty,
      ),
      _chat!,
    );
    final liveIds = (liveCandidates?.call() ?? <String>[])
        .where(
          (id) => _chat!.messages.values
              .expand((rows) => rows)
              .any(
                (m) =>
                    m.messageId == id &&
                    m.fromUserId.toString() != context.userId,
              ),
        )
        .toList();
    _notifications.reconcile(state, liveIds);
    final image = _image(state);
    final chat = image.chat;
    final snapshot = <String, dynamic>{
      'conversations': chat.conversations.map((c) => c.toJson()).toList(),
      'messages': {
        for (final entry in chat.messages.entries)
          entry.key: JsonListView(entry.value, _encodeChatMessage),
      },
      'outbox': {
        for (final entry in image.outbox.entries)
          entry.key: JsonListView(entry.value, _encodeChatMessage),
      },
      'positions': chat.positions,
      'notificationRecovery': _notifications.image(),
      'tombstones': _tombstones,
      'recovery': {
        'origin': context.origin,
        'userId': context.userId,
        'streamEpoch': context.streamEpoch,
        'cursor': state.cursor,
        'messages': JsonMapView(state.messages, _encodeMessageProof),
        'access': {
          for (final entry in state.access.entries)
            entry.key: {
              'accessVersion': entry.value.accessVersion,
              'readAllowed': entry.value.readAllowed,
              'sendAllowed': entry.value.sendAllowed,
            },
        },
        'seen': state.seen,
        'rebuild': state.rebuild.toList(),
      },
    };
    final run = _run;
    final revision = await store.commitRecovery(
      _owner(context),
      _revision,
      snapshot,
    );
    _check(context);
    if (run != _run) {
      throw const RecoveryProtocolError('STALE_RECOVERY');
    }
    _revision = revision;
    _committedCursor = state.cursor;
  }

  @override
  void publish(RecoveryContext context, RecoveryState state) {
    _check(context);
    if (!state.context.matches(context) ||
        state.phase != RecoveryPhase.onlineSafe ||
        state.cursor != _committedCursor) {
      throw const RecoveryProtocolError('PROTOCOL_ERROR');
    }
    onPublish(state, _image(state));
  }

  RecoveredChatImage _image(RecoveryState state) {
    final history = <String, List<ChatMessage>>{};
    final outbox = <String, List<ChatMessage>>{};
    for (final entry in _chat!.messages.entries) {
      history[entry.key] = entry.value
          .where(
            (m) =>
                m.delivery == Delivery.sent &&
                state.access[m.conversationId]?.readAllowed == true &&
                state.messages[m.messageId]?.state ==
                    MessageRecoveryState.normal,
          )
          .toList();
      outbox[entry.key] = entry.value
          .where((m) => m.delivery != Delivery.sent)
          .toList();
    }
    return RecoveredChatImage(
      RecoveryChatData(history, _chat!.conversations, _chat!.positions),
      outbox,
      Map.unmodifiable(_tombstones),
      _notifications.image(),
    );
  }

  @override
  void quarantine(String reason) {
    ++_run;
    _active = false;
    _committedCursor = null;
    onQuarantine(reason);
  }
}
