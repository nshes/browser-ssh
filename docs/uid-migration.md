# Service Account Migration

The default accounts are:

| Account | Default UID/GID | Purpose |
|---|---:|---|
| `browser-ssh` | `31002` | Web gateway and constrained broker client key |
| `browser-ssh-broker` | `31003` | Target registry and target private keys |

These are defaults, not globally reserved IDs. On an existing host, inspect the
local account database first and override `BROKER_UID` and `BROKER_GID` with the
same unused numeric value when `31003` is already assigned.

UIDs are installer parameters because a host may already allocate these
numbers. Inspect the host before applying:

```bash
getent passwd browser-ssh browser-ssh-broker
getent group browser-ssh browser-ssh-broker
find /home/browser-ssh /home/browser-ssh-broker \
  /etc/browser-ssh-gateway /etc/browser-ssh-broker \
  -xdev -printf '%U:%G %p\n' 2>/dev/null | sort -u
```

Stop the gateway, broker and Tunnel before changing IDs. Update file ownership
while services are stopped, then verify every private key and environment file
before restarting. Never use a UID already mapped to a container volume or
database process.

Expected ownership:

```text
/etc/browser-ssh-gateway/browser-ssh.env   root:browser-ssh 0640
/etc/browser-ssh-gateway/broker-client-key root:browser-ssh 0640
/etc/browser-ssh-broker/                   root:browser-ssh-broker 0750
/etc/browser-ssh-broker/keys/*             root:browser-ssh-broker 0640
```

After migration:

```bash
systemctl show browser-ssh-gateway browser-ssh-broker -p User -p Group
sudo -u browser-ssh test ! -r /etc/browser-ssh-broker/targets/default.conf
sudo -u browser-ssh-broker test -r /etc/browser-ssh-broker/targets/default.conf
```
