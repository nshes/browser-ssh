# Public Release Checklist

Do not publish this operations repository's existing Git history. Create a new
repository from the reviewed working tree so historical deployment hostnames,
account identifiers and infrastructure notes are not included.

- Apache License 2.0 is recorded in the repository `LICENSE`.
- Confirm `README.md`, examples and tests contain placeholders only.
- Review `THIRD-PARTY-NOTICES.md` against the release dependency tree.
- Run the Maven suite and broker policy tests.
- Verify vendored checksums, then run a secret scanner against the new
  repository and its complete history. The Gitleaks path exception is limited
  to the recorded unmodified minified xterm.js file.
- Confirm no `.env`, private key, Tunnel token, JWT, `known_hosts` deployment
  file or target configuration is tracked.
- Review vendored xterm license files and retain their notices.
- Verify vendored files against `docs/dependency-provenance.md`; update versions,
  upstream URLs and checksums together.
- Before publishing executable JARs, bundle required third-party license and
  notice texts and document source availability for EPL-licensed pty4j.
- Enable private vulnerability reporting.
- Enable branch protection, required CI checks and required code-owner review.
- Build release artifacts from CI and publish checksums.
- Keep deployment-specific state in a separate private operations repository.

Repository visibility alone does not grant rights beyond the terms in the
project and third-party license files.
