#!/system/bin/sh
# shellcheck disable=SC2317

MODDIR=${MODDIR:-/data/adb/modules/TailMate}
MODDIR=$(cd "$MODDIR" && pwd -P) || exit 1
TS_DIR=${TS_DIR:-/data/adb/TailMate}
TM_STAGED_DIR=${TM_STAGED_DIR:-/data/adb/modules_update/TailMate}
TM_LEGACY_DIR=${TM_LEGACY_DIR:-/data/adb/tailscale}
TM_LEGACY_MODULE=${TM_LEGACY_MODULE:-/data/adb/modules/magisk-tailscaled}
export MODDIR TS_DIR TM_STAGED_DIR TM_LEGACY_DIR TM_LEGACY_MODULE

BUNDLE_DIR=${BUNDLE_DIR:-${0%/scripts/*}}
export BUNDLE_DIR
. "$BUNDLE_DIR/scripts/bundle-lib.sh"
tm_private_root || exit 1
. "$BUNDLE_DIR/tailscale/scripts/common.sh"
. "$BUNDLE_DIR/scripts/activate-runtime.sh"
. "$BUNDLE_DIR/scripts/migrate-legacy.sh"
. "$BUNDLE_DIR/tailscale/scripts/engine-update.sh"

# Invoked indirectly by with_control_lock, not a dead function.
# shellcheck disable=SC2329
tm_dispatch() {
  if [ "$1" != boot ]; then
    locked_current=$(tm_current) || return 1
    if [ -n "$locked_current" ] && [ "$locked_current" != "$BUNDLE_DIR" ]; then
      BUNDLE_DIR=$locked_current sh "$locked_current/scripts/runtime-control.sh" "$1" 9>&9
      return $?
    fi
  fi
  # A later explicit stop must supersede the running intent saved by an
  # interrupted engine update. A marker leaves the journal metadata intact.
  if [ -d "$TS_DIR/engine-transaction" ]; then
    case "$1" in
      stop|disable) : > "$TS_DIR/engine-transaction/no-restart" || return 1 ;;
      toggle) if ! module_disabled; then : > "$TS_DIR/engine-transaction/no-restart" || return 1; fi ;;
    esac
  fi
  case "$1" in
    engine-check) engine_check ;;
    engine-prepare) engine_prepare ;;
    engine-apply) engine_apply ;;
    engine-status) engine_status ;;
    engine-recover) engine_recover ;;
    migration-status) tm_migration_status ;;
    migrate-legacy) tm_migrate_legacy ;;
    apply-staged) engine_recover && tm_apply_staged ;;
    boot)
      engine_recover || return 1
      [ ! -f "$MODDIR/disable" ] && [ ! -f "$MODDIR/remove" ] || return 0
      [ ! -f "$TS_DIR/migration-awaiting-enable" ] || return 0
      tm_require_identity_choice || return 1
      existing=$(tm_current) || return 1
      manager_version=$(sed -n 's/^versionCode=//p' "$BUNDLE_DIR/module.prop" 2>/dev/null | head -n 1)
      current_version=$(sed -n 's/^versionCode=//p' "$existing/module.prop" 2>/dev/null | head -n 1)
      manager_digest=$(sha256sum "$BUNDLE_DIR/bundle.sha256" 2>/dev/null | cut -d ' ' -f 1)
      current_digest=$(sha256sum "$existing/bundle.sha256" 2>/dev/null | cut -d ' ' -f 1)
      if [ -n "$existing" ] && [ -n "$manager_version" ] && [ "$manager_version" = "$current_version" ] && [ -n "$manager_digest" ] && [ "$manager_digest" = "$current_digest" ]; then
        # Boot starts the verified generation; the manager directory also has
        # UI and metadata files outside the immutable runtime manifest.
        tm_runtime "$existing" start
      else
        tm_prepare_manager
      fi
      ;;
    start|enable|restart|toggle)
      engine_recover || return 1
      tm_require_identity_choice || return 1
      tm_prepare_initial || return 1
      rm -f "$TS_DIR/migration-awaiting-enable"
      tm_runtime "$(tm_current)" "$1"
      ;;
  stop|disable|status|status-json|logs|diagnostics|web-restart|login-server|taildrop-save-dir|enable-dns|disable-dns|toggle-dns|enable-system-dns|disable-system-dns|private-dns-off)
      selected=$(tm_current) || return 1
      [ -n "$selected" ] || selected=$BUNDLE_DIR
      tm_runtime "$selected" "$1"
      ;;
    *) tm_error unsupported-command; return 2 ;;
  esac
}

[ "$#" -eq 1 ] || { tm_error one-command-required; exit 2; }
# Companion reads and transfers never mutate runtime selection. Resolve the
# current immutable generation, then replace this shell so cancellation reaches
# the official transfer process and no controller lock spans a network send.
case "$1" in
  taildrop-targets|taildrop-send)
    selected=$(tm_current) || exit 1
    [ -n "$selected" ] || selected=$BUNDLE_DIR
    BUNDLE_DIR=$selected
    export BUNDLE_DIR
    unset TM_LOCK_HELD
    exec sh "$selected/tailscale/scripts/tailscale-service" "$1" 9>&- 8>&-
    ;;
esac
# Managers and the companion run this from an app process. Android kills an
# app's whole process group (its uid_N/pid_M cgroup) when the app is removed,
# which would take the daemon down with it. Leave every per-app group first.
tm_leave_app_cgroup
with_control_lock tm_dispatch "$1"
result=$?
sh "$BUNDLE_DIR/scripts/status-summary.sh" >/dev/null 2>&1 || true
exit "$result"
