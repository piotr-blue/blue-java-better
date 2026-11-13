# Optional comparison with blue-java

`CanonicalCompatibility.java` runs matching Agreement, Order and Payment inputs against the original Java implementation and this library. By default it compares the complete root projection and BlueId, ordered event contents, emitting members and ordinals after each of 40 transitions at 1, 10 and 20 Orders.

The original processor uses `contracts-deterministic-gas-6`. Better uses `contracts-semantic-gas`. The edition enters the input, turn, occurrence and receipt commitments, so two runs can agree on every business field and still produce different receipts. The tool prints each engine’s gas and the agreed document identity. When comparing engines that implement the same accounting edition, pass `--exact-receipts` to additionally require equal receipts and complete canonical observations. Preserve this check when investigating determinism across two builds of the same edition.

Build both Java projects and obtain their runtime classpaths. Run `canonicalClasspath` in Better for its library and demo classes. Compile this file with JDK 25 and both classpaths, then run `CanonicalCompatibility` with the compiled directory and both classpaths. The optional tool only reads the projects. The ordinary Gradle tests use checked-in fixtures.

## Compare the public Java surface of two Better builds

The semantic comparison above checks processing results. A release can also run a public classfile comparison against a previous Better JAR. Build both revisions with JDK 25. Use japicmp 0.26.5 and the main dependency classpaths that belong to each build. The following invocation reports changed Java members and exits unsuccessfully for a detected source or binary incompatibility.

```bash
java -jar "$JAPICMP_JAR" \
  --old "$PREVIOUS_BLUE_JAR" --new "$CANDIDATE_BLUE_JAR" \
  --old-classpath "$PREVIOUS_DEPENDENCIES" --new-classpath "$CANDIDATE_DEPENDENCIES" \
  --only-modified --error-on-binary-incompatibility --error-on-source-incompatibility
```

Use the assembled JAR with dependencies and SHA-256 `c2d19b6c11edbfe2d5b200ae2af1bf96db342b3a5de47ff36ddc243f2a58c828`. This is development tooling. The Maven runtime distribution and Blue package identities do not depend on it. The result covers classfile compatibility. Continue to run the receipt and behavioral tests when the classfile report is empty. [Official command reference](https://siom79.github.io/japicmp/CliTool.html)
