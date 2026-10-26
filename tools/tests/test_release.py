import importlib.util
from pathlib import Path
import unittest

SPEC = importlib.util.spec_from_file_location("release", Path(__file__).parents[1] / "release.py")
release = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(release)


class VersionAllocationTests(unittest.TestCase):
    def test_first_release_uses_checked_in_version(self):
        self.assertEqual(release.choose_version(None, {"versionName": "0.0.6", "versionCode": "7"}, [], 0), ("0.0.6", 7))

    def test_next_patch_and_android_code_are_independent(self):
        self.assertEqual(release.choose_version(None, {"versionName": "0.0.6", "versionCode": "7"},
                                              [{"tag_name": "v0.0.6"}], 42), ("0.0.7", 43))

    def test_prepared_future_version_is_not_incremented_twice(self):
        self.assertEqual(release.choose_version(None, {"versionName": "0.1.0", "versionCode": "50"},
                                              [{"tag_name": "v0.0.6"}], 42), ("0.1.0", 50))

    def test_explicit_version_must_be_newer(self):
        for value in ["0.0.5", "0.0.6"]:
            with self.subTest(value=value), self.assertRaises(RuntimeError):
                release.choose_version(value, {"versionName": "0.0.6", "versionCode": "7"}, [{"tag_name": "v0.0.6"}], 7)

    def test_numeric_version_comparison(self):
        self.assertEqual(release.choose_version(None, {"versionName": "0.9.0", "versionCode": "7"},
                                              [{"tag_name": "v0.10.0"}, {"tag_name": "v0.9.0"}], 10), ("0.10.1", 11))

    def test_drafts_and_prereleases_do_not_advance_stable_name(self):
        self.assertEqual(release.choose_version(None, {"versionName": "0.0.6", "versionCode": "7"},
                                              [{"tag_name": "v9.0.0", "draft": True},
                                               {"tag_name": "v8.0.0", "prerelease": True}], 0), ("0.0.6", 7))

    def test_malformed_versions_are_rejected(self):
        for value in ["v1.2.3", "1.0", "1.0.0-beta", "1.2.3;echo x"]:
            with self.subTest(value=value), self.assertRaises(RuntimeError):
                release.version_tuple(value)

    def test_credentials_never_sent_to_download_host(self):
        with self.assertRaisesRegex(RuntimeError, "non-GitHub API host"):
            release.request("https://example.com/update.json", auth="test-only-placeholder")

    def test_signer_digest_old_and_new_build_tools(self):
        digest = "ab" * 32
        for prefix in ["Signer #1", "V2 Signer:", "V3.1 Signer:"]:
            with self.subTest(prefix=prefix):
                self.assertEqual(release.signing_fingerprints(f"{prefix} certificate SHA-256 digest: {digest}\n"), {digest})

    def test_public_key_digest_is_not_a_certificate_digest(self):
        self.assertEqual(release.signing_fingerprints("V2 Signer: public key SHA-256 digest: " + "ab" * 32), set())


if __name__ == "__main__":
    unittest.main()
