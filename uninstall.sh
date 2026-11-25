#!/system/bin/sh
MODDIR=${0%/*}
sh "$MODDIR/control.sh" stop
TS_DIR=${TS_DIR:-/data/adb/TailMate}
rm -f "$TS_DIR/system-dns-enabled"
for tool in iptables ip6tables; do
  command -v "$tool" >/dev/null 2>&1 || continue
  for protocol in udp tcp; do
    "$tool" -t nat -D OUTPUT -p "$protocol" --dport 53 -j TM_SYSDNS 2>/dev/null || true
    "$tool" -t nat -D OUTPUT -p "$protocol" --dport 53 -j TM_SYSDNS6 2>/dev/null || true
    "$tool" -D OUTPUT -p "$protocol" --dport 53 -j TM_SYSDNS6 2>/dev/null || true
  done
  "$tool" -t nat -F TM_SYSDNS 2>/dev/null || true
  "$tool" -t nat -X TM_SYSDNS 2>/dev/null || true
  "$tool" -t nat -F TM_SYSDNS6 2>/dev/null || true
  "$tool" -t nat -X TM_SYSDNS6 2>/dev/null || true
  "$tool" -F TM_SYSDNS6 2>/dev/null || true
  "$tool" -X TM_SYSDNS6 2>/dev/null || true
done
# Identity and generations are retained for an explicit reinstall or manual purge.
