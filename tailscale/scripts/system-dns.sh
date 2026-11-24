#!/system/bin/sh
# shellcheck disable=SC3043
# Opt-in system-wide DNS. Android's resolver does not read /etc/resolv.conf;
# instead, redirect port-53 traffic from other processes to the Tailscale
# internal resolver (100.100.100.100 or its IPv6 address). That resolver serves
# MagicDNS and forwards other names according to the tailnet DNS settings
# delivered by the coordination server.
#
# The redirect is owned by this module: one netfilter chain per family, jumped
# from OUTPUT only while the daemon runs. tailscaled's own upstream queries
# (uid 0), private destinations and the resolver itself are excluded so the
# redirect cannot loop or break LAN/captive-portal lookups.
#
# This feature is strictly opt-in. When it was never enabled, no netfilter
# command is executed at all; a marker records whether cleanup is needed.

SYSTEM_DNS_FLAG="$TS_DIR/system-dns-enabled"
SYSTEM_DNS_MARK="$TS_DIR/system-dns-applied"
SYSTEM_DNS_CHAIN=KST_SYSDNS
SYSTEM_DNS_CHAIN6=KST_SYSDNS6
SYSTEM_DNS_TARGET=100.100.100.100
SYSTEM_DNS_TARGET6='fd7a:115c:a1e0::53'

system_dns_enabled() { [ -f "$SYSTEM_DNS_FLAG" ]; }
system_dns_owned() { [ -f "$SYSTEM_DNS_FLAG" ] || [ -f "$SYSTEM_DNS_MARK" ]; }

system_dns_tool() { command -v "$1" >/dev/null 2>&1; }

system_dns_chain_present() {
  system_dns_tool iptables && iptables -t nat -S "$SYSTEM_DNS_CHAIN" >/dev/null 2>&1
}

system_dns_applied() {
  system_dns_owned || return 1
  system_dns_chain_present &&
    iptables -t nat -C OUTPUT -p udp --dport 53 -j "$SYSTEM_DNS_CHAIN" >/dev/null 2>&1 &&
    iptables -t nat -C OUTPUT -p tcp --dport 53 -j "$SYSTEM_DNS_CHAIN" >/dev/null 2>&1
}

system_dns_probe() {
  local output
  if [ -x "$KST_BUSYBOX" ]; then
    output=$(bounded 3 "$KST_BUSYBOX" nslookup example.com "$SYSTEM_DNS_TARGET" 2>/dev/null)
  else
    output=$(bounded 3 nslookup example.com "$SYSTEM_DNS_TARGET" 2>/dev/null)
  fi
  # The server banner also contains "Address"; require an actual answer section.
  printf '%s\n' "$output" | grep -q '^Name:' &&
    printf '%s\n' "$output" | grep -q '^Address'
}

# Active foreign redirects (jumped from OUTPUT/PREROUTING for port 53).
system_dns_active_conflict() {
  {
    iptables -t nat -S OUTPUT 2>/dev/null
    iptables -t nat -S PREROUTING 2>/dev/null
    ip6tables -t nat -S OUTPUT 2>/dev/null
    ip6tables -t nat -S PREROUTING 2>/dev/null
    ip6tables -S OUTPUT 2>/dev/null
    ip6tables -S PREROUTING 2>/dev/null
  } | grep -E -- '--dport 53' | grep -v -E -- "KST_SYSDNS" | head -n 1
}

# A dormant DNS module: a foreign chain whose own rules redirect port 53.
system_dns_other_module() {
  {
    iptables -t nat -S 2>/dev/null
    ip6tables -t nat -S 2>/dev/null
    ip6tables -S 2>/dev/null
  } | grep -v -E -- "KST_SYSDNS" | awk '
    /^-N / { chain=$2; next }
    /^-A / && $2 == chain && /--dport 53/ { print chain; exit }
  '
}

system_dns_remove6_quiet() {
  if system_dns_tool ip6tables; then
    ip6tables -t nat -F "$SYSTEM_DNS_CHAIN6" >/dev/null 2>&1 || true
    ip6tables -t nat -X "$SYSTEM_DNS_CHAIN6" >/dev/null 2>&1 || true
    ip6tables -F "$SYSTEM_DNS_CHAIN6" >/dev/null 2>&1 || true
    ip6tables -X "$SYSTEM_DNS_CHAIN6" >/dev/null 2>&1 || true
  fi
}

system_dns_remove_all() {
  local protocol
  if system_dns_tool iptables; then
    for protocol in udp tcp; do
      while iptables -t nat -C OUTPUT -p "$protocol" --dport 53 -j "$SYSTEM_DNS_CHAIN" >/dev/null 2>&1; do
        iptables -t nat -D OUTPUT -p "$protocol" --dport 53 -j "$SYSTEM_DNS_CHAIN" >/dev/null 2>&1 || break
      done
    done
    iptables -t nat -F "$SYSTEM_DNS_CHAIN" >/dev/null 2>&1 || true
    iptables -t nat -X "$SYSTEM_DNS_CHAIN" >/dev/null 2>&1 || true
  fi
  if system_dns_tool ip6tables; then
    for protocol in udp tcp; do
      while ip6tables -t nat -C OUTPUT -p "$protocol" --dport 53 -j "$SYSTEM_DNS_CHAIN6" >/dev/null 2>&1; do
        ip6tables -t nat -D OUTPUT -p "$protocol" --dport 53 -j "$SYSTEM_DNS_CHAIN6" >/dev/null 2>&1 || break
      done
      while ip6tables -C OUTPUT -p "$protocol" --dport 53 -j "$SYSTEM_DNS_CHAIN6" >/dev/null 2>&1; do
        ip6tables -D OUTPUT -p "$protocol" --dport 53 -j "$SYSTEM_DNS_CHAIN6" >/dev/null 2>&1 || break
      done
    done
  fi
  system_dns_remove6_quiet
}

# Guarded cleanup: never touches netfilter unless this feature owns rules.
system_dns_remove_quiet() {
  system_dns_owned || return 0
  system_dns_remove_all
  rm -f "$SYSTEM_DNS_MARK"
}

# Some kernels ship IPv4 NAT but not IPv6 NAT. When IPv6 NAT is missing,
# reject IPv6 port 53 instead of leaving carrier IPv6 resolvers reachable;
# resolvers then fall back to the redirected IPv4 path.
system_dns_apply6() {
  local protocol
  system_dns_tool ip6tables || return 1
  if ip6tables -t nat -S >/dev/null 2>&1; then
    ip6tables -t nat -N "$SYSTEM_DNS_CHAIN6" || return 1
    ip6tables -t nat -A "$SYSTEM_DNS_CHAIN6" -m owner --uid-owner 0 -j RETURN || return 1
    ip6tables -t nat -A "$SYSTEM_DNS_CHAIN6" -d ::1/128 -j RETURN || return 1
    ip6tables -t nat -A "$SYSTEM_DNS_CHAIN6" -d fc00::/7 -j RETURN || return 1
    ip6tables -t nat -A "$SYSTEM_DNS_CHAIN6" -d fe80::/10 -j RETURN || return 1
    ip6tables -t nat -A "$SYSTEM_DNS_CHAIN6" -d "$SYSTEM_DNS_TARGET6" -j RETURN || return 1
    ip6tables -t nat -A "$SYSTEM_DNS_CHAIN6" -p udp --dport 53 -j DNAT --to-destination "[$SYSTEM_DNS_TARGET6]:53" || return 1
    ip6tables -t nat -A "$SYSTEM_DNS_CHAIN6" -p tcp --dport 53 -j DNAT --to-destination "[$SYSTEM_DNS_TARGET6]:53" || return 1
    ip6tables -t nat -I OUTPUT 1 -p udp --dport 53 -j "$SYSTEM_DNS_CHAIN6" || return 1
    ip6tables -t nat -I OUTPUT 1 -p tcp --dport 53 -j "$SYSTEM_DNS_CHAIN6" || return 1
    return 0
  fi
  ip6tables -N "$SYSTEM_DNS_CHAIN6" || return 1
  ip6tables -A "$SYSTEM_DNS_CHAIN6" -m owner --uid-owner 0 -j RETURN || return 1
  ip6tables -A "$SYSTEM_DNS_CHAIN6" -d ::1/128 -j RETURN || return 1
  ip6tables -A "$SYSTEM_DNS_CHAIN6" -d fc00::/7 -j RETURN || return 1
  ip6tables -A "$SYSTEM_DNS_CHAIN6" -d fe80::/10 -j RETURN || return 1
  ip6tables -A "$SYSTEM_DNS_CHAIN6" -p udp --dport 53 -j REJECT --reject-with icmp6-port-unreachable || return 1
  ip6tables -A "$SYSTEM_DNS_CHAIN6" -p tcp --dport 53 -j REJECT --reject-with tcp-reset || return 1
  ip6tables -I OUTPUT 1 -p udp --dport 53 -j "$SYSTEM_DNS_CHAIN6" || return 1
  ip6tables -I OUTPUT 1 -p tcp --dport 53 -j "$SYSTEM_DNS_CHAIN6" || return 1
}

system_dns_apply() {
  local protocol network conflict
  system_dns_tool iptables || { echo 'iptables is required for system-wide DNS' >&2; return 1; }
  daemon_running || { echo 'The owned Tailscale daemon must be running' >&2; return 1; }
  conflict=$(system_dns_active_conflict)
  if [ -n "$conflict" ]; then
    echo 'Another DNS redirect is active; disable the other DNS module before enabling system-wide DNS' >&2
    return 1
  fi
  system_dns_probe || {
    echo 'The Tailscale resolver did not answer the DNS probe; configure a tailnet DNS nameserver and retry' >&2
    return 1
  }
  system_dns_remove_all
  iptables -t nat -N "$SYSTEM_DNS_CHAIN" || return 1
  iptables -t nat -A "$SYSTEM_DNS_CHAIN" -m owner --uid-owner 0 -j RETURN || { system_dns_remove_all; return 1; }
  for network in 10.0.0.0/8 172.16.0.0/12 192.168.0.0/16 169.254.0.0/16; do
    iptables -t nat -A "$SYSTEM_DNS_CHAIN" -d "$network" -j RETURN || { system_dns_remove_all; return 1; }
  done
  iptables -t nat -A "$SYSTEM_DNS_CHAIN" -d "$SYSTEM_DNS_TARGET" -j RETURN || { system_dns_remove_all; return 1; }
  iptables -t nat -A "$SYSTEM_DNS_CHAIN" -p udp --dport 53 -j DNAT --to-destination "$SYSTEM_DNS_TARGET:53" || { system_dns_remove_all; return 1; }
  iptables -t nat -A "$SYSTEM_DNS_CHAIN" -p tcp --dport 53 -j DNAT --to-destination "$SYSTEM_DNS_TARGET:53" || { system_dns_remove_all; return 1; }
  for protocol in udp tcp; do
    iptables -t nat -I OUTPUT 1 -p "$protocol" --dport 53 -j "$SYSTEM_DNS_CHAIN" || { system_dns_remove_all; return 1; }
  done
  if ! system_dns_apply6; then
    system_dns_remove6_quiet
    log WARN 'IPv6 DNS NAT is unavailable; IPv6 port-53 queries are not redirected'
  fi
  : > "$SYSTEM_DNS_MARK" || { system_dns_remove_all; return 1; }
}

system_dns_sync() {
  if system_dns_enabled; then
    if daemon_running; then
      system_dns_applied || system_dns_apply || { log WARN 'System-wide DNS could not be applied'; return 1; }
    else
      system_dns_remove_quiet
    fi
  else
    system_dns_remove_quiet
  fi
}
