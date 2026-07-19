# Dependency Provenance

This file records the origin of third-party files committed directly to the
repository. It supports reproducible review and does not replace an upstream
license.

## First-Party Material

The Java, JavaScript, shell, HTML, CSS and documentation outside the vendored
directory were developed for Browser SSH under the direction of the project
maintainer. AI coding agents assisted with implementation and review as disclosed
in the README. The maintainer reviewed the resulting repository and its live
deployment before the initial public release.

`browser-ssh-gateway/src/main/resources/static/favicon.svg` is an original,
project-specific geometric terminal mark. It was not copied from an icon library
or third-party product asset.

## Vendored xterm Files

The following files are unmodified files from official npm packages published by
the xterm.js project. The matching upstream MIT licenses are stored beside them.

| Repository file | Official package file | SHA-256 |
| --- | --- | --- |
| `vendor/xterm/xterm.js` | `@xterm/xterm@5.5.0/package/lib/xterm.js` | `1f991ac3b4b283ebf96e60ae23a00a52765dd3a2e46fa6fdda9f1aab032f7495` |
| `vendor/xterm/xterm.css` | `@xterm/xterm@5.5.0/package/css/xterm.css` | `ba8e6985669488981ccf40c0cefe3aba80722cb6c92de7ad628b0bd717faf2b6` |
| `vendor/xterm/LICENSE.xterm` | `@xterm/xterm@5.5.0/package/LICENSE` | `b569f629d00f2626a8100df2a1798210535621e42164dfd426a6fe5aac7b0ccd` |
| `vendor/xterm/addon-fit.js` | `@xterm/addon-fit@0.10.0/package/lib/addon-fit.js` | `bdaefa370b1bfc42ee88d46fe6072400902a4d4b2d45cd93438dda9b23c97089` |
| `vendor/xterm/LICENSE.addon-fit` | `@xterm/addon-fit@0.10.0/package/LICENSE` | `e256f01188af527e4d06d21d06fbf785ae9c50d4b328bf03cbe0ba7f0aa4228f` |

Official package pages:

- `https://www.npmjs.com/package/@xterm/xterm/v/5.5.0`
- `https://www.npmjs.com/package/@xterm/addon-fit/v/0.10.0`
- `https://github.com/xtermjs/xterm.js`

To reproduce the comparison, download the two package archives from the npm
registry, hash the listed files without transforming them and compare those
hashes with `sha256sum` against the repository files.

## Maven Dependencies

Maven dependencies are declared by coordinates rather than copied into the Git
source tree. The build currently uses `org.jetbrains.pty4j:pty4j:0.13.12`; its
official Maven metadata declares EPL 1.0 and points to
`https://github.com/JetBrains/pty4j` for source. Review the complete resolved
runtime tree before publishing executable artifacts because transitive versions
can change when the parent dependency management changes.

The parent POM overrides Jackson to `2.21.5` because the `2.21.4` version in
Spring Boot 3.5.15 has known security advisories. This is a targeted security
override rather than a general policy of independently pinning every transitive
dependency.
