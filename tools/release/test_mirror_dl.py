import importlib.util
from pathlib import Path
import sys
import unittest
from unittest.mock import Mock

# Publication runs on Linux; only the manifest policy is tested on Windows.
if sys.platform == "win32":
    sys.modules.setdefault("fcntl", Mock())
spec = importlib.util.spec_from_file_location("mirror_dl", Path(__file__).with_name("mirror-dl.py"))
mirror = importlib.util.module_from_spec(spec)
spec.loader.exec_module(mirror)


class PlatformVersionTests(unittest.TestCase):
    def test_unchanged_platform_keeps_its_original_download_version(self):
        self.assertEqual(mirror.asset_version({"version": "0.3.60"}, "0.3.62"), "0.3.60")

    def test_legacy_manifests_use_the_release_version(self):
        self.assertEqual(mirror.asset_version({}, "0.3.62"), "0.3.62")

    def test_future_and_invalid_asset_versions_are_rejected(self):
        for value in ("0.3.63", "../0.3.61", "0.3.61-beta"):
            with self.subTest(value=value), self.assertRaises(ValueError):
                mirror.asset_version({"version": value}, "0.3.62")


if __name__ == "__main__":
    unittest.main()
