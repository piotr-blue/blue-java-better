# Optional comparison with blue-java

`CanonicalCompatibility.java` runs identical initialization, Order creation, submission and
nested Payment settlement against the original Java implementation and this library. It compares
complete observations and receipts for 1, 10 and 20 Orders (40 transitions). The ordinary Gradle
build uses the small checked-in receipt fixture and never reads another repository or Python.

To run the optional independent check, first build both Java projects and obtain their runtime
classpath files. Run `canonicalClasspath` in this project. Compile this tool with `javac -cp`
containing both runtime classpaths, then run `CanonicalCompatibility` with the same classpath plus
the compiled tool directory. This tool does not alter either project or update test expectations.

The 2026-11-08 run exposed and corrected invocation-relative child allocation. That is why the
fixture checks exact receipts, rather than only final totals or gas.
