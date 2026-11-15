# Security policy

## Supported versions

| Version | Supported |
| --- | --- |
| 1.x (latest minor) | Yes |
| 0.x (pre-releases) | No |

Fixes are released as a new patch version of the latest 1.x minor release. Please update to it rather than expecting a backport.

## Reporting a vulnerability

**Don't open a public issue.** Report it privately through GitHub instead:

1. Go to the repository's **[Security tab](https://github.com/MrSluffy/blur-engine/security)**.
2. Choose **Report a vulnerability**.
3. Describe the problem, the affected versions, and how to reproduce it. A minimal project or test is ideal.

You'll get an acknowledgement within 7 days. If the report is confirmed, we'll agree on a fix and a disclosure date with you, publish a patch release, and credit you in the advisory unless you'd rather stay anonymous.

## Scope

In scope: the published libraries (`io.github.mrsluffy:blur-*`), for example:

- crashes or memory exhaustion that content behind a blur surface can trigger;
- leaking content from one app or window into another;
- problems in the published artifacts themselves, such as tampered or unsigned files on Maven Central or GitHub Packages.

Out of scope:

- the sample and showcase apps (`blur-samples`, `blur-showcase`) and the `benchmarks` module, which aren't published as libraries;
- the demo APK attached to releases, which is a debug build for trying the library, not for production;
- vulnerabilities in Android itself or in third-party dependencies (report those upstream; tell us if this library makes one exploitable).

## Verifying artifacts

Every artifact on Maven Central and GitHub Packages is signed with the project's PGP key. Check the `.asc` signature next to each file against the key published on `keyserver.ubuntu.com`.
