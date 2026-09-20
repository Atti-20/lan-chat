import 'dart:collection';
import 'models.dart';

/// Encoders consume these read-only views synchronously. The snapshot owns the
/// source collections; serializers create one temporary JSON row at a time.
class JsonListView<T> extends ListBase<Json> {
  JsonListView(this._source, this._encode);
  final List<T> _source;
  final Json Function(T) _encode;
  @override
  int get length => _source.length;
  @override
  set length(int _) => throw UnsupportedError('Read-only JSON snapshot');
  @override
  Json operator [](int index) => _encode(_source[index]);
  @override
  void operator []=(int index, Json value) =>
      throw UnsupportedError('Read-only JSON snapshot');
}

class JsonMapView<T extends Object> extends MapBase<String, Json> {
  JsonMapView(this._source, this._encode);
  final Map<String, T> _source;
  final Json Function(T) _encode;
  @override
  int get length => _source.length;
  @override
  Iterable<String> get keys => _source.keys;
  @override
  bool containsKey(Object? key) => _source.containsKey(key);
  @override
  Json? operator [](Object? key) {
    final value = _source[key];
    return value == null ? null : _encode(value);
  }

  @override
  void operator []=(String key, Json value) =>
      throw UnsupportedError('Read-only JSON snapshot');
  @override
  Json? remove(Object? key) =>
      throw UnsupportedError('Read-only JSON snapshot');
  @override
  void clear() => throw UnsupportedError('Read-only JSON snapshot');
}
