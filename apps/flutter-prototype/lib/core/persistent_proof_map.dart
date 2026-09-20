import 'dart:collection';

const _bucketCount = 1024;
int _bucket(String key) => key.hashCode & (_bucketCount - 1);

/// Immutable proof index with bounded copy-on-write buckets. Values must themselves
/// be immutable (recovery proofs and sequence strings are); no mutable bucket escapes.
class PersistentProofMap<V> extends MapBase<String, V> {
  PersistentProofMap._(this._buckets, this._length);
  factory PersistentProofMap.from(Map<String, V> source) {
    if (source is PersistentProofMap<V>) return source;
    final buckets = List.generate(_bucketCount, (_) => <String, V>{});
    for (final entry in source.entries) {
      buckets[_bucket(entry.key)][entry.key] = entry.value;
    }
    return PersistentProofMap._(List.unmodifiable(buckets), source.length);
  }
  final List<Map<String, V>> _buckets;
  final int _length;
  @override
  int get length => _length;
  @override
  V? operator [](Object? key) =>
      key is String ? _buckets[_bucket(key)][key] : null;
  @override
  bool containsKey(Object? key) =>
      key is String && _buckets[_bucket(key)].containsKey(key);
  @override
  Iterable<String> get keys sync* {
    for (final bucket in _buckets) {
      yield* bucket.keys;
    }
  }

  @override
  void operator []=(String key, V value) =>
      throw UnsupportedError('Immutable proof map');
  @override
  V? remove(Object? key) => throw UnsupportedError('Immutable proof map');
  @override
  void clear() => throw UnsupportedError('Immutable proof map');
}

/// One page owns its draft. Freezing transfers ownership and permanently disables
/// all mutation, so failed pages and later drafts cannot alter previous generations.
class ProofMapDraft<V> extends MapBase<String, V> {
  factory ProofMapDraft.from(Map<String, V> source) {
    final base = PersistentProofMap<V>.from(source);
    return ProofMapDraft._(List.of(base._buckets), base.length);
  }
  ProofMapDraft._(this._buckets, this._length);
  final List<Map<String, V>> _buckets;
  final Set<int> _copied = {};
  int _length;
  bool _closed = false;
  void _checkOpen() {
    if (_closed) throw StateError('Proof draft already frozen');
  }

  Map<String, V> _writeBucket(String key) {
    _checkOpen();
    final index = _bucket(key);
    if (_copied.add(index)) _buckets[index] = Map.of(_buckets[index]);
    return _buckets[index];
  }

  PersistentProofMap<V> freeze() {
    _checkOpen();
    _closed = true;
    return PersistentProofMap._(List.unmodifiable(_buckets), _length);
  }

  @override
  int get length => _length;
  @override
  V? operator [](Object? key) =>
      key is String ? _buckets[_bucket(key)][key] : null;
  @override
  bool containsKey(Object? key) =>
      key is String && _buckets[_bucket(key)].containsKey(key);
  @override
  Iterable<String> get keys sync* {
    for (final bucket in _buckets) {
      yield* bucket.keys;
    }
  }

  @override
  void operator []=(String key, V value) {
    final bucket = _writeBucket(key);
    if (!bucket.containsKey(key)) _length++;
    bucket[key] = value;
  }

  @override
  V? remove(Object? key) {
    _checkOpen();
    if (key is! String || !containsKey(key)) return null;
    _length--;
    return _writeBucket(key).remove(key);
  }

  @override
  void clear() {
    _checkOpen();
    for (var i = 0; i < _bucketCount; i++) {
      _buckets[i] = {};
      _copied.add(i);
    }
    _length = 0;
  }
}
