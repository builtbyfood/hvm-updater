# Security

This plugin runs privileged commands on the Morpheus appliance and can put hypervisor hosts into maintenance mode. Treat access to the page (`admin-cm`) as root access to the appliance.

- Secrets (HPE token, API token, credential secrets) are never written to logs; the diag endpoint redacts them. Session tokens from the page login are memory-only.
- User-supplied paths are single-quoted into commands after being resolved to absolute paths on the plugin side.
- The runner executes as root via `systemd-run`; the script is staged from the plugin jar on each launch, not read from a user-writable location.
- The plugin calls the appliance's own API with the configured token and My HPE Software Center with the portal token. No other outbound connections.

To report a vulnerability, open a private security advisory on the repository rather than a public issue.
