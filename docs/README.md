# Documentation map

Start with the [project README](../README.md) and [architecture](ARCHITECTURE.md). The pages below
are grouped by what you are trying to do. Dated reports describe one campaign or measurement and
are kept for their evidence; they are not maintained as current guidance.

## Using the library

| Topic | Page |
| --- | --- |
| Supported API at a glance | [SUPPORTED.md](SUPPORTED.md) |
| Language: identity, inheritance, lists, schemas | [LANGUAGE.md](LANGUAGE.md), [TUTORIAL.md](TUTORIAL.md) |
| Packages: loading, names, content | [PACKAGES.md](PACKAGES.md) |
| BEX and Contracts execution | [CANONICAL-EXECUTION.md](CANONICAL-EXECUTION.md), [CANONICAL-TRANSACTIONS.md](CANONICAL-TRANSACTIONS.md) |
| Gas | [SEMANTIC-GAS.md](SEMANTIC-GAS.md) |
| What is not implemented | [CAPABILITY-GAPS.md](CAPABILITY-GAPS.md) |

## Integrating a host

| Topic | Page |
| --- | --- |
| Native steps and channels | [NATIVE-STEPS.md](NATIVE-STEPS.md), [NATIVE-CHANNELS.md](NATIVE-CHANNELS.md) |
| Admission evidence and recovery | [NATIVE-ADMISSION.md](NATIVE-ADMISSION.md), [ADMISSION-EVIDENCE.md](ADMISSION-EVIDENCE.md), [ADMISSION-CHECKPOINTS.md](ADMISSION-CHECKPOINTS.md), [RECEIVING-STATE.md](RECEIVING-STATE.md) |
| Timelines | [TIMELINE-CHANNELS.md](TIMELINE-CHANNELS.md), [TIMELINE-VERIFICATION.md](TIMELINE-VERIFICATION.md), [TIMELINE-READING.md](TIMELINE-READING.md), [TIMELINE-ACCESS.md](TIMELINE-ACCESS.md), [PROVIDER-AUTHENTICATION.md](PROVIDER-AUTHENTICATION.md) |
| Durable sessions and reuse | [SESSION-COMPACTION.md](SESSION-COMPACTION.md), [CALCULATION-REUSE.md](CALCULATION-REUSE.md), [NORMALIZATION-SHARING.md](NORMALIZATION-SHARING.md) |

## Working on the code

| Topic | Page |
| --- | --- |
| Build, conventions | [CONTRIBUTING.md](../CONTRIBUTING.md) |
| Where a test belongs and what a failure means | [TESTING.md](TESTING.md) |
| Design references | [INSPIRATION.md](INSPIRATION.md) |
| Migration from removed experimental APIs | [CANONICAL-MIGRATION.md](../CANONICAL-MIGRATION.md) |

## Dated reports and history

These record what was measured or decided at a point in time. APIs they mention may have been
removed since.

- Reviews: [CAFFEINE-REVIEW.md](CAFFEINE-REVIEW.md) (3 November),
  [QUALITY-REVIEW.md](QUALITY-REVIEW.md) (4 November),
  [REVIEW-FOLLOW-UP-2026-11-09.md](REVIEW-FOLLOW-UP-2026-11-09.md)
- Campaigns: [CANONICAL-CAMPAIGN-2026-11-08.md](CANONICAL-CAMPAIGN-2026-11-08.md),
  [CANONICAL-CONTINUATION-2026-11-08.md](CANONICAL-CONTINUATION-2026-11-08.md),
  [retained-composition-2026-11-08.md](retained-composition-2026-11-08.md),
  [polish-2026-11-10](polish-2026-11-10/REPORT.md),
  [owner-local-2026-11-10](owner-local-2026-11-10/REPORT.md)
- Measurements: [CANONICAL-PERFORMANCE.md](CANONICAL-PERFORMANCE.md),
  [CANONICAL-VERIFICATION.md](CANONICAL-VERIFICATION.md),
  [LANGUAGE-VERIFICATION.md](LANGUAGE-VERIFICATION.md),
  [PACKAGE-VERIFICATION.md](PACKAGE-VERIFICATION.md)
- Proposals and logs: [SPEC-LOCALITY.md](SPEC-LOCALITY.md),
  [ITERATIONS-2026-11.md](ITERATIONS-2026-11.md), [retired experiments](history/retired-experiments/)
