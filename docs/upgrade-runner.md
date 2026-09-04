# The upgrade runner (`hvm-updater-upgrade.sh`)

Bash, root, no Python/jq. Started by the plugin as `systemd-run --unit=hvm-updater-upgrade`. Reads parameters from the env file passed as `$1`, records progress in `$WORK_DIR/upgrade.json` and `$WORK_DIR/upgrade.log`, and tees everything to stdout so `journalctl -u hvm-updater-upgrade -f` streams it live.

## Env file (`upgrade.env`)

Written by the plugin at launch:

| Var | Meaning |
|---|---|
| `PACKAGE` | Absolute path of the verified `.deb`. |
| `FROM_VERSION` / `TO_VERSION` | For the log, notifications and the final check. |
| `WORK_DIR` | Default `/var/opt/morpheus/hvm-updater`. |
| `BACKUP_DIR`, `BACKUP_KEEP` | Runner backup location and retention (default `$WORK_DIR/backups`, 3). |
| `BACKUP_DB` (1) | `mysqldump` the embedded DB. Skipped with a log line if the embedded mysqldump or secrets file is missing (external DB). |
| `BACKUP_UI_DIR` (0) | Also tar `/var/opt/morpheus/morpheus-ui` (large). |
| `APT_UPDATE` (0) | Run `apt-get update` in preflight. |
| `WAIT_MINUTES` (25) | How long `wait-ui` polls `/api/ping`. |
| `MORPHEUS_BACKUP_NOTE` | Text from the plugin about the Morpheus backup that preceded the run. |
| `WEBHOOK_URL`, `MIRROR_PATH`, `NOTIFY_URL`, `NOTIFY_TOKEN` | Optional sinks. |

## Steps

Index 0 (`morpheus-backup`) is performed by the plugin before launch and is present in `STEPS` only so the page shows one consistent list.

| # | Step | What happens |
|---|---|---|
| 1 | `preflight` | Package exists and is a `.deb`; `dpkg-deb --info` parses; `morpheus-ctl` present; disk space in `/var/opt/morpheus` and `/`; optional `apt-get update`. Records the currently installed version. |
| 2 | `backup` | `mysqldump` via `/opt/morpheus/embedded/mysql/bin/mysqldump` using the password from `/etc/morpheus/morpheus-secrets.json` → `morpheus.sql.gz`; copies `morpheus.rb`, the secrets file, version manifest; optional UI dir tar. Prunes old backups to `BACKUP_KEEP`. |
| 3 | `stop-ui` | `morpheus-ctl stop morpheus-ui`. From here the page can't reach the plugin. |
| 4 | `install` | `dpkg -i "$PACKAGE"`. |
| 5 | `reconfigure` | `morpheus-ctl reconfigure`. On failure: `systemctl restart morpheus-runsvdir`, reconfigure again, `morpheus-ctl restart morpheus-ui` (the documented recovery). On 9.0.x reconfigure returns before `morpheus-ui` is back up; the runner waits up to 90 s for runsvdir to start it and only then forces a restart. |
| 6 | `wait-ui` | Polls `https://127.0.0.1/api/ping` (insecure TLS) until it answers or `WAIT_MINUTES` elapse. Compares the installed version (manifest, since `/api/ping` returned an empty `buildVersion` on 9.0.2) with `TO_VERSION`. |
| 7 | `done` | Final state, notification, exit 0. |

A failure at any step sets `status=failed`, `error=<reason>`, sends the *failed* notification and exits non-zero. The systemd unit is transient (`--collect`), so `systemctl status hvm-updater-upgrade` only shows it while running; use `journalctl -u hvm-updater-upgrade` afterwards.

## State file (`upgrade.json`)

```json
{"status":"running","step":"reconfigure","stepIndex":5,
 "steps":["morpheus-backup","preflight","backup","stop-ui","install","reconfigure","wait-ui","done"],
 "fromVersion":"9.0.1","toVersion":"9.0.2",
 "packageName":"HPE_Morpheus_Enterprise_Appliance_9.0.2-1_debian_x86_64_S6E64-11234.deb",
 "backupPath":"/var/opt/morpheus/hvm-updater/backups/20260904-031622",
 "morpheusBackup":"backup 8 succeeded (result 527)","error":"",
 "started":1788491782000,"updated":1788492934000,"log":"/var/opt/morpheus/hvm-updater/upgrade.log"}
```

Written atomically (`.tmp` + `mv`). The plugin reads it with `sudo cat` and ships the last 200 log lines base64-encoded to the page.

## Sinks

- **Webhook** — POSTs `upgrade.json` (`Content-Type: application/json`) at every state write. Fire-and-forget; failures logged, never fatal.
- **Mirror** — `cp upgrade.json "$MIRROR_PATH"` at every state write. Point it at an NFS/CIFS mount to watch from another machine.
- **Morpheus notifications** — `POST $NOTIFY_URL/api/notifications` with `NOTIFY_TOKEN`, at start, done and failed only (the API is down in between).

## Running it by hand

Useful before trusting the plugin with it, or to re-run after fixing something:

```bash
sudo bash /var/opt/morpheus/hvm-updater/hvm-updater-upgrade.sh /var/opt/morpheus/hvm-updater/upgrade.env
```

The plugin stages both files on the first launch; or copy the script from `src/main/resources/runner/` and write a three-line env (`PACKAGE`, `FROM_VERSION`, `TO_VERSION`).

## Timings observed (9.0.1 → 9.0.2, homelab appliance VM)

| Phase | Duration |
|---|---|
| Morpheus backup (KVM incremental image, CIFS target) | ~12 min |
| preflight + backup (mysqldump) | ~1 min |
| dpkg -i | ~2 min |
| reconfigure (Chef, 675 resources) | ~10 min |
| wait-ui (UI JVM start) | ~3 min |
| **Total** | **~41 min**, UI unavailable for ~16 min |
