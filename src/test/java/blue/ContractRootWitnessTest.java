package blue;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigInteger;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

/** Physical reuse cannot omit a fresh exact handle or leak evidence between invocations. */
@ResourceLock(Resources.SYSTEM_PROPERTIES)
class ContractRootWitnessTest {
  private static ContractModel.Document document(String key) {
    var body = ExactNode.value(Map.of("value", BigInteger.valueOf(key.hashCode())));
    return new ContractModel.Document(key, body, "identity-" + key, null);
  }

  private static TopologyMap<String, ContractModel.Document> documents() {
    return new TopologyMap<>(
        true, ContractCanonical.CODE_POINTS, ContractModel.Document::sameMetadata);
  }

  @Test
  void unchangedSiblingsAreNotVisitedAgainAndNewHandlesAreNotSkipped() {
    var blue = new ContractBridge(Language.create());
    var work = new ContractWork(blue, new ContractModel.Meter(100));
    var original = documents();
    for (int i = 0; i < 10000; i++) original.put("d" + i, document("d" + i));
    assertEquals(10000, work.noteRoots(original));
    assertEquals(0, work.noteRoots(original), "quiet siblings were scanned again");
    var changed = documents();
    changed.copyFrom(original);
    var replacement = original.get("d123").withBody(document("d123").body);
    assertNotSame(original.get("d123").body, replacement.body);
    changed.put("d123", replacement);
    assertEquals(Set.of(), changed.changedKeys(original));
    assertEquals(Set.of("d123"), changed.changedValueKeys(original));
    assertEquals(1, work.noteRoots(changed));
    assertEquals(replacement.blueId, blue.known(replacement.body));
    assertEquals(1, work.noteRoots(original), "a discarded fork must restore its original witness");
    blue.witnessScope.set(new ContractLanguage.Witnesses());
    try {
      assertEquals(10000, work.noteRoots(original));
      assertEquals(original.get("d123").blueId, blue.known(original.get("d123").body));
      assertEquals(0, work.noteRoots(original));
    } finally {
      blue.witnessScope.remove();
    }
    assertEquals(10000, work.noteRoots(original));
  }

  @Test
  void physicalDifferencesSurviveMixedMetadataEditsAndDisconnectedHistory() {
    var original = documents();
    original.put("a", document("a"));
    original.put("b", document("b"));
    var changed = documents();
    changed.copyFrom(original);
    changed.put("a", original.get("a").withBody(document("a").body));
    changed.put("b", document("b").withBlueId("another"));
    assertEquals(Set.of("b"), changed.changedKeys(original));
    assertEquals(Set.of("a", "b"), changed.changedValueKeys(original));
    for (int i = 0; i < 4200; i++) changed.put("counter", document("counter"));
    assertEquals(Set.of("a", "b", "counter"), changed.changedValueKeys(original));
    changed.remove("counter");
    changed.put("a", original.get("a"));
    changed.put("b", original.get("b"));
    assertEquals(Set.of(), changed.changedValueKeys(original));
  }

  @Test
  void disablingReuseStillEstablishesTheSameWitnesses() {
    String property = "blue.physical.nativeDisabled", previous = System.getProperty(property);
    try {
      System.setProperty(property, "true");
      var blue = new ContractBridge(Language.create());
      var work = new ContractWork(blue, new ContractModel.Meter(100));
      var roots = documents();
      roots.put("a", document("a"));
      assertEquals(1, work.noteRoots(roots));
      assertEquals(1, work.noteRoots(roots));
      assertEquals(roots.get("a").blueId, blue.known(roots.get("a").body));
    } finally {
      if (previous == null) System.clearProperty(property);
      else System.setProperty(property, previous);
    }
  }

  @Test
  void finalizedRootsAndPrivateOverlaysRetainTheirOwnSnapshot() {
    var roots = documents();
    roots.put("a", document("a"));
    roots.put(
        "uninitialized", new ContractModel.Document("uninitialized", ExactNode.value(Map.of())));
    var original = roots.get("a");
    var finalized = new ContractGraph.Finalized(roots);
    roots.put("a", document("changed"));
    assertSame(original.body, finalized.get("a"));
    assertNull(finalized.get("uninitialized"));
    assertNull(finalized.get("missing"));
    var updated = document("updated").body;
    finalized.put("a", updated);
    var fork = finalized.copy();
    finalized.put("a", original.body);
    fork.put("new", updated);
    assertSame(updated, fork.get("a"));
    assertSame(original.body, finalized.get("a"));
    assertNull(finalized.get("new"));
    assertSame(updated, fork.get("new"));
  }
}
