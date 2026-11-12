package blue;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Exercises real overlap at the private-work/atomic-publication boundary. */
@Tag("contracts")
@Tag("concurrency")
class ContractsConcurrencyTest {
  private static final String COUNTER =
      """
      count: 0
      contracts:
        input: {type: Contracts/External Channel}
        increment:
          type: Contracts/Sequential Workflow
          channel: input
          steps:
            - type: Contracts/Update Document
              changeset:
                - op: replace
                  path: /count
                  val: {$add: [{$document: /count}, {$event: /amount}]}
      """;

  private record Outcome(int amount, String identity, boolean committed) {}

  @ParameterizedTest
  @ValueSource(ints = {2, 8})
  void overlappingCandidatesPublishExactlyOneWholeResult(int contenders) throws Exception {
    var contracts = Contracts.create();
    try (var session = contracts.openYaml(COUNTER);
        var workers = Executors.newFixedThreadPool(contenders)) {
      session.initialize().requireApplied();
      String initial = session.identity();
      var before = session.snapshot();
      var transactions = new ArrayList<Contracts.Transaction>();
      for (int n = 1; n <= contenders; n++) transactions.add(session.begin(Map.of("amount", n), n));
      var ready = new CyclicBarrier(contenders);
      var tasks = new ArrayList<Future<Outcome>>();
      for (int n = 0; n < contenders; n++) {
        int amount = n + 1;
        var transaction = transactions.get(n);
        tasks.add(
            workers.submit(
                () -> {
                  try (transaction) {
                    transaction.finish().requireApplied();
                    String identity = transaction.identity();
                    ready.await(20, TimeUnit.SECONDS);
                    try {
                      transaction.commit().requireApplied();
                      return new Outcome(amount, identity, true);
                    } catch (IllegalStateException stale) {
                      assertEquals("Stale Contracts transaction", stale.getMessage());
                      return new Outcome(amount, identity, false);
                    }
                  }
                }));
      }
      var outcomes = new ArrayList<Outcome>();
      for (var task : tasks) outcomes.add(task.get(30, TimeUnit.SECONDS));
      var winners = outcomes.stream().filter(Outcome::committed).toList();
      assertEquals(1, winners.size());
      var winner = winners.getFirst();
      assertEquals(winner.identity(), session.identity());
      assertEquals(BigInteger.valueOf(winner.amount()), session.read("/count"));
      try (var restored = contracts.restore(session.snapshot(), session.identity());
          var old = contracts.restore(before, initial)) {
        assertEquals(session.identity(), restored.identity());
        assertEquals(BigInteger.valueOf(winner.amount()), restored.read("/count"));
        assertEquals(BigInteger.ZERO, old.read("/count"));
      }
    }
  }

  @Test
  void independentSessionsSharingOneConfigurationDoNotLeakState() throws Exception {
    var contracts = Contracts.create();
    try (var initial = contracts.openYaml(COUNTER);
        var workers = Executors.newFixedThreadPool(8)) {
      initial.initialize().requireApplied();
      String identity = initial.identity();
      var snapshot = initial.snapshot();
      var ready = new CountDownLatch(1);
      var tasks = new ArrayList<Future<Map<String, Object>>>();
      for (int n = 1; n <= 8; n++) {
        int amount = n;
        tasks.add(
            workers.submit(
                () -> {
                  try (var session = contracts.restore(snapshot, identity)) {
                    ready.await();
                    for (int step = 1; step <= 16; step++)
                      session
                          .process(Map.of("amount", amount, "sequence", step), step)
                          .requireApplied();
                    assertEquals(BigInteger.valueOf(16L * amount), session.read("/count"));
                    return session.snapshot();
                  }
                }));
      }
      ready.countDown();
      for (int n = 0; n < tasks.size(); n++) {
        var result = tasks.get(n).get(30, TimeUnit.SECONDS);
        try (var restored = contracts.restore(result, (String) result.get("identity"))) {
          assertEquals(BigInteger.valueOf(16L * (n + 1)), restored.read("/count"));
        }
      }
      assertEquals(identity, initial.identity());
      assertEquals(BigInteger.ZERO, initial.read("/count"));
    }
  }
}
