import unittest
from pathlib import Path
from tempfile import TemporaryDirectory

from check_candidate import CandidateError, clean_generated, safe_build_arguments


class CandidateArgumentsTest(unittest.TestCase):
    def test_accepts_exact_release_build_without_injected_inputs(self):
        safe_build_arguments(
            ["flutter", "build", "apk", "--release", "--no-pub"],
            ["flutter", "build", "apk", "--release", "--no-pub"],
        )

    def test_rejects_debug_fixture_or_dart_define_changes(self):
        expected = ["flutter", "build", "apk", "--release", "--no-pub"]
        for arguments in (
            ["flutter", "build", "apk", "--debug", "--no-pub"],
            [*expected, "--dart-define=MESHX_NODE=http://fixture"],
            ["flutter", "build", "apk", "--release", "integration_test/app.dart"],
        ):
            with self.subTest(arguments=arguments):
                with self.assertRaises(CandidateError):
                    safe_build_arguments(arguments, expected)

    def test_generated_cleanup_requires_all_identity_markers(self):
        with TemporaryDirectory() as directory:
            target = Path(directory) / "GeneratedPluginRegistrant.java"
            target.write_text("public final class GeneratedPluginRegistrant {}")
            with self.assertRaises(CandidateError):
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
