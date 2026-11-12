package blue;

import static blue.ContractModel.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Reused byte fragments must hash exactly like independently encoded snapshot wire data. */
@Tag("contracts")
class ContractSnapshotCommitmentTest {
  @ParameterizedTest(name = "{0}, previously encoded={1}")
  @MethodSource("changesBeforeCopy")
  void copyPreservesPendingChanges(String name, boolean encoded, Consumer<Snapshot> change) {
    Snapshot source = new Snapshot("root");
    source.documents.put("root", member("root", 0));
    source.documents.put("child", member("child", 1));
    Edge edge = new Edge("root", "/child", "child", 1);
    source.edges.put(edge.key(), edge);
    if (encoded) source.identity();

    change.accept(source);
    Snapshot candidate = source.copy();
    // Read the copy first: the source's pending cache invalidations have not been forced.
    String copied = candidate.identity();
    assertEquals(digest("contracts.snapshot/completion-1", source.wire()), copied);
    assertEquals(source.wire(), candidate.wire());
    assertCanonical(candidate);

    candidate.documents.put(
        "root", candidate.documents.get("root").withChannelSerial("independent", 99L));
    candidate.edges.clear();
    candidate.runtimes.add(Map.of("name", "independent"));
    assertCanonical(candidate);
    assertNotEquals(copied, candidate.identity());
    assertEquals(copied, source.identity());
  }

  private static Stream<Arguments> changesBeforeCopy() {
    Map<String, Consumer<Snapshot>> changes = new java.util.LinkedHashMap<>();
    changes.put("unchanged", s -> {});
    changes.put("root", s -> s.root = "child");
    changes.put("release", s -> s.release = "another release");
    changes.put("generation", s -> s.generation++);
    changes.put("revision", s -> s.revision++);
    changes.put("runtimes", s -> s.runtimes.add(Map.of("name", "runtime")));
    changes.put("new member", s -> s.documents.put("new", member("new", 2)));
    changes.put("removed member", s -> s.documents.remove("child"));
    changes.put("replaced member", s -> s.documents.put("child", member("child", 3)));
    changes.put(
        "birth", s -> s.documents.computeIfPresent("child", (key, d) -> d.withBirth("changed")));
    changes.put(
        "channel life",
        s ->
            s.documents.computeIfPresent(
                "root",
                (key, d) ->
                    d.withChannels(
                        d.channelLives.plus("ch", new Life(2, "sig")), d.channelSerials)));
    changes.put(
        "channel serial",
        s -> s.documents.computeIfPresent("root", (key, d) -> d.withChannelSerial("ch", 2L)));
    changes.put(
        "edge serial",
        s -> s.documents.computeIfPresent("root", (key, d) -> d.withEdgeSerial("/child", 2L)));
    changes.put("removed edge", s -> s.edges.clear());
    changes.put(
        "edge generation",
        s -> {
          Edge changed = new Edge("root", "/child", "child", 2);
          s.edges.put(changed.key(), changed);
        });
    return changes.entrySet().stream()
        .flatMap(
            entry ->
                Stream.of(false, true)
                    .map(encoded -> Arguments.of(entry.getKey(), encoded, entry.getValue())));
  }

  @Test
  void growingAndEditedSnapshotsRetainCanonicalCommitmentsAndCopyIsolation() {
    Snapshot source = new Snapshot("root");
    source.documents.put("root", member("root", 0));
    for (int n = 0; n < 128; n++) {
      String key = "child-" + n + "-é-𐀀";
      source.documents.put(key, member(key, n));
      Edge edge = new Edge("root", "/children/" + n, key, 1);
      source.edges.put(edge.key(), edge);
      source.documents.put("root", source.documents.get("root").withEdgeSerial(edge.path(), 1L));
      source.generation++;
      assertCanonical(source);
    }
    String before = source.identity();
    Snapshot candidate = source.copy();
    assertEquals(before, candidate.identity());
    for (int n = 0; n < 128; n += 3) {
      String key = "child-" + n + "-é-𐀀";
      candidate.documents.put(key, member(key, -n - 1));
      candidate.revision++;
      assertCanonical(candidate);
      assertEquals(before, source.identity());
    }
    var removed = candidate.edges.keySet().iterator().next();
    candidate.edges.remove(removed);
    candidate.runtimes = java.util.List.of(Map.of("name", "new runtime"));
    assertCanonical(candidate);
    assertNotEquals(source.identity(), candidate.identity());
    assertCanonical(source);
  }

  @Test
  void identityAndWireInvalidateIndependentlyWhenRuntimeMetadataChanges() {
    Snapshot source = new Snapshot("root");
    source.documents.put("root", member("root", 0));
    var runtime = new java.util.LinkedHashMap<String, Object>(Map.of("version", 1L));
    source.runtimes.add(runtime);
    String before = source.identity();
    var beforeWire = source.wire();
    runtime.put("version", 2L);
    String changed = source.identity();
    assertNotEquals(before, changed);
    assertEquals(digest("contracts.snapshot/completion-1", source.wire()), changed);
    assertEquals(before, digest("contracts.snapshot/completion-1", beforeWire));
    runtime.put("version", 3L);
    var changedWire = source.wire();
    assertNotEquals(changed, source.identity());
    assertEquals(digest("contracts.snapshot/completion-1", changedWire), source.identity());
    assertCanonical(source.copy());
  }

  @Test
  void preparedProofRecordsKeepCanonicalBytesAndOwnTheirInput() {
    var child = new java.util.ArrayList<Object>(java.util.List.of("a\n", "é", "𐀀", true, 42L));
    var source = new java.util.LinkedHashMap<String, Object>();
    source.put("\uE000", child);
    source.put("𐀀", "quoted\"value");
    String expected = ContractCanonical.text(source);
    var prepared = new ContractCanonical.EncodedRecord(source);
    assertEquals(expected, ContractCanonical.text(prepared));
    assertSame(prepared, ContractWire.immutable(prepared));
    assertEquals(digest("proof-test", source), digest("proof-test", prepared));
    child.clear();
    source.clear();
    assertEquals(expected, ContractCanonical.text(prepared));
    assertThrows(UnsupportedOperationException.class, () -> prepared.put("changed", true));
    assertEquals("[" + expected + "]", ContractCanonical.text(java.util.List.of(prepared)));
  }

  private static Document member(String key, int count) {
    var body =
        ExactNode.value(
            Map.of(
                "name",
                key,
                "count",
                ExactNode.scalar("Integer", java.math.BigInteger.valueOf(count))));
    return new Document(key, body, ContractLanguage.identity(body), null);
  }

  private static void assertCanonical(Snapshot snapshot) {
    assertEquals(digest("contracts.snapshot/completion-1", snapshot.wire()), snapshot.identity());
  }
}
