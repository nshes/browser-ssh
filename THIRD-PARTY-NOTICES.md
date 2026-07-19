# Third-Party Notices

Browser SSH includes or depends on third-party software. This document
is informational and does not replace the license text distributed by each
project.

## Vendored Browser Components

| Component | Version | Upstream package | License notice |
| --- | --- | --- | --- |
| `@xterm/xterm` | 5.5.0 | `https://www.npmjs.com/package/@xterm/xterm/v/5.5.0` | `browser-ssh-gateway/src/main/resources/static/vendor/xterm/LICENSE.xterm` |
| `@xterm/addon-fit` | 0.10.0 | `https://www.npmjs.com/package/@xterm/addon-fit/v/0.10.0` | `browser-ssh-gateway/src/main/resources/static/vendor/xterm/LICENSE.addon-fit` |

The vendored JavaScript and CSS files are distributed with the corresponding
MIT license files above. Their checksums and verification procedure are recorded
in [Dependency Provenance](docs/dependency-provenance.md).

## Bundled JVM Runtime Components

The Spring Boot executable JAR contains Maven dependencies as nested JARs. Some
upstream JARs do not carry complete license or notice files internally. Do not
rely on nested metadata alone when redistributing a built executable JAR; review
the resolved dependency tree and include every license, notice and source offer
required by the selected upstream licenses.

| Component family | License reported by upstream Maven metadata |
| --- | --- |
| Spring Boot, Spring Framework and Spring Security | Apache License 2.0 |
| Apache Tomcat Embed and Log4j-to-SLF4J | Apache License 2.0 |
| Jackson | Apache License 2.0 |
| Micrometer | Apache License 2.0 |
| Nimbus JOSE+JWT and JCIP Annotations | Apache License 2.0 |
| Hibernate Validator, JBoss Logging and ClassMate | Apache License 2.0 |
| Jakarta Validation API | Apache License 2.0 |
| Jakarta Annotations API | EPL 2.0 or GPL 2.0 with Classpath Exception |
| SnakeYAML | Apache License 2.0 |
| JetBrains pty4j 0.13.12 | Eclipse Public License 1.0 |
| Kotlin standard library and JetBrains annotations | Apache License 2.0 |
| Java Native Access (JNA) | Apache License 2.0 or LGPL 2.1-or-later |
| SLF4J | MIT |
| Logback | EPL 2.0 or LGPL 2.1 |
| HdrHistogram | BSD 2-Clause or Public Domain (CC0) |
| LatencyUtils | Public Domain (CC0) |

Exact artifact names and versions are resolved from the module POMs. Review them for
each release with:

```bash
mvn dependency:tree -Dscope=runtime
mvn org.codehaus.mojo:license-maven-plugin:2.7.0:add-third-party
```

Test-only libraries are included in the generated Maven report but are not
packaged in the application runtime JAR.

The repository does not vendor pty4j source or binaries. Maven resolves pty4j
from Maven Central during a build. Anyone distributing a prebuilt executable JAR
must satisfy EPL 1.0 section 3, including making the corresponding pty4j source
available in a reasonable manner and including the applicable license terms.
The upstream project and source are available at
`https://github.com/JetBrains/pty4j`.

## Required or Optional External Programs

The following programs are invoked or documented as integrations but are not
redistributed by this repository:

- OpenJDK
- OpenSSH client and server
- GNU Bash and coreutils (`realpath` and `base64`)
- Info-ZIP `zip`
- OpenSSL during broker account installation
- Maven and Docker for the documented containerized build
- Cloudflare `cloudflared`, Tunnel and Access

Those programs remain governed by their own licenses and service terms.
