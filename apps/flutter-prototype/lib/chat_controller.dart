import 'core/recovered_conversation_summary.dart';
import 'dart:async';
import 'dart:io';
import 'dart:math';
import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import 'data/meshx_api.dart';
import 'data/attachments_models.dart';
import 'data/models.dart';
import 'data/friends_models.dart';
import 'data/groups_models.dart';
import 'data/temporary_rooms.dart';
import 'data/profile_models.dart';
import 'data/ws_contract.g.dart' show ChatSendPayload, WsEvents;
import 'core/platform_ports.dart';
import 'core/store.dart';
import 'core/recovery.dart';
import 'core/visible_read.dart';
import 'core/notification_recovery.dart';
import 'core/recovery_data.dart';
import 'application/recovery_chat_sink.dart';
import 'application/recovery_coordinator.dart';

class ChatController extends ChangeNotifier {
  ChatController({
    DiscoveryPort? discovery,
    this.store,
    this.draftStore,
    this.credentials,
    this.enableRecovery = const bool.fromEnvironment('MESHX_MUTATION_RECOVERY'),
    this.recoveryTransportFactory = ApiRecoveryTransport.new,
    this.allowLocalHttp = kDebugMode,
    this.apiFactory = MeshXApi.new,
    this.connectionFactory = RealtimeConnection.new,
  }) : discovery = discovery ?? const UnsupportedDiscovery();
  final MeshXApi Function(Uri) apiFactory;
  final RealtimeConnection Function(MeshXApi) connectionFactory;
  final ChatStore? store, draftStore;
  final CredentialStore? credentials;
  final bool enableRecovery;
  final RecoveryTransport Function(MeshXApi) recoveryTransportFactory;
  bool _recoveryMode = false, _recovering = false, _recoveryAgain = false;
  RecoveryCoordinator? _recovery;
  RecoveryChatSink? _recoverySink;
  RecoveryState? _recoveryState;
  Timer? _recoveryPoll;
  Future<void> _recoveryWrites = Future.value();
  RecoveryPhase? get recoveryPhase => _recoveryState?.phase;
  bool get usesMutationRecovery => _recoveryMode;
  List<RecoveryTerminal> _recoveryTerminals = [];
  final _liveNotificationCandidates = <String>{};
  Json? _recoveryNotifications;
  List<Json> get recoveryNotificationEffects =>
      _recoveryState?.phase != RecoveryPhase.onlineSafe
      ? []
      : [
          for (final e in (_recoveryNotifications?['effects'] as List? ?? []))
            Map<String, dynamic>.from(e as Map),
        ];
  bool recoveryNotificationAllows(String? messageId, String conversationId) =>
      messageId != null &&
      _recoveryState != null &&
      _recoveryState!.messages[messageId]?.conversationId == conversationId &&
      RecoveryNotificationJournal(
        _recoveryNotifications,
      ).allows(_recoveryState!, messageId);
  ChatMessage? recoveryNotificationMessage(String id) {
    final cid = _recoveryState?.messages[id]?.conversationId;
    if (cid == null || !recoveryNotificationAllows(id, cid)) return null;
    return _messages[cid]
        ?.where((m) => m.messageId == id && m.fromUserId != session?.userId)
        .firstOrNull;
  }

  Future<void> acknowledgeRecoveryNotification(String key) async {
    final generation = _generation,
        epoch = _socketEpoch,
        state = _recoveryState,
        sink = _recoverySink;
    final operation = _recoveryWrites.then((_) async {
      if (!_valid(generation, epoch) ||
          state == null ||
          sink == null ||
          !identical(state, _recoveryState) ||
          state.phase != RecoveryPhase.onlineSafe) {
        throw const RecoveryProtocolError('STALE_RECOVERY');
      }
      sink.acknowledgeNotification(state.context, key);
      try {
        await sink.commit(state.context, state);
        sink.publish(state.context, state);
      } catch (_) {
        _quarantineRecovery('PERSISTENCE_FAILED');
        rethrow;
      }
    });
    _recoveryWrites = operation.catchError((Object _) {});
    await operation;
  }

  final _removed = <String>{};
  final _revalidateIds = <String, String>{};
  final _unavailableIds = <String>{};
  bool conversationAccessible(String id) => !_removed.contains(id);
  int conversationRevision(String id) => _accessRevisions[id] ?? 0;
  String get connectionScope => '$_owner|$_generation|$_socketEpoch';
  final _accessRevisions = <String, int>{};
  final _terminals = <String, Map<String, bool>>{};
  final _memberAvatars = <int, String>{};
  final _conversationChanges = StreamController<String>.broadcast();
  Stream<String> get conversationChanges => _conversationChanges.stream;
  final _invalidations =
      StreamController<
        ({String conversationId, String? messageId})
      >.broadcast();
  Stream<({String conversationId, String? messageId})> get invalidations =>
      _invalidations.stream;
  bool get canUploadAttachment =>
      active != null &&
      canSendInActiveConversation &&
      (active!.kind != 'temporary' ||
          _rooms[active!.targetId]?.allowFileUpload == true);
  bool canDownloadAttachment(ChatMessage message) =>
      allowsMessage(message) &&
      (!message.conversationId.startsWith('temporary:') ||
          _rooms[int.tryParse(message.conversationId.split(':').last)]
                  ?.allowFileDownload ==
              true);
  bool allowsMessage(ChatMessage message) =>
      session != null &&
      online &&
      !_unavailableIds.contains(message.messageId) &&
      !_removed.contains(message.conversationId) &&
      !(_terminals[message.conversationId]?.containsKey(message.messageId) ??
          false) &&
      !message.recalled &&
      !message.burned &&
      (!message.isBurn || _recoveryMode);

  final _transferEvents = StreamController<Json>.broadcast();
  Stream<Json> get transferEvents => _transferEvents.stream;
  void sendTransferEvent(String event, Json payload, String conversationId) {
    if (!online ||
        !conversationId.startsWith('private:') ||
        !conversationAccessible(conversationId)) {
      throw const ApiException('直传会话不可用');
    }
    _realtime!.send(event, payload, conversationId: conversationId);
  }

  final _typing = <String, Map<int, DateTime>>{};
  Timer? _typingExpiry, _typingStop;
  DateTime? _lastTyping;
  String? _typingConversation;
  String get typingLabel {
    final users = _typing[active?.id]?.keys.toList() ?? [];
    if (!online || users.isEmpty) return '';
    return users.length == 1
        ? '${senderName(users.first)}正在输入…'
        : '${users.length}人正在输入…';
  }

  void sendTyping(bool typing) {
    if (!typing && _typingConversation == null) return;
    final id = active?.id;
    if (!online || !canSendInActiveConversation || id == null) return;
    final now = DateTime.now();
    if (typing &&
        _typingConversation == id &&
        _lastTyping != null &&
        now.difference(_lastTyping!).inSeconds < 3) {
      return;
    }
    try {
      _realtime!.send(
        typing ? 'TYPING_START' : 'TYPING_STOP',
        {},
        conversationId: id,
      );
      _typingConversation = typing ? id : null;
      _lastTyping = typing ? now : null;
      _typingStop?.cancel();
      if (typing) {
        _typingStop = Timer(
          const Duration(seconds: 4),
          () => sendTyping(false),
        );
      }
    } catch (_) {
      /* Ephemeral hints do not alter message delivery. */
    }
  }

  void _clearTyping() {
    _typing.clear();
    _typingExpiry?.cancel();
    _typingExpiry = null;
    _typingStop?.cancel();
    _typingConversation = null;
    _lastTyping = null;
  }

  /// Reveal only after the server confirms terminal destruction. No plaintext
  /// copy is persisted by the reading view; interruption discards it forever.
  Future<String?> consumeBurn(ChatMessage message) async {
    final scope = connectionScope, current = _realtime;
    if (!online ||
        current == null ||
        active?.id != message.conversationId ||
        !conversationAccessible(message.conversationId) ||
        !message.isBurn ||
        message.burned ||
        message.recalled ||
        message.contentType != 'text' ||
        message.fromUserId == session?.userId ||
        _unavailableIds.contains(message.messageId) ||
        (_terminals[message.conversationId]?.containsKey(message.messageId) ??
            false)) {
      return null;
    }
    final body = message.content;
    final response = await current.command('CHAT_BURN', {
      'messageId': message.messageId,
    }, conversationId: message.conversationId);
    if (_recoveryMode) {
      final deadline = DateTime.now().add(const Duration(seconds: 15));
      while (!_disposed &&
          connectionScope == scope &&
          active?.id == message.conversationId &&
          (!online ||
              _recoveryState?.messages[message.messageId]?.state !=
                  MessageRecoveryState.burned) &&
          DateTime.now().isBefore(deadline)) {
        await Future<void>.delayed(const Duration(milliseconds: 50));
      }
      if (_recoveryState?.messages[message.messageId]?.state !=
          MessageRecoveryState.burned) {
        return null;
      }
    }
    if (_disposed ||
        !online ||
        connectionScope != scope ||
        active?.id != message.conversationId ||
        !conversationAccessible(message.conversationId) ||
        response['conversationId'] != message.conversationId ||
        (response['payload'] as Json?)?['messageId'] != message.messageId) {
      return null;
    }
    if (!_recoveryMode) {
      _terminalEvent(message.conversationId, message.messageId, true);
      await _persist();
      if (_disposed || connectionScope != scope || !online) return null;
    }
    return body;
  }

  void _merge(String id, Iterable<ChatMessage> incoming) {
    if (_removed.contains(id)) return;
    _messages[id] = mergeMessages(
      _messages[id] ?? [],
      incoming.map((m) {
        final terminal = _terminals[id]?[m.messageId];
        return terminal == null ? m : m.terminal(burned: terminal);
      }),
    );
  }

  void _removeConversation(String id) {
    _removed.add(id);
    _accessRevisions[id] = (_accessRevisions[id] ?? 0) + 1;
    ++_summaryRevision;
    for (final m in _messages[id] ?? <ChatMessage>[]) {
      _ackTimers.remove(m.clientMsgId)?.cancel();
    }
    _messages.remove(id);
    _positions.remove(id);
    _hasOlder.remove(id);
    _pendingReads.remove(id);
    _readAttempts.remove(id);
    drafts.remove(id);
    conversations = conversations.where((c) => c.id != id).toList();
    if (active?.id == id) active = null;
    _readTracker = null;
    _invalidations.add((conversationId: id, messageId: null));
    _conversationChanges.add(id);
  }

  void _terminalEvent(String id, String messageId, bool burned) {
    final previous = _terminals[id]?[messageId];
    (_terminals[id] ??= {})[messageId] = burned || previous == true;
    _messages[id] = (_messages[id] ?? [])
        .map(
          (m) => m.messageId == messageId
              ? m.terminal(burned: burned || previous == true)
              : m,
        )
        .toList();
    ++_summaryRevision;
    _invalidations.add((conversationId: id, messageId: messageId));
    _scheduleListRefresh();
  }

  String? focusedMessageId;
  int? focusedSequence;
  void clearMessageFocus() {
    focusedMessageId = null;
    focusedSequence = null;
    _notify();
  }

  Future<bool> focusMessage(ChatMessage message) async {
    final current = api, scope = connectionScope;
    if (current == null ||
        active?.id != message.conversationId ||
        !allowsMessage(message)) {
      return false;
    }
    if (!_recoveryMode) {
      final nearby = await current.history(
        message.conversationId,
        before: message.sequence + 1,
        limit: 50,
      );
      if (_disposed ||
          scope != connectionScope ||
          active?.id != message.conversationId ||
          !allowsMessage(message)) {
        return false;
      }
      _merge(message.conversationId, nearby);
    }
    final found = _messages[message.conversationId]
        ?.where((m) => m.messageId == message.messageId && allowsMessage(m))
        .firstOrNull;
    if (found == null) return false;
    focusedMessageId = found.messageId;
    focusedSequence = found.sequence;
    _notify();
    return true;
  }

  Timer? _draftTimer;
  void updateDraft(String id, String text) {
    if (_removed.contains(id) ||
        session == null ||
        (drafts[id] ?? '') == text) {
      return;
    }
    drafts[id] = text;
    if ((store == null && draftStore == null) ||
        (_recoveryMode && draftStore == null)) {
      return;
    }
    _draftTimer?.cancel();
    final generation = _generation;
    _draftTimer = Timer(const Duration(milliseconds: 300), () {
      unawaited(
        (_recoveryMode ? _persistDrafts() : _persist()).catchError((
          Object failure,
        ) {
          if (_disposed || generation != _generation) return;
          error = describe(failure);
          _notify();
        }),
      );
    });
  }

  // Reading, receiving and recovery cursors have separate ownership.
  final _pendingReads = <String, int>{};
  final _readAttempts = <String, int>{};
  Timer? _readRetry;
  int _summaryRevision = 0;
  String get _ordinaryReadScope => !_recoveryMode && active != null && online
      ? '$_owner|$_generation|$_socketEpoch|${active!.id}|${_positions[active!.id]}|${_messages[active!.id]?.length ?? 0}|${conversations.where((c) => c.id == active!.id).firstOrNull?.lastReadSequence}'
      : '';

  void _observeOrdinaryRead(
    String scope,
    Set<int> visible,
    int now,
    bool foreground,
  ) {
    final conversation = conversations
        .where((c) => c.id == active?.id)
        .firstOrNull;
    if (scope.isEmpty ||
        scope != _ordinaryReadScope ||
        !foreground ||
        _paused ||
        !online ||
        conversation == null) {
      _readTracker?.cancel();
      return;
    }
    if (_readTracker?.scope != scope) {
      _readTracker = VisibleReadTracker(scope, conversation.lastReadSequence, [
        for (final m in messages.where(
          (m) => m.delivery == Delivery.sent && m.sequence > 0,
        ))
          ReadRow(m.sequence, m.fromUserId == session?.userId),
      ], _positions[conversation.id] ?? 0);
    }
    final position = _readTracker!.sample(scope, now, visible, true);
    if (position >
        max(
          conversation.lastReadSequence,
          _pendingReads[conversation.id] ?? 0,
        )) {
      _pendingReads[conversation.id] = position;
      _readAttempts.remove(conversation.id);
      unawaited(
        _persist().then((_) => _flushReads()).catchError((Object failure) {
          error = describe(failure);
          _notify();
        }),
      );
    }
  }

  void _flushReads() {
    if (_disposed || _recoveryMode || !online || _paused || _realtime == null) {
      return;
    }
    for (final entry in _pendingReads.entries.toList()) {
      final c = conversations.where((c) => c.id == entry.key).firstOrNull;
      if (c == null || c.lastReadSequence >= entry.value) {
        _pendingReads.remove(entry.key);
        continue;
      }
      if ((_readAttempts[entry.key] ?? 0) >= 3) continue;
      _readAttempts[entry.key] = (_readAttempts[entry.key] ?? 0) + 1;
      try {
        _realtime!.send('CHAT_READ', {
          'lastReadSequence': entry.value,
        }, conversationId: entry.key);
      } catch (_) {
        /* Retain the durable position for reconnect. */
      }
    }
    _readRetry?.cancel();
    if (_pendingReads.keys.any((id) => (_readAttempts[id] ?? 0) < 3)) {
      _readRetry = Timer(const Duration(seconds: 2), _flushReads);
    }
  }

  final _recoveryReadBaselines = <String, int>{};
  VisibleReadTracker? _readTracker;
  int _lastReadSent = 0;
  String get recoveryReadScope => !_recoveryMode
      ? _ordinaryReadScope
      : _recoveryState?.phase == RecoveryPhase.onlineSafe && active != null
      ? '$_owner|$_generation|$_socketEpoch|${_recoveryState!.context.streamEpoch}|${_recoveryState!.cursor}|${active!.id}'
      : '';
  void observeRecoveryRead(
    String scope,
    Set<int> visible,
    int now, {
    required bool foreground,
  }) {
    if (!_recoveryMode) {
      _observeOrdinaryRead(scope, visible, now, foreground);
      return;
    }
    final cid = active?.id, state = _recoveryState;
    if (scope.isEmpty ||
        scope != recoveryReadScope ||
        !foreground ||
        _paused ||
        !online ||
        cid == null ||
        state?.access[cid]?.readAllowed != true ||
        !_recoveryReadBaselines.containsKey(cid)) {
      _readTracker?.cancel();
      return;
    }
    if (_readTracker?.scope != scope) {
      _readTracker = VisibleReadTracker(scope, _recoveryReadBaselines[cid]!, [
        for (final m in messages.where(
          (m) => m.delivery == Delivery.sent && m.sequence > 0,
        ))
          ReadRow(m.sequence, m.fromUserId == session?.userId),
        for (final terminal in recoveryTerminals)
          ReadRow(terminal.sequence, false),
      ], _positions[cid] ?? 0);
      _lastReadSent = _recoveryReadBaselines[cid]!;
    }
    final position = _readTracker!.sample(scope, now, visible, true);
    if (position > _lastReadSent) {
      try {
        _realtime?.send('CHAT_READ', {
          'lastReadSequence': position,
        }, conversationId: cid);
        _lastReadSent = position;
      } catch (_) {
        _readTracker?.cancel();
      }
    }
  }

  List<RecoveryTerminal> get recoveryTerminals =>
      _recoveryState?.phase != RecoveryPhase.onlineSafe
      ? []
      : _recoveryTerminals
            .where((row) => row.conversationId == active?.id)
            .toList();

  bool get canSendInActiveConversation =>
      !_removed.contains(active?.id) &&
      (!_recoveryMode ||
          (online &&
              _recoveryState?.phase == RecoveryPhase.onlineSafe &&
              _recoveryState?.access[active?.id]?.sendAllowed == true));
  String get recoveryStatus {
    if (!_recoveryMode || _recoveryState?.phase == RecoveryPhase.onlineSafe) {
      return '';
    }
    if (_recoveryState?.phase == RecoveryPhase.storageBlocked ||
        lastErrorCode == 'PERSISTENCE_FAILED' ||
        lastErrorCode == 'STORAGE_UNAVAILABLE') {
      return '本地消息保存失败，请释放空间后重试';
    }
    if (lastErrorCode == 'BLOCKED_UPGRADE') return '节点暂不支持安全恢复，请更新节点后重试';
    return '正在校验消息，完成后可继续聊天';
  }

  final DiscoveryPort discovery;
  CapabilityStatus discoveryStatus = CapabilityStatus.available;
  StreamSubscription<DiscoveryUpdate>? _discoveryUpdates;
  Completer<void>? _scanDone;
  bool _resumeDiscovery = false;
  Timer? _candidateExpiry;
  void Function(ChatMessage, bool, bool)? onLiveMessage;
  final Map<String, int> _positions = {};
  final _friendChanges = StreamController<void>.broadcast();
  Stream<void> get friendChanges => _friendChanges.stream;
  final _broadcastChanges = StreamController<void>.broadcast();
  Stream<void> get broadcastChanges => _broadcastChanges.stream;
  Set<int> _friendIds = {};
  bool _friendAccessLoaded = false;
  Map<String, int> get positions => Map.unmodifiable(_positions);
  String? get _owner =>
      session == null ? null : accountScope(api!.origin, session!.userId);
  Future<void> _inbound = Future.value(), _credentialWrites = Future.value();
  bool _draining = false, _credentialBlocked = false;
  int _socketEpoch = 0;
  bool _valid(int generation, int epoch) =>
      !_disposed && generation == _generation && epoch == _socketEpoch;

  Future<void> _writeCredential(Json? value) {
    final operation = _credentialWrites.then((_) async {
      if (value == null) {
        await credentials?.clear();
      } else {
        await credentials?.write(value);
      }
    });
    _credentialWrites = operation.catchError((Object _) {});
    return operation;
  }

  Future<void> _draftWrites = Future.value();
  Future<void> _persistDrafts() {
    final owner = _owner;
    if (owner == null || draftStore == null) return Future.value();
    final snapshot = <String, dynamic>{
      'drafts': Map<String, String>.from(drafts),
    };
    final next = _draftWrites.then(
      (_) => draftStore!.save('$owner|drafts', snapshot),
    );
    _draftWrites = next.catchError((Object _) {});
    return next;
  }

  Future<void> _persist() async {
    final owner = _owner;
    if (owner == null) return;
    if (_recoveryMode) {
      final state = _recoveryState, sink = _recoverySink;
      if (state?.phase != RecoveryPhase.onlineSafe || sink == null) {
        throw const RecoveryProtocolError('RECOVERY_REQUIRED');
      }
      final captured = {
        for (final entry in _messages.entries) entry.key: entry.value.toList(),
      };
      final generation = _generation, epoch = _socketEpoch;
      final operation = _recoveryWrites.then((_) async {
        if (!_valid(generation, epoch) ||
            _recoveryState?.phase != RecoveryPhase.onlineSafe) {
          throw const RecoveryProtocolError('STALE_RECOVERY');
        }
        sink.replacePending(state!.context, captured);
        await sink.commit(state.context, state);
      });
      _recoveryWrites = operation.catchError((Object _) {});
      try {
        await operation;
      } catch (_) {
        if (_valid(generation, epoch)) {
          _quarantineRecovery('PERSISTENCE_FAILED');
        }
        rethrow;
      }
      await _persistDrafts();
      return;
    }
    await store?.save(owner, {
      'conversations': conversations.map((c) => c.toJson()).toList(),
      'messages': {
        for (final entry in _messages.entries)
          entry.key: entry.value.map((m) => m.toJson()).toList(),
      },
      'positions': Map<String, int>.from(_positions),
      'pendingReads': Map<String, int>.from(_pendingReads),
      'drafts': Map<String, String>.from(drafts),
    });
    await _persistDrafts();
  }

  Future<void> _restoreSnapshot(int generation) async {
    final owner = _owner;
    if (owner == null) return;
    final savedDrafts = await draftStore?.load('$owner|drafts');
    if (_disposed || generation != _generation) return;
    for (final entry in (savedDrafts?['drafts'] as Map? ?? {}).entries) {
      if (entry.key is String &&
          entry.value is String &&
          (entry.value as String).length <= 4000) {
        drafts[entry.key as String] = entry.value as String;
      }
    }
    final recoveryStore = store;
    final migrated = recoveryStore is RecoveryChatStore
        ? await (recoveryStore as RecoveryChatStore).loadRecovery(owner)
        : null;
    if (_disposed || generation != _generation) return;
    _recoveryMode = enableRecovery || migrated != null;
    if (_recoveryMode && migrated != null) {
      _messages.clear();
      _positions.clear();
      conversations = [];
      return; // The sink restores only held outbox; cached history stays quarantined.
    }
    final value = await store?.load(owner);
    if (_disposed || generation != _generation || value == null) return;
    _messages.clear();
    _positions.clear();
    drafts.addAll({
      for (final e in (value['drafts'] as Map? ?? {}).entries)
        if (!drafts.containsKey(e.key) &&
            e.key is String &&
            e.value is String &&
            (e.value as String).length <= 4000)
          e.key as String: e.value as String,
    });
    _pendingReads.addAll({
      for (final e in (value['pendingReads'] as Map? ?? {}).entries)
        if (e.key is String && e.value is int && e.value > 0)
          e.key as String: e.value as int,
    });
    conversations = (value['conversations'] as List)
        .map((j) => Conversation.restore(j as Json))
        .toList();
    for (final entry in (value['messages'] as Json).entries) {
      _messages[entry.key] = (entry.value as List)
          .map((j) => ChatMessage.restore(j as Json))
          .toList();
    }
    _positions.addAll(Map<String, int>.from(value['positions'] as Map));
    if (_recoveryMode) {
      for (final id in _messages.keys) {
        _messages[id] = _messages[id]!
            .where(
              (m) =>
                  m.fromUserId == session?.userId &&
                  m.delivery != Delivery.sent,
            )
            .map(
              (m) => m.withRecoveryDisposition(
                RecoveryDisposition.needsUserAction,
              ),
            )
            .toList();
      }
      conversations = conversations
          .map(
            (c) => Conversation(
              id: c.id,
              targetId: c.targetId,
              kind: c.kind,
              title: c.title,
            ),
          )
          .toList();
      _positions.clear();
    }
  }

  Future<void> restore() async {
    if (busy || session != null) return;
    busy = true;
    _notify();
    final generation = ++_generation;
    try {
      final saved = await credentials?.read();
      if (_disposed || generation != _generation || saved == null) return;
      final origin = parseNodeOrigin(
        saved['origin'] as String,
        allowLocalHttp: allowLocalHttp,
      );
      final current = apiFactory(origin)..restoreCredentials(saved);
      api = current;
      _bindCredentials(current, generation);
      await _restoreSnapshot(generation);
      if (_disposed || generation != _generation) return;
      _notify();
      final access = await discovery.prepareConnection(origin);
      if (!access.ok) throw ApiException(capabilityMessage(access.status));
      await reconnect();
    } catch (failure) {
      if (!_disposed && generation == _generation) error = describe(failure);
    } finally {
      if (generation == _generation) {
        busy = false;
        _notify();
      }
    }
  }

  void _bindCredentials(MeshXApi current, int generation) {
    current.onCredentialsChanged = (value) async {
      if (!_disposed && generation == _generation && identical(api, current)) {
        await _writeCredential(value);
      }
    };
  }

  final bool allowLocalHttp;
  MeshXApi? api;
  Session? get session => api?.session;
  NodeInfo? get node => api?.node;
  bool busy = false, scanning = false, online = false, loadingHistory = false;
  String? error;
  String? lastErrorCode;
  List<String> candidates = [];
  List<Conversation> conversations = [];
  Conversation? active;
  final Map<String, List<ChatMessage>> _messages = {};
  final Map<String, bool> _hasOlder = {};
  final Map<String, Timer> _ackTimers = {};
  final Map<String, String> drafts = {};
  final Map<int, String> _memberNames = {};
  String senderAvatar(int userId) => userId == session?.userId
      ? session!.avatar
      : _memberAvatars[userId] ??
            api?.cachedFriends
                ?.where((f) => f.userId == userId)
                .firstOrNull
                ?.avatar ??
            '';
  String conversationPreview(Conversation conversation) {
    final last = (_messages[conversation.id] ?? [])
        .where((m) => m.delivery == Delivery.sent)
        .lastOrNull;
    if (last?.isBurn == true) return '阅后即焚消息';
    if (last?.burned == true || last?.recalled == true) {
      return last!.displayContent;
    }
    return conversation.preview.isEmpty ? '开始交流' : conversation.preview;
  }

  String senderName(int userId) {
    if (userId == session?.userId) return session!.nickname;
    if (_memberNames.containsKey(userId)) return _memberNames[userId]!;
    for (final conversation in conversations) {
      if (conversation.kind == 'private' && conversation.targetId == userId) {
        return conversation.title;
      }
    }
    return '成员 $userId';
  }

  Future<void> applyProfile(UserProfile profile) async {
    final current = api;
    if (current?.session == null ||
        current!.session!.userId != profile.userId) {
      throw const ApiException('更新资料账号不匹配');
    }
    final before = current.session!;
    final updated = Session(
      before.userId,
      profile.nickname,
      before.token,
      username: profile.username,
      avatar: profile.avatar,
    );
    current.session = updated;
    try {
      await _writeCredential(current.credentials());
    } catch (_) {
      current.session = before;
      rethrow;
    }
    _notify();
  }

  List<ChatMessage> get messages =>
      _recoveryMode && _recoveryState?.phase != RecoveryPhase.onlineSafe
      ? const []
      : List.unmodifiable(
          (_messages[active?.id] ?? []).where(
            (m) => online || m.delivery != Delivery.sent,
          ),
        );
  bool get hasOlder => _hasOlder[active?.id] ?? false;
  RealtimeConnection? _realtime;
  Timer? _retryTimer, _listTimer;
  int _generation = 0, _attempt = 0;
  bool _disposed = false, _connecting = false, _paused = false;
  final Map<String, Completer<void>> _syncing = {};
  final Set<String> _syncAgain = {};
  bool _authRefreshAttempted = false;

  void _notify() {
    if (!_disposed) notifyListeners();
  }

  void reportFailure(Object failure) {
    error = describe(failure);
    _notify();
  }

  void clearError() {
    error = null;
    lastErrorCode = null;
    _notify();
  }

  String get connectionPhase {
    if (session == null) return 'signedOut';
    if (_paused) return 'background';
    if (online) return 'online';
    if (_connecting) return 'connecting';
    return 'offline';
  }

  String describe(Object failure) {
    if (failure is RecoveryProtocolError) {
      lastErrorCode = failure.reason;
      return '消息安全校验尚未完成，请重试连接';
    }
    if (failure is ApiException) {
      lastErrorCode = failure.code > 0 ? 'HTTP_${failure.code}' : 'API_ERROR';
      return failure.message;
    }
    if (failure is FormatException) {
      lastErrorCode = 'WIRE_FORMAT';
      return failure.message;
    }
    if (failure is PlatformException) {
      lastErrorCode = 'PLATFORM_ERROR';
      return failure.message ?? '平台操作失败，请重试';
    }
    if (failure is MissingPluginException) {
      lastErrorCode = 'PLATFORM_UNAVAILABLE';
      return '必要的平台能力不可用，无法完成操作';
    }
    if (failure is TimeoutException) {
      lastErrorCode = 'TIMEOUT';
      return '连接超时，请检查节点和网络后重试';
    }
    if (failure is HandshakeException) {
      lastErrorCode = 'TLS_ERROR';
      return '节点证书验证失败。请使用证书对应的域名，并请管理员检查证书有效期、完整证书链和设备信任；不会自动改用 HTTP。';
    }
    if (failure is SocketException) {
      lastErrorCode = 'NETWORK_ERROR';
      return '无法连接节点，请检查同一局域网、域名解析、端口和节点服务。';
    }
    lastErrorCode = 'UNKNOWN_ERROR';
    return '操作未完成，请重试';
  }

  Future<void> scan({bool requestPermission = true}) async {
    if (scanning || _disposed || _paused) return;
    scanning = true;
    _candidateExpiry?.cancel();
    error = null;
    candidates = [];
    final done = _scanDone = Completer<void>();
    await _discoveryUpdates?.cancel();
    _discoveryUpdates = discovery.updates.listen((update) {
      if (_disposed || !scanning || !identical(done, _scanDone)) return;
      discoveryStatus = update.result.status;
      if (update.result.reason == 'background' ||
          update.result.reason == 'networkChanged') {
        _resumeDiscovery = true;
      }
      final accepted = <String>{};
      for (final node in update.result.value ?? const <DiscoveredNode>[]) {
        for (final origin in node.origins) {
          try {
            accepted.add(
              parseNodeOrigin(
                origin,
                allowLocalHttp: allowLocalHttp,
              ).toString(),
            );
          } on FormatException {
            /* An untrusted advertisement is not usable. */
          }
        }
      }
      candidates = accepted.toList();
      if (!update.result.ok) error = capabilityMessage(update.result.status);
      if (update.complete) {
        if (update.result.ok && candidates.isEmpty) error = '未发现可用节点，可手动输入地址连接';
        if (update.result.ok && candidates.isNotEmpty) {
          _candidateExpiry = Timer(const Duration(seconds: 30), () {
            candidates = [];
            _notify();
          });
        }
        if (!done.isCompleted) done.complete();
      }
      _notify();
    });
    _notify();
    try {
      final result = await discovery.start(
        requestPermission: requestPermission,
      );
      if (_disposed || !identical(done, _scanDone)) return;
      if (!result.ok) {
        discoveryStatus = result.status;
        error = capabilityMessage(result.status);
        if (!done.isCompleted) done.complete();
      }
      await done.future;
    } finally {
      if (identical(done, _scanDone)) {
        scanning = false;
        _notify();
      }
    }
  }

  Future<void> stopScan({bool cancelled = true}) async {
    _resumeDiscovery = false;
    _candidateExpiry?.cancel();
    scanning = false;
    candidates = [];
    final done = _scanDone;
    _scanDone = null;
    if (done != null && !done.isCompleted) done.complete();
    await discovery.stop(cancelled: cancelled);
    discoveryStatus = cancelled
        ? CapabilityStatus.cancelled
        : CapabilityStatus.success;
    _notify();
  }

  Future<void> login(String origin, String username, String password) async {
    if (busy) return;
    if (username.trim().isEmpty || password.isEmpty) {
      error = '请填写账号和密码';
      _notify();
      return;
    }
    if (session != null) {
      await logout();
    }
    if (_credentialBlocked) {
      try {
        await _writeCredential(null);
        _credentialBlocked = false;
      } catch (failure) {
        error = describe(failure);
        _notify();
        return;
      }
    }
    busy = true;
    error = null;
    _notify();
    final generation = ++_generation;
    MeshXApi? candidate;
    try {
      final target = parseNodeOrigin(origin, allowLocalHttp: allowLocalHttp);
      final access = await discovery.prepareConnection(
        target,
        requestPermission: true,
      );
      if (!access.ok) throw ApiException(capabilityMessage(access.status));
      if (_disposed || generation != _generation) return;
      candidate = apiFactory(target);
      await candidate.handshake();
      await candidate.login(username, password);
      await candidate.validateCurrentUser();
      final snapshot = await candidate.conversations();
      if (_disposed || generation != _generation) {
        candidate.close();
        return;
      }
      api?.close();
      api = candidate;
      await _restoreSnapshot(generation);
      if (_disposed || generation != _generation) return;
      await _writeCredential(candidate.credentials());
      if (_disposed || generation != _generation) return;
      _bindCredentials(candidate, generation);
      conversations = !_recoveryMode
          ? snapshot
          : snapshot
                .map(
                  (c) => Conversation(
                    id: c.id,
                    targetId: c.targetId,
                    kind: c.kind,
                    title: c.title,
                    avatar: c.avatar,
                  ),
                )
                .toList();
      await _refreshFriendAccess(candidate, generation);
      _paused = false;
      _authRefreshAttempted = false;
      await reconnect();
    } catch (failure) {
      if (api == candidate && generation == _generation) {
        api = null;
        conversations = [];
        _messages.clear();
        _positions.clear();
      }
      candidate?.close();
      if (!_disposed && generation == _generation) error = describe(failure);
    } finally {
      if (generation == _generation) {
        busy = false;
        _notify();
      }
    }
  }

  Future<void> refreshConversations() async {
    final current = api, generation = _generation;
    if (current == null) return;
    if (_recoveryMode) {
      await _runRecovery();
      return;
    }
    try {
      final revision = _summaryRevision;
      final snapshot = await current.conversations();
      if (_disposed || generation != _generation) return;
      if (revision != _summaryRevision) {
        _scheduleListRefresh();
        return;
      }
      if (!_recoveryMode) {
        final present = snapshot.map((c) => c.id).toSet();
        // Only a current authoritative snapshot can restore a removed membership.
        _removed.removeWhere(present.contains);
        for (final id in _messages.keys.toList()) {
          if (!present.contains(id)) _removeConversation(id);
        }
        conversations = snapshot.where((c) => !_removed.contains(c.id)).map((
          next,
        ) {
          final old = conversations.where((c) => c.id == next.id).firstOrNull;
          if (old != null &&
              (next.lastSequence < old.lastSequence ||
                  next.lastReadSequence < old.lastReadSequence)) {
            return old;
          }
          return next;
        }).toList();
        _flushReads();
        await _persist();
        if (_disposed || generation != _generation) return;
      }
      await _refreshFriendAccess(current, generation);
      _notify();
    } catch (failure) {
      if (!_disposed && generation == _generation) {
        error = describe(failure);
        _notify();
      }
      rethrow;
    }
  }

  Future<void> _refreshFriendAccess(MeshXApi current, int generation) async {
    final friends = current.cachedFriends;
    if (friends == null) return;
    if (_disposed || generation != _generation || !identical(api, current)) {
      return;
    }
    final next = friends.map((friend) => friend.userId).toSet();
    if (_friendAccessLoaded) {
      for (final removed in _friendIds.difference(next)) {
        _failPrivateOutbox(removed);
      }
    } else {
      final cachedTargets = <int>{};
      for (final conversation in conversations) {
        if (conversation.kind == 'private') {
          cachedTargets.add(conversation.targetId);
        }
      }
      for (final id in _messages.keys) {
        final peer = privateConversationPeer(id, session!.userId);
        if (peer != null) cachedTargets.add(peer);
      }
      for (final removed in cachedTargets.difference(next)) {
        _failPrivateOutbox(removed);
      }
    }
    _friendIds = next;
    _friendAccessLoaded = true;
  }

  void _failPrivateOutbox(int friendId) {
    final id = privateConversationId(session!.userId, friendId);
    var changed = false;
    _messages[id] = (_messages[id] ?? []).map((message) {
      if (message.fromUserId == session!.userId &&
          (message.delivery == Delivery.queued ||
              message.delivery == Delivery.sending)) {
        _ackTimers.remove(message.clientMsgId)?.cancel();
        changed = true;
        return message.withDelivery(Delivery.failed);
      }
      return message;
    }).toList();
    if (changed && !_recoveryMode) unawaited(_persist());
  }

  void notifyFriendRequestsChanged() {
    if (!_disposed) _friendChanges.add(null);
  }

  void notifyBroadcastsChanged() {
    if (!_disposed) _broadcastChanges.add(null);
  }

  Future<void> relationshipsChanged() async {
    await refreshConversations();
    _friendChanges.add(null);
  }

  Future<void> openPrivateConversation(FriendContact friend) async {
    final me = session?.userId;
    if (me == null) return;
    final id = privateConversationId(me, friend.userId);
    Conversation? conversation;
    for (final item in conversations) {
      if (item.id == id) {
        conversation = item;
        break;
      }
    }
    if (conversation == null) {
      conversation = Conversation(
        id: id,
        targetId: friend.userId,
        kind: 'private',
        title: friend.displayName,
        avatar: friend.avatar,
      );
      conversations = [conversation, ...conversations];
    }
    await select(conversation);
  }

  final _rooms = <int, TemporaryRoom>{};
  Future<void> openTemporaryRoom(TemporaryRoom room) async {
    final current = api, generation = _generation;
    if (current == null) return;
    try {
      final verified = await current.temporaryRoom(room.id);
      if (_disposed || generation != _generation) return;
      _rooms[room.id] = verified;
      final conversation =
          conversations
              .where((c) => c.id == verified.conversationId)
              .firstOrNull ??
          Conversation(
            id: verified.conversationId,
            targetId: verified.id,
            kind: 'temporary',
            title: verified.name,
          );
      if (!conversations.any((c) => c.id == conversation.id)) {
        conversations = [conversation, ...conversations];
      }
      _removed.remove(conversation.id);
      await select(conversation);
    } catch (failure) {
      if (!_disposed && generation == _generation) {
        error = describe(failure);
        _notify();
      }
    }
  }

  Future<void> openGroupConversation(MeshXGroup group) async {
    final id = 'group:${group.id}';
    Conversation? conversation;
    for (final item in conversations) {
      if (item.id == id) {
        conversation = item;
        break;
      }
    }
    if (conversation == null) {
      conversation = Conversation(
        id: id,
        targetId: group.id,
        kind: 'group',
        title: group.name,
        avatar: group.avatar,
      );
      conversations = [conversation, ...conversations];
    }
    await select(conversation);
  }

  Future<void> confirmOwnGroupLeave(int groupId) async {
    final id = 'group:$groupId';
    _removeConversation(id);
    for (final message in _messages[id] ?? const <ChatMessage>[]) {
      _ackTimers.remove(message.clientMsgId)?.cancel();
    }
    conversations = conversations.where((item) => item.id != id).toList();
    if (active?.id == id) active = null;
    _messages.remove(id);
    _positions.remove(id);
    _hasOlder.remove(id);
    _syncAgain.remove(id);
    drafts.remove(id);
    if (_recoveryMode) {
      await _runRecovery();
      return;
    }
    await _persist();
    _notify();
  }

  void _scheduleListRefresh() {
    _listTimer?.cancel();
    _listTimer = Timer(const Duration(milliseconds: 250), () {
      unawaited(refreshConversations().catchError((Object _) {}));
    });
  }

  Future<void> select(Conversation conversation) async {
    if (active?.id != conversation.id) {
      focusedMessageId = null;
      focusedSequence = null;
    }
    _readTracker = null;
    if (_removed.contains(conversation.id)) return;
    active = conversation;
    error = null;
    _notify();
    final current = api, generation = _generation;
    if (current != null && conversation.kind == 'temporary') {
      try {
        final room = await current.temporaryRoom(conversation.targetId);
        if (_disposed ||
            generation != _generation ||
            _removed.contains(conversation.id)) {
          return;
        }
        _rooms[room.id] = room;
      } catch (failure) {
        if (!_disposed && generation == _generation) {
          error = describe(failure);
          _notify();
        }
        return;
      }
    }
    await _catchUp(conversation.id);
    if (current != null && conversation.kind == 'group') {
      try {
        final revision = _accessRevisions[conversation.id] ?? 0;
        final members = await current.groupMembers(conversation.targetId);
        if (!_disposed &&
            generation == _generation &&
            revision == (_accessRevisions[conversation.id] ?? 0)) {
          _memberNames.addAll({
            for (final m in members) m.userId: m.displayName,
          });
          _memberAvatars.addAll({for (final m in members) m.userId: m.avatar});
          _notify();
        }
      } catch (_) {
        /* Message contents remain usable with fallback member labels. */
      }
    }
  }

  void leaveConversation() {
    focusedMessageId = null;
    focusedSequence = null;
    sendTyping(false);
    _readTracker = null;
    active = null;
    _notify();
  }

  /// Fetch a complete overlap with the previous tail; do not advance a sync cursor
  /// past missing sequences just because a later realtime message arrived.
  Future<void> _catchUp(String id) async {
    final current = api, generation = _generation;
    if (current == null) return;
    if (_recoveryMode) return;
    final running = _syncing[id];
    if (running != null) {
      _syncAgain.add(id);
      return running.future;
    }
    final done = Completer<void>();
    _syncing[id] = done;
    _messages.putIfAbsent(id, () => []);
    loadingHistory = true;
    _notify();
    try {
      do {
        _syncAgain.remove(id);
        await _fetchHistory(current, id, generation);
      } while (!_disposed &&
          generation == _generation &&
          _syncAgain.contains(id));
    } finally {
      if (identical(_syncing[id], done)) {
        _syncing.remove(id);
        _syncAgain.remove(id);
        loadingHistory = _syncing.isNotEmpty;
      }
      done.complete();
      _notify();
    }
  }

  Future<void> _fetchHistory(
    MeshXApi current,
    String id,
    int generation,
  ) async {
    final revision = _accessRevisions[id] ?? 0, epoch = _socketEpoch;
    final old = _messages[id] ?? [];
    final confirmed = old.where((m) => m.sequence > 0).map((m) => m.sequence);
    final oldTail = confirmed.isEmpty
        ? 0
        : confirmed.reduce((a, b) => a > b ? a : b);
    try {
      var page = await current.history(id);
      if (!_valid(generation, epoch) ||
          revision != (_accessRevisions[id] ?? 0) ||
          _removed.contains(id)) {
        return;
      }
      _hasOlder[id] = page.length == 50;
      while (true) {
        _merge(id, page);
        final sequences = page
            .where((m) => m.sequence > 0)
            .map((m) => m.sequence);
        if (oldTail == 0 || page.length < 50 || sequences.isEmpty) break;
        final first = sequences.reduce((a, b) => a < b ? a : b);
        if (first <= oldTail) break;
        page = await current.history(id, before: first);
        if (!_valid(generation, epoch) ||
            revision != (_accessRevisions[id] ?? 0) ||
            _removed.contains(id)) {
          return;
        }
      }
      if (!_disposed && generation == _generation) await _persist();
      // This prototype intentionally doesn't emit read receipts based on page loading.
      // Receipt semantics need viewport visibility before they can be claimed as parity.
    } catch (failure) {
      if (!_disposed && generation == _generation) error = describe(failure);
    }
  }

  Future<void> loadOlder() async {
    if (_recoveryMode) return;
    final id = active?.id, current = api, generation = _generation;
    if (id == null || current == null || loadingHistory || !hasOlder) return;
    final values = (_messages[id] ?? [])
        .where((m) => m.sequence > 0)
        .map((m) => m.sequence);
    if (values.isEmpty) return;
    final revision = _accessRevisions[id] ?? 0, epoch = _socketEpoch;
    loadingHistory = true;
    error = null;
    _notify();
    try {
      final page = await current.history(
        id,
        before: values.reduce((a, b) => a < b ? a : b),
      );
      if (_disposed || generation != _generation) return;
      if (!_valid(generation, epoch) ||
          revision != (_accessRevisions[id] ?? 0) ||
          _removed.contains(id)) {
        return;
      }
      _merge(id, page);
      _hasOlder[id] = page.length == 50;
      await _persist();
    } catch (failure) {
      if (!_disposed && generation == _generation) error = describe(failure);
    } finally {
      if (!_disposed && generation == _generation) {
        loadingHistory = false;
        _notify();
      }
    }
  }

  void _quarantineRecovery(String reason, {bool cancel = true}) {
    if (!_recoveryMode) return;
    _recoveryPoll?.cancel();
    _readTracker?.cancel();
    _readTracker = null;
    for (final timer in _ackTimers.values) {
      timer.cancel();
    }
    _ackTimers.clear();
    if (cancel) _recovery?.cancel();
    if (_recoveryState != null) {
      _recoveryState = reason == 'PERSISTENCE_FAILED'
          ? storageBlockedRecovery(_recoveryState!)
          : quarantineRecovery(_recoveryState!, reason);
    }
    online = false;
    conversations = conversations
        .map(
          (c) => Conversation(
            id: c.id,
            targetId: c.targetId,
            kind: c.kind,
            title: c.title,
          ),
        )
        .toList();
    _notify();
  }

  Future<void> _runRecovery() async {
    if (!_recoveryMode || _disposed || _paused || api?.session == null) return;
    if (_recovering) {
      _recoveryAgain = true;
      return;
    }
    final current = api!, generation = _generation, epoch = _socketEpoch;
    final recoveryStore = store;
    if (recoveryStore is! RecoveryChatStore) {
      lastErrorCode = 'STORAGE_UNAVAILABLE';
      error = '当前存储不支持安全恢复';
      _quarantineRecovery('STORAGE_UNAVAILABLE');
      return;
    }
    final resumeFrom =
        _recoveryState?.phase == RecoveryPhase.onlineSafe &&
            _recoverySink != null
        ? _recoveryState
        : null;
    _recovering = true;
    _quarantineRecovery('RECOVERING');
    try {
      await _recoveryWrites;
      _recoveryReadBaselines.clear();
      final readPositions = await current.recoveryReadPositions();
      if (!_valid(generation, epoch) || _paused) return;
      _recoveryReadBaselines.addAll(readPositions);
      for (var round = 0; round < 8; round++) {
        _recoveryAgain = false;
        final sink = round == 0 && resumeFrom != null
            ? _recoverySink!
            : RecoveryChatSink(
                store: recoveryStore as RecoveryChatStore,
                liveCandidates: () => _liveNotificationCandidates.toList(),
                seed: () =>
                    RecoveryChatData(_messages, conversations, _positions),
                isCurrent: (c) =>
                    _valid(generation, epoch) &&
                    !_paused &&
                    identical(api, current) &&
                    c.userId == session?.userId.toString(),
                onQuarantine: (reason) {
                  if (_valid(generation, epoch)) {
                    _quarantineRecovery(reason, cancel: false);
                  }
                },
                onPublish: (state, image) {
                  if (!_valid(generation, epoch) || _recoveryAgain) return;
                  _removed.removeWhere(
                    (cid) => state.access[cid]?.readAllowed == true,
                  );
                  _recoveryNotifications = image.notificationRecovery;
                  _liveNotificationCandidates.clear();
                  _messages.clear();
                  for (final cid in {
                    ...image.chat.messages.keys,
                    ...image.outbox.keys,
                  }) {
                    final history = image.chat.messages[cid] ?? <ChatMessage>[];
                    _messages[cid] = [
                      ...history,
                      ...(image.outbox[cid] ?? <ChatMessage>[]).where(
                        (pending) => !history.any(
                          (m) =>
                              m.fromUserId == pending.fromUserId &&
                              m.clientMsgId.isNotEmpty &&
                              m.clientMsgId == pending.clientMsgId,
                        ),
                      ),
                    ];
                  }
                  drafts.removeWhere(
                    (cid, _) => state.access[cid]?.readAllowed != true,
                  );
                  final terminalsByConversation = <String, List<Json>>{};
                  for (final terminal in image.tombstones.values) {
                    terminalsByConversation
                        .putIfAbsent(
                          terminal['conversationId'] as String,
                          () => [],
                        )
                        .add(terminal);
                  }
                  final summaryTimes = <String, DateTime?>{};
                  final directoryOrder = {
                    for (var i = 0; i < image.chat.conversations.length; i++)
                      image.chat.conversations[i].id: i,
                  };
                  conversations = image.chat.conversations.map((c) {
                    final summary = recoveredConversationSummary(
                      image.chat.messages[c.id] ?? [],
                      terminalsByConversation[c.id] ?? [],
                      session!.userId,
                      _recoveryReadBaselines[c.id],
                    );
                    summaryTimes[c.id] = summary.timestamp;
                    return Conversation(
                      id: c.id,
                      targetId: c.targetId,
                      kind: c.kind,
                      title: c.title,
                      avatar: c.avatar,
                      preview: summary.preview,
                      unread: summary.unread,
                    );
                  }).toList();
                  conversations.sort((a, b) {
                    final first =
                        summaryTimes[a.id]?.millisecondsSinceEpoch ?? 0;
                    final second =
                        summaryTimes[b.id]?.millisecondsSinceEpoch ?? 0;
                    return first == second
                        ? directoryOrder[a.id]!.compareTo(directoryOrder[b.id]!)
                        : second.compareTo(first);
                  });
                  _positions
                    ..clear()
                    ..addAll(image.chat.positions);
                  _hasOlder.clear();
                  if (active != null) {
                    active = conversations
                        .where((c) => c.id == active!.id)
                        .firstOrNull;
                  }

                  _recoveryTerminals = image.tombstones.values
                      .where(
                        (item) =>
                            state.access[item['conversationId']]?.readAllowed ==
                            true,
                      )
                      .expand((item) {
                        final sequence = int.tryParse(
                          '${item['messageSequence']}',
                        );
                        return sequence != null &&
                                sequence > 0 &&
                                {
                                  'RECALLED',
                                  'BURNED',
                                  'UNAVAILABLE',
                                }.contains(item['state'])
                            ? [
                                RecoveryTerminal(
                                  item['messageId'] as String,
                                  item['conversationId'] as String,
                                  sequence,
                                  item['state'] as String,
                                ),
                              ]
                            : <RecoveryTerminal>[];
                      })
                      .toList();
                  _recoveryState = state;
                  online = true;
                  error = null;
                  _attempt = 0;
                  _authRefreshAttempted = false;
                  _notify();
                },
              );
        _recoverySink = sink;
        final runner = RecoveryCoordinator(
          recoveryTransportFactory(current),
          sink,
          (_) =>
              _valid(generation, epoch) && !_paused && identical(api, current),
        );
        _recovery = runner;
        final result = await runner.rebuild(
          RecoveryOwner(
            current.origin.origin,
            current.session!.userId.toString(),
            generation,
          ),
          requestId(),
          resumeFrom: round == 0 ? resumeFrom : null,
        );
        if (!_valid(generation, epoch) || _paused) return;
        if (_recoveryAgain ||
            {
              'REBUILD_REQUIRED',
              'CURSOR_EXPIRED',
              'STREAM_RESET',
              'CURSOR_AHEAD',
            }.contains(runner.reason)) {
          continue;
        }
        if (result?.phase != RecoveryPhase.onlineSafe) {
          _recoveryState = result;
          throw RecoveryProtocolError(runner.reason ?? 'RECOVERY_REQUIRED');
        }
        _recoveryPoll = Timer(
          const Duration(seconds: 5),
          () => unawaited(_runRecovery()),
        );
        return;
      }
      throw const RecoveryProtocolError('RECOVERY_BUSY');
    } catch (failure) {
      if (_valid(generation, epoch)) {
        _quarantineRecovery(
          failure is RecoveryProtocolError
              ? failure.reason
              : 'RECOVERY_UNAVAILABLE',
        );
        error = describe(failure);
        _scheduleReconnect();
      }
    } finally {
      _recovering = false;
      if (!_valid(generation, epoch) &&
          _recoveryMode &&
          !_paused &&
          !_disposed &&
          session != null) {
        _scheduleReconnect();
      }
      _notify();
    }
  }

  Future<void> reconnect() async {
    final current = api, generation = _generation;
    if (current?.session == null || _connecting || _disposed || _paused) return;
    _quarantineRecovery('RECONNECTING');
    final epoch = ++_socketEpoch;
    _connecting = true;
    _retryTimer?.cancel();
    online = false;
    _notify();
    final previous = _realtime;
    _realtime = null;
    try {
      await previous?.close();
      if (!_valid(generation, epoch) || _paused) return;
      final connection = connectionFactory(current!);
      _realtime = connection;
      // Every connection owns its queue; an old blocked handler never blocks AUTH.
      _inbound = Future.value();
      connection.events.stream.listen((event) {
        _inbound = _inbound
            .then((_) async {
              if (!_valid(generation, epoch)) return;
              _onEvent(event);
              if (!_recoveryMode && _valid(generation, epoch)) await _persist();
            })
            .catchError((Object failure) {
              if (_valid(generation, epoch)) {
                error = describe(failure);
                online = false;
                _notify();
              }
            });
      });
      connection.disconnected.stream.listen((_) {
        if (_valid(generation, epoch)) _disconnected();
      });
      await current.validateCurrentUser();
      if (!_valid(generation, epoch) || _paused) return;
      await connection.connect();
      if (!_valid(generation, epoch) || _paused) return;
      if (_recoveryMode) {
        await _runRecovery();
        return;
      }
      await refreshConversations();
      if (!_valid(generation, epoch) || _paused) return;
      for (final id in _messages.keys.toList()) {
        for (final m in _messages[id]!.where(
          (m) => m.delivery == Delivery.sent,
        )) {
          _revalidateIds[m.messageId] = id;
        }
        _messages[id] = _messages[id]!
            .where((m) => m.delivery != Delivery.sent)
            .toList();
      }
      _positions.clear();
      // Use bounded one-conversation pages, avoiding the server's global 100/200 cap.
      for (final conversation in conversations.toList()) {
        final id = conversation.id;
        for (var page = 0; ; page++) {
          if (page >= 1000) throw const ApiException('同步超过本次上限，请重连继续');
          final before = _positions[id] ?? 0;
          final response = await connection.synchronize({id: before});
          if (!_valid(generation, epoch) || _paused) return;
          if (_removed.contains(id)) break;
          if ((response['deniedConversationIds'] as List).contains(id)) {
            _removeConversation(id);
            await _persist();
            break;
          }
          final items = (response['messages'] as List)
              .map((j) => ChatMessage.fromJson(j as Json))
              .toList();
          if (items.any(
            (m) => m.conversationId != id || m.sequence <= before,
          )) {
            throw const FormatException('同步返回范围不匹配');
          }
          _merge(id, items);
          // Authoritative ascending sync may cross physically deleted sequences.
          final latest = integer((response['latestPositions'] as Map)[id]);
          final after = items.isEmpty ? latest : items.last.sequence;
          final received = _positions[id] ?? before;
          _positions[id] = after > received ? after : received;
          await _persist();
          if (!_valid(generation, epoch)) return;
          if (response['hasMore'] != true) break;
          if (after <= before) throw const ApiException('同步没有前进，已停止本轮重试');
        }
      }
      await _inbound;
      if (!_valid(generation, epoch) || _paused) return;
      final presentIds = {
        for (final rows in _messages.values)
          for (final m in rows) m.messageId,
      };
      for (final entry in _revalidateIds.entries) {
        if (!presentIds.contains(entry.key)) {
          _unavailableIds.add(entry.key);
          _invalidations.add((
            conversationId: entry.value,
            messageId: entry.key,
          ));
        }
      }
      _revalidateIds.clear();
      online = true;
      _readAttempts.clear();
      _flushReads();
      _authRefreshAttempted = false;
      _attempt = 0;
      error = null;
      _notify();
      await _drainOutbox();
    } catch (failure) {
      if (!_valid(generation, epoch)) return;
      await _realtime?.close();
      if (!_valid(generation, epoch)) return;
      online = false;
      error = describe(failure);
      if (failure is SessionRevokedException) {
        await logout(revokeRemote: false, reason: failure.message);
        return;
      }
      if (failure is ApiException && failure.code == 401) {
        if (_authRefreshAttempted) {
          await logout(revokeRemote: false, reason: '登录已失效，请重新登录');
          return;
        }
        _authRefreshAttempted = true;
        try {
          await current!.refresh();
        } catch (_) {
          if (_valid(generation, epoch)) {
            await logout(revokeRemote: false, reason: '登录已失效，请重新登录');
          }
          return;
        }
      }
      if (_valid(generation, epoch)) _scheduleReconnect();
    } finally {
      if (_valid(generation, epoch)) {
        _connecting = false;
        _notify();
      }
    }
  }

  void _scheduleReconnect() {
    if (_disposed ||
        _paused ||
        session == null ||
        _retryTimer?.isActive == true) {
      return;
    }
    _retryTimer = Timer(
      reconnectDelay(_attempt++, randomFraction: Random().nextDouble()),
      reconnect,
    );
  }

  void _disconnected() {
    _clearTyping();
    final generation = _generation;
    _readRetry?.cancel();
    _readTracker?.cancel();
    _quarantineRecovery('DISCONNECTED');
    ++_socketEpoch;
    _connecting = false;
    _draining = false;
    final old = _realtime;
    _realtime = null;
    unawaited(old?.close());
    online = false;
    for (final id in _messages.keys) {
      _messages[id] = _messages[id]!
          .map(
            (m) => m.delivery == Delivery.sending
                ? m.withDelivery(Delivery.queued)
                : m,
          )
          .toList();
    }
    for (final timer in _ackTimers.values) {
      timer.cancel();
    }
    _ackTimers.clear();
    if (!_recoveryMode) {
      unawaited(
        _persist().catchError((Object failure) {
          if (_disposed || generation != _generation) return;
          error = describe(failure);
          _notify();
        }),
      );
    }
    _scheduleReconnect();
    _notify();
  }

  bool send(
    String text, {
    ChatMessage? retry,
    String? replyToId,
    Set<int> mentions = const {},
    bool burn = false,
  }) => _sendContent(
    content: retry?.content ?? text.trim(),
    contentType: retry?.contentType ?? 'text',
    retry: retry,
    isBurn: retry?.isBurn ?? burn,
    replyToId: retry?.replyToId ?? replyToId,
    mentionUserIds:
        retry?.mentionUserIds ??
        (mentions.isEmpty ? null : (mentions.toList()..sort()).join(',')),
  );

  bool sendAttachment(
    AttachmentData attachment, {
    required String conversationId,
  }) {
    if (active?.id != conversationId) {
      error = '会话已切换，附件没有加入待发队列';
      _notify();
      return false;
    }
    return _sendContent(
      content: attachment.encode(),
      contentType: attachment.image ? 'image' : 'file',
    );
  }

  Future<bool> queueTransferredAttachment(
    AttachmentData attachment, {
    required String conversationId,
    required String clientMsgId,
  }) async {
    if (active?.id != conversationId) return false;
    final generation = _generation;
    final accepted = _sendContent(
      content: attachment.encode(),
      contentType: attachment.image ? 'image' : 'file',
      stableClientId: clientMsgId,
    );
    if (!accepted) return false;
    await _persist();
    return !_disposed && generation == _generation;
  }

  bool requestRecall(ChatMessage message) {
    if (!online ||
        !allowsMessage(message) ||
        message.fromUserId != session?.userId ||
        message.delivery != Delivery.sent) {
      return false;
    }
    try {
      _realtime!.send('CHAT_RECALL', {
        'messageId': message.messageId,
      }, conversationId: message.conversationId);
      return true;
    } catch (failure) {
      error = describe(failure);
      _notify();
      return false;
    }
  }

  bool retryMessage(ChatMessage message) => _sendContent(
    content: message.content,
    contentType: message.contentType,
    retry: message,
  );

  bool _sendContent({
    required String content,
    required String contentType,
    ChatMessage? retry,
    String? replyToId,
    String? mentionUserIds,
    bool isBurn = false,
    String? stableClientId,
  }) {
    final conversation = active;
    if (_recoveryMode &&
        (!online ||
            _recoveryState?.phase != RecoveryPhase.onlineSafe ||
            _recoveryState?.access[conversation?.id]?.sendAllowed != true)) {
      error = '消息校验或发送权限尚未就绪，内容保留在输入框';
      _notify();
      return false;
    }
    if (conversation == null ||
        session == null ||
        _removed.contains(conversation.id)) {
      error = '连接恢复后可以重试发送';
      _notify();
      return false;
    }
    if (retry != null &&
        (retry.fromUserId != session!.userId ||
            retry.conversationId != conversation.id ||
            !(_messages[conversation.id] ?? []).any(
              (m) =>
                  m.clientMsgId == retry.clientMsgId &&
                  m.fromUserId == retry.fromUserId &&
                  m.content == retry.content &&
                  m.contentType == retry.contentType,
            ))) {
      error = '重试消息不属于当前账号和会话';
      _notify();
      return false;
    }
    if (content.isEmpty) return false;
    focusedMessageId = null;
    focusedSequence = null;
    if (contentType == 'text' && content.length > 4000) {
      error = '每条消息最多 4000 个字符';
      _notify();
      return false;
    }
    if (!{'text', 'image', 'file'}.contains(contentType) ||
        (contentType != 'text' && content.length > 30000)) {
      error = '附件消息格式无效';
      _notify();
      return false;
    }
    if (conversation.kind == 'temporary' &&
        (!online ||
            (contentType != 'text' &&
                _rooms[conversation.targetId]?.allowFileUpload != true) ||
            _rooms[conversation.targetId]?.available != true)) {
      error = '临时房间已到期、离线或未允许发送文件';
      _notify();
      return false;
    }
    if (!{'private', 'group', 'temporary'}.contains(conversation.kind)) {
      error = '此类会话暂不支持发送';
      _notify();
      return false;
    }
    if (conversation.kind == 'private' &&
        _friendAccessLoaded &&
        !_friendIds.contains(conversation.targetId)) {
      error = '对方已不在联系人中，待发消息不会自动重试';
      _notify();
      return false;
    }
    if (stableClientId != null) {
      final existing = (_messages[conversation.id] ?? [])
          .where(
            (m) =>
                m.clientMsgId == stableClientId &&
                m.fromUserId == session!.userId,
          )
          .firstOrNull;
      if (existing != null) {
        return existing.content == content &&
            existing.contentType == contentType;
      }
    }
    final message =
        retry?.withDelivery(Delivery.queued) ??
        ChatMessage(
          messageId: '',
          clientMsgId: stableClientId ?? requestId(),
          conversationId: conversation.id,
          fromUserId: session!.userId,
          content: content,
          contentType: contentType,
          sequence: 0,
          createdAt: DateTime.now(),
          delivery: Delivery.queued,
          replyToId: replyToId,
          mentionUserIds: mentionUserIds,
          isBurn: isBurn,
        );
    if (message.recoveryDisposition != null) {
      error = '此待发消息已停止重试，请确认权限后新建消息';
      _notify();
      return false;
    }
    _messages[conversation.id] = mergeMessages(
      _messages[conversation.id] ?? [],
      [message],
    );
    error = null;
    if (store == null && online) {
      _transmit(conversation, message);
    } else {
      final generation = _generation;
      unawaited(() async {
        try {
          await _persist(); // Durable acceptance must precede the network side effect.
          if (!_disposed && generation == _generation) await _drainOutbox();
        } catch (failure) {
          if (!_disposed && generation == _generation) {
            _setDelivery(conversation.id, message.clientMsgId, Delivery.failed);
            error = describe(failure);
            _notify();
          }
        }
      }());
    }
    _notify();
    return true;
  }

  Future<void> _drainOutbox() async {
    if (_draining ||
        !online ||
        (_recoveryMode && _recoveryState?.phase != RecoveryPhase.onlineSafe)) {
      return;
    }
    _draining = true;
    final generation = _generation, epoch = _socketEpoch;
    try {
      while (_valid(generation, epoch) && online) {
        Conversation? target;
        ChatMessage? pending;
        for (final conversation in conversations) {
          if (_recoveryMode &&
              _recoveryState?.access[conversation.id]?.sendAllowed != true) {
            continue;
          }
          for (final message in _messages[conversation.id] ?? <ChatMessage>[]) {
            if (message.delivery == Delivery.queued &&
                message.recoveryDisposition == null &&
                message.fromUserId == session?.userId) {
              target = conversation;
              pending = message;
              break;
            }
          }
          if (pending != null) break;
        }
        if (target == null || pending == null) return;
        await _persist();
        if (!_valid(generation, epoch) || !online) return;
        final stillPending = (_messages[target.id] ?? []).any(
          (m) =>
              m.clientMsgId == pending!.clientMsgId &&
              m.fromUserId == session?.userId &&
              m.delivery == Delivery.queued,
        );
        if (stillPending) _transmit(target, pending);
      }
    } finally {
      if (_valid(generation, epoch)) _draining = false;
    }
  }

  void _transmit(Conversation conversation, ChatMessage message) {
    if (message.recoveryDisposition != null ||
        (_recoveryMode &&
            (!online ||
                _recoveryState?.phase != RecoveryPhase.onlineSafe ||
                _recoveryState?.access[conversation.id]?.sendAllowed !=
                    true))) {
      return;
    }
    if (_removed.contains(conversation.id) ||
        (conversation.kind == 'temporary' &&
            _rooms[conversation.targetId]?.available != true)) {
      _setDelivery(conversation.id, message.clientMsgId, Delivery.failed);
      return;
    }
    _setDelivery(conversation.id, message.clientMsgId, Delivery.sending);
    try {
      _realtime!.send(
        WsEvents.chat_send,
        ChatSendPayload(
          toUserId: conversation.kind == 'private'
              ? conversation.targetId
              : null,
          groupId: conversation.kind == 'group' ? conversation.targetId : null,
          contentType: message.contentType,
          content: message.content,
          isBurn: message.isBurn,
          replyToId: message.replyToId,
          mentionUserIds: message.mentionUserIds,
        ).toJson(),
        clientMsgId: message.clientMsgId,
        conversationId: conversation.id,
      );
      _ackTimers.remove(message.clientMsgId)?.cancel();
      final generation = _generation;
      _ackTimers[message.clientMsgId] = Timer(const Duration(seconds: 12), () {
        if (_disposed || generation != _generation) return;
        _setDelivery(conversation.id, message.clientMsgId, Delivery.failed);
        _ackTimers.remove(message.clientMsgId);
        unawaited(
          _persist().catchError((Object failure) {
            error = describe(failure);
          }),
        );
        _notify();
      });
    } catch (failure) {
      _setDelivery(conversation.id, message.clientMsgId, Delivery.failed);
      error = describe(failure);
    }
    _notify();
  }

  void _setDelivery(
    String id,
    String clientId,
    Delivery delivery, {
    String? messageId,
    int? sequence,
  }) {
    _messages[id] = (_messages[id] ?? [])
        .map(
          (m) => m.clientMsgId == clientId && m.fromUserId == session?.userId
              ? m.withDelivery(
                  delivery,
                  id: messageId,
                  serverSequence: sequence,
                )
              : m,
        )
        .toList();
  }

  void _onEvent(Json event) {
    if (_recoveryMode &&
        {
          'CHAT_ACK',
          'CHAT_DELIVER',
          'CHAT_READ',
          'CHAT_RECALL',
          'CHAT_BURN',
          'MUTATION_AVAILABLE',
          'FRIEND_CHANGED',
          'CONVERSATION_REMOVED',
          'CONVERSATION_CHANGED',
        }.contains(event['event'])) {
      if (event['event'] == 'CHAT_DELIVER' &&
          _recoveryState?.phase == RecoveryPhase.onlineSafe) {
        final id = (event['payload'] as Json?)?['messageId'];
        if (id is String &&
            id.isNotEmpty &&
            id.length <= 128 &&
            !_recoveryState!.messages.containsKey(id) &&
            _liveNotificationCandidates.length < 1000) {
          _liveNotificationCandidates.add(id);
        }
      }
      if (event['event'] == 'FRIEND_CHANGED') _friendChanges.add(null);
      final clientId =
          event['clientMsgId'] ?? (event['payload'] as Json?)?['clientMsgId'];
      if (clientId is String) _ackTimers.remove(clientId)?.cancel();
      _recoveryAgain = true;
      _quarantineRecovery('RECOVERY_REQUIRED', cancel: !_recovering);
      unawaited(_runRecovery());
      return; // Legacy bodies and ACK are scheduling hints, never version proofs.
    }
    if ('${event['event']}'.startsWith('FILE_TRANSFER_')) {
      _transferEvents.add(event);
    }
    final payload = event['payload'] as Json? ?? {};
    final id =
        (event['conversationId'] ?? payload['conversationId']) as String?;
    final clientId =
        (event['clientMsgId'] ?? payload['clientMsgId']) as String?;
    switch (event['event']) {
      case 'TYPING_START':
      case 'TYPING_STOP':
        final user = integer(payload['userId']);
        if (id == null ||
            user <= 0 ||
            user == session?.userId ||
            _removed.contains(id)) {
          break;
        }
        final users = _typing.putIfAbsent(id, () => {});
        if (event['event'] == 'TYPING_STOP') {
          users.remove(user);
        } else {
          users[user] = DateTime.now().add(const Duration(seconds: 6));
        }
        _typingExpiry ??= Timer.periodic(const Duration(seconds: 1), (timer) {
          final now = DateTime.now();
          for (final users in _typing.values) {
            users.removeWhere((_, end) => !end.isAfter(now));
          }
          _typing.removeWhere((_, users) => users.isEmpty);
          if (_typing.isEmpty) {
            timer.cancel();
            _typingExpiry = null;
          }
          _notify();
        });

      case 'CONVERSATION_REMOVED':
        if (id != null) _removeConversation(id);
      case 'CONVERSATION_CHANGED':
        if (id != null) {
          _accessRevisions[id] = (_accessRevisions[id] ?? 0) + 1;
          _removed.remove(id);
          _conversationChanges.add(id);
          _scheduleListRefresh();
        }
      case 'CHAT_RECALL':
      case 'CHAT_BURN':
        if (id != null &&
            payload['messageId'] is String &&
            !_removed.contains(id)) {
          _terminalEvent(
            id,
            payload['messageId'],
            event['event'] == 'CHAT_BURN',
          );
        }
      case 'CHAT_READ':
        if (id == null || integer(payload['userId']) != session?.userId) break;
        final last = payload['lastSequence'],
            read = payload['lastReadSequence'],
            unread = payload['unreadCount'];
        if (last is! int ||
            read is! int ||
            unread is! int ||
            read < 0 ||
            last < read ||
            unread < 0) {
          break;
        }
        final previous = conversations.where((c) => c.id == id).firstOrNull;
        if (previous == null) {
          _scheduleListRefresh();
          break;
        }
        if (read < previous.lastReadSequence) break;
        ++_summaryRevision;
        if (last < previous.lastSequence) {
          _scheduleListRefresh();
          break;
        }
        conversations = conversations
            .map(
              (c) => c.id == id
                  ? c.withReadState(
                      lastSequence: last,
                      lastReadSequence: read,
                      unread: unread,
                    )
                  : c,
            )
            .toList();
        if ((_pendingReads[id] ?? 0) <= read) _pendingReads.remove(id);
      case 'CHAT_ACK':
        if (id != null && clientId != null) {
          _ackTimers.remove(clientId)?.cancel();
          _setDelivery(
            id,
            clientId,
            Delivery.sent,
            messageId: payload['messageId']?.toString(),
            sequence: integer(payload['sequence']),
          );
          _messages[id] = mergeMessages(_messages[id] ?? [], []);
          _scheduleListRefresh();
        }
      case 'CHAT_DELIVER':
        final message = ChatMessage.fromJson(payload);
        if (_removed.contains(message.conversationId)) break;
        ++_summaryRevision;
        conversations = conversations.map((c) {
          if (c.id != message.conversationId ||
              message.sequence <= c.lastSequence) {
            return c;
          }
          return c.withReadState(
            lastSequence: message.sequence,
            lastReadSequence: c.lastReadSequence,
            unread:
                c.unread +
                (message.fromUserId != session?.userId &&
                        message.sequence > c.lastReadSequence
                    ? 1
                    : 0),
          );
        }).toList();
        final alreadyStored = (_messages[message.conversationId] ?? []).any(
          (old) =>
              (message.messageId.isNotEmpty &&
                  old.messageId == message.messageId) ||
              (message.clientMsgId.isNotEmpty &&
                  old.clientMsgId == message.clientMsgId &&
                  old.fromUserId == message.fromUserId),
        );
        onLiveMessage?.call(message, online && !_paused, alreadyStored);
        if (message.clientMsgId.isNotEmpty &&
            message.fromUserId == session?.userId) {
          _ackTimers.remove(message.clientMsgId)?.cancel();
        }
        {
          _merge(message.conversationId, [message]);
        }
        final position = _positions[message.conversationId] ?? 0;
        _positions[message.conversationId] = advanceContiguousSequence(
          position,
          (_messages[message.conversationId] ?? []).map((m) => m.sequence),
        );
        if (online && message.sequence > position + 1) {
          online = false;
          _scheduleReconnect();
        }
        _scheduleListRefresh();
      case 'ERROR':
        if (id != null && clientId != null) {
          _ackTimers.remove(clientId)?.cancel();
          _setDelivery(id, clientId, Delivery.failed);
        }
        error = payload['message'] as String? ?? '操作未完成，请重试';
      case 'FORCE_LOGOUT':
        unawaited(
          logout(
            revokeRemote: false,
            reason: payload['message'] as String? ?? '设备会话已经失效，请重新登录',
          ),
        );
      case 'TOKEN_EXPIRED':
        unawaited(_realtime?.close());
        _disconnected();
      case 'FRIEND_CHANGED':
        _friendChanges.add(null);
        _scheduleListRefresh();
      case 'BROADCAST':
      case 'BROADCAST_UPDATED':
      case 'BROADCAST_REMINDER':
      case 'BROADCAST_PERMISSION_UPDATED':
        _broadcastChanges.add(null);
    }
    _notify();
  }

  Future<void> pause() async {
    if (_paused || _disposed) return;
    _readRetry?.cancel();
    _readTracker?.cancel();
    final resumeDiscovery = scanning || _resumeDiscovery;
    _paused = true;
    _quarantineRecovery('BACKGROUND');
    unawaited(stopScan(cancelled: false));
    _resumeDiscovery = resumeDiscovery;
    ++_socketEpoch;
    _connecting = false;
    _retryTimer?.cancel();
    final generation = _generation, epoch = _socketEpoch;
    await _realtime?.close();
    if (_valid(generation, epoch)) _disconnected();
  }

  Future<void> resume() async {
    if (_disposed) return;
    final wasPaused = _paused;
    _paused = false;
    if (_resumeDiscovery) {
      _resumeDiscovery = false;
      unawaited(scan(requestPermission: false));
    }
    if (wasPaused || !online) await reconnect();
  }

  Future<void> networkChanged() async {
    if (_disposed) return;
    final scanningBefore = scanning || _resumeDiscovery;
    await stopScan(cancelled: false);
    if (_paused) {
      _resumeDiscovery = scanningBefore;
      return;
    }
    _resumeDiscovery = scanningBefore;
    // Reuse the existing guarded authentication and synchronization pipeline.
    await pause();
    await resume();
  }

  Future<void> logout({bool revokeRemote = true, String? reason}) async {
    _clearTyping();
    _draftTimer?.cancel();
    _revalidateIds.clear();
    _unavailableIds.clear();
    _rooms.clear();
    _removed.clear();
    _accessRevisions.clear();
    _terminals.clear();
    _memberAvatars.clear();
    _readRetry?.cancel();
    _pendingReads.clear();
    _readAttempts.clear();
    _readTracker = null;
    _quarantineRecovery('SIGNED_OUT');
    _recoveryMode = false;
    _recoveryState = null;
    _recoveryNotifications = null;
    _liveNotificationCandidates.clear();
    ++_generation;
    ++_socketEpoch;
    _connecting = false;
    _draining = false;
    busy = false;
    _retryTimer?.cancel();
    _listTimer?.cancel();
    final connection = _realtime;
    _realtime = null;
    for (final timer in _ackTimers.values) {
      timer.cancel();
    }
    _ackTimers.clear();
    final current = api;
    api = null;
    online = false;
    active = null;
    conversations = [];
    _messages.clear();
    _positions.clear();
    _hasOlder.clear();
    _syncing.clear();
    _syncAgain.clear();
    loadingHistory = false;
    drafts.clear();
    focusedMessageId = null;
    focusedSequence = null;
    _memberNames.clear();
    _friendIds = {};
    _friendAccessLoaded = false;
    error = reason;
    _paused = false;
    _notify();
    // Detach refresh callback before any await; pending writes finish before deletion.
    if (current != null) current.onCredentialsChanged = null;
    try {
      await _writeCredential(null);
      _credentialBlocked = false;
    } catch (failure) {
      _credentialBlocked = true;
      error = describe(failure);
      _notify();
    }
    await connection?.close();
    if (current != null) {
      try {
        if (revokeRemote) await current.logout();
      } catch (_) {
        /* Locally cleared; server may be unreachable. */
      } finally {
        current.close();
      }
    }
  }

  @override
  void dispose() {
    unawaited(_transferEvents.close());
    _clearTyping();
    _readRetry?.cancel();
    _draftTimer?.cancel();
    unawaited(_conversationChanges.close());
    unawaited(_invalidations.close());
    _quarantineRecovery('DISPOSED');
    _disposed = true;
    unawaited(stopScan());
    unawaited(_discoveryUpdates?.cancel());
    unawaited(discovery.dispose());
    ++_generation;
    ++_socketEpoch;
    _connecting = false;
    unawaited(_friendChanges.close());
    unawaited(_broadcastChanges.close());
    _draining = false;
    busy = false;
    _retryTimer?.cancel();
    _listTimer?.cancel();
    for (final timer in _ackTimers.values) {
      timer.cancel();
    }
    unawaited(_realtime?.close());
    api?.close();
    super.dispose();
  }
}
