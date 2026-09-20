import '../core/recovery.dart';
import '../core/recovery_snapshot.dart';
import '../data/meshx_api.dart';

class RecoveryOwner {
  const RecoveryOwner(this.origin, this.userId, this.generation);
  final String origin, userId;
  final int generation;
}

abstract interface class RecoveryTransport {
  Future<Object?> capabilities();
  Future<Object?> open(Map<String, dynamic> input, String key);
  Future<Object?> snapshot(String id, String? token, int limit);
  Future<Object?> cut(String id);
  Future<Object?> mutations(String id, String after, String through, int limit);
  Future<Object?> ready(String id, String cursor, bool complete);
  Future<void> release(String id);
}

class ApiRecoveryTransport implements RecoveryTransport {
  ApiRecoveryTransport(this.api);
  final MeshXApi api;
  @override
  Future<Object?> capabilities() => api.recoveryCapabilities();
  @override
  Future<Object?> open(Map<String, dynamic> input, String key) =>
      api.openRecovery(input, idempotencyKey: key);
  @override
  Future<Object?> snapshot(String id, String? token, int limit) =>
      api.recoverySnapshot(id, pageToken: token, limit: limit);
  @override
  Future<Object?> cut(String id) => api.recoveryCut(id);
  @override
  Future<Object?> mutations(
    String id,
    String after,
    String through,
    int limit,
  ) => api.recoveryMutations(id, after, through, limit: limit);
  @override
  Future<Object?> ready(String id, String cursor, bool complete) =>
      api.recoveryReady(id, cursor, snapshotComplete: complete);
  @override
  Future<void> release(String id) => api.releaseRecovery(id);
}

/// Implementations must scope all staging, commits and publication to context.
abstract interface class RecoverySink {
  Future<void> begin(RecoveryContext context);
  Future<void> resume(RecoveryContext context, RecoveryState state);
  Future<void> snapshot(RecoveryContext context, List<SnapshotItem> items);
  Future<void> mutations(RecoveryContext context, RecoveryPlan plan);
  Future<void> commit(RecoveryContext context, RecoveryState state);
  void publish(RecoveryContext context, RecoveryState state);
  void quarantine(String reason);
}

Map<String, dynamic> _record(Object? value) {
  if (value is! Map || value.keys.any((key) => key is! String)) {
    throw const RecoveryProtocolError('PROTOCOL_ERROR');
  }
  return Map<String, dynamic>.from(value);
}

String _id(Object? value) {
  if (value is! String || value.isEmpty || value.length > 128) {
    throw const RecoveryProtocolError('PROTOCOL_ERROR');
  }
  return value;
}

class RecoveryCoordinator {
  RecoveryCoordinator(this.transport, this.sink, this.isCurrent);
  final RecoveryTransport transport;
  final RecoverySink sink;
  final bool Function(RecoveryOwner owner) isCurrent;
  int _turn = 0;
  RecoveryState? state;
  String? reason, durableCursor;
  void cancel() {
    _turn++;
    if (state != null) state = quarantineRecovery(state!, 'DISCONNECTED');
    sink.quarantine('DISCONNECTED');
  }

  Future<RecoveryState?> rebuild(
    RecoveryOwner owner,
    String idempotencyKey, {
    RecoveryState? resumeFrom,
  }) async {
    final turn = ++_turn;
    RecoveryState? outcome;
    String? sessionId;
    bool valid() => turn == _turn && isCurrent(owner);
    void check() {
      if (!valid()) throw const RecoveryProtocolError('STALE_RECOVERY');
    }

    final workBudget = Stopwatch()..start();
    var pagesSinceYield = 0;
    Future<void> checkpoint() async {
      if (++pagesSinceYield < 4 && workBudget.elapsedMilliseconds < 8) return;
      // A completed HTTP future can otherwise keep the event loop in a long
      // microtask chain. Allow frames, cancellation and account changes through.
      await Future<void>.delayed(Duration.zero);
      check();
      pagesSinceYield = 0;
      workBudget.reset();
    }

    if (!valid()) return null;
    state = null;
    reason = null;
    durableCursor = null;
    sink.quarantine('RECOVERING');
    try {
      final capability = _record(await transport.capabilities());
      check();
      if (capability['capability'] != 'meshx.mutation-recovery' ||
          capability['recordVersion'] != 1 ||
          capability['versions'] is! List ||
          !(capability['versions'] as List).contains(1)) {
        throw const RecoveryProtocolError('BLOCKED_UPGRADE');
      }
      final limit = capability['maxPageSize'];
      if (limit is! int || limit < 1 || limit > 200) {
        throw const RecoveryProtocolError('PROTOCOL_ERROR');
      }
      if (resumeFrom != null &&
          (resumeFrom.phase != RecoveryPhase.onlineSafe ||
              resumeFrom.context.origin != owner.origin ||
              resumeFrom.context.userId != owner.userId ||
              resumeFrom.context.generation != owner.generation)) {
        throw const RecoveryProtocolError('STALE_RECOVERY');
      }
      final opened = _record(
        await transport.open({
          'protocolVersion': 1,
          'mode': resumeFrom == null ? 'rebuild' : 'resume',
          if (resumeFrom != null)
            'cursor': {
              'streamEpoch': resumeFrom.context.streamEpoch,
              'position': resumeFrom.cursor,
            },
        }, idempotencyKey),
      );
      sessionId = _id(opened['recoveryId']);
      check();
      final boundary = _id(opened['startCursor']);
      if (recoveryDecimal(opened['floor']) > recoveryDecimal(boundary) ||
          recoveryDecimal(opened['latest']) < recoveryDecimal(boundary)) {
        throw const RecoveryProtocolError('PROTOCOL_ERROR');
      }
      final context = RecoveryContext(
        origin: owner.origin,
        userId: owner.userId,
        generation: owner.generation,
        streamEpoch: _id(opened['streamEpoch']),
      );
      RecoveryState candidate;
      if (opened['mode'] == 'resume') {
        if (resumeFrom == null ||
            !resumeFrom.context.matches(context) ||
            boundary != resumeFrom.cursor) {
          throw const RecoveryProtocolError('STREAM_RESET');
        }
        candidate = resumeFrom.copyWith(phase: RecoveryPhase.catchingUp);
        await sink.resume(context, candidate);
        check();
        state = candidate;
      } else {
        if (opened['mode'] != 'rebuild' ||
            opened['snapshotBoundary'] != boundary) {
          throw const RecoveryProtocolError('PROTOCOL_ERROR');
        }
        var stage = beginFullRecoverySnapshot(
          context,
          _id(opened['snapshotId']),
          boundary,
        );
        state = stage.state;
        await sink.begin(context);
        check();
        do {
          final response = await transport.snapshot(
            sessionId,
            stage.nextToken,
            limit,
          );
          check();
          final page = stageRecoverySnapshotPage(
            stage,
            context,
            stage.nextToken,
            response,
            limit: limit,
          );
          await sink.snapshot(context, page.items);
          check();
          stage = page.stage;
          state = stage.state;
          await checkpoint();
        } while (!stage.complete);
        candidate = stage.state;
      }
      for (var round = 0; round < 64; round++) {
        final cut = _record(await transport.cut(sessionId));
        check();
        if (cut['streamEpoch'] != context.streamEpoch) {
          throw const RecoveryProtocolError('STREAM_RESET');
        }
        final through = _id(cut['through']);
        if (recoveryDecimal(cut['floor']) > recoveryDecimal(candidate.cursor)) {
          throw const RecoveryProtocolError('CURSOR_EXPIRED');
        }
        if (recoveryDecimal(through) < recoveryDecimal(candidate.cursor)) {
          throw const RecoveryProtocolError('CURSOR_AHEAD');
        }
        while (recoveryDecimal(candidate.cursor) < recoveryDecimal(through)) {
          final response = await transport.mutations(
            sessionId,
            candidate.cursor,
            through,
            limit,
          );
          check();
          final plan = planRecoveryPage(
            candidate,
            context,
            candidate.cursor,
            through,
            response,
            limit: limit,
          );
          if (!plan.changed || plan.next.phase == RecoveryPhase.quarantined) {
            throw RecoveryProtocolError(plan.next.reason ?? 'PROTOCOL_ERROR');
          }
          await sink.mutations(context, plan);
          check();
          candidate = plan.next;
          state = candidate;
          await checkpoint();
        }
        if (candidate.rebuild.isNotEmpty) {
          throw const RecoveryProtocolError('REBUILD_REQUIRED');
        }
        try {
          await sink.commit(context, candidate);
        } catch (error) {
          if (valid()) state = storageBlockedRecovery(candidate);
          rethrow;
        }
        check();
        durableCursor = candidate.cursor;
        final reply = await transport.ready(sessionId, candidate.cursor, true);
        check();
        candidate = acceptRecoveryReady(
          candidate,
          context,
          through,
          true,
          reply,
        );
        state = candidate;
        if (candidate.phase == RecoveryPhase.quarantined) {
          throw RecoveryProtocolError(candidate.reason ?? 'PROTOCOL_ERROR');
        }
        if (candidate.phase == RecoveryPhase.onlineSafe) {
          sink.publish(context, candidate);
          outcome = candidate;
          break;
        }
      }
      if (outcome == null) throw const RecoveryProtocolError('RECOVERY_BUSY');
    } catch (error) {
      if (valid()) {
        final data = error is ApiException ? error.data : null;
        final remote = data is Map ? data['reason'] : null;
        reason = state?.phase == RecoveryPhase.storageBlocked
            ? 'PERSISTENCE_FAILED'
            : error is RecoveryProtocolError
            ? error.reason
            : remote is String && RegExp(r'^[A-Z_]{1,64}$').hasMatch(remote)
            ? remote
            : 'RECOVERY_UNAVAILABLE';
        if (state != null && state!.phase != RecoveryPhase.storageBlocked) {
          state = quarantineRecovery(state!, reason!);
        }
        sink.quarantine(reason!);
        outcome = state;
      }
    } finally {
      if (sessionId != null && isCurrent(owner)) {
        try {
          await transport.release(sessionId);
        } catch (_) {
          /* TTL releases the pin. */
        }
      }
    }
    return valid() ? outcome : null;
  }
}
