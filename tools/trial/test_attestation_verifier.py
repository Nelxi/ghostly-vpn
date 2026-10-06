#!/usr/bin/env python3
"""Tests for tools/trial/attestation_verifier.py.

Builds self-signed chains in-memory (cryptography lib), embeds a synthetic
attestation extension, and checks: valid / wrong signer digest / wrong
challenge / software level / rooted boot state / revoked serial /
wrong package / bad root.

Run: python3 -m pytest tools/trial/test_attestation_verifier.py -q
(or: python3 tools/trial/test_attestation_verifier.py)
Requires: cryptography>=42.
"""
from __future__ import annotations

import datetime
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.x509.oid import NameOID, ObjectIdentifier

import attestation_verifier as v

PKG = b"app.ghostly.vpn"
CHALLENGE = bytes(range(32))
RELEASE_DIGEST = bytes(0xAB for _ in range(32))
OTHER_DIGEST = bytes(0xCD for _ in range(32))


def tlv(tag: int, body: bytes) -> bytes:
    if len(body) < 128:
        return bytes([tag, len(body)]) + body
    lb = len(body).to_bytes((len(body).bit_length() + 7) // 8, "big")
    return bytes([tag, 0x80 | len(lb)]) + lb + body


def uint(n: int) -> bytes:
    b = n.to_bytes(max(1, (n.bit_length() + 7) // 8), "big", signed=False)
    if b[0] & 0x80:
        b = b"\x00" + b
    return tlv(0x02, b)


def app_id_ext(package: bytes = PKG, digest: bytes = RELEASE_DIGEST,
               version: int = 61) -> bytes:
    pkg_info = tlv(0x30, tlv(0x04, package) + uint(version))
    return tlv(0x30, tlv(0x31, pkg_info) + tlv(0x31, tlv(0x04, digest)))


def root_of_trust(state: int = 0, locked: bool = True) -> bytes:
    return tlv(0x30, tlv(0x04, bytes(32)) + tlv(0x01, b"\xff" if locked else b"\x00")
               + bytes([0x0A, 0x01, state]))


def att_record(challenge: bytes = CHALLENGE, att_level: int = 1,
               km_level: int = 1, package: bytes = PKG,
               digest: bytes = RELEASE_DIGEST,
               state: int = 0, locked: bool = True) -> bytes:
    body = (uint(4) + uint(att_level) + uint(41) + uint(km_level)
            + tlv(0x04, challenge)
            + tlv(0xA1, app_id_ext(package, digest) + root_of_trust(state, locked)))
    return tlv(0x30, body)


def make_chain(att: bytes, serial: int = 0x1234):
    now = datetime.datetime.now(datetime.timezone.utc)
    root_key = ec.generate_private_key(ec.SECP256R1())
    leaf_key = ec.generate_private_key(ec.SECP256R1())
    name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "test-root")])
    root = (x509.CertificateBuilder().subject_name(name).issuer_name(name)
            .public_key(root_key.public_key()).serial_number(0xBEEF)
            .not_valid_before(now - datetime.timedelta(days=1))
            .not_valid_after(now + datetime.timedelta(days=365))
            .add_extension(x509.BasicConstraints(ca=True, path_length=1), True)
            .sign(root_key, hashes.SHA256()))
    leaf = (x509.CertificateBuilder()
            .subject_name(x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "test-leaf")]))
            .issuer_name(name).public_key(leaf_key.public_key())
            .serial_number(serial)
            .not_valid_before(now - datetime.timedelta(days=1))
            .not_valid_after(now + datetime.timedelta(days=30))
            .add_extension(
                x509.UnrecognizedExtension(ObjectIdentifier(v.ATTEST_OID), att), False)
            .sign(root_key, hashes.SHA256()))
    lp = leaf.public_bytes(serialization.Encoding.PEM).decode()
    rp = root.public_bytes(serialization.Encoding.PEM).decode()
    return [lp, rp], [rp]


def decide_with(att: bytes, **kw):
    chain, roots = make_chain(att, serial=kw.pop("serial", 0x1234))
    return v.decide(chain, kw.pop("challenge", CHALLENGE), [RELEASE_DIGEST],
                    roots, kw.pop("revoked", []))


def t_valid():
    ok, reason = decide_with(att_record())
    assert ok, reason


def t_wrong_digest():
    ok, reason = decide_with(att_record(digest=OTHER_DIGEST))
    assert not ok and "signer" in reason, reason


def t_wrong_challenge():
    ok, reason = decide_with(att_record(), challenge=bytes(32))
    assert not ok and "challenge" in reason, reason


def t_software_level():
    ok, reason = decide_with(att_record(att_level=0, km_level=0))
    assert not ok and "software" in reason, reason


def t_software_one_side():
    ok, reason = decide_with(att_record(att_level=1, km_level=0))
    assert not ok, reason


def t_rooted():
    ok, reason = decide_with(att_record(state=2, locked=False))
    assert not ok and "root" in reason, reason


def t_unlocked():
    ok, reason = decide_with(att_record(state=0, locked=False))
    assert not ok, reason


def t_revoked():
    ok, reason = decide_with(att_record(), serial=0x1234, revoked=["1234"])
    assert not ok and "revoked" in reason, reason


def t_wrong_package():
    ok, reason = decide_with(att_record(package=b"com.evil.fork"))
    assert not ok and "package" in reason, reason


def t_bad_root():
    chain, _ = make_chain(att_record())
    ok, reason = v.decide(chain, CHALLENGE, [RELEASE_DIGEST], [], [])
    assert not ok and "root" in reason, reason


TESTS = [t_valid, t_wrong_digest, t_wrong_challenge, t_software_level,
         t_software_one_side, t_rooted, t_unlocked, t_revoked,
         t_wrong_package, t_bad_root]

if __name__ == "__main__":
    failed = 0
    for t in TESTS:
        try:
            t()
            print(f"PASS {t.__name__}")
        except AssertionError as e:
            failed += 1
            print(f"FAIL {t.__name__}: {e}")
    sys.exit(1 if failed else 0)
