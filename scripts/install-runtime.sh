#!/system/bin/sh

[ "$#" -eq 1 ] || exit 2
BUNDLE_DIR=$(cd "$1" && pwd -P) || exit 1
MODDIR=${TM_ACTIVE_DIR:-/data/adb/modules/TailMate}
TS_DIR=${TS_DIR:-/data/adb/TailMate}
TM_LEGACY_DIR=${TM_LEGACY_DIR:-/data/adb/tailscale}
TM_PREDECESSOR_DIR=${TM_PREDECESSOR_DIR:-/data/adb/kernelsu-tailscaled}
TM_PREDECESSOR_MODULE=${TM_PREDECESSOR_MODULE:-/data/adb/modules/kernelsu-tailscaled}
export BUNDLE_DIR MODDIR TS_DIR TM_LEGACY_DIR TM_PREDECESSOR_DIR TM_PREDECESSOR_MODULE

. "$BUNDLE_DIR/scripts/bundle-lib.sh"
tm_verify_bundle "$BUNDLE_DIR" || exit 1
case "$MODDIR" in /*) ;; *) tm_error invalid-active-directory; exit 1 ;; esac
[ ! -L "$MODDIR" ] || { tm_error symlink-active-directory; exit 1; }
mkdir -p "$MODDIR" || exit 1
MODDIR=$(cd "$MODDIR" && pwd -P) || exit 1
export MODDIR
tm_private_root || exit 1
. "$BUNDLE_DIR/tailscale/scripts/common.sh"
. "$BUNDLE_DIR/scripts/activate-runtime.sh"
. "$BUNDLE_DIR/scripts/migrate-legacy.sh"

tm_install_complete() (
  # Finish a pending engine rollback using the installed controller before
  # activating a different module generation or touching its manager view.
  existing=$(tm_current) || exit 1
  if [ -d "$TS_DIR/engine-transaction" ] && [ -n "$existing" ]; then
    BUNDLE_DIR="$existing" sh "$existing/scripts/runtime-control.sh" engine-recover 9>&9 || exit 1
  fi
  prepared=$(mktemp -d "$MODDIR/.install.XXXXXX") || exit 1
  # Invoked indirectly by the exit and signal traps.
  # shellcheck disable=SC2317,SC2329
  cleanup_install() {
    if [ -d "$prepared/previous-webroot" ] && [ ! -e "$MODDIR/webroot" ]; then
      mv "$prepared/previous-webroot" "$MODDIR/webroot" || return 1
    fi
    rm -rf "$prepared"
  }
  trap cleanup_install EXIT
  trap 'exit 1' HUP INT TERM

  # Prepare the entire manager view before stopping or selecting any runtime.
  for name in control.sh action.sh service.sh uninstall.sh module.prop; do
    [ ! -d "$MODDIR/$name" ] && [ ! -L "$MODDIR/$name" ] || exit 1
    cp "$BUNDLE_DIR/$name" "$prepared/$name" || exit 1
    chmod 0755 "$prepared/$name" || exit 1
  done
  chmod 0644 "$prepared/module.prop" || exit 1
  [ ! -L "$MODDIR/webroot" ] || exit 1
  [ -f "$BUNDLE_DIR/webroot/index.html" ] || exit 1
  cp -R "$BUNDLE_DIR/webroot" "$prepared/webroot" || exit 1
  find "$prepared/webroot" -type d -exec chmod 0755 {} \; || exit 1
  find "$prepared/webroot" -type f -exec chmod 0644 {} \; || exit 1
  chown -R 0:0 "$prepared" || exit 1
  chown 0:0 "$MODDIR" || exit 1
  chmod 0755 "$MODDIR" || exit 1

  # A predecessor identity handed over now counts as already present below.
  tm_handover_predecessor || exit 1
  wanted=1
  if [ -f "$TS_DIR/migration-required" ] || ! tm_require_identity_choice; then wanted=0; fi
  before=$(tm_current) || exit 1
  # KernelSU enables this module ID after customize; apply that intent now.
  rm -f "$MODDIR/disable" "$MODDIR/remove" || exit 1
  tm_activate "$BUNDLE_DIR" "$wanted" || exit 1
  selected=$(tm_current) || exit 1
  if [ "$wanted" = 1 ]; then
    if [ "$selected" = "$before" ]; then tm_runtime "$selected" start || exit 1; fi
    rm -f "$TS_DIR/migration-awaiting-enable" || exit 1
  else
    tm_runtime "$selected" stop || exit 1
    set_lifecycle migration-required || exit 1
  fi

  for name in control.sh action.sh service.sh uninstall.sh module.prop; do
    mv -f "$prepared/$name" "$MODDIR/$name" || exit 1
  done
  if [ -e "$MODDIR/webroot" ]; then
    mv "$MODDIR/webroot" "$prepared/previous-webroot" || exit 1
  fi
  mv "$prepared/webroot" "$MODDIR/webroot" || exit 1
  if [ "$wanted" = 1 ]; then
    printf '%s\n' '- Runtime installed and started; existing identity and configuration preserved'
  else
    printf '%s\n' '- Runtime and manager installed; service stopped pending explicit identity migration'
  fi
)

with_control_lock tm_install_complete
