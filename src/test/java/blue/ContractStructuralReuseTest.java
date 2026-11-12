package blue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

/** Structural knowledge follows an exact snapshot fork; authority never enters this cache. */
@Tag("contracts")
@ResourceLock(Resources.SYSTEM_PROPERTIES)
class ContractStructuralReuseTest {
  @Test
  void repeatedPreflightPreservesExactReceiptsAtGasBoundariesAndAfterNewEmbeddings() {
    Map<String, Object> checkpoint;
    String identity;
    long required;
    try (Contracts.Session original = CanonicalAgreementTest.open(Contracts.create(), 3)) {
      checkpoint = original.snapshot();
      identity = original.identity();
      required =
          original
              .call(
                  "order-0", "submit", Map.of("payment", CanonicalAgreementTest.payment("0")), 100)
              .requireApplied()
              .gas();
    }
    for (long allowance : new long[] {1, required / 2, required - 1, required, required + 1}) {
      Map<String, Object> ordinary = observe(checkpoint, identity, allowance, true);
      assertEquals(
          ordinary,
          observe(checkpoint, identity, allowance, false),
          "Whole-root result at gas allowance " + allowance);
    }
  }

  private static Map<String, Object> observe(
      Map<String, Object> checkpoint, String identity, long allowance, boolean disabled) {
    String property = "blue.physical.nativeDisabled";
    String previous = System.getProperty(property);
    try {
      System.setProperty(property, Boolean.toString(disabled));
      Contracts contracts = Contracts.newBuilder().gasLimit(allowance).build();
      try (Contracts.Session session = contracts.restore(checkpoint, identity)) {
        Contracts.Transition outcome =
            session.call(
                "order-0", "submit", Map.of("payment", CanonicalAgreementTest.payment("0")), 100);
        return Map.of(
            "receipt",
            outcome.receipt(),
            "observations",
            outcome.observations(),
            "snapshot",
            session.snapshot());
      }
    } finally {
      if (previous == null) {
        System.clearProperty(property);
      } else {
        System.setProperty(property, previous);
      }
    }
  }

  @Test
  void aPrivateProcessorForkReusesPlansAndPositiveStructuralChecks() {
    ContractProcessor original = new ContractProcessor();
    var snapshot =
        original.create(
                Map.of("contracts", Map.of("input", Map.of("type", "Contracts/External Channel"))))
            .state;
    var member = snapshot.documents.get(snapshot.root);
    var declarations = original.registry.contracts(member);
    assertTrue(original.registry.facts.check("markers", member.key, member, List.of()).known());
    ContractProcessor candidate = new ContractProcessor(original, new ContractPrograms());
    var copy = member.copy();
    assertSame(declarations, candidate.registry.contracts(copy));
    assertTrue(candidate.registry.facts.check("markers", copy.key, copy, List.of()).known());
    copy = copy.withChannelSerial("changed", 1L);
    assertFalse(candidate.registry.facts.check("markers", copy.key, copy, List.of()).known());
    assertTrue(original.registry.facts.check("markers", member.key, member, List.of()).known());
  }

  @Test
  void dependencyChangesAndExplicitRevocationCannotUseAnEarlierFact() {
    ContractProcessor original = new ContractProcessor();
    var snapshot = original.create(Map.of("count", 0)).state;
    var member = snapshot.documents.get(snapshot.root);
    var facts = original.registry.facts;
    var earlier = facts.check("test", member.key, member, List.of("before"));
    assertFalse(earlier.known());
    facts.established(earlier);
    assertTrue(facts.check("test", member.key, member, List.of("before")).known());
    assertFalse(facts.check("test", member.key, member, List.of("after")).known());
    ContractProcessor independent = new ContractProcessor();
    assertFalse(
        independent.registry.facts.check("test", member.key, member, List.of("before")).known());
    facts.clear();
    assertFalse(facts.check("test", member.key, member, List.of("before")).known());
  }

  @Test
  void markerKnowledgeFollowsTheExactBodyAndDoesNotChangeItsSourceCopy() {
    ContractProcessor processor = new ContractProcessor();
    var snapshot = processor.create(Map.of("count", 0)).state;
    var original = snapshot.documents.get(snapshot.root);
    assertNotNull(processor.registry.marker(original, "initialized"));
    assertNull(processor.registry.marker(original, "terminated"));
    var changed = original.copy();
    changed = changed.withBody(processor.blue.open(changed.body).with("contracts", null));
    assertNull(processor.registry.marker(changed, "initialized"));
    assertNotNull(processor.registry.marker(original, "initialized"));
  }
}
