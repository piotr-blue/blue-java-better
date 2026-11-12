package blue;

import static blue.ContractModel.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** One semantic oracle, exercised through direct, yielded, discarded and restored execution. */
@Tag("contracts")
@Tag("conformance")
class CanonicalTransactionTest {
  @Nested
  class Publication {
    @Test
    void inspectionStaysBoundToItsSnapshotAcrossCommitDiscardAndCompaction() {
      try (Contracts.Session session = CanonicalAgreementTest.open(Contracts.create(), 5)) {
        Contracts.Reception before = session.reception();
        Contracts.Graph beforeGraph = session.graph();
        String previous = session.identity();
        try (Contracts.Transaction discarded = session.begin(submit(), 100)) {
          assertNotEquals(previous, discarded.reception().snapshotIdentity());
          assertEquals(before, session.reception());
          assertNotEquals(beforeGraph, discarded.graph());
          assertEquals(beforeGraph, session.graph());
        }
        assertEquals(before, session.reception());
        assertEquals(beforeGraph, session.graph());
        Contracts.Reception candidate;
        Map<String, Object> snapshot;
        Contracts.Graph candidateGraph;
        Map<String, List<Contracts.Operation>> candidateOperations = new java.util.HashMap<>();
        try (Contracts.Transaction transaction = session.begin(submit(), 100)) {
          candidate = transaction.reception();
          snapshot = transaction.snapshot();
          candidateGraph = transaction.graph();
          for (var member : candidateGraph.members())
            candidateOperations.put(member.key(), transaction.operations(member.key()));
          assertThrows(BlueException.class, () -> transaction.operations("absent"));
          assertEquals(transaction.identity(), candidate.snapshotIdentity());
          assertEquals(transaction.identity(), snapshot.get("identity"));
          assertEquals(candidate, transaction.reception());
          transaction.commit().requireApplied();
        }
        assertEquals(candidate, session.reception());
        assertEquals(snapshot, session.snapshot());
        assertEquals(candidateGraph, session.graph());
        candidateOperations.forEach(
            (member, operations) -> assertEquals(operations, session.operations(member)));
        session.compact();
        assertEquals(candidate, session.reception());
        session.process(operation("payment-0", "settle", Map.of()), 101).requireApplied();
        assertNotEquals(candidate.snapshotIdentity(), session.reception().snapshotIdentity());
        assertEquals(previous, before.snapshotIdentity());
      }
    }

    @Test
    void remainsPrivateUntilCommittedAndCanBeDiscarded() {
      try (Contracts.Session session = CanonicalAgreementTest.open(Contracts.create(), 1)) {
        String before = session.identity();
        try (Contracts.Transaction transaction = session.begin(submit(), 100)) {
          transaction.finish().requireApplied();
          assertNotEquals(before, transaction.identity());
          assertEquals(before, session.identity());
        }
        assertEquals(before, session.identity());
        try (Contracts.Transaction transaction = session.begin(submit(), 100)) {
          String after = transaction.identity();
          transaction.commit().requireApplied();
          assertEquals(after, session.identity());
          assertThrows(IllegalStateException.class, transaction::advance);
        }
      }
    }

    @Test
    void concurrentCandidatesCannotOverwriteOneAnother() {
      try (Contracts.Session session = CanonicalAgreementTest.open(Contracts.create(), 1);
          Contracts.Transaction first = session.begin(submit(), 100);
          Contracts.Transaction stale = session.begin(submit(), 100)) {
        first.finish().requireApplied();
        stale.finish().requireApplied();
        first.commit();
        String committed = session.identity();
        assertThrows(IllegalStateException.class, stale::commit);
        assertEquals(committed, session.identity());
      }
    }
  }

  @Nested
  class Equivalence {
    @ParameterizedTest
    @ValueSource(ints = {1, 5, 16})
    void treeExecutionMatchesAfterEveryCompleteInvocation(int count) {
      Contracts contracts = Contracts.create();
      try (Contracts.Session direct = CanonicalAgreementTest.open(contracts, count);
          Contracts.Session incremental = contracts.restore(direct.snapshot(), direct.identity())) {
        compare(direct, incremental, submit(), 100, true);
        compare(direct, incremental, operation("payment-0", "settle", Map.of()), 101, true);
      }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void cyclicReactionAndFailureMatchTheInlineGraph(boolean fail) {
      ContractProcessor source = new ContractProcessor();
      ContractProcessor.Result initial =
          source.admit(
              m("a", cyclicMember("a", "b", false), "b", cyclicMember("b", "a", fail)), "a");
      assertEquals("APPLIED", initial.status());
      Contracts contracts = Contracts.create();
      try (Contracts.Session direct =
              contracts.restore(
                  source.exportSnapshot(initial.document()), initial.document().identity());
          Contracts.Session incremental = contracts.restore(direct.snapshot(), direct.identity())) {
        String before = direct.identity();
        compare(direct, incremental, m("target", "a"), 1, !fail);
        if (fail) assertEquals(before, incremental.identity());
        else {
          assertEquals("1", incremental.read("/count").toString());
          assertEquals("1", incremental.read("/peer/count").toString());
          assertEquals("1", incremental.read("/peer/peer/count").toString());
        }
      }
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3})
    void feedbackCyclesAdvanceInCanonicalRounds(int size) {
      Map<String, Object> members = new java.util.LinkedHashMap<>();
      for (int i = 0; i < size; i++)
        members.put("n" + i, feedbackMember("n" + i, "n" + ((i + 1) % size), false));
      ContractProcessor source = new ContractProcessor();
      ContractProcessor.Result initial = source.admit(members, "n0");
      assertEquals("APPLIED", initial.status());
      Contracts contracts = Contracts.create();
      try (Contracts.Session direct =
              contracts.restore(
                  source.exportSnapshot(initial.document()), initial.document().identity());
          Contracts.Session incremental = contracts.restore(direct.snapshot(), direct.identity())) {
        compare(direct, incremental, m("target", "n0"), 1, true);
        assertEquals("3", incremental.read("/count").toString());
        incremental.compact();
        assertEquals(direct.blueId(), incremental.blueId());
      }
    }

    @Test
    void changingAnEmbeddingBreaksACycleWithoutChangingTheCanonicalResult() {
      Map<String, Object> a = obj(cyclicMember("a", "b", false));
      Map<String, Object> contracts = obj(a.get("contracts"));
      contracts.put("cut", m("type", "Contracts/External Channel", "event", m("target", "cut")));
      contracts.put(
          "disconnect",
          m(
              "type",
              "Contracts/Sequential Workflow",
              "channel",
              "cut",
              "steps",
              List.of(
                  m(
                      "type",
                      "Contracts/Update Document",
                      "changeset",
                      List.of(
                          m(
                              "op",
                              "replace",
                              "path",
                              "/contracts/embedded/paths",
                              "val",
                              List.of()))))));
      ContractProcessor source = new ContractProcessor();
      ContractProcessor.Result initial =
          source.admit(m("a", a, "b", cyclicMember("b", "a", false)), "a");
      assertEquals("APPLIED", initial.status());
      Contracts runtime = Contracts.create();
      try (Contracts.Session direct =
              runtime.restore(
                  source.exportSnapshot(initial.document()), initial.document().identity());
          Contracts.Session candidate = runtime.restore(direct.snapshot(), direct.identity())) {
        compare(direct, candidate, m("target", "a"), 1, true);
        compare(direct, candidate, m("target", "cut"), 2, true);
        compare(direct, candidate, m("target", "a", "sequence", 2), 3, true);
        assertEquals(List.of(), candidate.read("/contracts/embedded/paths"));
      }
    }

    @Test
    void nonTerminatingFeedbackExhaustsTheSameBudgetWithoutPartialPublication() {
      ContractProcessor source = new ContractProcessor();
      ContractProcessor.Result initial =
          source.admit(m("n0", feedbackMember("n0", "n0", true)), "n0");
      assertEquals("APPLIED", initial.status());
      Contracts limited = Contracts.newBuilder().gasLimit(20_000).build();
      try (Contracts.Session direct =
              limited.restore(
                  source.exportSnapshot(initial.document()), initial.document().identity());
          Contracts.Session incremental = limited.restore(direct.snapshot(), direct.identity())) {
        String before = direct.identity();
        compare(direct, incremental, m("target", "n0"), 1, false);
        assertEquals(before, incremental.identity());
      }
    }

    @Test
    void budgetExhaustionRollsBackTheWholeGraph() {
      try (Contracts.Session initialized = CanonicalAgreementTest.open(Contracts.create(), 1)) {
        Contracts limited = Contracts.newBuilder().gasLimit(20).build();
        try (Contracts.Session direct =
                limited.restore(initialized.snapshot(), initialized.identity());
            Contracts.Session incremental =
                limited.restore(initialized.snapshot(), initialized.identity())) {
          String before = direct.identity();
          compare(direct, incremental, submit(), 100, false);
          assertEquals(before, incremental.identity());
        }
      }
    }
  }

  static void compare(
      Contracts.Session direct,
      Contracts.Session incremental,
      Object event,
      long timestamp,
      boolean applied) {
    Contracts.Transition expected = direct.process(event, timestamp);
    try (Contracts.Transaction transaction = incremental.begin(event, timestamp)) {
      String before = incremental.identity();
      int units = 0;
      while (transaction.advance()) {
        assertEquals(before, incremental.identity());
        assertTrue(++units < 100, "Scheduling did not terminate");
      }
      Contracts.Transition actual = transaction.finish();
      assertEquals(applied, actual.applied(), actual.observations().toString());
      assertEquals(expected.status(), actual.status());
      assertEquals(expected.gas(), actual.gas());
      assertEquals(expected.receipt(), actual.receipt());
      assertEquals(expected.observations(), actual.observations());
      assertEquals(direct.snapshot(), transaction.snapshot());
      transaction.commit();
      assertEquals(direct.identity(), incremental.identity());
      assertEquals(direct.blueId(), incremental.blueId());
    }
  }

  static Object cyclicMember(String name, String peer, boolean fail) {
    Object increment =
        m(
            "$appendChange",
            m(
                "op",
                "replace",
                "path",
                "/count",
                "val",
                m("$add", List.of(m("$document", "/count"), 1))));
    return m(
        "name",
        name,
        "count",
        0,
        "peer",
        ContractProcessor.link(peer),
        "contracts",
        m(
            "embedded", m("type", "Contracts/Process Embedded", "paths", List.of("/peer")),
            "input", m("type", "Contracts/External Channel", "event", m("target", name)),
            "run",
                m(
                    "type",
                    "Contracts/Sequential Workflow",
                    "channel",
                    "input",
                    "steps",
                    List.of(
                        m(
                            "type",
                            "Contracts/Compute",
                            "do",
                            List.of(increment, m("$appendEvent", m("kind", "Ping")))))),
            "peerEvents",
                m(
                    "type",
                    "Contracts/Embedded Node Channel",
                    "sourcePath",
                    "/peer",
                    "event",
                    m("kind", "Ping")),
            "react",
                m(
                    "type",
                    "Contracts/Sequential Workflow",
                    "channel",
                    "peerEvents",
                    "steps",
                    List.of(
                        m(
                            "type",
                            "Contracts/Compute",
                            "do",
                            fail
                                ? List.of(increment, m("$fail", "REJECTED_BY_PEER"))
                                : List.of(increment))))));
  }

  static Object feedbackMember(String name, String peer, boolean unbounded) {
    Map<String, Object> member = obj(cyclicMember(name, peer, false));
    Map<String, Object> react = obj(obj(member.get("contracts")).get("react"));
    Map<String, Object> compute = obj(arr(react.get("steps")).getFirst());
    Object increment = arr(compute.get("do")).getFirst();
    Object emit = m("$appendEvent", m("kind", "Ping"));
    compute.put(
        "do",
        List.of(
            increment,
            unbounded
                ? emit
                : m(
                    "$if",
                    m(
                        "cond",
                        m("$lt", List.of(m("$document", "/count"), 2)),
                        "then",
                        List.of(emit)))));
    return member;
  }

  static Object submit() {
    return operation("order-0", "submit", Map.of("payment", CanonicalAgreementTest.payment("0")));
  }

  static Object operation(String channel, String operation, Object request) {
    return m(
        "type",
        "Contracts/Operation Request",
        "channel",
        channel,
        "operation",
        operation,
        "request",
        request);
  }
}
