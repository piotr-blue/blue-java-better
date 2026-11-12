# Sharing repeated exact values during normalization

Normalization used to construct a new immutable exact value, register it under its BlueId, and
return the new value even when the same representation already existed in the current context.
Repeated inline workflow definitions could consequently stay reachable through many different
root documents. Replacing the catalogue entry did not release copies retained by those roots.

Normalization now returns the existing value when its fields have the same materialization.
It uses the context's existing body catalogue, with no global interner or additional cache. Child
values must already be the same immutable objects; references must have equal identities and the
same attached cyclic-body hint. Normalization builds children first, allowing reuse from leaves
upward. This is physical sharing, not evidence or semantic-validation reuse.

Equal BlueIds alone are insufficient: an inline child and a collapsed reference keep their distinct
representations, and the last offered representation still becomes the catalogue entry. A plain
cyclic reference never acquires a verified body hint from another representation. Every normalization
step and subsequent admission obligation remains; identities, work accounting and wire output are
unchanged. Independent contexts do not gain a shared mutable registry.

## Bounded diagnostic

Run `./gradlew normalizationSharingProbe` outside ordinary tests. The probe warms eight inputs, then
normalizes 16, 256 and 1,024 documents containing the same inline workflow in one context. It retains
all roots, traverses them and the body catalogue by Java object identity, and counts reachable exact
values/references. Allocation is measured on the invoking thread. These counts are not a heap-size
estimate. The fixture is structural; it does not execute Contracts or contact AWS.

A baseline/candidate pair on the same local JDK 25, each with a 512 MiB heap, produced:

| Documents | Retained exact values before / after | Retained references before / after | Allocated bytes before / after | Normalization ms before / after |
| ---: | ---: | ---: | ---: | ---: |
| 16 | 3,746 / 212 | 1,649 / 90 | 14,714,800 / 14,185,592 | 18.570 / 20.155 |
| 256 | 56,546 / 692 | 24,929 / 330 | 193,016,256 / 182,862,928 | 116.164 / 114.736 |
| 1,024 | 225,506 / 2,228 | 99,425 / 1,098 | 732,078,080 / 665,696,088 | 386.364 / 277.002 |

Identity digests and logical work match at every size (225,280 steps at 1,024 documents). This is one
bounded diagnostic pair, not a statistical timing claim or a prediction of AWS latency/memory.
Allocation is still substantial because nodes must be normalized and verified before sharing.
The actual deployed Agreement workload needs separate measurement.

`LanguageNormalizationTest` owns physical sharing, work equality, immutable output, inline/reference
presentation, typed-list payloads and cyclic-hint isolation. The ordinary full suites retain Language
and Contracts reference vectors, provider failures, cyclic execution, gas, snapshot and native-binding
checks. No reference expectation or package/specification identity changes for this optimization.
