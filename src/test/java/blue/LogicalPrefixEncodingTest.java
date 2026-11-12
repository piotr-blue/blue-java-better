package blue;

import static blue.ContractModel.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Physical prefix reuse preserves the existing digest and speculative-fork isolation. */
@Tag("contracts")
class LogicalPrefixEncodingTest {
  @Test
  void preparedFieldsAndPartitionsPreserveCanonicalBytesAndReplacement() {
    var expected = new TreeMap<String, Object>(ContractCanonical.CODE_POINTS);
    var fields = new TreeMap<String, ContractCanonical.Field>(ContractCanonical.CODE_POINTS);
    for (String key : List.of("😀", "\uE000", "escaped\n\"", "", "😀")) {
      Object value = expected.size() == 2 ? null : Long.MAX_VALUE;
      expected.put(key, value);
      fields.put(key, new ContractCanonical.Field(key, value));
      assertEquals(
          ContractCanonical.text(expected),
          ContractCanonical.text(new ContractCanonical.Fields(fields.values())));
    }
    var processor = new ContractProcessor();
    var work = new ContractWork(processor.blue, new Meter(1_000_000));
    var group = new ArrayList<>(List.of("😀", "\uE000"));
    work.partition(List.of(group));
    Object first = work.partitionEncoding();
    assertSame(first, work.partitionEncoding());
    group.clear();
    assertEquals("[[\"😀\",\"\uE000\"]]", ContractCanonical.text(first));
    work.partition(List.of(List.of("replacement")));
    assertNotSame(first, work.partitionEncoding());
    assertEquals(
        ContractCanonical.text(work.partition()), ContractCanonical.text(work.partitionEncoding()));
    work.partition(null);
    assertEquals("null", ContractCanonical.text(work.partitionEncoding()));
  }

  @Test
  void logicalPrefixMatchesPlainEncodingAcrossChargesWorkAndForks() {
    var processor =
        new ContractProcessor(
            Language.create(), ContractBindings.core().withGasSchedule(GasSchedule.STANDARD));
    var document = processor.create(Map.of("counter", 0));
    var run = new ContractRun(processor, document.state, null, null, true);
    var payload = processor.blue.source(Map.of("value", "event"));
    run.graph.logical.partition(List.of(List.of(document.state.root)));
    for (int index = 0; index < 20; index++) {
      run.meter.owner = "member-" + index;
      run.meter.contracts("localTurnOpened");
      run.workRecord(document.state.root, "input", payload, "INITIALIZATION");
      assertEquals(plainCommitment(run), run.logicalCommitment());
    }
    var fork = ContractRunFork.copy(run);
    String original = run.logicalCommitment();
    fork.meter.owner = "fork";
    fork.meter.mergeAdmitted(7);
    fork.workRecord(document.state.root, "fork", payload, "INITIALIZATION");
    fork.graph.logical.partition(List.of(List.of("fork")));
    assertEquals(plainCommitment(fork), fork.logicalCommitment());
    assertEquals(original, run.logicalCommitment());
    assertNotEquals(original, fork.logicalCommitment());
    assertThrows(
        UnsupportedOperationException.class,
        () -> obj(run.work.getFirst()).put("identity", "changed"));
  }

  private static String plainCommitment(ContractRun run) {
    var logical = run.graph.logical;
    return digest(
        "contracts.logical-prefix/completion-1",
        m(
            "gas", m("used", run.meter.used, "localUsed", new TreeMap<>(run.meter.localUsed)),
            "work", ContractWire.json(ContractWire.write(run.work)),
            "manifests", logical.manifests.encoding(),
            "established", logical.established.encoding(),
            "proofs", logical.proofs.encoding(),
            "partition", logical.partition(),
            "boundIdentities", logical.boundIdentities.encoding(),
            "exactValues", logical.exactEncoding(),
            "carried", logical.carriedRoots.encoding()));
  }
}
