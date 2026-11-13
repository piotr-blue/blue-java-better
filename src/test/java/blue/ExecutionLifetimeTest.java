package blue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Closing an owner attempt revokes new work even if a caller still holds the adapter. */
class ExecutionLifetimeTest {
  @Test
  void closedAttemptCannotIntroduceFreshDocumentsReadersOrVerifications() {
    var runtime = Contracts.create();
    var document = Language.create().readYaml("count: 3");
    var attempt = Contracts.Execution.using(runtime, null);
    var initial = attempt.initialize(attempt.prepare("root", document)).requireApplied();
    var snapshot = initial.after().checkpoint();
    var identity = initial.after().blueId();
    attempt.close();
    attempt.close();
    assertThrows(IllegalStateException.class, () -> attempt.prepare("root", document));
    assertThrows(
        IllegalStateException.class, () -> attempt.prepare(Map.of("root", Map.of("n", 1)), "root"));
    assertThrows(IllegalStateException.class, () -> attempt.restore(snapshot));
    assertThrows(
        IllegalStateException.class, () -> attempt.reuseCalculations(key -> Optional.empty()));
    var receiver =
        new NativeAdmission.Receiver(
            "root", document.blueId(), "desk", 1, document.blueId(), Map.of(), Optional.empty());
    assertThrows(IllegalStateException.class, () -> attempt.observes(receiver));
    assertEquals(identity, initial.after().blueId());
  }
}
