package blue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

/** Reuse is optional, but a hit must bind all of the independently verified response's inputs. */
@Tag("contracts")
@ResourceLock(Resources.SYSTEM_PROPERTIES)
class ContractEvidenceCacheTest {
  @Test
  void aHitRequiresTheSameClaimTextAndLimitsAndOwnsItsImmutableBodyMap() {
    String claim = "verified-" + UUID.randomUUID();
    var limits = Map.of("depth", 64L);
    var key = ContractEvidenceCache.key(claim, Map.of("text", "{}"), limits, true);
    assertNotNull(key);
    var body = ExactNode.value(Map.of("value", "already verified"));
    var bodies = new HashMap<>(Map.of(claim, body));
    ContractEvidenceCache.put(key, bodies);
    bodies.clear();
    var retained = ContractEvidenceCache.get(key);
    assertEquals(Map.of(claim, body), retained);
    assertThrows(UnsupportedOperationException.class, retained::clear);
    assertSame(
        retained,
        ContractEvidenceCache.get(
            ContractEvidenceCache.key(
                claim,
                Map.of("text", "{}", "mode", "exact", "format", "json"),
                Map.of("depth", 64L),
                true)));
    assertNull(
        ContractEvidenceCache.get(
            ContractEvidenceCache.key("different-claim", Map.of("text", "{}"), limits, true)));
    assertNull(
        ContractEvidenceCache.get(
            ContractEvidenceCache.key(claim, Map.of("text", "{ }"), limits, true)));
    assertNull(
        ContractEvidenceCache.get(
            ContractEvidenceCache.key(claim, Map.of("text", "{}"), Map.of("depth", 63L), true)));
  }

  @Test
  void environmentProofsCyclesAndUnsupportedResponsesUseOrdinaryVerification() {
    for (Object response :
        List.of(
            "{}",
            Map.of(),
            Map.of("text", 1),
            Map.of("text", "{}", "environment", Map.of()),
            Map.of("text", "{}", "mode", "source"),
            Map.of("text", "{}", "format", "yaml"))) {
      assertNull(ContractEvidenceCache.key("uncached", response, Map.of(), true));
    }
    assertNull(ContractEvidenceCache.key("cyclic#0", Map.of("text", "{}"), Map.of(), true));
    assertNull(ContractEvidenceCache.key("disabled", Map.of("text", "{}"), Map.of(), false));
    assertNull(ContractEvidenceCache.key("absent", null, Map.of(), true));
    assertNull(ContractEvidenceCache.get(null));
    ContractEvidenceCache.put(null, Map.of());
  }

  @Test
  void theDiagnosticDisableSwitchCannotPopulateNewReusableResponses() {
    String property = "blue.physical.nativeDisabled";
    String previous = System.getProperty(property);
    try {
      System.setProperty(property, "true");
      assertNull(ContractEvidenceCache.key("switch", Map.of("text", "{}"), Map.of(), true));
    } finally {
      if (previous == null) {
        System.clearProperty(property);
      } else {
        System.setProperty(property, previous);
      }
    }
  }

  @Test
  void responseTextAndVerifiedBodiesShareOneEntryBudget() {
    String run = UUID.randomUUID().toString();
    String largestText = "x".repeat(512 * 1024);
    var edge =
        ContractEvidenceCache.key(run + "-text-limit", Map.of("text", largestText), Map.of(), true);
    assertNotNull(edge);
    assertNull(
        ContractEvidenceCache.key(
            run + "-text-limit", Map.of("text", largestText + "x"), Map.of(), true));
    ContractEvidenceCache.put(edge, Map.of());
    assertNull(ContractEvidenceCache.get(edge), "The key alone exceeds the complete entry budget");

    var combined =
        ContractEvidenceCache.key(
            run + "-combined-budget", Map.of("text", "x".repeat(400_000)), Map.of(), true);
    var body = ExactNode.value(Map.of("value", "y".repeat(200_000)));
    ContractEvidenceCache.put(combined, Map.of("body", body));
    assertNull(ContractEvidenceCache.get(combined), "Key and body must be weighed together");

    var oversized =
        ContractEvidenceCache.key(run + "-body-budget", Map.of("text", "{}"), Map.of(), true);
    ContractEvidenceCache.put(
        oversized, Map.of("body", ExactNode.value(Map.of("value", "y".repeat(600_000)))));
    assertNull(ContractEvidenceCache.get(oversized));
  }
}
