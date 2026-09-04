# Troubleshooting

Collected from real runs. Logs: plugin side in `morpheus-ui` (`sudo morpheus-ctl tail morpheus-ui | grep hvm-updater`), runner side in `journalctl -u hvm-updater-upgrade` and `/var/opt/morpheus/hvm-updater/upgrade.log`.

## Page / plugin

| Symptom | Cause | Fix |
|---|---|---|
| Page 404 / not found | Plugin not loaded or route registration failed. | Administration → Plugins: is it enabled? `grep -i hvmupdater` the UI log for a Groovy exception at load. |
| *HPE token missing or expired* | No token and not signed in this session. | Sign in on the page, or paste `localStorage.authToken` into the setting. |
| Sign-in fails at *password step* with `challenge-authenticator` remaining | Wrong password (Okta hasn't accepted the credential). | Retry. If it persists with the right password, MFA may now be enforced on the account — use the token setting. |
| *Download directory … is on tmpfs* warning | Dir resolves under `/tmp`, `/dev/shm`, `/run`. | Set an absolute persistent path; default is `/var/opt/morpheus/hvm-updater/downloads`. |
| A directory literally named `~` appeared in `/tmp` (0.9.0) | `~` was single-quoted into the command. | `sudo rm -rf '/tmp/~'` (quoted). Fixed in 0.9.1. |
| Em-dashes show as `?` or `�` | Built with a cp1252 default charset, or a value saved through a Latin-1-decoded param before 0.9.4. | Rebuild with 0.9.4+ (encoding forced); re-save the Setup card once to rewrite stored labels. |
| Plugin settings show an `UNKNOWN` provider row | Pre-0.9.1 registered the controller as a provider. | Cosmetic; gone in 0.9.1+. |
| `/api/diag?probe=1` → command probe fails | The appliance isn't a managed server, or the command user lacks passwordless sudo. | Check the server record under Infrastructure; ensure `sudo -n true` works for that user. |

## Appliance upgrade

| Symptom | Cause | Fix |
|---|---|---|
| Upgrade card idle after clicking *Upgrade appliance…* (pre-0.9.2) | Backup phase wasn't surfaced. | 0.9.2+ shows `morpheus-backup`; on older builds check the UI log for `hvm-updater: backup`. |
| *Morpheus backup failed* / launch never happens | Backup job errored or exceeded *Backup wait*. | Run it standalone from Setup, fix the job, retry. Nothing on the appliance was touched. |
| *launch failed* | `systemd-run` refused (unit already exists, sudo). | `systemctl status hvm-updater-upgrade`; if a stale unit exists, `sudo systemctl reset-failed hvm-updater-upgrade`. |
| Runner stuck at `reconfigure` | Chef run ~10 min on 9.0.x is normal. | Watch `journalctl -u hvm-updater-upgrade -f`. If it fails, the runner restarts `morpheus-runsvdir` and reconfigures again. |
| `morpheus-ui not running after reconfigure; starting it` | 9.0.x reconfigure returns before the UI is up. | Informational; 0.9.2+ waits 90 s before forcing a restart. |
| `wait-ui` times out but the UI is actually up | TLS/hostname on `127.0.0.1`, or slow JVM start. | Increase *Wait for UI*; the version check still records the manifest version. |
| Page doesn't resume after the UI is back | Browser tab backoff. | Click *Reload*; state is in `upgrade.json`, nothing is lost. |
| `buildVersion` empty in `/api/ping` | Observed on 9.0.2. | Version comes from the manifest instead; nothing to do. |

## Fleet (hosts & VMs)

| Symptom | Cause | Fix |
|---|---|---|
| Hosts show `(no cluster)` | `server.cluster` not populated (pre-0.9.4 mapping). | 0.9.4+ resolves via `/api/clusters/{id}`. If still empty, `/api/fleet/probe?serverId=` and open an issue with the output. |
| ESXi host listed as a host | Type name contains *hypervisor*. | 0.9.4+ shows *not HVM — skipped*. |
| Windows VM flagged *behind* against Linux 3.x | Pre-0.9.4 single version line. | 0.9.4+ compares per platform. |
| A VM upgraded twice | Hidden-but-checked row (pre-0.9.5). | 0.9.5+ sends only visible checked rows and clears selection at start. |
| Target *stalled*; `morpheus-ui` shows `Unable to determine distro. Command output: null` | Morpheus found no route to the guest: agent not reporting, no saved SSH/WinRM creds, or powered off. | Fix agent connectivity or add credentials; the *route* column predicts this. |
| Run says *interrupted* / rows stuck *running* (pre-0.9.6) | Plugin restarted mid-run. | 0.9.6+ resumes watching. Morpheus completed the upgrades regardless — check Processes. |
| Rolling: host left in maintenance mode | A step failed after `enter maintenance`, by design. | Inspect, then Host → Actions → Leave maintenance mode. |
| Rolling: powered-off relocate errors | Placement API body shape not confirmed on this build. | Leave *also move powered-off VMs* unticked; they stay on the host through the upgrade harmlessly. |
