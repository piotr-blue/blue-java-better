package blue;

import static blue.ContractModel.m;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Current-state maintenance has the same boundary as portable recovery, without wire conversion.
 */
@Tag("contracts")
@Tag("conformance")
class ContractCompactionTest {
  @Test
  void dropsUnreachableEvidenceAndProcessViewsWhilePreservingBorrowedValues() {
    ContractProcessor source = new ContractProcessor();
    var historical = source.initialize(m("discarded", "unused history")).document();
    var selected = source.initialize(m("list", List.of("first", "second"), "count", 1)).document();
    LanguageDocument borrowed =
        source.blue.context.document(historical.state.documents.get(historical.state.root).body);
    var portable = source.exportSnapshot(selected);
    ContractProcessor target = new ContractProcessor();
    var current = ContractStorage.compact(source, target, selected.state);
    assertEquals(portable, target.exportSnapshot(new ContractProcessor.Document(current)));
    assertEquals(1, target.archive.versions.size());
    assertNull(target.archive.get(historical.blueId()));
    assertThrows(
        LanguageFailure.class,
        () -> target.blue.context.open(new ExactNode.Ref(historical.blueId())));
    assertEquals("unused history", borrowed.at("/discarded").text());

    current.documents.put(
        current.root, current.documents.get(current.root).withChannelSerial("private", 1L));
    assertFalse(
        selected.state.documents.get(selected.state.root).channelSerials.containsKey("private"));
    assertEquals(portable, source.exportSnapshot(selected));
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void cyclesAndTreesMatchPortableRecoveryForTheNextInvocation(boolean cyclic) {
    ContractProcessor source = new ContractProcessor();
    var initial =
        cyclic
            ? source.admit(
                m(
                    "a",
                    CanonicalTransactionTest.cyclicMember("a", "b", false),
                    "b",
                    CanonicalTransactionTest.cyclicMember("b", "a", false)),
                "a")
            : source.initialize(
                m(
                    "one",
                    m("values", List.of(1, 2, 3)),
                    "two",
                    m("values", List.of(1, 2, 3)),
                    "contracts",
                    m(
                        "embedded",
                        m("type", "Contracts/Process Embedded", "paths", List.of("/one", "/two")),
                        "input",
                        m("type", "Contracts/External Channel"))));
    assertEquals("APPLIED", initial.status());
    Contracts contracts = Contracts.create();
    try (Contracts.Session session =
        contracts.restore(
            source.exportSnapshot(initial.document()), initial.document().identity())) {
      session.process(m("target", "a"), 1).requireApplied();
      var before = session.snapshot();
      var borrowed = session.document();
      try (Contracts.Session recovered = contracts.restore(before, session.identity());
          Contracts.Transaction obsolete = session.begin(m("target", "a", "sequence", 2), 2)) {
        session.compact();
        assertEquals(before, session.snapshot());
        assertEquals(borrowed.blueId(), session.blueId());
        assertThrows(IllegalStateException.class, obsolete::commit);
        CanonicalTransactionTest.compare(
            recovered, session, m("target", "a", "sequence", 3), 3, true);
      }
    }
  }
}
