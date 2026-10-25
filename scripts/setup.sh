#!/usr/bin/env bash
# Guided setup: pick what to do, it connects to the SHIELD, backs it up and does it.
#
#   scripts/setup.sh                  menu
#   scripts/setup.sh --full           debloat + Zen Home launcher
#   scripts/setup.sh --debloat-only   debloat, keep the stock Google TV home screen
#   scripts/setup.sh --restore        undo everything (add --uninstall-zen to remove Zen Home too)
#
# Other options: --address <SHIELD IP>, --keep <package>[,<package>] (leave these enabled),
#                --yes (don't ask), --dry-run (change nothing).
set -euo pipefail
DIR="$(cd "$(dirname "$0")" && pwd)"
. "$DIR/common.sh"

MODE=""; RESTORE_ARGS=()
while [ $# -gt 0 ]; do
  case "$1" in
    --full) MODE=full ;;
    --debloat-only) MODE=debloat ;;
    --restore|--undo) MODE=restore ;;
    --uninstall-zen) RESTORE_ARGS+=(--uninstall-zen) ;;
    --address) export ANDROID_SERIAL="$2"; shift ;;
    --keep) export SHIELD_KEEP="${SHIELD_KEEP:+$SHIELD_KEEP,}$(echo "$2" | tr ' ' ',')"; shift ;;
    --yes|-y) export ASSUME_YES=1 ;;
    --dry-run) export DRY_RUN=1 ;;
    -h|--help) sed -n '2,11p' "$0"; exit 0 ;;
    *) die "unknown option: $1 (see --help)" ;;
  esac
  shift
done

say ""
say "${B}SHIELD TV debloat + Zen Home${N}"
say "Nothing is uninstalled: apps are only disabled, and option 3 undoes everything."
[ "${DRY_RUN:-0}" = 1 ] && warn "Dry run: nothing on the SHIELD will be changed."

if [ -z "$MODE" ]; then
  interactive || die "no terminal to ask on. Pass --full, --debloat-only or --restore."
  cat <<EOF

  1) Debloat + Zen Home launcher   ${B}(recommended)${N}
     Removes telemetry, promo apps and the ad-filled home screen; Zen Home replaces it.
  2) Debloat only
     Removes telemetry and promo apps, keeps the stock Google TV home screen.
  3) Undo everything
     Puts the SHIELD back the way it was before.

EOF
  while [ -z "$MODE" ]; do
    ask "Choose 1, 2 or 3 (q to quit):"
    case "$REPLY" in
      1) MODE=full ;; 2) MODE=debloat ;; 3) MODE=restore ;;
      q|Q) exit 0 ;;
    esac
  done
fi

step "Connecting to the SHIELD"
shield_connect

if [ "$MODE" = restore ]; then
  if [ ${#RESTORE_ARGS[@]} = 0 ] && [ "${ASSUME_YES:-0}" != 1 ] \
     && adb shell pm path com.zen.home </dev/null >/dev/null 2>&1; then
    ask "Also uninstall Zen Home? [y/N]" n
    case "$REPLY" in [Yy]*) RESTORE_ARGS+=(--uninstall-zen) ;; esac
  fi
  step "Restoring"
  "$DIR/restore.sh" ${RESTORE_ARGS[@]+"${RESTORE_ARGS[@]}"}
  say ""; ok "Done. The SHIELD is back to stock."
  exit 0
fi

say ""
say "This will disable these packages (skipping any your SHIELD doesn't have):"
grep -vE '^\s*(#|$)' "$DIR/debloat-list.txt" | while read -r line; do
  [ "$MODE" = debloat ] && [[ "$line" == *"[phase2]"* ]] && continue
  pkg="$(echo "${line%%#*}" | xargs)"
  kept "$pkg" && continue
  printf '  %-42s %s\n' "$pkg" "$(echo "${line#*#}" | sed 's/\[phase2\] *//' | xargs)"
done
[ "$MODE" = full ] && say "...then install Zen Home and make it the home screen."
say ""
confirm "Go ahead?" || exit 0

if [ ! -f "$ROOT/backup/LATEST" ]; then
  step "Backing up the SHIELD's current state"
  if [ "${DRY_RUN:-0}" = 1 ]; then say "  [dry-run] scripts/backup.sh"; else "$DIR/backup.sh"; fi
fi

if [ "$MODE" = full ]; then
  "$DIR/apply.sh"
else
  step "Disabling telemetry and bloat"
  "$DIR/debloat.sh" phase1
fi

say ""
ok "Done!"
[ "$MODE" = full ] && say "Press Home on the remote. Hold OK on an app to move, dock or hide it."
say "To undo later, run this again and choose 3, or: $ROOT/scripts/restore.sh"
say "Keep the $ROOT/backup folder: it records how your SHIELD was before."
