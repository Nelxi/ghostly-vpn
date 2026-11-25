import pathlib
import tempfile
import unittest
from unittest.mock import Mock

from ghostlink_link_crypto import happ_link, incy_link, LinkEncryptionError
from incy_link_encoder import decrypt_link


class LinkCryptoTest(unittest.TestCase):
    def test_happ_cache_and_plaintext_response_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            cache = pathlib.Path(directory) / "links.db"
            post = Mock()
            post.return_value.json.return_value = {"encrypted_link": "happ://crypt5/" + "A" * 80}
            url = "https://example.com/sub/test"
            value = happ_link(url, cache=cache, post=post, now=100000)
            self.assertEqual(value, happ_link(url, cache=cache, post=post, now=100001))
            self.assertEqual(post.call_count, 1)
            post.return_value.json.return_value = {"encrypted_link": "happ://add/" + url}
            with self.assertRaises(LinkEncryptionError):
                happ_link(url, cache=cache, post=post, now=200000)

    def test_incy_round_trip_and_randomized_encryption(self):
        url = "https://example.com/sub/test"
        first, second = incy_link(url), incy_link(url)
        self.assertNotEqual(first, second)
        self.assertEqual(url, decrypt_link(first).url)
        self.assertEqual("Ghostly VPN", decrypt_link(first).name)

    def test_rejects_unsafe_url(self):
        for url in ["http://example.com/sub/x", "https://a:b@example.com/x", "file:///secret"]:
            with self.assertRaises(ValueError):
                incy_link(url)


if __name__ == "__main__":
    unittest.main()
