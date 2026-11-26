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
