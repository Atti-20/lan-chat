"""Versioned design schema, reference resolver and deterministic CSS/Dart outputs. Stdlib only."""
import argparse
import json
import math
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
KINDS = {'color', 'dimension', 'number', 'duration', 'cubicBezier', 'fontFamily', 'webOnly'}
MODES = ('light', 'dark')
NAME = re.compile(r'[a-z][a-z0-9]*(?:[.-][a-z0-9]+)*')
CSS_REF = re.compile(r'var\(\s*--([a-z0-9-]+)')


def require(condition, message):
    if not condition:
        raise ValueError(message)


def record(value, keys, path):
    require(isinstance(value, dict), f'{path}: expected object')
    require(set(value) == set(keys), f'{path}: expected fields {sorted(keys)}, got {sorted(value)}')


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        require(key not in result, f'Duplicate JSON key: {key}')
        result[key] = value
    return result


def load(path):
    return json.loads(path.read_text(encoding='utf-8'), object_pairs_hook=unique_object)


def finite(value):
    return type(value) in (int, float) and math.isfinite(value)


def color_argb(value):
    require(isinstance(value, str), 'Color must be a string')
    if re.fullmatch(r'#[a-fA-F0-9]{6}', value):
        return 'ff' + value[1:].lower()
    match = re.fullmatch(r'rgba\((\d+),\s*(\d+),\s*(\d+),\s*((?:\d*\.)?\d+)\)', value)
    require(match is not None, f'Unsupported color: {value}')
    rgb, alpha = [int(match[i]) for i in (1, 2, 3)], float(match[4])
    require(all(0 <= v <= 255 for v in rgb) and 0 <= alpha <= 1, 'Color outside supported range')
    return ''.join(f'{v:02x}' for v in [int(alpha * 255 + .5), *rgb])


def validate_value(kind, value, path):
    require(kind in KINDS, f'{path}: unsupported token type {kind}')
    if kind == 'color':
        color_argb(value)
    elif kind in {'dimension', 'duration'}:
        record(value, {'value', 'unit'}, path)
        units = {'px', 'rem'} if kind == 'dimension' else {'ms'}
        require(value['unit'] in units, f'{path}: unsupported {kind} unit')
        require(finite(value['value']) and value['value'] >= 0, f'{path}: expected finite nonnegative value')
        if kind == 'duration':
            require(float(value['value']).is_integer(), f'{path}: milliseconds must be an integer')
    elif kind == 'number':
        require(finite(value) and value >= 0, f'{path}: expected finite nonnegative number')
    elif kind == 'cubicBezier':
        require(isinstance(value, list) and len(value) == 4 and all(finite(v) for v in value), f'{path}: expected four finite curve coordinates')
        require(0 <= value[0] <= 1 and 0 <= value[2] <= 1, f'{path}: curve x coordinates must be in [0, 1]')
    elif kind == 'fontFamily':
        require(isinstance(value, list) and value and all(isinstance(v, str) and v.strip() for v in value), f'{path}: expected nonempty font stack')
        require(len(set(value)) == len(value), f'{path}: duplicate font family')
    else:
        require(isinstance(value, str) and value.strip() and not any(c in value for c in ';{}\n\r'), f'{path}: expected a single CSS value')


def css_value(kind, value):
    validate_value(kind, value, kind)
    if kind in {'color', 'webOnly'}:
        return value
    if kind in {'dimension', 'duration'}:
        return f"{number_text(value['value'])}{value['unit']}"
    if kind == 'number':
        return number_text(value)
    if kind == 'fontFamily':
        return ', '.join(v if re.fullmatch(r'[-\w]+', v) else json.dumps(v, ensure_ascii=False) for v in value)
    return 'cubic-bezier(' + ', '.join(number_text(v) for v in value) + ')'


def number_text(value):
    # Keep source precision; :g's default six digits can change CSS while Dart keeps more.
    return str(int(value)) if float(value).is_integer() else json.dumps(value, allow_nan=False)


def css_name(name):
    return 'mx-' + name.replace('.', '-')


def resolve_source(data):
    """Validate the closed v2 schema and resolve both modes without ambient UI state."""
    record(data, {'schemaVersion', 'baseFontSize', 'primitives', 'tokens', 'aliases', 'web'}, 'tokens.json')
    require(type(data['schemaVersion']) is int and data['schemaVersion'] == 2, 'Unsupported design schema version')
    require(finite(data['baseFontSize']) and data['baseFontSize'] > 0, 'baseFontSize must be positive and finite')
    for section in ('primitives', 'tokens', 'aliases'):
        require(isinstance(data[section], dict) and data[section], f'{section}: expected nonempty object')
        require(all(NAME.fullmatch(name) for name in data[section]), f'{section}: invalid name')
    for name, primitive in data['primitives'].items():
        record(primitive, {'type', 'value'}, 'primitives.' + name)
        validate_value(primitive['type'], primitive['value'], 'primitives.' + name)
    for name, token in data['tokens'].items():
        record(token, {'type', 'layer', 'light', 'dark'}, 'tokens.' + name)
        require(token['layer'] in {'semantic', 'component'}, f'{name}: unknown layer')
        require(token['type'] in KINDS, f'{name}: unknown type')
        require(name.startswith('component.') == (token['layer'] == 'component'), f'{name}: layer/name mismatch')
        for mode in MODES:
            record(token[mode], {'ref'}, f'{name}.{mode}')
            require(isinstance(token[mode]['ref'], str), f'{name}.{mode}: expected reference string')
    css_targets = {css_name(name): name for name in data['tokens']}
    require(len(css_targets) == len(data['tokens']), 'Token names collide after CSS normalization')
    for name, target in data['aliases'].items():
        require('.' not in name and not name.startswith('mx-'), f'{name}: invalid legacy alias')
        require(isinstance(target, str) and target in data['tokens'], f'{name}: alias target is missing')
    record(data['web'], {'root', 'dark'}, 'web')
    for mode, properties in data['web'].items():
        require(isinstance(properties, dict) and properties, f'web.{mode}: expected declarations')
        for name, value in properties.items():
            require(re.fullmatch(r'[a-z]+(?:-[a-z]+)*', name) and isinstance(value, str) and value, f'web.{mode}: invalid declaration')
    require(data['web']['root'].get('color-scheme') == 'light' and data['web']['dark'].get('color-scheme') == 'dark', 'Both color-scheme mappings are required')

    resolved = {mode: {} for mode in MODES}

    def resolve(name, mode, stack=()):
        require(name not in stack, f'{mode}: circular reference {" -> ".join((*stack, name))}')
        if name in resolved[mode]:
            return resolved[mode][name]
        token = data['tokens'][name]
        section, _, target = token[mode]['ref'].partition('.')
        require(section in {'primitives', 'tokens'} and target in data[section], f'{name}.{mode}: unknown reference {token[mode]["ref"]}')
        dependency = data[section][target]
        require(dependency['type'] == token['type'], f'{name}.{mode}: reference type mismatch')
        if section == 'tokens':
            require(token['layer'] == 'component' or dependency['layer'] == 'semantic', f'{name}: semantic cannot depend on component')
            value = resolve(target, mode, (*stack, name))
        else:
            value = dependency['value']
        resolved[mode][name] = value
        return value

    for mode in MODES:
        for name in sorted(data['tokens']):
            resolve(name, mode)
    for name, token in data['tokens'].items():
        if token['type'] not in {'color', 'webOnly'}:
            require(resolved['light'][name] == resolved['dark'][name], f'{name}: mode-specific {token["type"]} needs an explicit Dart adapter')

    def css_dependencies(value):
        result = []
        for name in CSS_REF.findall(value):
            target = css_targets.get(name, data['aliases'].get(name))
            require(target is not None, f'Unknown CSS reference: --{name}')
            result.append(target)
        return result

    def check_material(name, mode, stack=()):
        require(name not in stack, f'{mode}: circular CSS material reference {" -> ".join((*stack, name))}')
        if data['tokens'][name]['type'] == 'webOnly':
            for dependency in css_dependencies(resolved[mode][name]):
                check_material(dependency, mode, (*stack, name))

    for mode in MODES:
        for name in data['tokens']:
            check_material(name, mode)
    for properties in data['web'].values():
        for value in properties.values():
            css_dependencies(value)
    return resolved


def outputs(data=None, root=None):
    root = root or ROOT
    data = load(root / 'packages/design-tokens/tokens.json') if data is None else data
    resolved = resolve_source(data)
    tokens, aliases = data['tokens'], data['aliases']
    css = ['/* Generated by tooling/workspace.py generate from tokens.json. Do not edit. */']
    for mode, selector, web in [('light', ':root', 'root'), ('dark', '[data-theme="dark"]', 'dark')]:
        css += [selector + ' {']
        for name in sorted(tokens):
            reference = tokens[name][mode]['ref']
            value = ('var(--' + css_name(reference.removeprefix('tokens.')) + ')') if reference.startswith('tokens.') else css_value(tokens[name]['type'], resolved[mode][name])
            css += [f'  --{css_name(name)}: {value};']
        css += ['  /* Compatibility aliases: new UI consumes the mx semantic names. */']
        css += [f'  --{name}: var(--{css_name(target)});' for name, target in sorted(aliases.items())]
        css += [f'  {key}: {value};' for key, value in sorted(data['web'][web].items())]
        css += ['}', '']

    dart = ['// Generated by tooling/workspace.py generate from tokens.json. Do not edit.',
            "import 'package:flutter/painting.dart';", '',
            '// Canonical semantic/component keys plus compatibility keys for existing consumers.',
            '// Web-only gradients/shadows are intentionally not converted into native materials.']
    entries = {**{name: name for name in tokens}, **aliases}
    for mode, name in [('light', 'meshXLight'), ('dark', 'meshXDark')]:
        dart += [f'const {name} = <String, Color>{{']
        for key, target in sorted(entries.items()):
            if tokens[target]['type'] == 'color':
                dart += [f"  '{key}': Color(0x{color_argb(resolved[mode][target])}),"]
        dart += ['};', '']
    for kind, name, value_type in [('dimension', 'meshXSizes', 'double'), ('number', 'meshXNumbers', 'double'),
                                  ('duration', 'meshXDurations', 'Duration'), ('cubicBezier', 'meshXCurves', 'List<double>'),
                                  ('fontFamily', 'meshXFontFamilies', 'List<String>')]:
        dart += [f'const {name} = <String, {value_type}>{{']
        for key, target in sorted(entries.items()):
            if tokens[target]['type'] != kind:
                continue
            value = resolved['light'][target]
            if kind in {'dimension', 'duration'}:
                number = value['value'] * (data['baseFontSize'] if value['unit'] == 'rem' else 1)
                rendered = number_text(number) if kind == 'dimension' else f'Duration(milliseconds: {int(number)})'
            elif kind == 'number':
                rendered = number_text(value)
            else:
                rendered = json.dumps(value, ensure_ascii=False).replace('$', r'\$')
            dart += [f"  '{key}': {rendered},"]
        dart += ['};', '']
    return {'packages/design-tokens/tokens.css': '\n'.join(css),
            'apps/flutter-prototype/lib/ui/tokens.g.dart': '\n'.join(dart)}


def check(root=None):
    root = root or ROOT
    try:
        source = load(root / 'packages/design-tokens/tokens.json')
        resolve_source(source)
    except (ValueError, KeyError, TypeError) as error:
        return [str(error)]
    known = set(source['tokens']) | set(source['aliases'])
    components = load(root / 'packages/design-tokens/components.json')
    events = load(root / 'contracts/websocket/events.schema.json')['x-events']
    errors = []
    required = {'button', 'input', 'avatar', 'conversation', 'message', 'connection'}
    if components.get('schemaVersion') != 2 or not required.issubset(components['components']):
        errors.append('Design component catalog requires version 2 and all six foundation components')
    for name, component in components['components'].items():
        for token in component['tokens']:
            if token not in known:
                errors.append(f'Unknown design token in {name}: {token}')
        if not component['states'] or len(set(component['states'])) != len(component['states']):
            errors.append(f'Missing or duplicate design state: {name}')
        if 'spec' in component and not (root / component['spec']).is_file():
            errors.append(f'Missing component specification: {name}')
        if name in required:
            coverage = component.get('stateCoverage', {})
            if not component.get('spec') or set(coverage) != set(component['states']):
                errors.append(f'Missing component state coverage/specification: {name}')
            if any(status not in {'implemented', 'composed', 'not-implemented', 'not-applicable'} for status in coverage.values()):
                errors.append(f'Unknown component implementation status: {name}')
            if not {'hover', 'focus', 'pressed', 'selected', 'loading', 'error', 'disabled'}.issubset(coverage):
                errors.append(f'Missing foundation interaction states: {name}')
    for name, state in components['messageStates'].items():
        if state['tone'] not in known:
            errors.append(f'Unknown message tone: {name}')
        if 'event' in state and 'server' not in events.get(state['event'], {}):
            errors.append(f'Message state refers to unknown server event: {name}')
    if not set(components['messageStates']).issubset(components['components']['message']['states']):
        errors.append('Message state catalog does not match the message component')
    icon_source = (root / 'apps/web/src/components/base/UiIcon.vue').read_text(encoding='utf-8')
    icon_union = re.search(r'export type IconName\s*=([\s\S]*?)interface Props', icon_source)
    assets = set(re.findall(r"'([^']+)'", icon_union.group(1))) if icon_union else set()
    semantics = components.get('iconSemantics', {})
    if not {'send', 'add', 'search', 'settings', 'warning', 'success', 'file', 'image', 'user', 'group', 'broadcast'}.issubset(semantics):
        errors.append('Missing foundation icon semantics')
    for name, entry in semantics.items():
        if not entry.get('meaning') or 'asset' not in entry or (entry['asset'] is not None and entry['asset'] not in assets):
            errors.append(f'Unknown icon asset or missing semantic description: {name}')
    return errors


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, default=ROOT, help='Repository or isolated test fixture root')
    parser.add_argument('--check', action='store_true', help='Fail on invalid source or generated-file drift; never rewrite files')
    args = parser.parse_args()
    try:
        generated = outputs(root=args.root)
        errors = check(args.root)
        if args.check:
            errors += [f'Stale generated design file: {name}' for name, expected in generated.items()
                       if not (args.root/name).is_file() or (args.root/name).read_text(encoding='utf-8') != expected]
        require(not errors, '\n'.join(errors))
        if not args.check:
            for name, expected in generated.items():
                (args.root/name).write_text(expected, encoding='utf-8')
    except (ValueError, KeyError, TypeError) as error:
        parser.exit(1, f'Design token check failed: {error}\n')
    print('Design source, references, themes and CSS/Dart outputs are valid' + ('; no drift.' if args.check else '.'))


if __name__ == '__main__':
    main()
