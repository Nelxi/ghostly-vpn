#!/bin/sh
# Stateless TCP flag validation, before conntrack. Never rate-limit or ban clients.
set -eu
chain=GHOSTLY_MSK_FLAGS
work=$(mktemp -d)
trap 'rm -f "$work/raw4" "$work/raw6" "$work/trust"; rmdir "$work"' EXIT

prepare() {
    firewall=$1
    output=$2
    {
        printf '*raw\n:%s - [0:0]\n' "$chain"
        printf -- '-F %s\n' "$chain"
        if ! "$firewall" -w 5 -t raw -C PREROUTING -p tcp --dport 443 -j "$chain" >/dev/null 2>&1; then
            printf -- '-I PREROUTING 1 -p tcp --dport 443 -j %s\n' "$chain"
        fi
        # These combinations cannot occur in a normal TCP handshake/data flow.
        printf -- '-A %s -p tcp --tcp-flags SYN,FIN SYN,FIN -j DROP\n' "$chain"
        printf -- '-A %s -p tcp --tcp-flags SYN,RST SYN,RST -j DROP\n' "$chain"
        printf -- '-A %s -p tcp --tcp-flags FIN,RST FIN,RST -j DROP\n' "$chain"
        printf -- '-A %s -p tcp --tcp-flags ALL NONE -j DROP\n' "$chain"
        printf 'COMMIT\n'
    } > "$output"
}

case "${1:-}" in
    check|start)
        prepare iptables "$work/raw4"
        prepare ip6tables "$work/raw6"
        # Explicit node trust precedes scanner lists. TLS/REALITY authentication stays intact.
        {
            printf '*filter\n:GHOSTLY_MSK_NODES - [0:0]\n-F GHOSTLY_MSK_NODES\n'
            for node in 78.17.1.122 147.45.63.107 168.113.215.30; do
                printf -- '-A GHOSTLY_MSK_NODES -s %s -d 45.10.219.94 -p tcp --dport 443 -j ACCEPT\n' "$node"
            done
            if iptables -w 5 -C INPUT -j GHOSTLY_MSK_NODES >/dev/null 2>&1; then
                printf -- '-D INPUT -j GHOSTLY_MSK_NODES\n'
            fi
            printf -- '-I INPUT 1 -j GHOSTLY_MSK_NODES\nCOMMIT\n'
        } > "$work/trust"
        iptables-restore --wait 5 --noflush --test < "$work/raw4"
        ip6tables-restore --wait 5 --noflush --test < "$work/raw6"
        iptables-restore --wait 5 --noflush --test < "$work/trust"
        if [ "$1" = start ]; then
            iptables-restore --wait 5 --noflush < "$work/raw4"
            ip6tables-restore --wait 5 --noflush < "$work/raw6"
            iptables-restore --wait 5 --noflush < "$work/trust"
        fi
        ;;
    stop)
        for firewall in iptables ip6tables; do
            if "$firewall" -w 5 -t raw -C PREROUTING -p tcp --dport 443 -j "$chain" >/dev/null 2>&1; then
                "$firewall" -w 5 -t raw -D PREROUTING -p tcp --dport 443 -j "$chain"
                "$firewall" -w 5 -t raw -F "$chain"
                "$firewall" -w 5 -t raw -X "$chain"
            fi
        done
        if iptables -w 5 -C INPUT -j GHOSTLY_MSK_NODES >/dev/null 2>&1; then
            iptables -w 5 -D INPUT -j GHOSTLY_MSK_NODES
            iptables -w 5 -F GHOSTLY_MSK_NODES
            iptables -w 5 -X GHOSTLY_MSK_NODES
        fi
        ;;
    *) echo 'usage: apply.sh {check|start|stop}' >&2; exit 2 ;;
esac
