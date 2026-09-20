import 'models.dart';

/// Atomic account snapshot. Implementations must never store credentials here.
abstract interface class ChatStore {
  Future<Json?> load(String owner);
  Future<void> save(String owner, Json snapshot);
}

/// The payload contains chat data and recovery proofs in one committed image.
/// A load is unverified input: the coordinator must quarantine it until READY.
class RecoveryStoredSnapshot {
  const RecoveryStoredSnapshot(this.revision, this.snapshot);
  final int revision;
  final Json snapshot;
}

abstract interface class RecoveryChatStore {
  Future<RecoveryStoredSnapshot?> loadRecovery(String owner);
  Future<int> commitRecovery(String owner, int expectedRevision, Json snapshot);
}

abstract interface class CredentialStore {
  Future<Json?> read();
  Future<void> write(Json credentials);
  Future<void> clear();
}

/// Ordinary, non-sensitive UI preferences. Credentials must never use this.
abstract interface class PreferenceStore {
  Future<String?> read(String key);
  Future<void> write(String key, String value);
}

String accountScope(Uri origin, int userId) => '${origin.origin}|$userId';

int advanceContiguousSequence(int previous, Iterable<int> candidates) {
  var cursor = previous > 0 && previous <= 9007199254740991 ? previous : 0;
  final ordered =
      candidates.where((v) => v > 0 && v <= 9007199254740991).toSet().toList()
        ..sort();
  for (final value in ordered) {
    if (value <= cursor) continue;
    if (value != cursor + 1) break;
    cursor = value;
  }
  return cursor;
}

/// attempt is zero-based here; TS receives attempt + 1. Entropy is injectable.
Duration reconnectDelay(int attempt, {double randomFraction = 0}) {
  final base = (1000 * (1 << attempt.clamp(0, 5))).clamp(1000, 30000);
  final jitter = (randomFraction * (base * 0.25).clamp(250, 7500)).floor();
  return Duration(milliseconds: base + jitter);
}
