# Trial check for the Ghostly Android app (server side, reference copy)

The backend is a closed repo; this folder keeps a copy of the one module the app's behaviour
depends on, with its tests.

A trial subscription is given to any client that sends a device id (HWID). On top of that, a client
that presents itself as **Ghostly for Android** has to prove it is our untouched build on a phone
that is not rooted — otherwise a re-packed APK could say anything about the device.
Paid subscriptions and other clients are never asked.

## Flow

1. The app fetches `/sub/<id>` as usual. For a trial the server answers with a stub subscription
   and the header `ghostly-attest: v1;<challenge id>;<base64 challenge>`.
2. The app makes a key in the Android Keystore bound to that challenge
   (`AndroidTrialAttestation`) and posts the certificate chain to `/sub/<id>/attest`
   (`Attest.Answer`: chain, root signs, app version).
3. The server (`ghostlink_attest.py`) checks: the chain ends in a Google attestation root and none
   of its certificates is revoked; the key carries the challenge issued for this subscription and
   device; the package is `app.ghostly.vpn` signed with the release certificate; the key is
   hardware-backed; the bootloader is locked and the system verified; the genuine app reports no
   root. The verdict is stored per (subscription, device).
4. The app fetches the subscription again (with a one-off `attested=<id>` query, so a caching mirror
   cannot hand back the stub) and gets either the servers or a stub that names the reason.

## Files

| File | What |
|---|---|
| `ghostlink_attest.py` | DER parser for the attestation record, chain and policy checks, challenges, verdicts, cached Google lists. |
| `test_ghostlink_attest.py` | `python3 -m unittest test_ghostlink_attest` — a synthetic chain laid out like KeyMint's, every refusal. |

Server config (`trial_attest`): `enabled`, `package`, `cert_sha256` (of the release signing
certificate), `min_version`, `min_version_code`, `from_ts` (trials started earlier are left alone),
`retry_after_sec`.

Certificate validity dates are deliberately not checked: phones with a factory keybox send
intermediates dated 2016–2026, and Google's answer to a leaked key is the revocation list.
