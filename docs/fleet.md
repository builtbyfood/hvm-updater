# Post-upgrade: hosts & VMs

After an appliance upgrade, the Morpheus agent on HVM hosts and managed VMs is typically behind. The *Post-upgrade — hosts & VMs* card drives the same **Upgrade Agent** action the Hosts page offers, but with selection, ordering, HA checks and per-target tracking.

## Inventory

`MorpheusApi.fleet()` pages through `GET /api/servers` and classifies:

- **Host** — `computeServerType.vmHypervisor == true` or the type code/name mentions *hypervisor*. Only **HVM/KVM/MVM** hosts (type code, or membership in a cluster from `/api/clusters`) are eligible; other hypervisor records (an ESXi host in inventory, for example) are shown as *not HVM — skipped* and disabled.
- **VM** — managed server with `agentInstalled` or an `agentVersion`.
- **Cluster** — `server.cluster` isn't populated for HVM hosts on 9.0.x, so membership is resolved from `GET /api/clusters` → `GET /api/clusters/{id}` `servers[]`.
- **VMs per host** — via `parentServer.id`; used for the on/off counts and for the maintenance-mode wait.
- **Platform** — Windows vs everything else (distro names like `ubuntu` fold into `linux`). Agent version lines differ per platform (Linux 3.x, Windows 2.x), so **behind** is computed against the newest agent *of the same platform* seen in the inventory. Upgrade one host first if everything is still on the old version.
- **Route** — how Morpheus will reach the guest: `agent` (agent reported within 10 min), `ssh creds` (`sshUsername` or a credential on the server), or `no route`. No-route VMs are listed but excluded from select-all: Morpheus hunts for a route for ~3 minutes and then fails them with `Unable to determine distro. Command output: null` (observed on the first real run).
- The appliance VM itself (from Setup) is tagged *this appliance* and gets a confirm nudge.

Selection is by visible, enabled, checked rows only; hiding a row with the *only show behind* filter unchecks it; the selection resets when a run starts; the confirm dialog lists the exact target names.

## Modes

### All at once
Fires `PUT /api/servers/{id}/upgrade` for every target, then watches them all. Equivalent to *select all → Upgrade Agent* (the UI's own bulk call is the internal `PUT /infrastructure/servers/upgrade` with an id list; same background job).

### Rolling with maintenance mode
Hosts strictly one at a time, then VMs in parallel.

Per host:
1. **preflight** — fresh inventory; see below. A block stops before anything is touched.
2. **maintenance** — `PUT /api/servers/{id}/maintenance`. Morpheus live-migrates running VMs; the run polls until `0` running VMs remain on the host (20 min budget).
3. **relocate** — only if *also move powered-off VMs* is ticked: `PUT /api/servers/{vmId}/placement` to a peer host for each powered-off VM. Body shape is an educated guess; failures are recorded per VM and the VM stays put.
4. **upgrade** + **wait** — as in parallel mode.
5. **leave-maintenance** — `PUT /api/servers/{id}/leave-maintenance` once the agent is back.

A failed host stops the run and is **left in maintenance mode** — the row says so — so you can look before anything else moves.

### Manual
Nothing is executed. The run records the selected targets and *Refresh from API* updates each row's agent version and last process while you do the upgrades in Morpheus.

## HA preflight (rolling)

For the host about to go out:

| Check | Result |
|---|---|
| No other host in the cluster | **block** |
| Peers' free memory (`maxMemory − usedMemory`, online peers) < this host's running-VM memory | **block** |
| One or more peers already down | warning |
| 3-node cluster (quorum drops to 2/3 while this host is out) | warning |
| Host has no cluster reference | warning (capacity checked across all hosts) |
| Peer memory stats unavailable | warning |

Warnings require *proceed despite preflight warnings*. *Check HA preflight for selected hosts* runs the same checks without starting anything.

## Watching a target

Every 8 s per open target: `GET /api/servers/{id}` (agent version) and `GET /api/processes` filtered to that server since the request time. A target is:

- **done** — agent version increased, or the process reports complete.
- **failed** — the process reports failed/error (message shown).
- **stalled** — no process and no version change after 4 min (the no-route case).
- **timeout** — the per-target wait budget (default 30 min) ran out.

The process's display name/message is shown in the row, e.g. `[morpheus-node] Linked morpheus-agent.jar -> morphd-3.3.0-all.jar … Installing systemd service`.

## Persistence and restarts

Run state lives in `fleet.json` under the plugin dir and is rewritten on every change. If the plugin restarts (installing a new build, `morpheus-ui` restart) mid-run: targets not yet sent are marked *cancelled*, targets already sent are **resumed** — Morpheus is still upgrading them — and the run finishes honestly. A host caught mid-rolling gets a note to check its maintenance state.

## Diagnosing field mismatches

`GET /plugin/hvmUpdater/api/fleet/probe?serverId=N` returns the trimmed raw server object, its key list, and recent processes for that server as this build returns them. Use it when clusters, parent hosts or processes don't line up.
