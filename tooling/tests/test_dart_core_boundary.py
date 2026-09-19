import importlib.util
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('dart_boundary', Path(__file__).parents[2] /
    'apps/flutter-prototype/tool/check_core.py')
boundary = importlib.util.module_from_spec(spec)
spec.loader.exec_module(boundary)


class DartBoundaryTests(unittest.TestCase):
    def test_empty_core_fails(self):
        with tempfile.TemporaryDirectory() as folder:
            with self.assertRaises(ValueError):
                boundary.check(Path(folder))

    def test_nested_and_conditional_escape_fails(self):
        for directive in ["import 'package:flutter/widgets.dart';",
                          "export '../theme.dart';", "import 'dart:io';",
                          "import '../model.dart' if (dart.library.ui) 'package:flutter/widgets.dart';"]:
            with self.subTest(directive=directive), tempfile.TemporaryDirectory() as folder:
                root = Path(folder)
                (root / 'nested').mkdir()
                (root / 'model.dart').write_text("import 'dart:collection';")
                (root / 'nested/bad.dart').write_text(directive)
                with self.assertRaises(ValueError):
                    boundary.check(root)

    def test_pure_relative_core_passes(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            (root / 'model.dart').write_text("import 'dart:collection';")
            (root / 'store.dart').write_text("import 'model.dart';")
            self.assertEqual(boundary.check(root), 2)
