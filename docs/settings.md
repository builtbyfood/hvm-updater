# Settings

Administration → Integrations → Plugins → HVM Updater. Settings are read at call time; no plugin reload needed after a change.

## Portal

| Setting | Default | Notes |
|---|---|---|
| HPE token (x-authtoken) | — | `localStorage.authToken` from a signed-in Software Center tab, or a Trust credential reference. Optional if you use *Sign in to HPE* on the page. |
| Products | blank | Comma-separated `productNumber`s. Blank = every product the account can see. |
| Catalog refresh interval (minutes) | 360 | Background listing refresh. |

## Appliance commands

| Setting | Default | Notes |
|---|---|---|
| Download directory | `/var/opt/morpheus/hvm-updater/downloads` | Absolute path on the appliance. `~`, `$HOME`, `${HOME}` are expanded to the command user's home; relative paths fall back to the default with a warning; `/tmp`, `/dev/shm`, `/run` or any tmpfs shows a red warning on the page. Created with sudo and chowned to the command user on first use. |
| SSH host / port | `127.0.0.1` / 22 | The appliance to upgrade. `127.0.0.1` = this appliance (commands go through `executeCommandOnServer` on its own server record). |
| SSH credential (Trust ID or name) | — | Used by the token shell fallback and the diag probe. Hover the edit pencil under Infrastructure → Trust → Credentials to see the ID in the URL. |
| SSH user / private key path | — | Fallbacks when no credential is set. |

## Upgrade runner

| Setting | Default | Notes |
|---|---|---|
| Backup directory | `/var/opt/morpheus/hvm-updater/backups` | Runner backups (DB dump, config, secrets, manifest). |
| Backups to keep | 3 | Retention by count. |
| Wait for UI (minutes) | 25 | `wait-ui` budget for `/api/ping` after reconfigure. |
| Include /var/opt/morpheus/morpheus-ui in runner backup | off | Adds a tar of the UI dir (large). |

## Appliance API

| Setting | Default | Notes |
|---|---|---|
| Appliance URL | `https://127.0.0.1` | Base for `/api/*`. |
| Morpheus API token | — | Admin user token. Used for version, backups, servers, clusters, processes, agent upgrades, maintenance mode. |
| Backup wait (minutes) | 90 | How long the pre-upgrade Morpheus backup may take before the launch aborts. |

## Sinks

| Setting | Default | Notes |
|---|---|---|
| Progress webhook URL | — | POSTs `upgrade.json` at every state change. |
| Mirror status file to path | — | Copies `upgrade.json` there at every state change (NFS/CIFS). |
| Send Morpheus notifications | off | Start/finish/failed via `/api/notifications`. |

## Page setup (stored in `config.json`, not in plugin settings)

*Setup — pre-upgrade Morpheus backup*: the appliance's own server record (`applianceServerId`) and one of its backup jobs (`backupId`, `backupName`). Chosen from lists the page fetches via the API. Save once after upgrading past 0.9.3 to rewrite the label with correct encoding.
