package blue;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

@Tag("contracts")
@ResourceLock(Resources.SYSTEM_PROPERTIES)
class CollectionAppendTest {
  @Test
  void sparseReconciliationAndFullReconciliationPublishTheSameReceiptAndSnapshot() {
    String property = "blue.physical.fullCollectionReconciliation";
    String prior = System.getProperty(property);
    try {
      var cases = new ArrayList<Object>();
      for (boolean full : List.of(false, true)) {
        System.setProperty(property, Boolean.toString(full));
        try (var session = CanonicalAgreementTest.open(Contracts.create(), 12)) {
          String key = "new/~key";
          var transition =
              session.call(
                  "input",
                  "addOrder",
                  Map.of("key", key, "order", CanonicalAgreementTest.order(key)),
                  51);
          // The fixture concatenates the request key as a pointer itself. Exercise ordinary
          // success independently as well as any native rollback on this authored escaped key.
          var applied =
              session
                  .call(
                      "input",
                      "addOrder",
                      Map.of("key", "52", "order", CanonicalAgreementTest.order("52")),
                      52)
                  .requireApplied();
          cases.add(
              List.of(
                  transition.observations(),
                  transition.receipt(),
                  applied.observations(),
                  applied.receipt(),
                  session.snapshot()));
        }
      }
      assertEquals(cases.get(0), cases.get(1));
    } finally {
      if (prior == null) System.clearProperty(property);
      else System.setProperty(property, prior);
    }
  }
}
