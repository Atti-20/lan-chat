import 'models.dart';
import 'recovery.dart';

/// Body-free notification routing and durable OS effects in the chat generation.
class RecoveryNotificationJournal {
  RecoveryNotificationJournal([Object? raw]) {
    if (raw == null) {
      _effects['migration'] = {'key': 'migration', 'kind': 'CANCEL_ALL'};
      return;
    }
    if (raw is! Map ||
        raw['version'] != 1 ||
        raw['routes'] is! List ||
        raw['effects'] is! List ||
        raw.keys.any((k) => !{'version', 'routes', 'effects'}.contains(k))) {
      _fail();
    }
    final value = raw;
    for (final r in value['routes'] as List) {
      if (r is! Map ||
          r.length != 5 ||
          r.keys.any(
            (k) => !{
              'messageId',
              'conversationId',
              'objectVersion',
              'accessVersion',
              'streamEpoch',
            }.contains(k),
          ) ||
          !_id(r['messageId']) ||
          !_id(r['conversationId']) ||
          !_id(r['streamEpoch']) ||
          _routes.containsKey(r['messageId'])) {
        _fail();
      }
      recoveryDecimal(r['objectVersion'], allowZero: false);
      recoveryDecimal(r['accessVersion'], allowZero: false);
      _routes[r['messageId'] as String] = Map<String, dynamic>.from(r);
    }
    for (final e in value['effects'] as List) {
      if (e is! Map ||
          !_id(e['key']) ||
          _effects.containsKey(e['key']) ||
          !{'SHOW', 'CANCEL', 'CANCEL_ALL'}.contains(e['kind']) ||
          e.keys.any((k) => !{'key', 'kind', 'messageId'}.contains(k)) ||
          (e['kind'] != 'CANCEL_ALL' && !_id(e['messageId'])) ||
          (e['kind'] == 'SHOW' && !_routes.containsKey(e['messageId']))) {
        _fail();
      }
      _effects[e['key'] as String] = Map<String, dynamic>.from(e);
    }
  }
  final _routes = <String, Json>{}, _effects = <String, Json>{};
  static Never _fail() =>
      throw const RecoveryProtocolError('UNSUPPORTED_SECURE_STATE');
  static bool _id(Object? value) =>
      value is String && value.isNotEmpty && value.length <= 256;
  bool _matches(RecoveryState state, Json r) {
    final message = state.messages[r['messageId']],
        access = state.access[r['conversationId']];
    return state.context.streamEpoch == r['streamEpoch'] &&
        message?.state == MessageRecoveryState.normal &&
        message?.conversationId == r['conversationId'] &&
        message?.objectVersion == r['objectVersion'] &&
        access?.readAllowed == true &&
        access?.accessVersion == r['accessVersion'] &&
        !state.rebuild.contains(r['conversationId']);
  }

  void reconcile(
    RecoveryState state, [
    List<String> liveMessageIds = const [],
  ]) {
    for (final id in _routes.keys.toList()) {
      if (!_matches(state, _routes[id]!)) {
        _routes.remove(id);
        _effects.remove('show:$id');
        _effects['cancel:$id'] = {
          'key': 'cancel:$id',
          'kind': 'CANCEL',
          'messageId': id,
        };
      }
    }
    for (final id in liveMessageIds) {
      if (_routes.containsKey(id)) continue;
      final message = state.messages[id],
          access = message == null
              ? null
              : state.access[message.conversationId];
      if (message?.state != MessageRecoveryState.normal ||
          access?.readAllowed != true ||
          state.rebuild.contains(message!.conversationId)) {
        continue;
      }
      _routes[id] = {
        'messageId': id,
        'conversationId': message.conversationId,
        'objectVersion': message.objectVersion,
        'accessVersion': access!.accessVersion,
        'streamEpoch': state.context.streamEpoch,
      };
      _effects['show:$id'] = {
        'key': 'show:$id',
        'kind': 'SHOW',
        'messageId': id,
      };
    }
  }

  bool allows(RecoveryState state, String id) =>
      state.phase == RecoveryPhase.onlineSafe &&
      _routes.containsKey(id) &&
      _matches(state, _routes[id]!);
  void acknowledge(String key) => _effects.remove(key);
  Json image() => {
    'version': 1,
    'routes': [for (final r in _routes.values) Map<String, dynamic>.of(r)],
    'effects': [for (final e in _effects.values) Map<String, dynamic>.of(e)],
  };
}
