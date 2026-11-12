package blue;

/**
 * Diagnostic system properties. Each selects the unoptimized form of one physical strategy.
 *
 * <p>A switch must never change a document, event, identity, receipt or gas amount: it exists so a
 * test or an investigation can run the same input with one optimization turned off and compare the
 * results. None is a supported configuration surface. {@code blue.physical.*} properties bypass
 * reuse of previously completed work; {@code blue.canonical.*} properties select an alternative
 * route to the same canonical encoding.
 *
 * <p>Except where noted, a switch is read at each use rather than cached, so a test that holds the
 * system-properties lock may change it between invocations.
 */
final class DiagnosticSwitches {
  private DiagnosticSwitches() {}

  /**
   * {@code blue.physical.nativeDisabled}: recompute native declarations, preparation and evidence
   * instead of reusing completed results.
   */
  static boolean nativeReuseDisabled() {
    return Boolean.getBoolean("blue.physical.nativeDisabled");
  }

  /**
   * {@code blue.physical.borrowedHeadsDisabled}: build each head independently instead of borrowing
   * the one retained by the Language context.
   */
  static boolean borrowedHeadsDisabled() {
    return Boolean.getBoolean("blue.physical.borrowedHeadsDisabled");
  }

  /**
   * {@code blue.physical.fullPatch}: reconcile the embedding graph after every patch, including one
   * that is disjoint from every declared embedding.
   */
  static boolean fullPatch() {
    return Boolean.getBoolean("blue.physical.fullPatch");
  }

  /**
   * {@code blue.physical.sparseScheduleDisabled}: visit every member in each phase instead of only
   * the members with ready work.
   */
  static boolean sparseScheduleDisabled() {
    return Boolean.getBoolean("blue.physical.sparseScheduleDisabled");
  }

  /**
   * {@code blue.physical.inputsFullScan}: rescan a member's embedding links when installing inputs
   * instead of consulting the edge index.
   */
  static boolean inputsFullScan() {
    return Boolean.getBoolean("blue.physical.inputsFullScan");
  }

  /**
   * {@code blue.physical.fullCollectionReconciliation}: reconcile a whole collection instead of
   * recognizing an append to it.
   */
  static boolean fullCollectionReconciliation() {
    return Boolean.getBoolean("blue.physical.fullCollectionReconciliation");
  }

  /**
   * {@code blue.physical.contractFactsBytes}: byte bound of each contract-facts cache; 8 MiB when
   * unset.
   */
  static long contractFactsBytes() {
    return Long.getLong("blue.physical.contractFactsBytes", 8L * 1024 * 1024);
  }

  /**
   * {@code blue.physical.counters}: record process-wide diagnostic counters and spans. Read once,
   * when {@link ContractMetrics} is initialized.
   */
  static boolean counters() {
    return Boolean.getBoolean("blue.physical.counters");
  }

  /**
   * {@code blue.physical.cpu}: also record thread CPU time for spans when counters are enabled.
   * Read once, when {@link ContractMetrics} is initialized.
   */
  static boolean cpuTime() {
    return Boolean.getBoolean("blue.physical.cpu");
  }

  /**
   * {@code blue.canonical.fragmentedSnapshotDisabled}: digest the complete snapshot record instead
   * of reusing the encodings of unchanged members.
   */
  static boolean fragmentedSnapshotDisabled() {
    return Boolean.getBoolean("blue.canonical.fragmentedSnapshotDisabled");
  }

  /**
   * {@code blue.canonical.prefixWitnesses}: add the canonical rendering's prefix witnesses to
   * native record closures, which do not otherwise need them.
   */
  static boolean prefixWitnesses() {
    return Boolean.getBoolean("blue.canonical.prefixWitnesses");
  }

  /**
   * {@code blue.canonical.recordTextDisabled}: render the exact text of each record on demand
   * instead of using the record-text cache.
   */
  static boolean recordTextDisabled() {
    return Boolean.getBoolean("blue.canonical.recordTextDisabled");
  }

  /**
   * {@code blue.canonical.inlineRecords}: serialize descendants inside each record instead of
   * referencing their own records.
   */
  static boolean inlineRecords() {
    return Boolean.getBoolean("blue.canonical.inlineRecords");
  }

  /**
   * {@code blue.canonical.recompile}: compile BEX programs and project shared definitions on every
   * use instead of caching them by identity.
   */
  static boolean recompile() {
    return Boolean.getBoolean("blue.canonical.recompile");
  }

  /**
   * {@code blue.canonical.revalidateClosures}: validate retained closures again instead of reusing
   * validation completed within the host budget.
   */
  static boolean revalidateClosures() {
    return Boolean.getBoolean("blue.canonical.revalidateClosures");
  }

  /**
   * {@code blue.canonical.finalizationReuseDisabled}: finalize every component, including those
   * whose references are unchanged.
   */
  static boolean finalizationReuseDisabled() {
    return Boolean.getBoolean("blue.canonical.finalizationReuseDisabled");
  }
}
