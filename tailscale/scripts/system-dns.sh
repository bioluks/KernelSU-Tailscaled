#!/system/bin/sh
# shellcheck disable=SC3043
# Opt-in system-wide DNS. Android's resolver does not read /etc/resolv.conf;
# instead, redirect port-53 traffic from other processes to the tailnet
# nameservers delivered by the coordination server (`tailscale dns status`),
# which serve both MagicDNS names and forwarded lookups.
#
# Queries are source-NATed to the node's tailnet address on egress, because
# tailnet resolvers ignore queries from non-tailnet source addresses (for
# example a carrier CGNAT address). tailscaled's own traffic is excluded by
# its socket mark (0x80000/0xff0000); Android's netd runs as root, so a uid
# exclusion would also skip Android's own resolver. Loopback is excluded.
#
# This feature is strictly opt-in. When it was never enabled, no netfilter
# command is executed at all; markers record whether cleanup is needed.
#
# IPv6: when the kernel has IPv6 NAT and the tailnet provides an IPv6
# nameserver, IPv6 queries are redirected the same way. Otherwise IPv6
# port 53 is rejected so resolvers fall back to the redirected IPv4 path.

SYSTEM_DNS_FLAG="$TS_DIR/system-dns-enabled"
SYSTEM_DNS_MARK="$TS_DIR/system-dns-applied"
SYSTEM_DNS_CHAIN=TM_SYSDNS
SYSTEM_DNS_CHAIN6=TM_SYSDNS6
SYSTEM_DNS_DISCOVERED=
SYSTEM_DNS_TARGET4=
SYSTEM_DNS_TARGET6=
SYSTEM_DNS_NODE4=
SYSTEM_DNS_NODE6=
SYSTEM_DNS_JSON=

system_dns_enabled() { [ -f "$SYSTEM_DNS_FLAG" ]; }
system_dns_owned() { [ -f "$SYSTEM_DNS_FLAG" ] || [ -f "$SYSTEM_DNS_MARK" ]; }
system_dns_tool() { command -v "$1" >/dev/null 2>&1; }

system_dns_cli() {
  bounded "$TM_CLI_TIMEOUT" "$TAILSCALE_BIN" --socket="$SOCKET_FILE" "$@"
}


# Coordination-server DNS values and this node's tailnet addresses.
system_dns_discover() {
  [ -z "${SYSTEM_DNS_DISCOVERED:-}" ] || return 0
  SYSTEM_DNS_JSON=$(system_dns_cli dns status --json 2>/dev/null) || return 1
  SYSTEM_DNS_TARGET4=$(printf '%s\n' "$SYSTEM_DNS_JSON" |
    grep -o '"Addr":[[:space:]]*"[0-9][0-9.]*"' | head -n 1 |
    grep -oE '[0-9][0-9.]*' | tail -n 1)
  SYSTEM_DNS_TARGET6=$(printf '%s\n' "$SYSTEM_DNS_JSON" |
    grep -o '"Addr":[[:space:]]*"[0-9a-fA-F:]*"' | grep ':' | head -n 1 |
    grep -oE '[0-9a-fA-F:]*' | tail -n 1)
  SYSTEM_DNS_NODE4=$(system_dns_cli ip -4 2>/dev/null | head -n 1)
  SYSTEM_DNS_NODE6=$(system_dns_cli ip -6 2>/dev/null | head -n 1)
  [ -n "$SYSTEM_DNS_TARGET4" ] || [ -n "$SYSTEM_DNS_TARGET6" ] || return 1
  SYSTEM_DNS_DISCOVERED=1
}

system_dns_desired() {
  printf '%s %s %s %s\n' \
    "${SYSTEM_DNS_TARGET4:-none}" "${SYSTEM_DNS_TARGET6:-none}" \
    "${SYSTEM_DNS_NODE4:-none}" "${SYSTEM_DNS_NODE6:-none}"
}

system_dns_probe() {
  local output self answers
  if [ -x "$TM_BUSYBOX" ]; then
    output=$(bounded 3 "$TM_BUSYBOX" nslookup example.com "${SYSTEM_DNS_TARGET4}" 2>/dev/null)
  else
    output=$(bounded 3 nslookup example.com "${SYSTEM_DNS_TARGET4}" 2>/dev/null)
  fi
  # The server banner also contains "Address"; require an actual answer section.
  printf '%s\n' "$output" | grep -q '^Name:' || return 1
  printf '%s\n' "$output" | grep -q '^Address' || return 1
  # MagicDNS must also work through the chosen resolver.
  self=$(printf '%s\n' "$SYSTEM_DNS_JSON" |
    sed -n 's/.*"SelfDNSName":[[:space:]]*"\([^"]*\)".*/\1/p' | head -n 1)
  if [ -n "$self" ] && [ "$self" != null ]; then
    if [ -x "$TM_BUSYBOX" ]; then
      answers=$(bounded 3 "$TM_BUSYBOX" nslookup "$self" "${SYSTEM_DNS_TARGET4}" 2>/dev/null)
    else
      answers=$(bounded 3 nslookup "$self" "${SYSTEM_DNS_TARGET4}" 2>/dev/null)
    fi
    printf '%s\n' "$answers" | awk -v target="$SYSTEM_DNS_TARGET4" '
      /^Address/ { if ($3 != target) found=1 }
      END { exit !found }'
  fi
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
  } | grep -E -- '--dport 53' | grep -v -E -- "TM_SYSDNS" | head -n 1
}

# A dormant DNS module: a foreign chain whose own rules redirect port 53.
system_dns_other_module() {
  system_dns_foreign_rules | awk '{print $2}' | sort -u | head -n 1
}

# Every non-owned rule in a user chain that touches port 53 ("-A CHAIN ...").
system_dns_foreign_rules() {
  {
    iptables -t nat -S 2>/dev/null
    ip6tables -t nat -S 2>/dev/null
    ip6tables -S 2>/dev/null
  } | grep -E -- '--dport 53' | grep -v -E -- 'TM_SYSDNS' |
    awk '$1 == "-A" && $2 !~ /^(OUTPUT|PREROUTING|POSTROUTING|INPUT|FORWARD)$/'
}

# Human-readable report for the other-module warning: each foreign chain, its
# port-53 rules, whether a builtin chain jumps to it (active) or not (dormant),
# and which boot script or module mentions it. Read-only; never edits rules.
system_dns_other_module_report() {
  local rules chains chain jumps owners all adb
  rules=$(system_dns_foreign_rules)
  [ -n "$rules" ] || return 0
  chains=$(printf '%s\n' "$rules" | awk '{print $2}' | sort -u)
  all=$({ iptables -t nat -S; ip6tables -t nat -S; ip6tables -S; iptables -S; } 2>/dev/null)
  adb=${TM_ADB_ROOT:-/data/adb}
  for chain in $chains; do
    jumps=$(printf '%s\n' "$all" | grep -E -- "^-A (OUTPUT|PREROUTING|INPUT|FORWARD|POSTROUTING) .*-j $chain( |\$)" | head -n 3)
    owners=$(grep -l -s -- "$chain" "$adb"/service.d/* "$adb"/post-fs-data.d/* \
      "$adb"/modules/*/service.sh "$adb"/modules/*/post-fs-data.sh 2>/dev/null |
      grep -v '/TailMate/' | head -n 3)
    if [ -n "$jumps" ]; then
      printf 'Chain %s: ACTIVE, traffic is redirected now\n' "$chain"
    else
      printf 'Chain %s: dormant, nothing jumps to it yet\n' "$chain"
    fi
    if [ -n "$owners" ]; then
      printf '%s\n' "$owners" | sed 's/^/  created by /'
    else
      printf '  created by an unknown script\n'
    fi
    printf '%s\n' "$rules" | awk -v c="$chain" '$2 == c' | sed 's/^-A [^ ]* /  rule: /' | head -n 4
    [ -z "$jumps" ] || printf '%s\n' "$jumps" | sed 's/^/  jump: /'
  done
}

# Android Private DNS: "hostname" (strict DoT to a named server) bypasses the
# port-53 redirect entirely; "opportunistic" falls back to port 53 when the
# resolver does not speak DoT, so only strict mode defeats system-wide DNS.
system_dns_private_mode() {
  local mode
  mode=$(bounded 2 settings get global private_dns_mode 2>/dev/null) || mode=
  case "$mode" in
    off|opportunistic|hostname) printf '%s\n' "$mode" ;;
    *) printf 'opportunistic\n' ;;  # unset means the Android default
  esac
}

system_dns_private_host() {
  local host
  host=$(bounded 2 settings get global private_dns_specifier 2>/dev/null) || host=
  [ "$host" = null ] || printf '%s\n' "$host"
}

# Explicit user action only: switch Private DNS off so strict DoT cannot bypass
# the redirect. Never called automatically.
system_dns_private_off() {
  bounded 3 settings put global private_dns_mode off >/dev/null 2>&1 || {
    echo 'Could not change Android Private DNS' >&2; return 1
  }
  [ "$(system_dns_private_mode)" = off ] || { echo 'Android Private DNS did not switch off' >&2; return 1; }
  log INFO 'Android Private DNS switched off on request'
  echo 'Android Private DNS is off'
}

system_dns_remove_all() {
  local protocol stored4 stored6 node4 node6
  stored4=none; stored6=none; node4=none; node6=none
  if [ -f "$SYSTEM_DNS_FLAG" ]; then
    read -r stored4 stored6 node4 node6 < "$SYSTEM_DNS_FLAG" 2>/dev/null || true
  fi
  if system_dns_tool iptables; then
    for protocol in udp tcp; do
      while iptables -t nat -C OUTPUT -p "$protocol" --dport 53 -j "$SYSTEM_DNS_CHAIN" >/dev/null 2>&1; do
        iptables -t nat -D OUTPUT -p "$protocol" --dport 53 -j "$SYSTEM_DNS_CHAIN" >/dev/null 2>&1 || break
      done
    done
    iptables -t nat -F "$SYSTEM_DNS_CHAIN" >/dev/null 2>&1 || true
    iptables -t nat -X "$SYSTEM_DNS_CHAIN" >/dev/null 2>&1 || true
    if [ "$stored4" != none ] && [ "$node4" != none ]; then
      for protocol in udp tcp; do
        while iptables -t nat -C POSTROUTING -o tailscale0 -p "$protocol" --dport 53 -d "$stored4" -j SNAT --to-source "$node4" >/dev/null 2>&1; do
          iptables -t nat -D POSTROUTING -o tailscale0 -p "$protocol" --dport 53 -d "$stored4" -j SNAT --to-source "$node4" >/dev/null 2>&1 || break
        done
      done
    fi
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
    if [ "$stored6" != none ] && [ "$node6" != none ]; then
      for protocol in udp tcp; do
        while ip6tables -t nat -C POSTROUTING -o tailscale0 -p "$protocol" --dport 53 -d "$stored6" -j SNAT --to-source "$node6" >/dev/null 2>&1; do
          ip6tables -t nat -D POSTROUTING -o tailscale0 -p "$protocol" --dport 53 -d "$stored6" -j SNAT --to-source "$node6" >/dev/null 2>&1 || break
        done
      done
    fi
    ip6tables -t nat -F "$SYSTEM_DNS_CHAIN6" >/dev/null 2>&1 || true
    ip6tables -t nat -X "$SYSTEM_DNS_CHAIN6" >/dev/null 2>&1 || true
    ip6tables -F "$SYSTEM_DNS_CHAIN6" >/dev/null 2>&1 || true
    ip6tables -X "$SYSTEM_DNS_CHAIN6" >/dev/null 2>&1 || true
  fi
}

# Guarded cleanup: never touches netfilter unless this feature owns rules.
system_dns_remove_quiet() {
  system_dns_owned || return 0
  system_dns_remove_all
  rm -f "$SYSTEM_DNS_MARK"
}

system_dns_chain_present() {
  system_dns_tool iptables && iptables -t nat -S "$SYSTEM_DNS_CHAIN" >/dev/null 2>&1
}

system_dns_snat_present() {
  local stored4 stored6 node4 node6
  [ -f "$SYSTEM_DNS_FLAG" ] || return 1
  read -r stored4 stored6 node4 node6 < "$SYSTEM_DNS_FLAG" 2>/dev/null || return 1
  [ "$stored4" = "${SYSTEM_DNS_TARGET4:-none}" ] || return 1
  [ "$stored6" = "${SYSTEM_DNS_TARGET6:-none}" ] || return 1
  if [ "$stored4" != none ] && [ "$node4" != none ]; then
    iptables -t nat -C POSTROUTING -o tailscale0 -p udp --dport 53 -d "$stored4" -j SNAT --to-source "$node4" >/dev/null 2>&1 || return 1
    iptables -t nat -C POSTROUTING -o tailscale0 -p tcp --dport 53 -d "$stored4" -j SNAT --to-source "$node4" >/dev/null 2>&1 || return 1
  fi
}

system_dns_applied() {
  system_dns_owned || return 1
  system_dns_discover || return 1
  system_dns_chain_present &&
    iptables -t nat -C OUTPUT -p udp --dport 53 -j "$SYSTEM_DNS_CHAIN" >/dev/null 2>&1 &&
    iptables -t nat -C OUTPUT -p tcp --dport 53 -j "$SYSTEM_DNS_CHAIN" >/dev/null 2>&1 &&
    system_dns_snat_present
}

# IPv6 fallback when NAT is missing: reject port 53 instead of leaking
# carrier IPv6 resolvers, so lookups fall back to the redirected IPv4 path.
system_dns_apply6() {
  local protocol
  system_dns_tool ip6tables || return 1
  if ip6tables -t nat -S >/dev/null 2>&1 && [ -n "$SYSTEM_DNS_TARGET6" ] && [ -n "$SYSTEM_DNS_NODE6" ]; then
    ip6tables -t nat -N "$SYSTEM_DNS_CHAIN6" || return 1
    ip6tables -t nat -A "$SYSTEM_DNS_CHAIN6" -m mark --mark 0x80000/0xff0000 -j RETURN || return 1
    ip6tables -t nat -A "$SYSTEM_DNS_CHAIN6" -d ::1/128 -j RETURN || return 1
    ip6tables -t nat -A "$SYSTEM_DNS_CHAIN6" -d "$SYSTEM_DNS_TARGET6" -j RETURN || return 1
    ip6tables -t nat -A "$SYSTEM_DNS_CHAIN6" -p udp --dport 53 -j DNAT --to-destination "[$SYSTEM_DNS_TARGET6]:53" || return 1
    ip6tables -t nat -A "$SYSTEM_DNS_CHAIN6" -p tcp --dport 53 -j DNAT --to-destination "[$SYSTEM_DNS_TARGET6]:53" || return 1
    ip6tables -t nat -I OUTPUT 1 -p udp --dport 53 -j "$SYSTEM_DNS_CHAIN6" || return 1
    ip6tables -t nat -I OUTPUT 1 -p tcp --dport 53 -j "$SYSTEM_DNS_CHAIN6" || return 1
    ip6tables -t nat -I POSTROUTING 1 -o tailscale0 -p udp --dport 53 -d "$SYSTEM_DNS_TARGET6" -j SNAT --to-source "$SYSTEM_DNS_NODE6" || return 1
    ip6tables -t nat -I POSTROUTING 1 -o tailscale0 -p tcp --dport 53 -d "$SYSTEM_DNS_TARGET6" -j SNAT --to-source "$SYSTEM_DNS_NODE6" || return 1
    return 0
  fi
  ip6tables -N "$SYSTEM_DNS_CHAIN6" || return 1
  ip6tables -A "$SYSTEM_DNS_CHAIN6" -m mark --mark 0x80000/0xff0000 -j RETURN || return 1
  ip6tables -A "$SYSTEM_DNS_CHAIN6" -d ::1/128 -j RETURN || return 1
  ip6tables -A "$SYSTEM_DNS_CHAIN6" -p udp --dport 53 -j REJECT --reject-with icmp6-port-unreachable || return 1
  ip6tables -A "$SYSTEM_DNS_CHAIN6" -p tcp --dport 53 -j REJECT --reject-with tcp-reset || return 1
  ip6tables -I OUTPUT 1 -p udp --dport 53 -j "$SYSTEM_DNS_CHAIN6" || return 1
  ip6tables -I OUTPUT 1 -p tcp --dport 53 -j "$SYSTEM_DNS_CHAIN6" || return 1
}

system_dns_apply() {
  local protocol conflict
  system_dns_tool iptables || { echo 'iptables is required for system-wide DNS' >&2; return 1; }
  daemon_running || { echo 'The owned Tailscale daemon must be running' >&2; return 1; }
  system_dns_discover || { echo 'Could not read the tailnet DNS configuration from the daemon' >&2; return 1; }
  [ -n "$SYSTEM_DNS_TARGET4" ] || { echo 'The tailnet has no IPv4 DNS nameserver; configure one in your coordination server' >&2; return 1; }
  conflict=$(system_dns_active_conflict)
  if [ -n "$conflict" ]; then
    echo 'Another DNS redirect is active; disable the other DNS module before enabling system-wide DNS' >&2
    return 1
  fi
  system_dns_probe || {
    echo 'The tailnet resolver did not answer the DNS probe (public and MagicDNS names); check the tailnet DNS configuration' >&2
    return 1
  }
  system_dns_remove_all
  iptables -t nat -N "$SYSTEM_DNS_CHAIN" || return 1
  iptables -t nat -A "$SYSTEM_DNS_CHAIN" -m mark --mark 0x80000/0xff0000 -j RETURN || { system_dns_remove_all; return 1; }
  iptables -t nat -A "$SYSTEM_DNS_CHAIN" -d 127.0.0.0/8 -j RETURN || { system_dns_remove_all; return 1; }
  iptables -t nat -A "$SYSTEM_DNS_CHAIN" -d "$SYSTEM_DNS_TARGET4" -j RETURN || { system_dns_remove_all; return 1; }
  iptables -t nat -A "$SYSTEM_DNS_CHAIN" -p udp --dport 53 -j DNAT --to-destination "$SYSTEM_DNS_TARGET4:53" || { system_dns_remove_all; return 1; }
  iptables -t nat -A "$SYSTEM_DNS_CHAIN" -p tcp --dport 53 -j DNAT --to-destination "$SYSTEM_DNS_TARGET4:53" || { system_dns_remove_all; return 1; }
  for protocol in udp tcp; do
    iptables -t nat -I OUTPUT 1 -p "$protocol" --dport 53 -j "$SYSTEM_DNS_CHAIN" || { system_dns_remove_all; return 1; }
  done
  if [ -n "$SYSTEM_DNS_NODE4" ]; then
    for protocol in udp tcp; do
      iptables -t nat -I POSTROUTING 1 -o tailscale0 -p "$protocol" --dport 53 -d "$SYSTEM_DNS_TARGET4" -j SNAT --to-source "$SYSTEM_DNS_NODE4" || { system_dns_remove_all; return 1; }
    done
  fi
  if ! system_dns_apply6; then
    log WARN 'IPv6 DNS is not redirected'
  fi
  : > "$SYSTEM_DNS_MARK" || { system_dns_remove_all; return 1; }
}

system_dns_sync() {
  if system_dns_enabled; then
    if daemon_running; then
      system_dns_discover || { log WARN 'System-wide DNS could not read the tailnet configuration'; return 1; }
      system_dns_applied || system_dns_apply || { log WARN 'System-wide DNS could not be applied'; return 1; }
    else
      system_dns_remove_quiet
    fi
  else
    system_dns_remove_quiet
  fi
}
