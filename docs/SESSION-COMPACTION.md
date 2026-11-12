# Current-state session maintenance

`Contracts.Session.compact()` keeps the same Blue graph and configured native registrations while
rebuilding its working state. The implementation retains verified reachable immutable bodies and
cyclic proofs directly. It reconstructs canonical metadata, checks the snapshot identity and execution
bindings, and starts a fresh archive containing only the selected snapshot. Declaration/resolver caches
from old invocations are not copied. Returned immutable Language values remain independently valid.

This is an in-process maintenance boundary over library-owned completed state. It is not an import
API or a permission to trust uploaded snapshots. `Contracts.restore` continues to verify wire, exact
content and its separately trusted expected identity. A failed maintenance operation leaves the
original session selected. Existing private transactions become stale after successful compaction.

Compaction is still proportional to the reachable closure: historical content referenced by the
current graph is necessary evidence and remains retained. This change removes wire conversion and
repeated admission at this boundary; it does not make a growing current graph constant-size.

## Local measurement

Five alternating pairs per size, first two discarded. Static members; no DynamoDB or Timeline. Every
portable snapshot and next receipt/gas/identity matches. The portable baseline explicitly performs the
previous export/restore maintenance algorithm. These bounded samples have no confidence interval.

| Members | Portable / direct median, ms | Portable / direct allocated bytes |
| ---: | ---: | ---: |
| 17 | 9.843 / 2.863 | 16,041,832 / 2,520,136 |
| 257 | 22.889 / 12.475 | 50,161,680 / 27,171,048 |
| 1,025 | 108.401 / 49.467 | 212,939,376 / 105,921,656 |

The `blue.SessionCompactionProbe` demo executable reproduces the comparison. It is excluded from the
library artifact and normal test execution. Full formatting/check/Javadoc passed with 2,517 tests
in 21 seconds, zero failures/errors/skips. Native channel/step and published-clause suites exercise
the existing public maintenance API. AWS benefit remains to be measured by the companion host.

```bash
./gradlew canonicalClasspath
java -Xmx768m -cp "$(cat build/canonical-classpath.txt)" blue.SessionCompactionProbe
```
