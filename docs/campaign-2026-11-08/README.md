# Retained campaign evidence

These are bounded reports from the 2026-11-08 six-hour campaign. The [main report](../CANONICAL-CAMPAIGN-2026-11-08.md)
explains the iterations, semantic contract, measurements and unmet performance target.

* `summary.json` derives the final tables without discarding individual retained samples.
* `test-gates.json` records Java suite counts and XML aggregate hashes; original-Java comparison
  output is retained separately. These are selected conformance checks, not complete certification.
* `local-growth-final.json` and `local-growth-repeat.json` close/reopen durable storage before every
  operation. All samples compare exact root receipts outside timing; package/JVM state stays warm.
* `aws-cold-final.json` recreates transport caches per input. The final sample at each size is
  profiled and excluded from its three-sample unprofiled median. Acquisition is a separate interval
  and is not silently added to `durableProcessingMillis`. The command and phase records supply
  artifact/worker provenance and storage-call counters.
* `aws-cold-iteration26.json` is an earlier selected run, retained to avoid claiming that the final
  run is the fastest recorded result. The campaign does not establish a final speedup over it.
* `aws-clauses10-root-timing.json` waits for every exact submitted Agreement entry and compares all
  fifty results with an uncached native oracle. Phase work across owners may overlap; phase medians
  are not an additive decomposition of median client latency.
* `aws-clauses20-condition-timing.json` compares all one hundred exact root results, but some timed
  observations stop at child visibility. Its older `parentObservedMillis` label is not a universal
  parent-completion measurement. Do not merge it with the strict ten-Order timing series.
* `aws-final-user-reproduction.json` includes HTTPS/polling and exact parent completion.
  `restoreAdmissionExecutionMillis` is the legacy API aggregate, not pure native CPU.
* `aws-final-https-check.json`, `aws-final-external-check.json` and `aws-mixed-source-check.json`
  retain authenticated feature, verified coverage and mixed-source replay outcomes.
* Cycle/ring reports retain repeatable complete histories and exact native comparisons. Their
  recorded artifact hashes identify the iteration tested; final suites separately cover cycles.
* `lookup-jmh.json` is a short isolated microbenchmark. It does not establish end-to-end AWS speed.

`MANIFEST.json` maps each report back to its original campaign path and provides SHA-256 and byte
size. No source changes, benchmark binaries, giant traces, database snapshots or private login
credentials are included here. Report identifiers refer to disposable QA accounts, not the user's
existing document data. The directory is small enough to retain with the source repository.

The final physical-work diagnostic is `local-physical-work.json`; its counters are separate from
the uninstrumented latency series. Final host/example assembly logs verify the promoted checkout
paths. Preview health and temporary-worker cleanup are recorded with the verified AWS account.
The mixed-source rerun failure is retained: its bootstrap returned 503 and prompted the exact-version
custody correction in iteration 37. It is not removed from the evidence if a later replay passes.

Iteration 37's final runtime is identified by `aws-final-deployment.json`. The large scale and
50/100-receipt clause measurements precede its custody-only correction. `aws-mixed-final-runtime.json`
checks the final deployed code; `aws-mixed-pre-fix-rerun.json` is explicitly a pre-fix rerun and is not
credited to the fix. The five new custody regression tests are included in the final 812-test host
gate. `aws-mixed-rerun-failure.txt` preserves the preceding bootstrap 503 instead of hiding it.

`aws-final-custody-user-reproduction.json` is the last two-Order HTTPS journey on the final deployed
artifact. The earlier `aws-final-user-reproduction.json` is retained as the preceding run, not
silently overwritten. Both use exact submitted-entry parent-history completion.

`local-final-rings64.json` repeats the eight eight-member cyclic-group scenario on the final
promoted library. Its first run has 48 exact uncached oracle comparisons; all three schedules
produce identical full histories and heads. `aws-close-health.json` checks the final deployment
and confirms all temporary qualification workers have been terminated.
