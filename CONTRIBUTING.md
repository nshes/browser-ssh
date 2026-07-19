# Contributing

Contributions are reviewed at the maintainer's discretion. Submitting an issue
or pull request does not guarantee acceptance, a response or a support timeline.

For substantial changes, open an issue first and describe the intended behavior,
security impact and operational tradeoffs. Keep pull requests focused, include
tests for changed behavior and update the relevant documentation.

Run the following checks before submitting a pull request:

```bash
bash -n deploy/*.sh deploy/browser-ssh-broker deploy/browser-ssh-broker-client
./tests/broker-policy-test.sh
mvn test
```

Do not include credentials, deployment hostnames, account identifiers, private
keys, Access tokens, JWTs, `known_hosts` files or production configuration.
Report security vulnerabilities through the private process in `SECURITY.md`,
not a public issue.

By submitting a contribution, you confirm that you have the right to provide it
under the Apache License 2.0. Section 5 of that license applies unless you
conspicuously state that the submission is not a contribution.
