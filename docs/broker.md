# SSH Broker

## Purpose

The broker prevents the web application from reading target SSH keys or
selecting arbitrary network destinations. It runs behind a dedicated OpenSSH
daemon bound to `127.0.0.1:10022`.

The gateway-to-broker key is not a target credential. The broker sshd always
executes `/usr/local/libexec/browser-ssh-broker`, regardless of the requested
remote command.

## Protocol

The broker client sends one versioned command through `SSH_ORIGINAL_COMMAND`:

```text
v1 <target-id> <mode> <max-bytes> <base64url-path-or-dash>
```

Allowed modes:

- `terminal`
- `upload`
- `inspect`
- `download-file`
- `download-archive`

Target IDs use at most 64 letters, numbers, dots, underscores or hyphens. Paths
are base64url-encoded to avoid shell tokenization and are decoded as data. The
broker rejects unknown fields, extra fields, invalid sizes and paths outside
the configured operation root.

## Target Configuration

Target files contain only recognized `KEY=value` records. They are parsed
without `source` or `eval`.

```text
TARGET_HOST=192.0.2.10
TARGET_PORT=22
TARGET_USER=operator
TARGET_KEY=/etc/browser-ssh-broker/keys/default
TARGET_KNOWN_HOSTS=/etc/browser-ssh-broker/known-hosts/default
TARGET_REMOTE_HOME=/home/operator
TARGET_UPLOAD_ROOT=/home/operator/uploads
TARGET_DOWNLOAD_ROOT=/home/operator
```

The key and known-hosts paths must remain under their corresponding broker
configuration directories. Upload and download roots must remain inside the
declared remote home.

## OS Separation

| Component | Account | Credential access |
|---|---|---|
| Gateway | `browser-ssh` | Broker client key only |
| Broker sshd master | root | Broker host key and authorized gateway key |
| Broker forced command | `browser-ssh-broker` | Target registry and target keys |
| Tunnel | deployment choice | Tunnel token only |

The gateway systemd sandbox marks `/etc/browser-ssh-broker` inaccessible. The
broker is a separate sshd child and does not inherit the gateway mount
namespace.

## Provider Boundary

The static target registry is the initial provider. Dynamic inventory systems
such as OpenStack should resolve their instance authorization and address
before creating or selecting a broker target. They must not bypass the broker
by passing raw host or key parameters to the gateway.
