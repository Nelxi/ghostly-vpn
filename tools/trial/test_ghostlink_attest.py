#!/usr/bin/env python3
"""Tests for ghostlink_attest: a synthetic attestation chain built the way KeyMint lays it out
(high tag numbers, EXPLICIT wrappers), checked against every refusal the policy has.

    python3 -m unittest test_ghostlink_attest -v
"""
import base64
import hashlib
import tempfile
import unittest
from datetime import datetime, timedelta

from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.x509.oid import NameOID

import ghostlink_attest as ga

RELEASE = hashlib.sha256(b"our release certificate").digest()
PACKAGE = "app.ghostly.vpn"


# ---- a tiny DER writer

def _len(n: int) -> bytes:
    if n < 0x80:
        return bytes([n])
    raw = n.to_bytes((n.bit_length() + 7) // 8, "big")
    return bytes([0x80 | len(raw)]) + raw


def tlv(tag: int, value: bytes) -> bytes:
    return bytes([tag]) + _len(len(value)) + value


def seq(*items: bytes) -> bytes:
    return tlv(0x30, b"".join(items))


def set_of(*items: bytes) -> bytes:
    return tlv(0x31, b"".join(items))


def integer(n: int, tag: int = 0x02) -> bytes:
    return tlv(tag, n.to_bytes(max(1, (n.bit_length() + 8) // 8), "big", signed=True))


def octets(b: bytes) -> bytes:
    return tlv(0x04, b)


def explicit(number: int, inner: bytes) -> bytes:
    """[number] EXPLICIT, context class, constructed, high-tag-number form."""
    out = [number & 0x7F]
    number >>= 7
    while number:
        out.append(0x80 | (number & 0x7F))
        number >>= 7
    return bytes([0xBF]) + bytes(reversed(out)) + _len(len(inner)) + inner


def key_description(challenge: bytes, package=PACKAGE, digest=RELEASE, version_code=62, level=1, km_level=1,
                    locked=True, boot=0, root_of_trust=True, rot_in_software=False, extra_package=None) -> bytes:
    packages = [seq(octets(package.encode()), integer(version_code))]
    if extra_package:
        packages.append(seq(octets(extra_package.encode()), integer(1)))
    app_id = seq(set_of(*packages), set_of(octets(digest)))
    rot = seq(octets(b"\x11" * 32), tlv(0x01, b"\xff" if locked else b"\x00"), integer(boot, 0x0A), octets(b"\x22" * 32))
    software = [explicit(701, integer(1700000000000)), explicit(709, octets(app_id))]
    hardware = [explicit(1, set_of(integer(2))), explicit(2, integer(3))]
    if root_of_trust:
        (software if rot_in_software else hardware).append(explicit(704, rot))
        software.sort()
        hardware.sort()
    return seq(
        integer(300), integer(level, 0x0A), integer(300), integer(km_level, 0x0A),
        octets(challenge), octets(b""), seq(*software), seq(*hardware),
    )


# ---- a certificate chain: root -> intermediate -> leaf (with the attestation extension)

def _name(cn: str) -> x509.Name:
    return x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, cn)])


def _cert(subject, issuer, pub, signer, serial, ca, ext=None, not_after=None):
    now = datetime.utcnow()
    b = (
        x509.CertificateBuilder().subject_name(_name(subject)).issuer_name(_name(issuer)).public_key(pub)
        .serial_number(serial).not_valid_before(now - timedelta(days=30))
        .not_valid_after(not_after or now + timedelta(days=3650))
        .add_extension(x509.BasicConstraints(ca=ca, path_length=None), critical=True)
    )
    if ext is not None:
        b = b.add_extension(x509.UnrecognizedExtension(x509.ObjectIdentifier(ga.ATTEST_OID), ext), critical=False)
    return b.sign(signer, hashes.SHA256())


class Pki:
    def __init__(self):
        self.root_key = ec.generate_private_key(ec.SECP384R1())
        self.mid_key = ec.generate_private_key(ec.SECP256R1())
        self.leaf_key = ec.generate_private_key(ec.SECP256R1())
        self.root = _cert("root", "root", self.root_key.public_key(), self.root_key, 0xA1, True)
        self.mid = _cert("mid", "root", self.mid_key.public_key(), self.root_key, 0xB2, True)

    def chain(self, ext: bytes, mid=None) -> list[bytes]:
        leaf = _cert("leaf", "mid", self.leaf_key.public_key(), self.mid_key, 0xC3, False, ext)
        return [c.public_bytes(serialization.Encoding.DER) for c in (leaf, mid or self.mid, self.root)]


PKI = Pki()
POLICY = ga.Policy(PACKAGE, [RELEASE], min_version_code=62)
NONCE = b"n" * 32


def decide(ext: bytes, revoked=(), roots=None, policy=POLICY, chain=None):
    return ga.decide(chain or PKI.chain(ext), NONCE, policy, roots or [PKI.root], revoked)


class DecideTest(unittest.TestCase):

    def refused(self, reason: str, *args, **kw):
        with self.assertRaises(ga.AttestError) as ctx:
            decide(*args, **kw)
        self.assertEqual(reason, ctx.exception.reason, ctx.exception)

    def test_a_genuine_app_on_a_locked_phone_passes(self):
        rec = decide(key_description(NONCE))
        self.assertEqual([(PACKAGE, 62)], rec.packages)
        self.assertTrue(rec.device_locked)
        self.assertEqual(ga.LEVEL_TEE, rec.security_level)
        decide(key_description(NONCE, level=2, km_level=2))  # StrongBox

    def test_a_repacked_apk_has_another_signer(self):
        self.refused("modified", key_description(NONCE, digest=hashlib.sha256(b"someone else").digest()))

    def test_another_package_is_refused(self):
        self.refused("modified", key_description(NONCE, package="app.ghostly.vpn.mod"))
        self.refused("modified", key_description(NONCE, extra_package="com.other"))

    def test_the_key_must_carry_our_challenge(self):
        self.refused("challenge", key_description(b"x" * 32))

    def test_software_keys_are_refused(self):
        self.refused("software_key", key_description(NONCE, level=0))
        self.refused("software_key", key_description(NONCE, km_level=0))

    def test_unlocked_or_unverified_phones_are_refused(self):
        self.refused("rooted", key_description(NONCE, locked=False))
        self.refused("rooted", key_description(NONCE, boot=2))
        self.refused("rooted", key_description(NONCE, root_of_trust=False))
        # A root of trust the software wrote about itself proves nothing.
        self.refused("rooted", key_description(NONCE, rot_in_software=True))

    def test_an_old_app_build_is_refused(self):
        self.refused("old_app", key_description(NONCE, version_code=61))

    def test_the_chain_must_end_in_a_pinned_root(self):
        self.refused("chain", key_description(NONCE), roots=[Pki().root])

    def test_a_forged_link_in_the_chain_is_refused(self):
        other = Pki()
        forged_mid = _cert("mid", "root", PKI.mid_key.public_key(), other.root_key, 0xB2, True)
        self.refused("chain", key_description(NONCE), chain=PKI.chain(key_description(NONCE), mid=forged_mid))

    def test_factory_intermediates_past_their_date_still_count(self):
        # Factory keyboxes: intermediates dated 2016…2026 are what real phones send today.
        old = _cert("mid", "root", PKI.mid_key.public_key(), PKI.root_key, 0xB2, True, not_after=datetime.utcnow() - timedelta(days=1))
        decide(key_description(NONCE), chain=PKI.chain(key_description(NONCE), mid=old))

    def test_revoked_serials_in_hex_or_decimal(self):
        self.refused("revoked", key_description(NONCE), revoked=["b2"])
        self.refused("revoked", key_description(NONCE), revoked=[str(0xC3)])
        decide(key_description(NONCE), revoked=["ffff"])

    def test_a_key_without_the_extension_is_refused(self):
        leaf = _cert("leaf", "mid", PKI.leaf_key.public_key(), PKI.mid_key, 0xC3, False)
        chain = [c.public_bytes(serialization.Encoding.DER) for c in (leaf, PKI.mid, PKI.root)]
        self.refused("no_attestation", b"", chain=chain)

    def test_garbage_is_refused_not_crashed_on(self):
        self.refused("chain", b"", chain=[b"\x30\x03\x02\x01\x01", b"junk"])
        self.refused("parse", b"\x30\x03\x02\x01\x01")
        self.refused("parse", key_description(NONCE)[:-7])


class ServiceTest(unittest.TestCase):

    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        self.svc = ga.Attestation(self.dir.name + "/attest.db", self.dir.name, POLICY)
        self.svc._roots, self.svc._revoked, self.svc._loaded_at = [PKI.root], set(), 1e18

    def tearDown(self):
        self.dir.cleanup()

    def chain_for(self, cid_nonce: str, **kw) -> list[str]:
        ext = key_description(base64.b64decode(cid_nonce), **kw)
        return [base64.b64encode(c).decode() for c in PKI.chain(ext)]

    def test_challenge_then_verdict(self):
        self.assertIsNone(self.svc.verdict("sub_1", "hw"))
        cid, nonce = self.svc.challenge("sub_1", "hw")
        self.assertEqual((True, "ok"), self.svc.submit("sub_1", "hw", cid, self.chain_for(nonce), {}))
        self.assertEqual((True, "ok"), self.svc.verdict("sub_1", "hw")[:2])
        self.assertIsNone(self.svc.verdict("sub_2", "hw"))

    def test_a_challenge_works_once_and_only_for_its_device(self):
        cid, nonce = self.svc.challenge("sub_1", "hw")
        chain = self.chain_for(nonce)
        self.assertEqual((False, "challenge"), self.svc.submit("sub_1", "other", cid, chain, {}))
        self.assertEqual((False, "challenge"), self.svc.submit("sub_1", "hw", cid, chain, {}))  # burnt above
        self.assertIsNone(self.svc.verdict("sub_1", "hw"))  # a challenge mishap is not a verdict

    def test_refusals_are_remembered(self):
        cid, nonce = self.svc.challenge("sub_1", "hw")
        self.assertEqual((False, "modified"), self.svc.submit("sub_1", "hw", cid, self.chain_for(nonce, digest=b"z" * 32), {}))
        self.assertEqual((False, "modified"), self.svc.verdict("sub_1", "hw")[:2])

    def test_a_genuine_app_reporting_root_is_refused(self):
        cid, nonce = self.svc.challenge("sub_1", "hw")
        self.assertEqual((False, "rooted"), self.svc.submit("sub_1", "hw", cid, self.chain_for(nonce), {"root": True, "rootSigns": "su"}))

    def test_bad_input(self):
        cid, _ = self.svc.challenge("sub_1", "hw")
        self.assertEqual((False, "chain"), self.svc.submit("sub_1", "hw", cid, ["@@@", "!!"], {}))
        self.assertEqual((False, "challenge"), self.svc.submit("sub_1", "hw", "nope", [], {}))

    def test_a_phone_that_cannot_attest_is_remembered_as_such(self):
        cid, _ = self.svc.challenge("sub_1", "hw")
        self.assertEqual((False, "no_attestation"), self.svc.submit("sub_1", "hw", cid, [], {"error": "KeyStoreException"}))
        self.assertEqual((False, "no_attestation"), self.svc.verdict("sub_1", "hw")[:2])


if __name__ == "__main__":
    unittest.main()
