---
name: Bug report
about: Something didn't work on your appliance
---

**Morpheus/VME version:**  
**Plugin version:**  
**Topology:** single appliance / HA · embedded DB / external · hosts: HVM / other

**What I did**

**What the page showed**

**Logs** (redact tokens/secrets)
```
sudo morpheus-ctl tail morpheus-ui | grep -i hvm-updater
journalctl -u hvm-updater-upgrade --no-pager      # upgrade issues
/plugin/hvmUpdater/api/fleet/probe?serverId=N     # fleet field issues
```
