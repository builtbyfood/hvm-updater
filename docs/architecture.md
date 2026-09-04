# Architecture

## Components

| Class | Role |
|---|---|
| `HvmUpdaterPlugin` | Plugin entry point. Registers the controller (`controllers.add`), declares settings, owns config (`config.json`), wires the services. Reload-safe: `onDestroy` stops the catalog timer. |
| `HvmUpdaterController` | `PluginController`: one page route (`/hvmUpdater`) that returns the whole UI as an inline HTML/JS page, plus JSON API routes under `/hvmUpdater/api/*`. All routes require `admin-cm`. |
| `FleetPage` | The JS/HTML for the hosts & VMs card, kept as a separate Groovy string literal so it doesn't live inside the controller's GString (no `$`/backslash escaping traps). |
| `Catalog` / `SwcClient` | HPE Software Center listing: entitlement search, per-product download listing, pre-signed URL fetch. Cached to `catalog.json`. Background timer (`refreshMinutes`). |
| `TokenManager` / `HpeLogin` / `CredentialResolver` | Portal token lifecycle: setting → Trust credential → session login (Okta IDX HTTP replay) → shell fallback (`hpe_swc.py login`). Session tokens are memory-only. |
| `TaskAgent` | Everything that runs *on* the appliance: directory checks, downloads (curl + Range), SHA-512 verify, file delete/move, storage report, runner staging, `systemd-run` launch, reading `upgrade.json`/`upgrade.log`. Uses `morpheus.executeCommandOnServer(server, cmd)` against the appliance's own ComputeServer. Each command echoes `RESULT_JSON={...}` which is parsed out of the result. |
| `UpgradeService` | The appliance upgrade orchestration on the plugin side: version resolution, pre-upgrade Morpheus backup (`/api/backups/{id}/execute` + poll), env file, launch, `state()` merge of plugin-side launch status and runner state. |
| `MorpheusApi` | Thin client for the appliance's REST API with the configured token: ping/version, servers, backups, clusters, processes, agent upgrade, maintenance mode. |
| `FleetService` | Host/VM agent-upgrade orchestration (parallel / rolling / manual), preflight, process watching, persistence to `fleet.json`, resume after plugin restart. |
| `Util` | Small helpers (`asInt`, `asBool`, size formatting). |

Runner-side, shipped in `src/main/resources/runner/` and staged onto the appliance by the plugin:

| File | Role |
|---|---|
| `hvm-updater-upgrade.sh` | The detached upgrade runner. Bash only, runs as root under `systemd-run`. |
| `hpe_swc.py` | Standalone/Morpheus-task Python tool for the Software Center (list/check/download/login). Also used as the last-resort token fallback. |

## Why `executeCommandOnServer` and `systemd-run`

The plugin needs root on the appliance for `dpkg`, `morpheus-ctl`, and writing under `/var/opt/morpheus`. Rather than SSH from the plugin JVM to itself, it runs commands the way Morpheus runs anything on a managed server: `executeCommandOnServer(server, cmd)` on the appliance's own ComputeServer record, with `sudo` inline. That gives the same user and privileges the platform already has and no extra credential handling for the local case.

The upgrade itself must outlive `morpheus-ctl stop morpheus-ui`, and any child of the UI process dies with it. So the plugin stages the runner script plus an env file and starts it as a **transient systemd unit**:

```
sudo systemd-run --unit=hvm-updater-upgrade --collect --property=WorkingDirectory=/var/opt/morpheus/hvm-updater \
     /var/opt/morpheus/hvm-updater/hvm-updater-upgrade.sh /var/opt/morpheus/hvm-updater/upgrade.env
```

From that point the plugin only ever *reads* two files the runner writes. No sockets, no callbacks — the UI can vanish and come back and nothing is lost.

## Data flow of an appliance upgrade

1. Page → `/api/upgrade/start?file=<name>` (only a file with a `.sha512ok` marker is accepted).
2. `UpgradeService.launch()` resolves from/to versions, writes `launchStatus` (`morpheus-backup`), and spawns a plugin thread.
3. Thread: if a backup job is configured → `POST /api/backups/{id}/execute`, poll `/api/backups/{id}` for a *new* result with `status=SUCCEEDED` (up to *Backup wait*). Failure → `launchStatus=failed`, nothing touched.
4. Thread: `TaskAgent.launch()` stages `hvm-updater-upgrade.sh` + `upgrade.env` under `/var/opt/morpheus/hvm-updater` (created via sudo) and runs `systemd-run`.
5. Runner writes `upgrade.json` (step, status, versions, error, timestamps) and appends `upgrade.log` at every step; optional sinks fire.
6. Page polls `/api/upgrade/state` every few seconds. `UpgradeService.state()` returns `launchStatus` until the runner's file is newer, then the runner state + last 200 log lines. When the UI is down the fetch fails and the page keeps retrying with backoff.
7. Runner ends at `done` (or `failed`), UI is back, page shows the final state.

## Files on the appliance

| Path | Owner | Purpose |
|---|---|---|
| `/var/opt/morpheus/morpheus-ui/hvm-updater/` | UI user | Plugin-private: `config.json` (page setup), `catalog.json` (cache), `fleet.json` (last fleet run). |
| `/var/opt/morpheus/hvm-updater/` | root (created with sudo) | Runner work dir: `hvm-updater-upgrade.sh`, `upgrade.env`, `upgrade.json`, `upgrade.log`. |
| `/var/opt/morpheus/hvm-updater/downloads/` | command user (chowned) | Default download dir. `<file>.part` while in flight, `<file>.sha512ok` marker once verified. |
| `/var/opt/morpheus/hvm-updater/backups/<timestamp>/` | root | Runner backups: `morpheus.sql.gz`, `morpheus.rb`, `morpheus-secrets.json`, version manifest, optional `morpheus-ui.tar.gz`. Pruned to *Backups to keep*. |

Nothing in `/tmp`. (0.9.0 briefly created `/tmp/~` because a literal `~` was single-quoted into a command; see CHANGELOG 0.9.1.)

## Threading and persistence

- Catalog refresh: one daemon timer.
- Downloads: one plugin thread per file runs `curl -fL --retry 3 -C -` on the appliance through `executeCommandOnServer`; the page polls `.part` size. Resumable because a re-click continues the same `.part`.
- Appliance upgrade: one plugin thread until the runner is launched, then nothing in the JVM.
- Fleet run: one daemon thread (`hvm-updater-fleet`); state mutated in place and persisted to `fleet.json` on every change; a plugin restart re-reads the file, cancels not-yet-started targets and resumes watching in-flight ones.
- All settings are read through closures at call time, so changing settings doesn't need a plugin reload.

## Security notes

- Secrets (`hpeToken`, `apiToken`, credential secrets) are never logged; the diag endpoint redacts them.
- Session HPE tokens obtained via the page login are held in memory only.
- The page is `admin-cm` only. Everything it can do — upgrade the appliance, put hosts in maintenance — is an admin action.
- Commands built for the appliance single-quote every user-supplied path (`sq()`); paths are resolved to absolute on the plugin side first.
