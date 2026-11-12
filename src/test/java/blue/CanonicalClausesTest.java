package blue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import blue.examples.CanonicalClauses;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("contracts")
@Tag("packages")
@Tag("scenario")
class CanonicalClausesTest {
  @Test
  void publishedInstallerConnectsInitializationAndEventDrivenClauses() {
    try (Contracts.Session session =
        CanonicalClauses.contracts().openYaml(CanonicalClauses.source())) {
      session.initialize().requireApplied();
      assertEquals("Hello canonical clauses", session.read("/greeting"));
      assertEquals("before", session.read("/activity"));
      Contracts.Transition ping = session.call("input", "ping", Map.of(), 1).requireApplied();
      assertEquals("Received a ping", session.read("/activity"));
      assertTrue(
          ping.events().stream()
              .anyMatch(
                  event ->
                      event instanceof Map<?, ?> map && "Received a ping".equals(map.get("text"))));
      String identity = session.identity();
      session.compact();
      assertEquals(identity, session.identity());
      assertEquals("Received a ping", session.read("/activity"));
    }
  }
}
