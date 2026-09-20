import unittest
from pathlib import Path
from tempfile import TemporaryDirectory

from clean_generated import GeneratedSourceError, clean_generated


class GeneratedCleanupTest(unittest.TestCase):
    def test_generated_cleanup_requires_all_identity_markers(self):
        with TemporaryDirectory() as directory:
            target = Path(directory) / "GeneratedPluginRegistrant.java"
            target.write_text("public final class GeneratedPluginRegistrant {}")
            with self.assertRaises(GeneratedSourceError):
                clean_generated(target)
            self.assertTrue(target.exists())
            target.write_text(
                "package io.flutter.plugins;\n"
                "/** Generated file. Do not edit. */\n"
                "public final class GeneratedPluginRegistrant {}\n"
            )
            self.assertTrue(clean_generated(target)["removed"])
            self.assertFalse(target.exists())


if __name__ == "__main__":
    unittest.main()
