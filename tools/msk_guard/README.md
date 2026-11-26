# MSK packet guard

Drops impossible TCP flag combinations on destination port 443 before conntrack,
for IPv4 and IPv6. No client rate limits or automatic source bans. Three owned
nodes have explicit IPv4 TCP/443 access ahead of scanner lists. Existing CDN,
SSH, UDP relay and established-connection rules are preserved.

This does not stop valid SYN floods or traffic that saturates the provider link.
SYN cookies and the existing backlog settings remain responsible for SYN queue
protection. Upstream mitigation must be handled by the hosting provider. Zero
false positives against every possible client/network cannot be guaranteed.

Install `apply.sh` in `/etc/ghostly/msk-guard/` and the unit in
`/etc/systemd/system/`. Run `sh apply.sh check` before enabling the unit.
`systemctl disable --now ghostly-msk-guard` removes only these rules.
After manually rebuilding VDS_IN or restarting the scanner filter, reload this
unit to put node trust first again. `iptables -t raw -nvL GHOSTLY_MSK_FLAGS`
shows drop counters. No nginx/Xray restart is needed.

## Scheduled cascade restoration

`restore_cascade.py` atomically restores the owned FI/DE/NL TCP and Hysteria
subscription endpoints to MSK. It preserves authentication, TLS pins, other
subscription settings and LTE XHTTP. It makes a timestamped config backup,
preserves ownership/mode, checks for concurrent edits, and is idempotent.
`--check` validates the current config without switching its endpoints.

Install as `/usr/local/sbin/ghostly-msk-restore.py` (mode 0700), install the
service unit, then create `ghostly-msk-restore.timer` with a single absolute
`OnCalendar=YYYY-MM-DD HH:MM:SS UTC`, `AccuracySec=1s`, `Persistent=true`, and
`Unit=ghostly-msk-restore.service`. Enable only the timer. Check its exact next
trigger with `systemctl show ghostly-msk-restore.timer -p NextElapseUSecRealtime`.
Cancel with `systemctl disable --now ghostly-msk-restore.timer` before it fires.
No API or VPN restart is needed: the subscription API reloads config on request.

Scheduling is unconditional at the requested time; there is no health gate.
Persistent timers execute after boot if the server was offline at the due time.
The service retries failures up to the systemd start limit (five starts within
ten minutes), rather than asking for interactive approval.
