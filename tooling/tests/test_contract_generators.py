import json
from pathlib import Path
import sys
import unittest
from unittest.mock import patch
sys.path.insert(0, str(Path(__file__).parents[1]))
import design_tokens
import protocol_types
import workspace

class ContractGeneratorTests(unittest.TestCase):
    def test_alpha_rounding_and_dark_inheritance(self):
        self.assertEqual(design_tokens.color_argb('rgba(0, 111, 232, 0.55)'), '8c006fe8')
        self.assertEqual(design_tokens.color_argb('#FFFFFF'), 'ffffffff')
        with self.assertRaises(ValueError):
            design_tokens.color_argb('rgba(500, 0, 0, 1)')

    def test_font_and_dimension_conversion(self):
        self.assertEqual(design_tokens.css_value('fontFamily', ['SF Pro Text', 'sans-serif']), '"SF Pro Text", sans-serif')
        self.assertEqual(design_tokens.css_value('dimension', {'value': .875, 'unit':'rem'}), '.875rem'.replace('.875', '0.875'))
        self.assertIn("'font-body': 14,", design_tokens.outputs()['apps/flutter-prototype/lib/ui/tokens.g.dart'])
        with self.assertRaises(ValueError):
            design_tokens.css_value('unsupported', {})

    def test_nullable_wire_ids_and_nested_sync_types(self):
        self.assertEqual(protocol_types.dart_type({'type':['integer','null']}), 'int?')
        self.assertEqual(protocol_types.ts_type({'type':['string','null']}), 'string | null')
        self.assertEqual(protocol_types.dart_type({'type':'array','items':{'$ref':'#/$defs/MessagePayload'}}), 'List<MessagePayload>')
        with self.assertRaises(ValueError):
            protocol_types.dart_type({'type':['string','integer']})

    def test_event_inventory_and_payload_drift_are_detected(self):
        source = (protocol_types.ROOT / 'services/server/src/main/java/com/lanchat/websocket/ChatWebSocketHandler.java').read_text()
        self.assertEqual(protocol_types.check(source=source), [])
        self.assertTrue(any('client event drift' in e for e in protocol_types.check(source=source.replace('case "CHAT_SEND"', 'case "NEW_SEND"'))))
        self.assertTrue(any('ChatAckPayload' in e for e in protocol_types.check(source=source.replace('ackPayload.put("sequence"', 'ackPayload.put("newSequence"'))))

    def test_dynamic_directions_and_schema_branches_cannot_drift(self):
        schema = json.loads((protocol_types.ROOT / 'contracts/websocket/events.schema.json').read_text())
        for event in ['TYPING_START', 'TYPING_STOP', 'CONVERSATION_CHANGED', 'CONVERSATION_REMOVED']:
            changed = json.loads(json.dumps(schema))
            del changed['x-events'][event]['server']
            self.assertTrue(any('server event drift' in e for e in protocol_types.check(schema=changed)))
        schema['anyOf'].pop()
        self.assertTrue(any('branches differ' in e for e in protocol_types.check(schema=schema)))

    def test_recovery_dispatcher_is_part_of_the_server_event_inventory(self):
        path = 'services/server/src/main/java/com/lanchat/recovery/MutationHintDispatcher.java'
        source = (protocol_types.ROOT / path).read_text()
        self.assertEqual(protocol_types.check(additional_sources={path: source}), [])
        changed = source.replace('setEvent("MUTATION_AVAILABLE")', 'setEvent("UNDECLARED_HINT")')
        self.assertTrue(any('server event drift' in e for e in protocol_types.check(additional_sources={path: changed})))
        dynamic = source.replace('setEvent("MUTATION_AVAILABLE")', 'setEvent(eventName)')
        self.assertTrue(any('dynamic external event dispatch' in e for e in protocol_types.check(additional_sources={path: dynamic})))

    def test_component_catalog_uses_known_tokens_and_protocol_states(self):
        self.assertEqual(design_tokens.check(), [])

    def test_manual_edits_to_generated_java_and_dart_rest_are_rejected(self):
        read = workspace.read
        for artifact in ['services/server/src/main/java/com/lanchat/dto/WebSocketEnvelope.java',
                         'apps/flutter-prototype/lib/data/rest_contract.g.dart',
                         'packages/protocol/src/rest-contract.ts']:
            with self.subTest(artifact=artifact), patch.object(workspace, 'read',
                    side_effect=lambda path: read(path) + ('\n// drift\n' if path == artifact else '')):
                self.assertTrue(any(artifact in problem for problem in workspace.check_contracts()))

if __name__ == '__main__':
    unittest.main()
