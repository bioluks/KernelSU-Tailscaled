#!/system/bin/sh

tm_engine_order() {
  tm_awk -v old="$(cat "$1/engine-version")" -v new="$(cat "$2/engine-version")" 'BEGIN {
    sub(/^v/, "", old); sub(/^v/, "", new)
    if (old !~ /^[0-9]+\.[0-9]+\.[0-9]+$/ || new !~ /^[0-9]+\.[0-9]+\.[0-9]+$/) exit 2
    split(old, a, "."); split(new, b, ".")
    for (i=1;i<=3;i++) { if (b[i]+0 < a[i]+0) exit 1; if (b[i]+0 > a[i]+0) exit 0 }
    exit 0
  }'
}

tm_activate() (
  candidate=$1
  wanted=$2
  old=$(tm_current) || exit 1
  new=$(tm_generation "$candidate") || exit 1
  [ "$new" != "$old" ] || exit 0
  # A compatibility bundle must not replace a newer separately installed engine.
  # Check selection in a subshell before stopping the current generation.
  BUNDLE_DIR="$new" sh -c '. "$BUNDLE_DIR/tailscale/scripts/engine-lib.sh"; engine_select' || exit 1
  if [ -n "$old" ]; then
    old_code=$(tm_prop "$old" versionCode)
    new_code=$(tm_prop "$new" versionCode)
    [ "$new_code" -gt "$old_code" ] || { tm_error version-not-increasing; exit 1; }
    tm_engine_order "$old" "$new" || { tm_error engine-downgrade-or-invalid-version; exit 1; }
    # Only the installed generation's stop needs the longer fallback. Start,
    # rollback, and engine recovery keep their normal shutdown policy.
    handoff_timeout=${TM_STOP_TIMEOUT:-6}
    if ! TM_STOP_TIMEOUT="$handoff_timeout" tm_runtime "$old" stop; then
      sleep 0.5
      TM_STOP_TIMEOUT="$handoff_timeout" tm_runtime "$old" stop || { tm_error current-stop-failed; exit 1; }
    fi
    tm_pointer previous "$old" || exit 1
  fi
  tm_pointer current "$new" || exit 1
  if [ "$wanted" = 1 ] && ! tm_runtime "$new" start; then
    printf '%s\n' candidate-start-failed > "$TS_DIR/run/activation-failure"
    tm_runtime "$new" stop || { tm_error candidate-stop-failed; exit 1; }
    printf 'failed\n' > "$TS_DIR/run/lifecycle"
    printf '1\n' > "$TS_DIR/run/wanted"
    if [ -n "$old" ] && cmp -s "$old/engine-version" "$new/engine-version"; then
      if tm_pointer current "$old"; then
        tm_runtime "$old" start || printf '%s\n' rollback-start-failed > "$TS_DIR/run/activation-failure"
      fi
    fi
    tm_error candidate-start-failed
    exit 1
  fi
  rm -f "$TS_DIR/run/activation-failure"
)

tm_prepare_initial() {
  existing=$(tm_current) || return 1
  [ -n "$existing" ] || tm_activate "$BUNDLE_DIR" 0
}

tm_prepare_manager() {
  existing=$(tm_current) || return 1
  if [ -z "$existing" ]; then
    tm_activate "$MODDIR" 1
    return $?
  fi
  if [ ! -f "$MODDIR/bundle.sha256" ]; then
    tm_verify_bundle "$existing" || return 1
    tm_runtime "$existing" start
    return $?
  fi
  tm_verify_bundle "$MODDIR" || return 1
  manager_code=$(tm_prop "$MODDIR" versionCode)
  active_code=$(tm_prop "$existing" versionCode)
  if [ "$manager_code" -gt "$active_code" ]; then
    tm_activate "$MODDIR" 1
  elif [ "$manager_code" -eq "$active_code" ]; then
    cmp -s "$MODDIR/bundle.sha256" "$existing/bundle.sha256" || { tm_error equal-version-different-bundle; return 1; }
    tm_runtime "$existing" start
  else
    tm_runtime "$existing" start
  fi
}

tm_apply_staged() {
  [ -d "$TM_STAGED_DIR" ] || { tm_error no-staged-update; return 1; }
  tm_require_identity_choice || return 1
  wanted=0
  [ "$(cat "$TS_DIR/run/wanted" 2>/dev/null)" != 1 ] || wanted=1
  [ ! -f "$MODDIR/disable" ] && [ ! -f "$MODDIR/remove" ] || wanted=0
  tm_activate "$TM_STAGED_DIR" "$wanted"
}
