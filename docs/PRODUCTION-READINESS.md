# Readiness work, 14 November 2026

Baseline: `4fb26ef1d22f61b9a0497716fe6f69b22afb111a`. The attached readiness review is useful
as a risk inventory. Its multi-month architectural proposals are not all prerequisites for
shipping a safe incremental improvement.

## Decisions in this revision

* Preserve public class names, signatures and package locations. Moving the public API into
  new modules needs a separately versioned migration. `checkApi`, included in `check`, now
  compares public/protected declarations, JVM descriptors, generic signatures and constants
  against a committed baseline using the JDK's own `javap` tool. Additions are allowed.
* Keep default Error Prone errors and selective NullAway checking. Also fail on
  `EqualsUnsafeCast`, `MutablePublicArray`, `MissingCasesInEnumSwitch` and `LockNotBeforeTry`.
  Other warnings can be inventoried with `-PerrorProneWarnings=true`. Do not suppress real
  errors merely to achieve a score. Reference-identity checks on persistent structures and
  sentinel values are intentional; replacing them mechanically with `equals` is unsafe.
* Run CI on `main`, pull requests and `codex/**` branches. The ordinary suite includes a
  deterministic package-archive mutation test. `adversarialTest` expands it to 4,096 mutated
  carriers and 1,369,440 generated identity candidates, alongside persistent-map model tests.
* Correct the stale gas figures in the canonical execution guide. Document the actual input
  and trust boundaries in [SECURITY.md](../SECURITY.md).

The API guard is conservative about changed existing declarations, but it is not a complete
behavioral or Java source-compatibility proof. In particular, newly added abstract methods,
overload ambiguity and annotations require review. Changes to the baseline need an explicit
versioning decision; never regenerate it simply to make a failed build green. See
[the baseline provenance](../api/README.md).

## Recommendations already supported

The library already has persistent-map model tests, leading-zero identity boundary tests,
input work limits, archive/path validation, reproducible JAR settings, strict Javadoc,
compiler warnings as errors, and separate instrumented coverage runs. The extended tests
add inputs to those mechanisms. They do not replace them with file-length thresholds or
counts of `Map`/`instanceof` expressions.

## Deliberately separate work

Semantic gas, source exclusion and a large-collection representation change protocol
semantics. They require editioned specifications and independent vectors. Regenerating
expected receipts with this implementation would not establish conformance. A broad
package/module move, owner-local scheduling behind `Session`, and a Java 21 support promise
need dedicated compatibility/performance work. They are not silently included here.

The owner must select a license and release policy. External security review, a protected
GitHub release workflow, dedicated performance runners and an operating record cannot be
substituted by a self-assigned engineering score. Local checks and the AWS comparison are
reported separately from any remote CI result. This revision does not claim production
readiness or Caffeine-equivalent maturity.
