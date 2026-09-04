#!/bin/bash
# hvm-updater-upgrade.sh — detached Morpheus / VME appliance upgrade runner.
#
# Started by the hvm-updater plugin as a transient systemd unit so it survives
# `morpheus-ctl stop morpheus-ui`. Reads its parameters from the env file passed
# as $1 and records progress in $WORK_DIR/upgrade.json + upgrade.log. The plugin
# only ever reads those two files; it never talks to this script directly.
#
# Steps: morpheus-backup (done by the plugin) -> preflight -> backup -> stop-ui -> install -> reconfigure -> wait-ui -> done
#
# Runs as root (via sudo from systemd-run). Bash only — no python, no jq.

set -u
ENV_FILE="${1:?usage: $0 upgrade.env}"
# shellcheck disable=SC1090
. "$ENV_FILE"

: "${WORK_DIR:=/var/opt/morpheus/hvm-updater}"
: "${BACKUP_DIR:=$WORK_DIR/backups}"
: "${BACKUP_KEEP:=3}"
: "${BACKUP_DB:=1}"
: "${BACKUP_UI_DIR:=0}"
: "${APT_UPDATE:=0}"
: "${WAIT_MINUTES:=25}"
: "${FROM_VERSION:=}"
: "${TO_VERSION:=}"
: "${MORPHEUS_BACKUP_NOTE:=not requested}"
: "${WEBHOOK_URL:=}"
: "${MIRROR_PATH:=}"
: "${NOTIFY_URL:=}"
: "${NOTIFY_TOKEN:=}"

STATE="$WORK_DIR/upgrade.json"
LOG="$WORK_DIR/upgrade.log"
STEPS=(morpheus-backup preflight backup stop-ui install reconfigure wait-ui done)
STARTED=$(date +%s%3N)
STEP_INDEX=0
ERROR=""
BACKUP_PATH=""

mkdir -p "$WORK_DIR" "$BACKUP_DIR"
# send everything to the log file AND to stdout so `journalctl -u hvm-updater-upgrade -f` streams it live
exec > >(tee -a "$LOG") 2>&1

log() { printf '%s %s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$*"; }

json_escape() { local s=${1//\\/\\\\}; s=${s//\"/\\\"}; s=${s//$'\n'/\\n}; printf '%s' "$s"; }

STEPS_JSON=$(printf '"%s",' "${STEPS[@]}"); STEPS_JSON="[${STEPS_JSON%,}]"

write_state() {  # $1=status $2=step
  local now; now=$(date +%s%3N)
  cat >"$STATE.tmp" <<EOF
{"status":"$1","step":"$2","stepIndex":$STEP_INDEX,"steps":$STEPS_JSON,
 "fromVersion":"$(json_escape "$FROM_VERSION")","toVersion":"$(json_escape "$TO_VERSION")",
 "packageName":"$(json_escape "$(basename "$PACKAGE")")","backupPath":"$(json_escape "$BACKUP_PATH")",
 "morpheusBackup":"$(json_escape "$MORPHEUS_BACKUP_NOTE")","error":"$(json_escape "$ERROR")",
 "started":$STARTED,"updated":$now,"log":"$LOG"}
EOF
  mv -f "$STATE.tmp" "$STATE"
  emit_sinks "$1" "$2"
}

# fan-out to optional sinks. Never blocks the upgrade (short timeouts, backgrounded).
emit_sinks() {  # $1=status $2=step
  local payload
  payload=$(cat "$STATE" 2>/dev/null)
  # mirror the status file to an external path (NFS/CIFS/datastore) for outside watching
  if [[ -n "$MIRROR_PATH" ]]; then
    ( mkdir -p "$(dirname "$MIRROR_PATH")" 2>/dev/null; printf '%s\n' "$payload" > "$MIRROR_PATH" 2>/dev/null
      cp -f "$LOG" "${MIRROR_PATH%.json}.log" 2>/dev/null ) &
  fi
  # generic webhook: POST the status JSON
  if [[ -n "$WEBHOOK_URL" ]]; then
    ( curl -fsS --max-time 8 -X POST -H 'Content-Type: application/json' -d "$payload" "$WEBHOOK_URL" >/dev/null 2>&1 ) &
  fi
}

# Morpheus notification — bookend only (API is down mid-upgrade). $1=title $2=message $3=level
notify() {
  [[ -n "$NOTIFY_URL" && -n "$NOTIFY_TOKEN" ]] || return 0
  ( curl -fsSk --max-time 8 -X POST -H "Authorization: Bearer $NOTIFY_TOKEN" -H 'Content-Type: application/json' \
      -d "{\"notification\":{\"name\":\"$(json_escape "$1")\",\"message\":\"$(json_escape "$2")\",\"level\":\"${3:-info}\"}}" \
      "$NOTIFY_URL/api/notifications" >/dev/null 2>&1 ) &
}

step() { STEP_INDEX=$1; write_state running "${STEPS[$1]}"; log "=== step $1: ${STEPS[$1]} ==="; }

fail() {
  ERROR="$1"
  log "FAILED at ${STEPS[$STEP_INDEX]}: $ERROR"
  write_state failed "${STEPS[$STEP_INDEX]}"
  notify "HVM Updater: upgrade FAILED" "Failed at ${STEPS[$STEP_INDEX]}: $ERROR" "critical"
  exit 1
}

installed_version() {
  awk '/^(morpheus-appliance|morpheus-vm-essentials|hpe)/ {print $2; exit}' /opt/morpheus/version-manifest.txt 2>/dev/null
}

ui_up() {
  local body; body=$(curl -ksS --max-time 5 https://127.0.0.1/api/ping 2>/dev/null) || return 1
  [[ "$body" == *'"success":true'* ]] || return 1
  printf '%s' "$body"
}

log "hvm-updater runner starting: $(basename "$PACKAGE") ($FROM_VERSION -> $TO_VERSION) pid $$"
notify "HVM Updater: upgrade started" "$(basename "$PACKAGE"): $FROM_VERSION -> $TO_VERSION" "info"
log "morpheus backup (run by plugin before launch): $MORPHEUS_BACKUP_NOTE"

# ── 1 preflight ────────────────────────────────────────────────────────────
step 1
[[ $EUID -eq 0 ]] || fail "runner is not root (uid $EUID) — check sudo configuration"
[[ -f "$PACKAGE" ]] || fail "package not found: $PACKAGE"
command -v morpheus-ctl >/dev/null || fail "morpheus-ctl not found in PATH"
[[ "$PACKAGE" == *.deb ]] || fail "only .deb packages are supported by this runner (got $(basename "$PACKAGE"))"
dpkg-deb -W "$PACKAGE" >/dev/null 2>&1 || fail "dpkg-deb cannot read $PACKAGE"
log "package: $(dpkg-deb -W "$PACKAGE")"
[[ -z "$FROM_VERSION" ]] && FROM_VERSION=$(installed_version)
log "installed: ${FROM_VERSION:-unknown}"
for mnt in / /var /opt; do
  avail=$(df -BG --output=avail "$mnt" 2>/dev/null | tail -1 | tr -dc '0-9')
  [[ -n "$avail" ]] && log "free space on $mnt: ${avail}G"
done
avail=$(df -BG --output=avail /var 2>/dev/null | tail -1 | tr -dc '0-9')
[[ -n "$avail" && "$avail" -lt 8 ]] && fail "less than 8G free on /var ($avail G)"
if [[ "$APT_UPDATE" == 1 ]]; then
  log "apt-get update"; DEBIAN_FRONTEND=noninteractive apt-get update -q || log "apt-get update returned $? (continuing)"
fi

# ── 2 backup ───────────────────────────────────────────────────────────────
step 2
if [[ "$BACKUP_DB" == 1 ]]; then
  BACKUP_PATH="$BACKUP_DIR/$(date +%Y%m%d-%H%M%S)-${FROM_VERSION:-unknown}"
  mkdir -p "$BACKUP_PATH"
  MYSQLDUMP=/opt/morpheus/embedded/mysql/bin/mysqldump
  SECRETS=/etc/morpheus/morpheus-secrets.json
  if [[ -x "$MYSQLDUMP" && -f "$SECRETS" ]]; then
    DBPASS=$(grep -o '"morpheus_password"[[:space:]]*:[[:space:]]*"[^"]*"' "$SECRETS" | head -1 | sed 's/.*:[[:space:]]*"//; s/"$//')
    [[ -n "$DBPASS" ]] || fail "could not read mysql password from $SECRETS"
    log "mysqldump -> $BACKUP_PATH/morpheus.sql.gz"
    if ! MYSQL_PWD="$DBPASS" "$MYSQLDUMP" -u morpheus -h 127.0.0.1 --single-transaction --quick morpheus | gzip >"$BACKUP_PATH/morpheus.sql.gz"; then
      fail "mysqldump failed"
    fi
  else
    log "embedded mysqldump or secrets file not found — skipping DB dump (external DB?)"
  fi
  cp -a /etc/morpheus/morpheus.rb "$BACKUP_PATH/" 2>/dev/null || log "no morpheus.rb to copy"
  cp -a /etc/morpheus/morpheus-secrets.json "$BACKUP_PATH/" 2>/dev/null || true
  chmod 600 "$BACKUP_PATH"/morpheus-secrets.json 2>/dev/null || true
  cp -a /opt/morpheus/version-manifest.txt "$BACKUP_PATH/" 2>/dev/null || true
  if [[ "$BACKUP_UI_DIR" == 1 ]]; then
    log "tar /var/opt/morpheus/morpheus-ui (may be large)"
    tar -C /var/opt/morpheus -czf "$BACKUP_PATH/morpheus-ui.tar.gz" morpheus-ui || log "morpheus-ui tar returned $?"
  fi
  log "backup written: $(du -sh "$BACKUP_PATH" | cut -f1) at $BACKUP_PATH"
  # retention
  ls -1dt "$BACKUP_DIR"/*/ 2>/dev/null | tail -n +$((BACKUP_KEEP + 1)) | while read -r old; do
    log "pruning old backup $old"; rm -rf "$old"
  done
  write_state running backup
else
  log "DB backup disabled by request"
fi

# ── 3 stop-ui ──────────────────────────────────────────────────────────────
step 3
morpheus-ctl stop morpheus-ui || fail "morpheus-ctl stop morpheus-ui failed"
sleep 5

# ── 4 install ──────────────────────────────────────────────────────────────
step 4
if ! DEBIAN_FRONTEND=noninteractive dpkg -i "$PACKAGE"; then
  log "dpkg -i failed; attempting to restore the UI service"
  morpheus-ctl start morpheus-ui || true
  fail "dpkg -i failed (see log)"
fi
SUPP="${PACKAGE%.deb}.supplemental.deb"
if [[ -f "$SUPP" ]]; then
  log "installing supplemental package $(basename "$SUPP")"
  DEBIAN_FRONTEND=noninteractive dpkg -i "$SUPP" || fail "supplemental dpkg -i failed"
fi

# ── 5 reconfigure ──────────────────────────────────────────────────────────
step 5
if ! morpheus-ctl reconfigure; then
  # Documented recovery path: restart runsvdir, reconfigure again, then restart the UI.
  log "reconfigure failed — restarting morpheus-runsvdir and retrying once"
  systemctl restart morpheus-runsvdir; sleep 15
  morpheus-ctl reconfigure || fail "reconfigure failed twice"
fi
# reconfigure on 9.0.x can return before morpheus-ui is (re)started; give runsvdir up to 90s before forcing it
ui_up() { morpheus-ctl status 2>/dev/null | grep -q '^run: morpheus-ui'; }
if ! ui_up; then
  for i in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18; do sleep 5; ui_up && break; done
fi
if ui_up; then log "morpheus-ui is running after reconfigure"; else log "morpheus-ui still not running 90s after reconfigure; starting it"; morpheus-ctl restart morpheus-ui || true; fi

# ── 6 wait-ui ──────────────────────────────────────────────────────────────
step 6
deadline=$(( $(date +%s) + WAIT_MINUTES * 60 ))
while :; do
  if body=$(ui_up); then
    log "UI is answering: $body"
    break
  fi
  [[ $(date +%s) -lt $deadline ]] || fail "UI did not come up within $WAIT_MINUTES minutes (check: morpheus-ctl tail morpheus-ui)"
  sleep 15
done
NEW_VERSION=$(installed_version)
log "installed version now: ${NEW_VERSION:-unknown}"
if [[ -n "$TO_VERSION" && -n "$NEW_VERSION" && "$NEW_VERSION" != "$TO_VERSION"* && "$TO_VERSION" != "$NEW_VERSION"* ]]; then
  log "WARNING: expected $TO_VERSION but manifest reports $NEW_VERSION"
fi

# ── 7 done ─────────────────────────────────────────────────────────────────
step 7
TO_VERSION="${NEW_VERSION:-$TO_VERSION}"
write_state done done
notify "HVM Updater: upgrade complete" "$FROM_VERSION -> $TO_VERSION on $(hostname)" "info"
log "upgrade complete: $FROM_VERSION -> $TO_VERSION"
exit 0
