# Changelog

## 0.9.6 — 2026-09-04
- Fleet: a plugin restart (e.g. installing a new plugin build) mid-run used to freeze the run as *interrupted* with rows stuck on *running*. Morpheus keeps upgrading the targets already sent regardless, so the plugin now resumes watching those from the API and finishes the run; not-yet-started targets are marked *cancelled*; a host caught mid-rolling gets a note to check its maintenance-mode state.
- Fleet: `serverOs.platform` can be a distro name (`ubuntu`) — every non-Windows platform now folds into `linux` for the per-platform "newest agent" comparison.

## 0.9.5 — 2026-09-04
- Fix: a VM that had just been upgraded could be re-sent on the next run. The page remembered its checkbox across reloads, the "only show behind" filter hid the row once it was current, and hidden-but-checked rows were still collected. Now only visible, enabled, checked rows are sent; hiding a row clears its selection; the selection is reset when a run starts; and the confirm dialog lists the exact target names.

## 0.9.4 — 2026-09-04
- Fix (encoding): em-dashes and middle-dots in the page came out as `?`/`�`. Two causes: Gradle compiled the UTF-8 Groovy source with the Windows default charset (now `UTF-8` forced for Java/Groovy compile), and Tomcat decodes query params as ISO-8859-1 so saved names like the backup label were stored mangled (params are now re-decoded as UTF-8 when that happened). Config/catalog/fleet JSON files are read and written with explicit UTF-8.
- Fleet: hosts showed `(no cluster)` — `server.cluster` isn't populated for HVM hosts on 9.0.x; membership now comes from `/api/clusters/{id}` `servers[]`.
- Fleet: non-HVM hypervisor records (e.g. an ESXi host in inventory) were listed as hosts; they now show *not HVM — skipped*, disabled, and excluded from select-all.
- Fleet: *behind* is computed per platform — Windows agents (2.x line) are no longer flagged behind against Linux agents (3.x). The header shows the newest agent per platform.
- Fleet: the appliance VM itself is tagged *this appliance* and gets a confirm nudge when included.

## 0.9.3 — 2026-09-04
- Fleet: VM rows now show the **route** Morpheus will use for the agent upgrade — *agent* (agent reported in the last 10 min), *ssh creds* (`sshUsername`/credential saved), or *no route*. No-route VMs are listed but excluded from select-all, with a banner explaining that Morpheus hunts for a route for ~3 min and then fails them with `Unable to determine distro` (observed on the first real host/VM run: one VM out of the batch, the rest unaffected).
- Fleet: a target with no upgrade process and an unchanged agent version 4 min after the request is marked **stalled** with that hint, instead of waiting the whole per-target budget. Stalled counts as failed for the run outcome and stops a rolling run.

## 0.9.2 — 2026-09-04
- **Post-upgrade — hosts & VMs card.** After the appliance is upgraded (first real run: 9.0.1 → 9.0.2, 41 min end to end), upgrade the agent on HVM hosts and managed VMs through the appliance API — the same `PUT /api/servers/{id}/upgrade` the Hosts page "Upgrade Agent" action makes. Inventory groups hosts by cluster with status, agent version (flagged *behind* vs the newest agent seen), VMs on/off and memory; VMs list filters to "behind" by default.
- Three modes, chosen per run: **All at once** (what select-all → Upgrade Agent does); **Rolling with maintenance mode** — one host at a time: HA preflight (other cluster hosts online, their free memory ≥ this host's running-VM memory, 3-node quorum warning) → enter maintenance (Morpheus live-migrates running VMs, the run waits until none remain) → optional relocate of powered-off VMs to a peer → upgrade → wait for the process/agent version → leave maintenance; a failure stops the run and leaves the host in maintenance for inspection; **Manual** — nothing executed, the card tracks the selected targets from the API while you do them in Morpheus.
- Every target row shows status, step, agent before → after and the latest matching process from `/api/processes`; runs persist to `fleet.json` so a reload shows the last run, and a plugin restart marks an in-flight run *interrupted* rather than pretending it's still going.
- Fix: the pre-launch Morpheus backup phase was invisible — `runLaunch` now publishes `morpheus-backup` / `preflight` (launching) state to the Upgrade card until the runner takes over, and a failed backup or failed `systemd-run` shows as *failed* with the reason instead of silently aborting.
- Runner: after `morpheus-ctl reconfigure` on 9.0.x `morpheus-ui` wasn't up yet when checked; now waits up to 90 s for runsvdir to start it before forcing `morpheus-ctl restart morpheus-ui`.
- Diag: `/plugin/hvmUpdater/api/fleet/probe?serverId=N` returns the trimmed raw server object + recent processes — use it if host/cluster/process fields don't line up on your build.

## 0.9.1 — 2026-09-03
- Fix: a `~` or `$HOME` in the Download directory setting (and the old blank fallback of `$HOME/hvm-updater`) was single-quoted into the appliance command and never expanded — the command context runs with cwd `/tmp` and no reliable `$HOME`, so the plugin created a directory literally named `~` on tmpfs (`/tmp/~`) and downloaded there. Clean up with `sudo rm -rf '/tmp/~'` (quoted).
- Download directory now resolves plugin-side to an absolute path before it is ever quoted: blank → `/var/opt/morpheus/hvm-updater/downloads` (new persistent default under the runner work dir); `~`, `~/x`, `$HOME/x`, `${HOME}/x` → the command user's real home looked up once on the appliance; relative paths → default with a warning.
- The download dir is created with sudo when the parent is root-owned and chowned to the command user, so the new default works out of the box.
- Removed the stray `registerProvider(controller)` call: a `PluginController` is registered via `controllers.add` only (matches the official docs, HPE plugin samples, and other community controller plugins). Its only effect was the "HVM Updater Controller — UNKNOWN" row under Providers in plugin settings.
- Staged card shows a red warning when the effective dir is on tmpfs (`/tmp`, `/dev/shm`, `/run`, or df reports tmpfs/ramfs) or when the configured value was overridden.

## 0.9.0 — 2026-09-03
- Staged on appliance card now shows the RESOLVED absolute download path (readlink -f, no more ambiguous ~) and how much disk the download dir is using.
- Per-file Delete (with confirm) removes the package + its .sha512ok/.sig/.err/.part sidecars.
- Per-file Move… offloads a package to another location: pick from a dropdown of Morpheus storage buckets + datastores (only those with an appliance-writable path are usable) or type any path; the plugin validates the destination is writable before moving.

## 0.8.3 — 2026-09-03
- Fix: backup status polling hit /api/backup-results which returns 404 on this build (log spam + Run backup now would never resolve). Now reads the backup object lastResult first (present on all builds), then falls back to history/backup-results only if needed.

## 0.8.2 — 2026-09-03
- Setup card now has a standalone Run backup now button: runs the selected Morpheus backup on its own and shows running → succeeded/failed, so you can confirm the backup is done BEFORE committing to the upgrade (previously the backup only ran as the first hidden step of the upgrade). Backup runs async and the page polls its status.

## 0.8.1 — 2026-09-03
- Fix: 0.8.0 broke the page with a JS SyntaxError (blank cards, stuck on Loading) — newlines in the new upgrade-confirm strings were written as \n, which Groovy's triple-quoted string collapses into real newlines, breaking the JS string literal. Escaped as \\n, and removed a leftover watchCmds() reference. Verified against the actual Groovy-rendered output this time.

## 0.8.0 — 2026-09-03
- Watch the upgrade from outside the browser. On launch the page shows copy-paste SSH commands (journalctl -u hvm-updater-upgrade -f, tail upgrade.log, morpheus-ctl tail morpheus-ui), pre-filled with the appliance host.
- Multi-sink progress from the runner at every step: (1) systemd journal + log file for SSH; (2) generic webhook — POSTs the status JSON to a URL you set, survives the UI outage; (3) mirror the status file + log to a path you set (NFS/CIFS mount or datastore) so another box can tail it while the Manager is down; (4) bookend Morpheus notifications (started/completed/failed only — the API is down mid-reconfigure). All sinks are fire-and-forget and never block the upgrade.
- Staged upgrade confirmation spells out the exact steps and downtime before you commit.
- New settings: Progress webhook URL, Mirror status file to path, Send Morpheus notifications.

## 0.7.3 — 2026-09-03
- Runner output now tees to the systemd journal as well as upgrade.log, so you can watch the upgrade live from an SSH shell with `sudo journalctl -u hvm-updater-upgrade -f` (survives the UI restart). The Runner log card on the page shows the exact SSH commands to tail.

## 0.7.2 — 2026-09-02
- Fix: executeCommandOnServer returns an RxJava Single/Observable on this build, not a ServiceResponse — the plugin was reading the wrapper's toString() ("no RESULT_JSON ...rxjava..."). Now unwraps with blockingGet() before extracting the command output.

## 0.7.1 — 2026-09-02
- Fix: applianceServerId lives in the plugin's page config (Setup card), not the OptionType settings — TaskAgent was reading the wrong place and always saw 'server not set' even with super-earth selected. Now reads config first, and tries multiple computeServer lookup variants, surfacing the real error if the lookup fails.

## 0.7.0 — 2026-09-02
- Dropped the Morpheus-task approach entirely (kept hitting task-type/execute binding walls). Per Travis's suggestion, privileged appliance work now runs via morpheus.executeCommandOnServer(server, cmd) directly against the appliance ComputeServer (server id from Setup) — the documented plugin-api path, sudo inline, no task object, no /api/tasks, no localScript type. Each command emits RESULT_JSON={...} which the plugin parses.
- Agent actions (dircheck, version, stagelist, download+verify, upstatus, launch) are now inline bash command blocks. The detached systemd runner still owns the upgrade and survives the UI restart.
- Requires the appliance VM selected in Setup (applianceServerId) so the plugin knows which ComputeServer to target.

## 0.6.4 — 2026-09-02
- Fix (the real one): agent task payload confirmed against /api/tasks on the appliance — script belongs in file.content with taskType localScript + executeTarget local (my 0.6.0 shape was right; 0.6.3's taskOptions.script was wrong). The actual bug was PUT-updating an existing task corrupting its structure (script ended up in taskType.content, breaking local execution). Now delete + recreate the task on change instead of PUT.

## 0.6.3 — 2026-09-02
- Fix: agent task create used the wrong body field. The Local Shell Script type ('localScript') wants the script in taskOptions.script — not a file/content block — which is why local execution was rejected. Sending script + sudo now. (Confirmed against /api/task-types on the appliance.)

## 0.6.2 — 2026-09-02
- Fix: appliance version showed 'unknown' after the 0.6.0 rewrite (only /api/ping was tried and it lacks the field). Added a 'version' agent action that reads /opt/morpheus/version-manifest.txt on the appliance, with /api/ping as fallback.

## 0.6.1 — 2026-09-02
- Fix: agent task was created as taskType 'localScript' which rejected local execution ("local execution not supported for task type"). Correct type for a bash script is 'shell' with executeTarget 'local' and sudo; also send shell=/bin/bash and a fileName.

## 0.6.0 — 2026-09-02
- Major rearchitecture: the plugin no longer does SSH at all. All privileged appliance work (dircheck, download+verify, stage list, backup launch, systemd-run of the detached runner, reading upgrade.json) runs through a Morpheus LOCAL task the plugin auto-creates/updates via the API (hvm-updater-agent). The task runs in appliance/user context — the context that can sudo locally and read Trust credentials — solving the plugin-cannot-decrypt-credentials wall and the per-call SSH reconnect slowness in one move.
- Task execution prefers in-process morpheus.executeLocalTask (synchronous TaskResult.output) with automatic REST /api/tasks/{id}/execute + poll fallback if that service is not present.
- Agent inputs pass via customOptions template substitution; each action prints RESULT_JSON={...}.
- The detached runner still does stop-ui/dpkg/reconfigure/wait-ui and survives the UI restart.
- Removed the JSch dependency and the in-plugin ApplianceDownloader/SSH code. Manual upload is now copy-the-file-into-the-download-dir + Refresh (direct multipart upload disabled in task mode).

## 0.5.3 — 2026-09-02
- Credential decrypt: AccountCredential.data is transient and only populated after a decrypt call, which is why get/list returned an empty secret. The resolver now calls accountCredential.loadCredentialConfig({type:local, credential:{id}}) to pull the decrypted username/password/privateKey for a Trust credential referenced by ID. This is the supported in-process path (REST /api/credentials/{id} masks by design).

## 0.5.2 — 2026-09-02
- Fix: 0.5.1 removed the `Map s` binding that catalog.start() still referenced (MissingPropertyException at load). Restored it.

## 0.5.1 — 2026-09-02
- Fix: 0.5.0 failed to load — initialize() still constructed the old DownloadManager into the ApplianceDownloader field (cast error at load). Removed the duplicate construction and deleted the unused DownloadManager class.

## 0.5.0 — 2026-09-02
- Downloads now happen ON THE APPLIANCE, not in the plugin JVM: the plugin SSHes in and curls the pre-signed CDN URL directly to the download directory, verifying SHA-512 there (sha512sum). Nothing multi-GB touches the plugin filesystem. Progress is polled from the growing .part file.
- Download directory is now an appliance path; blank defaults to the SSH user home (~/hvm-updater). A remote NFS/CIFS mount works, with an inline note that install is slower by ~the file size. Dir is validated (test -w) and free space shown.
- Manual upload box: supply your own .deb/ISO with no HPE login; verified green against the catalog sha when the filename matches, amber unverified otherwise. If the multipart POST is blocked by the CSRF filter, the page tells you to SFTP to the shown directory instead.
- Staged list + upgrade read from the appliance directory over SSH, so what you see is what installs. Plugin metadata (config, catalog cache) still lives beside other plugins under morpheus-ui.

## 0.4.6 — 2026-09-02
- Downloads now create the destination directory on demand (and fail with a clear message if they cannot), so a Download directory setting pointing at a not-yet-created path no longer fails with a .part "No such file or directory".

## 0.4.5 — 2026-09-02
- Login errors now carry the step trace all the way to the Sign in box and diag, so a failed replay shows exactly which IDX hop broke (and network/egress errors surface distinctly).

## 0.4.4 — 2026-09-02
- HpeLogin rewritten from the confirmed capture: `interact` is a form POST (SPA client
  0oa1keb1lcphJkrtc358, scope "openid profile email", redirect_uri auth.hpe.com/hpe/cf/, PKCE S256)
  returning interactionHandle; `introspect` sends {interactionHandle}; `identify` sends
  {identifier, stateHandle}; `challenge/answer` sends {credentials:{passcode}, stateHandle}. Plugin
  generates its own PKCE + cookie jar, so the whole login runs headless from the appliance. The
  in-browser Sign in to HPE box drives this; only the resulting token is kept in memory.

## 0.4.3 — 2026-09-02
- HPE auth is now interactive and store-free: a "Sign in to HPE" box on the plugin page. Username
  and password are sent as request headers (not query string, not body) on a same-origin GET, so
  they never hit access logs or the CSRF filter, and only the resulting x-authtoken is kept — in
  memory. Sign out clears it. Removed the stored HPE-credential setting (nothing on this build hands
  a plugin a decrypted secret anyway); the manual token field remains only as an emergency fallback.
- On a 401 the dead token is cleared so the page prompts to sign in again.

## 0.4.2 — 2026-09-02
- Diag: `?cypher=secret/<key>` probes each key spelling and read signature, reporting value length only (never the value) to pin down why a Cypher key reads empty.

## 0.4.1 — 2026-09-02
- Fix: Cypher read used a non-existent `read(String)` signature. Now tries `getAt(key)` / `cypher.get(key)` (sync and async) in order — the working overloads the runtime reported. Applies to both `secret/` HPE and SSH credentials.

## 0.4.0 — 2026-09-02
- Automatic HPE token retrieval. New setting **HPE credential** (Trust ID/name or `secret/<key>`):
  when set, the plugin mints and refreshes the x-authtoken itself and the manual token becomes a
  fallback. On any 401 from the portal the catalog now runs auto-login and retries once.
- `HpeLogin`: Okta IDX HTTP replay (interact → introspect → identify → answer) with a cookie jar,
  following the portal redirect chain to `?authToken=<uuid>`; reads remediation hrefs from each
  response rather than hardcoding. Okta issuer `auth.hpe.com/oauth2/aus43pf0g8mvh4ntv357`.
- `TokenManager`: cached token, priority cached → manual setting → auto-login; HTTP replay first,
  then a shell fallback that runs the bundled `hpe_swc.py login --headless` on the appliance over
  SSH (HPE creds passed via env, never on the command line).
- Diag: `?login=1` tests auto-login end to end; `token` block shows cache/source/last error.

## 0.3.4 — 2026-09-02
- HPE token: extract the UUID from whatever was pasted (quotes, whitespace, header prefix) instead of rejecting anything that is not exactly 36 chars. Diag shows raw vs used length.

## 0.3.3 — 2026-09-02
- Fix: page actions (refresh, download, config save, upgrade start) were POSTs and tripped the Morpheus CSRF filter (403 /error/invalid-csrf). All plugin API calls are now GETs with query parameters; HTTP errors surface as messages instead of JSON parse exceptions.

## 0.3.2 — 2026-09-02
- Credential: plugin-api `get(id)` is broken upstream (declared Maybe, returns Single); use `listById`/`list(DataQuery)` instead. Added Cypher path: set `SSH credential` to `secret/<key>` holding `user:password` or JSON.

## 0.3.1 — 2026-09-02
- Fix: credential resolver never reached REST (local variable shadowed the method) and used the wrong plugin-api facade; now `morpheus.async.accountCredential.get(id).blockingGet()` with sync fallback, then REST.

## 0.3.0 — 2026-09-02
- SSH access now comes from a Morpheus **Trust credential** referenced by ID (or name) in the
  `SSH credential` setting — same pattern as the migration tasks and the VMRC plugin. Resolved
  via plugin-api `accountCredential.get()` first, REST `/api/credentials/{id}` second; the first
  lookup that returns an unmasked password or private key wins. Password is piped to `sudo -S`;
  key-only credentials assume NOPASSWD sudo. Private keys are used in-memory (no file on disk).
- `/api/diag` reports credential resolution (source, user, secret length — never values) and
  `?probe=1` runs an ssh + sudo `id -un` round-trip.
- Settings: `SSH password` and `sudo needs the password` removed; `SSH user` / key path remain
  as a fallback when no credential is set.

## 0.2.4 — 2026-09-02
- SSH password setting renamed internally to `sshSecret` — Morpheus appears to scrub settings keys containing "password", which is why it always came back null.
- Plugin-side files (catalog cache, config, downloads) now default to `/var/opt/morpheus/morpheus-ui/hvm-updater`, owned by the UI user out of the box. No appliance prep required: the launch creates `/var/opt/morpheus/hvm-updater` for the root-written runner files via `sudo -S`.

## 0.2.3 — 2026-09-02
- Fix: checkbox settings arrive as real Booleans (not `on`); `sudo needs the password` and `Include morpheus-ui dir` were being ignored.

## 0.2.2 — 2026-09-02
- Diagnostics: `/plugin/hvmUpdater/api/diag` shows which setting keys the plugin actually receives (values redacted); catalog error now lists the keys too.

## 0.2.1 — 2026-09-02
- Fix: build broke on invalid Groovy escapes (`\(`, `\'`, regex literals) inside the page JS string. Regexes replaced with plain string ops.

## 0.2.0 — 2026-09-02
- Pre-upgrade protection now uses the appliance's own Morpheus **backup job** instead of a
  VM snapshot: Setup card on the page picks the appliance VM (it's just a managed server)
  and one of its backups; the launch runs `POST /api/backups/{id}/execute`, waits for a
  new successful result, then stages the runner. New first step `morpheus-backup`.
- `MorpheusApi` REST helper (servers, backups, execute, results). Settings: `Backup wait`,
  `Include morpheus-ui dir` toggle; snapshot settings removed.
- Launch is asynchronous; failures before the runner starts are recorded in the state file.

## 0.1.1 — 2026-09-02
- Fix: plugin failed to load on first install (`GroovyCastException` in `initialize()` when
  `refreshMinutes` was unset). Added `asInt()` helper; also applied to `sshPort`.

## 0.1.0 — 2026-09-02
- Initial scaffold: HPE Software Center catalog polling, verified downloads, detached
  `systemd-run` upgrade runner with resumable state, status page at `/plugin/hvmUpdater`.
