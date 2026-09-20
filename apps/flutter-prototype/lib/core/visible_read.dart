class ReadRow {
  const ReadRow(this.sequence, this.own);
  final int sequence;
  final bool own;
}

/// UI samples establish eligibility; transport events never call this core.
class VisibleReadTracker {
  VisibleReadTracker(
    this.scope,
    int baseline,
    List<ReadRow> rows,
    this.completeThrough,
  ) : _position = baseline,
      _rows = List.of(rows)..sort((a, b) => a.sequence.compareTo(b.sequence)) {
    if (baseline < 0 ||
        baseline > 9007199254740991 ||
        completeThrough < 0 ||
        completeThrough > 9007199254740991) {
      throw const FormatException('INVALID_READ_PROOF');
    }
    final seen = <int>{};
    for (final row in _rows) {
      if (row.sequence <= 0 ||
          row.sequence > 9007199254740991 ||
          !seen.add(row.sequence)) {
        throw const FormatException('INVALID_READ_PROOF');
      }
    }
  }
  final String scope;
  final int completeThrough;
  final List<ReadRow> _rows;
  int _position;
  final _since = <int, int>{};
  final _qualified = <int>{};
  int? _sampledAt;
  void cancel() {
    _since.clear();
    _sampledAt = null;
  }

  int sample(String source, int now, Set<int> visible, bool foreground) {
    if (source != scope || !foreground) {
      cancel();
      return _position;
    }
    if (_sampledAt != null && (now < _sampledAt! || now - _sampledAt! > 200)) {
      _since.clear();
    }
    _sampledAt = now;
    _since.removeWhere((sequence, _) => !visible.contains(sequence));
    for (final row in _rows) {
      if (visible.contains(row.sequence)) {
        final start = _since.putIfAbsent(row.sequence, () => now);
        if (now - start >= 300) _qualified.add(row.sequence);
      }
    }
    for (final row in _rows) {
      if (row.sequence <= _position) continue;
      if (row.sequence > _position + 1 && row.sequence - 1 > completeThrough) {
        break;
      }
      if (!row.own && !_qualified.contains(row.sequence)) break;
      _position = row.sequence;
    }
    return _position;
  }
}
