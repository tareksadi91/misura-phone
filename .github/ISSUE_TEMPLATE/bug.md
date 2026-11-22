---
name: Bug report
about: Report a reproducible failure
title: ""
labels: bug
assignees: ""
---

For unintended sends/payments, approval bypass, credential leakage or other security issues, follow the repository's `SECURITY.md`. Do not include vulnerability details or private data in this public report.

## What failed?

What did you ask or tap? What happened, and what should have happened?

## Steps to reproduce

1.
2.

Include the app's starting screen and whether this was a fresh run or a retry.

## Setup

- Misura commit or APK version:
- Phone model or emulator / Android version:
- Target app and version:
- Shizuku version and running/granted status:
- Misura accessibility and notification access enabled:
- Input: typed / Hold to talk / side key:

## If setup failed

- README step or settings button that failed:
- First install / update / previously working:
- Selected model provider and model ID (omit private deployment names):
- Sign-in route: provider API key / Vertex service account / Vertex adb token / unconfigured:
- Web search selection and observed fallback, if relevant:
- Optional Google Cloud integration configured (yes/no):
- Exact error with project ids, emails, tokens and other private values removed:
- Which parts already work, if any:

Do not attach API keys, authentication headers, service-account JSON, tokens, an app-data backup or an unredacted diagnostic export.

## Evidence

Attach a minimal redacted log or screenshot if useful. Remove names, message text and device identifiers. Never attach keys, briefings or preference dumps.

For unintended sends/payments or approval bypass, use the repository's `SECURITY.md` instead of this public report. Do not repeat a real send or payment to reproduce it.
