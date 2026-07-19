# Embedding Browser SSH Core

`browser-ssh-core` contains no target inventory, user database, SSH key lookup,
network namespace, or identity-provider integration. An embedding application
must authorize a target before starting its process and must keep private key
material outside browser-controlled input.

## Source Pinning

Until a release repository is configured, pin this repository as a Git
submodule and include the core module in the embedding application's Maven
reactor:

```ini
[submodule "vendor/browser-ssh"]
    path = vendor/browser-ssh
    url = ../browser-ssh.git
```

```xml
<modules>
    <module>vendor/browser-ssh/browser-ssh-core</module>
    <module>application</module>
</modules>
```

The application module then depends on the matching pinned version:

```xml
<dependency>
    <groupId>kim.mingyo</groupId>
    <artifactId>browser-ssh-core</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

Use a release version instead of `SNAPSHOT` after tagged artifacts are
published. Keep the submodule commit fixed; do not build production directly
from a moving branch.

## Shared Boundary

`TerminalProtocol` parses the browser message envelope and applies the shared
terminal dimension bounds. `TerminalSessionState` owns input-rate, idle, and
hard-duration tracking. The embedding application still owns the WebSocket,
process lifecycle, audit records, and user-visible error policy.

```java
TerminalClientMessage message = TerminalProtocol.parse(objectMapper, payload);
TerminalSessionState state = TerminalSessionState.open(
        Duration.ofMinutes(15),
        Duration.ofHours(2),
        300
);
```

Mark activity only for terminal input, terminal output, and accepted resize
messages. Heartbeats keep the WebSocket transport alive but must not reset the
terminal idle timer.

## Provider Responsibilities

Before a terminal process starts, the provider integration must:

- authenticate the browser identity;
- authorize that identity for the requested target;
- resolve target IDs without accepting host, user, port, key path, or arbitrary
  SSH options from the browser;
- pin the target host key;
- constrain upload and download roots independently of browser input;
- terminate the process when the WebSocket closes or a session policy expires;
- sanitize infrastructure errors before returning them to the browser.

Provider-specific code should not be added to `browser-ssh-core`.
