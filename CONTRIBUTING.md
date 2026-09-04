# Contributing

Issues and PRs welcome — especially reports from appliance versions or topologies the plugin hasn't seen (HA appliances, external DB, other clusters/hypervisors).

## Reporting a problem

Include:
- Morpheus/VME version (`/api/ping` or the Appliance card) and plugin version.
- What you clicked and what the page showed.
- `sudo morpheus-ctl tail morpheus-ui | grep -i hvm-updater` around the time.
- For upgrade issues: `journalctl -u hvm-updater-upgrade --no-pager` and `/var/opt/morpheus/hvm-updater/upgrade.json`.
- For fleet field problems: `/plugin/hvmUpdater/api/fleet/probe?serverId=<id>` output (redact anything you don't want public).
- Never paste tokens, passwords or `morpheus-secrets.json` contents. The diag endpoint redacts secrets; the log does not always.

## Development

```bash
./gradlew clean shadowJar
# upload build/libs/hvm-updater-<version>-all.jar via Administration → Plugins
```

- JDK 17, Groovy 3.0.13, morpheus-plugin-api 1.3.3 (pinned in `gradle.properties`).
- Source is UTF-8; the build forces the compiler encoding.
- Anything that runs on the appliance lives in `TaskAgent` (commands must end with `RESULT_JSON={…}`) or in `src/main/resources/runner/`.
- The page is inline HTML/JS inside a Groovy GString. Inside it: escape `$` as `\$`, use `\\n` for newlines, no regex literals. The fleet card's JS is a plain triple-single-quoted literal in `FleetPage.groovy` with **no backslashes or `$`** — keep it that way; `node --check` it after edits (the CI workflow does).
- Bump `version` in `build.gradle` and `Plugin-Version` in `plugin.properties` together, and add a CHANGELOG entry.

## Testing on a real appliance

There is no simulator. Order of risk:
1. `/api/diag?probe=1` — no side effects.
2. Catalog refresh + download — writes only to the download dir.
3. `Run backup now` — executes a Morpheus backup job.
4. Runner dry run by hand with a copy of the env file pointing `PACKAGE` at the *currently installed* version (dpkg treats it as a reinstall; still stops/reconfigures the UI).
5. Fleet in *manual* mode, then *all at once* on one VM, then *rolling* on one host in a cluster with spare capacity.
6. The real upgrade.
