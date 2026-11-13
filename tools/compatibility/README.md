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

## Compare the public Java surface of two Better builds

The semantic comparison above checks processing results. A release can also run a public classfile comparison against a previous Better JAR. Build both revisions with JDK 25. Use japicmp 0.26.5 and the main dependency classpaths that belong to each build. The following invocation reports changed Java members and exits unsuccessfully for a detected source or binary incompatibility.

```bash
java -jar "$JAPICMP_JAR" \
  --old "$PREVIOUS_BLUE_JAR" --new "$CANDIDATE_BLUE_JAR" \
  --old-classpath "$PREVIOUS_DEPENDENCIES" --new-classpath "$CANDIDATE_DEPENDENCIES" \
  --only-modified --error-on-binary-incompatibility --error-on-source-incompatibility
```

Use the assembled JAR with dependencies and SHA-256 `c2d19b6c11edbfe2d5b200ae2af1bf96db342b3a5de47ff36ddc243f2a58c828`. This is development tooling. The Maven runtime distribution and Blue package identities do not depend on it. The result covers classfile compatibility. Continue to run the receipt and behavioral tests when the classfile report is empty. [Official command reference](https://siom79.github.io/japicmp/CliTool.html)
