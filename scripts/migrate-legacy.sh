#!/system/bin/sh

tm_legacy_state() {
  if [ -f "$TM_LEGACY_DIR/tailscaled.state" ]; then
    printf '%s\n' "$TM_LEGACY_DIR/tailscaled.state"
  elif [ -f "$TM_LEGACY_DIR/tmp/tailscaled.state" ]; then
    printf '%s\n' "$TM_LEGACY_DIR/tmp/tailscaled.state"
  fi
}

tm_process_uses_root() (
  root=$1
  scope=${2:-legacy}
  for proc in /proc/[0-9]*; do
    [ -r "$proc/cmdline" ] || continue
    executable=$(readlink "$proc/exe" 2>/dev/null)
    case "$executable" in "$root/"*) exit 0 ;; esac
    if tr '\000' '\n' < "$proc/cmdline" 2>/dev/null | (
      while IFS= read -r argument; do
        if [ "$scope" = legacy ]; then
          case "$argument" in "$root"|"$root/"*) exit 0 ;; esac
        fi
        case "$argument" in "$root/"*)
          case "${argument##*/}" in
            tailscale|tailscaled|tailscale-supervisor|tailscale-daemon|tailscale-web|tailscale-tunnel|hev-socks5-tunnel) exit 0 ;;
          esac ;;
        esac
      done
      exit 1
    ); then exit 0; fi
  done
  exit 1
)

tm_migration_status() {
  if [ -e "$TS_DIR/migration.pending" ]; then
    printf '%s\n' '{"state":"migration-incomplete"}'
  elif [ -f "$TS_DIR/migration-awaiting-enable" ]; then
    printf '%s\n' '{"state":"awaiting-enable"}'
  elif [ -e "$TS_DIR/tailscaled.state" ]; then
    printf '%s\n' '{"state":"destination-exists"}'
  elif [ -n "$(tm_legacy_state)" ]; then
    printf '%s\n' '{"state":"migration-required"}'
  else
    printf '%s\n' '{"state":"no-legacy-identity"}'
  fi
}

tm_finish_migration() (
  pending="$TS_DIR/migration.pending"
  [ -d "$pending" ] && [ ! -L "$pending" ] || exit 1
  [ -z "$(find "$pending" -type l -print)" ] || exit 1
  tm_awk 'NF != 2 || length($1) != 64 || $1 ~ /[^0-9a-f]/ || $2 !~ /^(tailscaled.state|module.conf|migration-origin|migration-awaiting-enable)$/ { exit 1 }
    seen[$2]++ { exit 1 }
    END { if (!seen["tailscaled.state"] || !seen["migration-origin"] || !seen["migration-awaiting-enable"]) exit 1 }
  ' "$pending/journal.sha256" || { tm_error invalid-migration-journal; exit 1; }
  (cd "$pending" && sha256sum -c journal.sha256 >/dev/null 2>&1) || { tm_error migration-journal-changed; exit 1; }
  for marker in migration-origin migration-awaiting-enable; do
    [ ! -L "$TS_DIR/$marker" ] || exit 1
    if [ -e "$TS_DIR/$marker" ]; then
      cmp -s "$pending/$marker" "$TS_DIR/$marker" || { tm_error migration-marker-changed; exit 1; }
    else
      ln "$pending/$marker" "$TS_DIR/$marker" || exit 1
    fi
  done
  if [ -f "$pending/module.conf" ]; then
    [ ! -L "$TS_DIR/config/module.conf" ] || exit 1
    if [ -e "$TS_DIR/config/module.conf" ]; then
      cmp -s "$pending/module.conf" "$TS_DIR/config/module.conf" || { tm_error migration-config-changed; exit 1; }
    else
      cp "$pending/module.conf" "$pending/config.publish" || exit 1
      ln "$pending/config.publish" "$TS_DIR/config/module.conf" || exit 1
      rm -f "$pending/config.publish"
    fi
  fi
  # Persist preference and explicit-enable gates before making the identity visible.
  sync
  [ ! -L "$TS_DIR/tailscaled.state" ] || exit 1
  if [ -e "$TS_DIR/tailscaled.state" ]; then
    cmp -s "$pending/tailscaled.state" "$TS_DIR/tailscaled.state" || { tm_error migration-identity-changed; exit 1; }
  else
    ln "$pending/tailscaled.state" "$TS_DIR/tailscaled.state" || exit 1
  fi
  sync
  completed="$TS_DIR/.migration-complete.$$"
  [ ! -e "$completed" ] && [ ! -L "$completed" ] || exit 1
  mv "$pending" "$completed" || exit 1
  sync
  rm -rf "$completed" || true
  printf '%s\n' 'Legacy identity copied; legacy files preserved; runtime awaits explicit enable.'
)

tm_migrate_legacy() (
  source=$(tm_legacy_state)
  [ -n "$source" ] || { tm_error no-legacy-identity; exit 1; }
  if [ -L "$TM_LEGACY_DIR" ] || [ -L "${source%/*}" ] || [ -L "$source" ]; then
    tm_error symlink-legacy-state
    exit 1
  fi
  [ ! -e "$TM_LEGACY_MODULE" ] || [ -f "$TM_LEGACY_MODULE/disable" ] || [ -f "$TM_LEGACY_MODULE/remove" ] || { tm_error legacy-module-must-be-disabled; exit 1; }
  if tm_process_uses_root "$TM_LEGACY_DIR" || tm_process_uses_root "$TM_LEGACY_MODULE" || tm_process_uses_root "$TS_DIR/releases" components; then
    tm_error migration-requires-stopped-runtimes
    exit 1
  fi
  if [ -e "$TS_DIR/migration.pending" ] || [ -L "$TS_DIR/migration.pending" ]; then
    tm_finish_migration
    exit $?
  fi
  if [ -e "$TS_DIR/tailscaled.state" ] || [ -L "$TS_DIR/tailscaled.state" ]; then
    tm_error destination-identity-exists
    exit 1
  fi
  temporary=$(mktemp -d "$TS_DIR/.migration.XXXXXX") || exit 1
  trap 'rm -rf "$temporary"' EXIT HUP INT TERM
  if [ -e "$TM_LEGACY_DIR/config/module.conf" ]; then
    [ ! -L "$TM_LEGACY_DIR/config" ] && [ ! -L "$TM_LEGACY_DIR/config/module.conf" ] || exit 1
    if [ -e "$TS_DIR/config/module.conf" ] || [ -L "$TS_DIR/config/module.conf" ]; then
      tm_error destination-config-exists
      exit 1
    fi
    cp "$TM_LEGACY_DIR/config/module.conf" "$temporary/module.conf" || exit 1
    (CONFIG_DIR=$temporary; load_config) || { tm_error invalid-legacy-config; exit 1; }
  fi
  cp "$source" "$temporary/tailscaled.state" || exit 1
  chmod 0600 "$temporary/tailscaled.state" || exit 1
  cmp -s "$source" "$temporary/tailscaled.state" || exit 1
  printf '%s\n' legacy-copy-preserve-prefs > "$temporary/migration-origin"
  : > "$temporary/migration-awaiting-enable"
  (cd "$temporary" && sha256sum tailscaled.state migration-origin migration-awaiting-enable > journal.sha256) || exit 1
  [ ! -f "$temporary/module.conf" ] || (cd "$temporary" && sha256sum module.conf >> journal.sha256) || exit 1
  find "$temporary" -type f -exec chmod 0600 {} \; || exit 1
  mv "$temporary" "$TS_DIR/migration.pending" || exit 1
  trap - EXIT HUP INT TERM
  sync
  tm_finish_migration
)

tm_handover_same_tree() (
  cd "$1" || exit 1
  [ "$(find . | sort)" = "$(cd "$2" && find . | sort)" ] || exit 1
  find . -type f | while IFS= read -r file; do cmp -s "$file" "$2/$file" || exit 1; done
)

# Automatic one-time handover from the predecessor install (old module id).
# Its identity and settings are copied (never moved), it is stopped and marked
# for manager removal on reboot; its state directory stays untouched.
tm_handover_predecessor() (
  old_dir=$TM_PREDECESSOR_DIR
  old_module=$TM_PREDECESSOR_MODULE
  old_state="$old_dir/tailscaled.state"
  case "$old_dir" in /?*) ;; *) tm_error invalid-predecessor-path; exit 1 ;; esac
  case "$old_module" in /?*) ;; *) tm_error invalid-predecessor-path; exit 1 ;; esac
  [ -e "$old_state" ] || [ -L "$old_state" ] || exit 0
  # TailMate's own identity, a completed handover, or an explicit legacy
  # migration in progress always wins.
  for entry in tailscaled.state handover-origin migration.pending; do
    [ ! -e "$TS_DIR/$entry" ] && [ ! -L "$TS_DIR/$entry" ] || exit 0
  done
  for entry in "$old_dir" "$old_module" "$old_state" "$old_dir/config" "$old_dir/config/module.conf" \
    "$old_dir/hostname.initialized" "$old_dir/system-dns-enabled" "$old_dir/profile-data" "$old_dir/files"; do
    [ ! -L "$entry" ] || { tm_error symlink-predecessor-state; exit 1; }
  done
  [ -f "$old_state" ] || exit 0
  for entry in profile-data files; do
    [ ! -d "$old_dir/$entry" ] || [ -z "$(find "$old_dir/$entry" -type l -print)" ] || {
      tm_error symlink-predecessor-state; exit 1;
    }
  done

  if [ -f "$old_module/control.sh" ]; then
    # The old controller resolves its own module and state directories.
    bounded 30 env -u BUNDLE_DIR MODDIR="$old_module" TS_DIR="$old_dir" \
      sh "$old_module/control.sh" stop </dev/null >/dev/null 2>&1 || :
  fi
  if tm_process_uses_root "$old_dir" || tm_process_uses_root "$old_module"; then
    tm_error predecessor-still-running
    exit 1
  fi
  # The predecessor's opt-in system DNS hooks used its own chain names.
  for tool in iptables ip6tables; do
    command -v "$tool" >/dev/null 2>&1 || continue
    for protocol in udp tcp; do
      "$tool" -t nat -D OUTPUT -p "$protocol" --dport 53 -j KST_SYSDNS >/dev/null 2>&1 || :
      "$tool" -t nat -D OUTPUT -p "$protocol" --dport 53 -j KST_SYSDNS6 >/dev/null 2>&1 || :
      "$tool" -D OUTPUT -p "$protocol" --dport 53 -j KST_SYSDNS6 >/dev/null 2>&1 || :
    done
    "$tool" -t nat -F KST_SYSDNS >/dev/null 2>&1 || :
    "$tool" -t nat -X KST_SYSDNS >/dev/null 2>&1 || :
    "$tool" -t nat -F KST_SYSDNS6 >/dev/null 2>&1 || :
    "$tool" -t nat -X KST_SYSDNS6 >/dev/null 2>&1 || :
    "$tool" -F KST_SYSDNS6 >/dev/null 2>&1 || :
    "$tool" -X KST_SYSDNS6 >/dev/null 2>&1 || :
  done
  temporary=$(mktemp -d "$TS_DIR/.handover.XXXXXX") || exit 1
  trap 'rm -rf "$temporary"' EXIT HUP INT TERM
  if ! { cp "$old_state" "$temporary/tailscaled.state" && chmod 0600 "$temporary/tailscaled.state" &&
    cmp -s "$old_state" "$temporary/tailscaled.state"; }; then
    tm_error predecessor-copy-failed; exit 1
  fi
  # Entries TailMate already has are its own; a retried handover keeps them.
  for entry in config/module.conf hostname.initialized system-dns-enabled; do
    [ -f "$old_dir/$entry" ] && [ ! -e "$TS_DIR/$entry" ] && [ ! -L "$TS_DIR/$entry" ] || continue
    case "$entry" in */*) mkdir -p "$temporary/${entry%/*}" || exit 1 ;; esac
    if ! { cp "$old_dir/$entry" "$temporary/$entry" && cmp -s "$old_dir/$entry" "$temporary/$entry"; }; then
      tm_error predecessor-copy-failed; exit 1
    fi
  done
  if [ -f "$temporary/config/module.conf" ] && ! (CONFIG_DIR=$temporary/config; load_config); then
    rm -f "$temporary/config/module.conf"
    printf '%s\n' '- Previous settings were invalid; TailMate defaults apply'
  fi
  for entry in profile-data files; do
    [ -d "$old_dir/$entry" ] && [ ! -e "$TS_DIR/$entry" ] && [ ! -L "$TS_DIR/$entry" ] || continue
    if ! { cp -R "$old_dir/$entry" "$temporary/$entry" &&
      tm_handover_same_tree "$old_dir/$entry" "$temporary/$entry"; }; then
      tm_error predecessor-copy-failed; exit 1
    fi
  done
  # Managers delete a module carrying this flag on the next reboot; the old
  # runtime already refuses to start while it exists. Flag it only once every
  # copy is verified, and before the identity appears, so two runtimes never
  # share one identity and a failed copy leaves the old module usable.
  if [ -d "$old_module" ]; then : > "$old_module/remove" || exit 1; fi
  sync
  for entry in profile-data files config/module.conf hostname.initialized system-dns-enabled; do
    [ -e "$temporary/$entry" ] || continue
    mv -f "$temporary/$entry" "$TS_DIR/$entry" || exit 1
  done
  sync
  # The identity becomes visible last among copied files, so an interruption
  # leaves no half handover; the origin marker then records completion.
  mv "$temporary/tailscaled.state" "$TS_DIR/tailscaled.state" || exit 1
  printf '%s\n' kernelsu-tailscaled > "$TS_DIR/handover-origin" || exit 1
  sync
  printf '%s\n' "- Identity and settings copied from KernelSU-Tailscaled; the old module is removed at next reboot; $old_dir was kept and can be deleted manually"
)
