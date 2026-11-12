package blue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** The property names are an informal interface for tests and investigations; pin each one. */
@Tag("contracts")
@ResourceLock(Resources.SYSTEM_PROPERTIES)
class DiagnosticSwitchesTest {
  record Switch(String property, BooleanSupplier value) {
    @Override
    public String toString() {
      return property;
    }
  }

  static List<Switch> switches() {
    return List.of(
        new Switch("blue.physical.nativeDisabled", DiagnosticSwitches::nativeReuseDisabled),
        new Switch(
            "blue.physical.borrowedHeadsDisabled", DiagnosticSwitches::borrowedHeadsDisabled),
        new Switch("blue.physical.fullPatch", DiagnosticSwitches::fullPatch),
        new Switch(
            "blue.physical.sparseScheduleDisabled", DiagnosticSwitches::sparseScheduleDisabled),
        new Switch("blue.physical.inputsFullScan", DiagnosticSwitches::inputsFullScan),
        new Switch(
            "blue.physical.fullCollectionReconciliation",
            DiagnosticSwitches::fullCollectionReconciliation),
        new Switch("blue.physical.counters", DiagnosticSwitches::counters),
        new Switch("blue.physical.cpu", DiagnosticSwitches::cpuTime),
        new Switch(
            "blue.canonical.fragmentedSnapshotDisabled",
            DiagnosticSwitches::fragmentedSnapshotDisabled),
        new Switch("blue.canonical.prefixWitnesses", DiagnosticSwitches::prefixWitnesses),
        new Switch("blue.canonical.recordTextDisabled", DiagnosticSwitches::recordTextDisabled),
        new Switch("blue.canonical.inlineRecords", DiagnosticSwitches::inlineRecords),
        new Switch("blue.canonical.recompile", DiagnosticSwitches::recompile),
        new Switch("blue.canonical.revalidateClosures", DiagnosticSwitches::revalidateClosures),
        new Switch(
            "blue.canonical.finalizationReuseDisabled",
            DiagnosticSwitches::finalizationReuseDisabled));
  }

  private static void restore(String property, String previous) {
    if (previous == null) {
      System.clearProperty(property);
    } else {
      System.setProperty(property, previous);
    }
  }

  @ParameterizedTest
  @MethodSource("switches")
  void aSwitchIsOffUnlessItsPropertyIsTrueAndIsReadAtEachUse(Switch selected) {
    String previous = System.getProperty(selected.property());
    try {
      System.clearProperty(selected.property());
      assertFalse(selected.value().getAsBoolean());
      System.setProperty(selected.property(), "true");
      assertTrue(selected.value().getAsBoolean());
      System.setProperty(selected.property(), "false");
      assertFalse(selected.value().getAsBoolean());
    } finally {
      restore(selected.property(), previous);
    }
  }

  @Test
  void everySwitchHasItsOwnNamespacedProperty() {
    Set<String> names = new HashSet<>();
    for (Switch selected : switches()) {
      assertTrue(selected.property().matches("blue\\.(physical|canonical)\\.[A-Za-z]+"));
      assertTrue(names.add(selected.property()), selected.property());
    }
  }

  @Test
  void theFactsCacheBoundDefaultsToEightMebibytesAndFollowsItsProperty() {
    String property = "blue.physical.contractFactsBytes";
    String previous = System.getProperty(property);
    try {
      System.clearProperty(property);
      assertEquals(8L * 1024 * 1024, DiagnosticSwitches.contractFactsBytes());
      System.setProperty(property, "4096");
      assertEquals(4096, DiagnosticSwitches.contractFactsBytes());
    } finally {
      restore(property, previous);
    }
  }
}
