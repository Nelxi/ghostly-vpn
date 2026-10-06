#!/usr/bin/env python3
"""Ghostly trial gate (server-side reference, default-deny).

Drop this next to the REAL trial issuance code. It does NOT issue trials
itself: it decides whether the existing issue_trial() may run.

Rule: no valid attestation key -> NO trial. Always answer neutrally;
the real reason goes to the server log only.

Wire-up (your backend language may differ, logic must not):
  1. POST /trial/challenge -> mint 32 random bytes, store row in
     trial_challenge (id, challenge, expires_at = now()+300s), return
     {id, challenge_b64, expiresIn}.
  2. POST /trial/claim {challengeId, chainPem[], packageName, appVersion,
     hwidHint?} -> trial_gate(...) below.
  3. Subscription fetch with header x-trial-challenge-id -> the trial
     backend binds the fetch to a consumed challenge; without a consumed
     valid claim the trial sub is never returned.

Checks inside trial_gate (order matters, all logged, one neutral reply):
  challenge known + fresh + atomically consumed (used_at IS NULL) ->
  app version >= 0.3.26/61 -> TG not claimed before -> IP under soft
  limits (/32 3/24h, /24 10/24h, v6 /64 as unit; over = neutral, no ban) ->
  decide() from attestation_verifier.py True ->
  package/signer/challenge/hw-level/boot all good -> issue trial.

Requires: attestation_verifier.py in the same dir, cryptography>=42.
Config (env/file, NOT code): GOOGLE_ROOTS_PEM, REVOCATION_SERIALS cache,
RELEASE_CERT_SHA256 (apksigner verify --print-certs of release APK).
"""
from __future__ import annotations

import base64
import os
import secrets
from dataclasses import dataclass

from attestation_verifier import NEUTRAL_ERROR, decide

MIN_VERSION_CODE = 61


@dataclass
class GateResult:
    ok: bool
    internal_reason: str
    neutral: dict


def neutral() -> dict:
    return {"ok": False, "error": NEUTRAL_ERROR}


def mint_challenge() -> tuple[str, str]:
    raw = secrets.token_bytes(32)
    cid = secrets.token_hex(16)
    return cid, base64.b64encode(raw).decode()


def version_ok(app_version: str) -> bool:
    parts = []
    for p in app_version.split("."):
        digits = "".join(c for c in p if c.isdigit())
        parts.append(int(digits) if digits else 0)
    while len(parts) < 3:
        parts.append(0)
    return tuple(parts) >= (0, 3, 26)


def trial_gate(*, challenge_row, consume_ok: bool, app_version: str,
               tg_claimed: bool, ip_over_limit: bool, chain_pem: list[str],
               expected_challenge: bytes, release_digests: list[bytes],
               roots_pem: list[str], revoked: list[str]) -> GateResult:
    """Pure decision helper; DB/IP/TG inputs are resolved by the caller."""
    if challenge_row is None:
        return GateResult(False, "unknown challenge", neutral())
    if not consume_ok:
        return GateResult(False, "challenge reuse/expired", neutral())
    if not version_ok(app_version):
        return GateResult(False, f"old client {app_version}", neutral())
    if tg_claimed:
        return GateResult(False, "tg already claimed", neutral())
    if ip_over_limit:
        return GateResult(False, "ip soft limit", neutral())
    ok, reason = decide(chain_pem, expected_challenge, release_digests,
                        roots_pem, revoked)
    if not ok:
        return GateResult(False, reason, neutral())
    return GateResult(True, "ok", {"ok": True})


if __name__ == "__main__":
    cid, ch = mint_challenge()
    print(f"challenge id={cid} b64={ch[:12]}...")
    print("RELEASE_CERT_SHA256=" + os.environ.get("RELEASE_CERT_SHA256", "<unset>"))
