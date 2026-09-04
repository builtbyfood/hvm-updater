# Plugin endpoints

All under `https://<appliance>/plugin/hvmUpdater`, all require `admin-cm`, all return JSON unless noted. Parameters are query-string (the page sends UTF-8; the controller re-decodes Latin-1-mangled params).

| Route | Purpose |
|---|---|
| `/` | The page (HTML). |
| `/api/overview` | Appliance version, catalog summary, download dir check, staged files, upgrade/backup state, token state. The page's initial load. |
| `/api/refresh` | Force a catalog refetch from HPE. |
| `/api/download?product=&file=&sig=1` | Start/resume a download on the appliance. |
| `/api/downloads` | Progress of in-flight and staged downloads. |
| `/api/upgrade/start?file=` | Launch the appliance upgrade (file must be staged/verified). |
| `/api/upgrade/state` | Current state: plugin-side launch status until the runner owns `upgrade.json`, then runner state + log tail. |
| `/api/servers` | Managed servers (for Setup). |
| `/api/backups?serverId=` | Backup jobs for a server (for Setup). |
| `/api/config` (`?save=1&…`) | Read/write page setup. |
| `/api/backup/run` / `/api/backup/state` | Run the configured Morpheus backup on its own and poll it. |
| `/api/file/delete?name=` / `/api/file/move?name=&to=` | Manage staged files (with `.part`/`.sig`/marker). |
| `/api/storage` | Disk usage report for the download dir's filesystem. |
| `/api/dircheck` | Resolved download dir, fs type, free space, warnings. |
| `/api/upload` | Register a manually placed file (verify + mark). |
| `/api/diag?probe=1&cypher=secret/x` | Redacted config, credential resolution, command probe (`sudo -n true`), optional Cypher probe. |
| `/api/hpeLogin` (user/password) / `/api/hpeSignout` | Session login to the portal / drop the session token. |
| `/api/fleet` | Inventory (hosts, VMs, newest agent per platform, notes) + last run. |
| `/api/fleet/preflight?hostIds=1,2` | HA preflight per host without starting. |
| `/api/fleet/start?mode=parallel|rolling|manual&hostIds=&vmIds=&relocateOff=1&ignoreWarnings=1&waitMinutes=30` | Start a fleet run. |
| `/api/fleet/state` (`?refresh=1` in manual mode) | Current run. |
| `/api/fleet/cancel` | Stop after the current target. |
| `/api/fleet/probe?serverId=` | Raw (trimmed) server object + recent processes for field-name debugging. |

Errors come back as `{"error": "…"}` with HTTP 200 so the page can show them inline.
