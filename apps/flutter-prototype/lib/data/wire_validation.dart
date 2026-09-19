// Shared, transport-free validation for generated REST/WS models. No app state.
import 'dart:convert';

typedef WireJson = Map<String, dynamic>;

void validateWire(
  Object? value,
  WireJson schema,
  Map<String, dynamic> definitions,
) {
  final problem = wireProblem(value, schema, definitions);
  if (problem != null) throw FormatException(problem);
}

String? wireProblem(
  Object? value,
  WireJson schema,
  Map<String, dynamic> definitions, [
  String path = r'$',
]) {
  final ref = schema[r'$ref'];
  if (ref != null) {
    final name = (ref as String).split('/').last;
    final target = definitions[name];
    if (target is! Map) throw StateError('Unknown generated schema: $ref');
    return wireProblem(
      value,
      Map<String, dynamic>.from(target),
      definitions,
      path,
    );
  }
  if (schema.containsKey('anyOf')) {
    final branches = schema['anyOf'] as List;
    if (!branches.any(
      (branch) =>
          wireProblem(
            value,
            Map<String, dynamic>.from(branch as Map),
            definitions,
            path,
          ) ==
          null,
    )) {
      return '$path does not match any schema alternative';
    }
  }
  if (schema.containsKey('allOf')) {
    for (final branch in schema['allOf'] as List) {
      final issue = wireProblem(
        value,
        Map<String, dynamic>.from(branch as Map),
        definitions,
        path,
      );
      if (issue != null) return issue;
    }
  }
  if (schema.containsKey('const') &&
      jsonEncode(value) != jsonEncode(schema['const'])) {
    return '$path has an unsupported constant';
  }
  if (schema.containsKey('enum') &&
      !(schema['enum'] as List).any(
        (item) => jsonEncode(item) == jsonEncode(value),
      )) {
    return '$path is outside the enum';
  }
  final type = schema['type'];
  final types = type is List ? type.cast<String>() : [if (type is String) type];
  if (value == null) {
    if (schema['nullable'] == true || types.contains('null') || types.isEmpty) {
      return null;
    }
    return '$path cannot be null';
  }
  if (types.isNotEmpty) {
    final valid = types.any(
      (kind) => switch (kind) {
        'object' => value is Map,
        'array' => value is List,
        'integer' => value is int,
        'number' => value is num && value.isFinite,
        'string' => value is String,
        'boolean' => value is bool,
        'null' => false,
        _ => throw StateError('Unsupported generated type: $kind'),
      },
    );
    if (!valid) return '$path has the wrong JSON type';
  }
  if (value is Map) {
    for (final name in (schema['required'] as List? ?? const [])) {
      if (!value.containsKey(name)) return '$path.$name is missing';
    }
    final fields = schema['properties'] as Map? ?? const {};
    for (final entry in value.entries) {
      final field = fields[entry.key];
      if (field is Map) {
        final issue = wireProblem(
          entry.value,
          Map<String, dynamic>.from(field),
          definitions,
          '$path.${entry.key}',
        );
        if (issue != null) return issue;
      } else if (schema['additionalProperties'] == false) {
        return '$path.${entry.key} is not allowed';
      } else if (schema['additionalProperties'] is Map) {
        final issue = wireProblem(
          entry.value,
          Map<String, dynamic>.from(schema['additionalProperties'] as Map),
          definitions,
          '$path.${entry.key}',
        );
        if (issue != null) return issue;
      }
    }
  }
  if (value is List && schema['items'] is Map) {
    for (var index = 0; index < value.length; index++) {
      final issue = wireProblem(
        value[index],
        Map<String, dynamic>.from(schema['items'] as Map),
        definitions,
        '$path[$index]',
      );
      if (issue != null) return issue;
    }
    if (schema['minItems'] is num && value.length < schema['minItems']) {
      return '$path is too short';
    }
    if (schema['maxItems'] is num && value.length > schema['maxItems']) {
      return '$path is too long';
    }
  }
  if (value is String) {
    if (schema['minLength'] is num &&
        value.runes.length < schema['minLength']) {
      return '$path is too short';
    }
    if (schema['maxLength'] is num &&
        value.runes.length > schema['maxLength']) {
      return '$path is too long';
    }
    if (schema['pattern'] is String &&
        !RegExp(schema['pattern'] as String).hasMatch(value)) {
      return '$path has an invalid format';
    }
  }
  if (value is num) {
    if (schema['minimum'] is num && value < schema['minimum']) {
      return '$path is below its minimum';
    }
    if (schema['maximum'] is num && value > schema['maximum']) {
      return '$path exceeds its maximum';
    }
  }
  return null;
}
