"""Regression checks for importing publishing credentials into another workstation."""
import importlib.util
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import zipfile

spec = importlib.util.spec_from_file_location("publishing_config", Path(__file__).with_name("publishing-config.py"))
config = importlib.util.module_from_spec(spec)
spec.loader.exec_module(config)


class PublishingImportTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.bundle = self.root / "bundle.zip"
        self.values = dict(zip(config.KEYS, ("test-store", "test-alias", "test-key", "a" * 64)))
        with zipfile.ZipFile(self.bundle, "w") as package:
            package.writestr("private/local.properties", "\n".join(k + "=" + v for k, v in self.values.items()))
            package.writestr("private/keystore/jingyuniang-release.jks", b"test-keystore")
            package.writestr("../../unexpected.txt", b"must never extract")
        self.addCleanup(patch.stopall)
        patch.dict(os.environ, {}, clear=True).start()
        patch.object(config, "verify_keystore").start()

    def test_import_preserves_workstation_settings_and_is_repeatable(self):
        props = self.root / "local.properties"
        props.write_text("sdk.dir=D:/my-sdk\nai.secret=workstation-only\n", encoding="utf-8")
        config.import_bundle(self.bundle, self.root)
        config.import_bundle(self.bundle, self.root)
        values = config.read_properties(props)
        self.assertEqual("D:/my-sdk", values["sdk.dir"])
        self.assertEqual("workstation-only", values["ai.secret"])
        self.assertEqual(self.values["WHALE_UPDATE_TOKEN"], values["WHALE_UPDATE_TOKEN"])
        self.assertFalse(props.read_bytes().startswith(b"\xef\xbb\xbf"))
        self.assertFalse((self.root / "unexpected.txt").exists())

    def test_conflicting_token_leaves_existing_config_unchanged(self):
        props = self.root / "local.properties"
        original = "sdk.dir=mine\nWHALE_UPDATE_TOKEN=" + "b" * 64
        props.write_text(original, encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "WHALE_UPDATE_TOKEN differs"):
            config.import_bundle(self.bundle, self.root)
        self.assertEqual(original, props.read_text())
        self.assertFalse((self.root / "keystore").exists())

    def test_environment_conflict_cannot_silently_override_import(self):
        with patch.dict(os.environ, {"WHALE_UPDATE_TOKEN": "b" * 64}):
            with self.assertRaisesRegex(ValueError, "WHALE_UPDATE_TOKEN differs"):
                config.import_bundle(self.bundle, self.root)
        self.assertFalse((self.root / "local.properties").exists())

    def test_different_keystore_is_never_replaced(self):
        dest = self.root / "keystore/jingyuniang-release.jks"
        dest.parent.mkdir()
        dest.write_bytes(b"keep-existing-keystore")
        with self.assertRaisesRegex(ValueError, "keystore differs"):
            config.import_bundle(self.bundle, self.root)
        self.assertEqual(b"keep-existing-keystore", dest.read_bytes())

    def test_missing_or_bad_update_token_rejected(self):
        for value in ("", "new-token", "A" * 64):
            with self.subTest(token=value):
                with self.assertRaises(ValueError):
                    config.validate_values(dict(self.values, WHALE_UPDATE_TOKEN=value))


if __name__ == "__main__":
    unittest.main()
