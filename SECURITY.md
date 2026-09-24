# Security Policy

## Supported versions

dml_utils is pre-1.0 and under active development. Security fixes are applied
to the `main` branch and to the latest tagged release, if one exists. Older
versions are not supported.

## Reporting a vulnerability

**Please do not report security vulnerabilities through public GitHub issues,
discussions, or pull requests.**

Report them privately using GitHub's private vulnerability reporting:

1. Go to the [Security tab](https://github.com/eyupmiduck/dml_utils/security)
   of the repository.
2. Click **Report a vulnerability** (direct link:
   <https://github.com/eyupmiduck/dml_utils/security/advisories/new>).
3. Describe the issue in the private advisory.

Include as much of the following as you can:

- The type of issue (e.g. SQL injection, privilege escalation, unsafe SQL
  generation against a live database, credential exposure).
- The affected version, tag, or commit.
- Reproduction steps or a minimal proof of concept.
- The impact and how an attacker might exploit it.
- Any suggested mitigation or fix.

## What to expect

- **Acknowledgement** within a few days.
- **Assessment**: we will confirm the issue and determine its severity, or
  explain why it is not considered a vulnerability.
- **Fix and disclosure**: we will work on a fix and coordinate a disclosure
  timeline with you. Please give us a reasonable window to release a fix before
  any public disclosure.
- **Credit**: with your permission, we will credit you in the advisory.

This is a personal project maintained on a best-effort basis, so response times
may vary. There is no bug bounty program.

## Scope

dml_utils generates and applies PostgreSQL SQL, so the highest-risk areas are:

- Input that is interpolated into generated SQL (SQL injection).
- Privilege and role handling in the Liquibase changelog and the custom
  PostgreSQL image.
- DML statements that could cause data loss on large tables.

Issues in third-party dependencies should be reported upstream; if a
dependency issue affects dml_utils, we still want to know.

## Secrets

This repository runs automated secret scanning in CI (gitleaks). If you
accidentally commit a credential, treat it as compromised: rotate it first, then
remove it. Deleting the file is not enough, because it remains in git history.
