# 05 Suite integration and simple partner facade

Type: task
Status: ready-for-agent
Blocked by: 01

Spec: ../spec.md

## Scope and acceptance

Own retentionkit/**, focused suite-firebase retention adapter additions and optional integration package. Implement short umbrella install/config with standard module defaults and feature toggles, dynamic validated/cached overrides wired to the existing RemoteConfigClient and Trackkit without duplicate fetchers. Provide optional OnboardKit route/permission/resume-ad integration preserving no-ad navigation and existing consumers. Never overwrite existing setResumeSkipPolicy. Coordinate final module APIs from tickets02–04. Tests for missing remote keys, stale config, no dependency on ads in selective kits, source callback failure and entry consumption. Any necessary changes to existing OnboardKit must be narrow/source-compatible with regression tests.

Follow the shared implementation contract and acceptance ledger in spec.md. Commit only owned files; write implementation/test results under Answer and change Status to resolved when verified. Provide commit hashes and concise context pointers for the merger.

## Comments

Implementation pending.
