package blue;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Metadata revisions retain only unchanged, privately frozen collection projections. */
class ContractMetadataSharingTest {
  static ContractModel.Document document(int count) {
    var edges = new TreeMap<String, Long>();
    for (int i = 0; i < count; i++) edges.put("/children/child-" + i, 1L);
    edges.put("/children/\uE000", 2L);
    edges.put("/children/\uD83D\uDE00", 3L);
    return new ContractModel.Document(
        "root",
        ExactNode.value(Map.of()),
        "before",
        "birth",
        Map.of("input", new ContractModel.Life(1, "signature")),
        Map.of("input", 1L),
        edges);
  }

  static ContractModel.Document fresh(ContractModel.Document value) {
    return new ContractModel.Document(
        value.key,
        value.body,
        value.blueId,
        value.birth,
        value.channelLives,
        value.channelSerials,
        value.edgeSerials);
  }

  @ParameterizedTest
  @ValueSource(ints = {10, 1000})
  void scalarRevisionDoesNotCopyUnchangedAttachmentMetadata(int count) {
    var source = document(count);
    var before = source.metadata();
    var after = source.withBlueId("after");
    var result = after.metadata();
    assertEquals("before", before.get("blueId"));
    assertEquals("after", result.get("blueId"));
    for (String field : List.of("channels", "channelSerials", "edgeSerials"))
      assertSame(before.get(field), result.get(field), field);
    var lives = new TreeMap<String, Object>();
    source.channelLives.forEach((k, v) -> lives.put(k, v.wire()));
    var plain =
        ContractWire.immutable(
            ContractModel.m(
                "key",
                after.key,
                "blueId",
                after.blueId,
                "channels",
                lives,
                "channelSerials",
                after.channelSerials,
                "edgeSerials",
                after.edgeSerials,
                "birth",
                after.birth));
    assertEquals(ContractCanonical.text(plain), ContractCanonical.text(result));
    assertEquals(ContractModel.digest("metadata", plain), ContractModel.digest("metadata", result));
    assertEquals(ContractCanonical.text(fresh(after).metadata()), ContractCanonical.text(result));
    assertEquals(
        ContractModel.digest("metadata", fresh(after).metadata()),
        ContractModel.digest("metadata", result));
  }

  @Test
  void attachmentGenerationChangesCannotReuseAnOldProjection() {
    var source = document(32);
    var before = source.metadata();
    var after = source.withEdgeSerial("/children/child-0", 9L);
    assertEquals(1L, ContractModel.obj(before.get("edgeSerials")).get("/children/child-0"));
    assertEquals(
        9L, ContractModel.obj(after.metadata().get("edgeSerials")).get("/children/child-0"));
    assertEquals(fresh(after).metadata(), after.metadata());
  }

  @Test
  void channelLifetimeAndSerialChangesCannotReuseOldProjections() {
    var source = document(32);
    var before = source.metadata();
    var after =
        source.withChannels(
            source.channelLives.plus("input", new ContractModel.Life(2, "next")),
            source.channelSerials.plus("input", 2L));
    assertEquals(fresh(after).metadata(), after.metadata());
    assertNotEquals(before.get("channels"), after.metadata().get("channels"));
    assertEquals(1L, ContractModel.obj(before.get("channelSerials")).get("input"));
    assertEquals(2L, ContractModel.obj(after.metadata().get("channelSerials")).get("input"));
  }

  @Test
  void sharedNestedMetadataCannotBeModifiedThroughThePublishedMap() {
    var value = document(32).metadata();
    assertThrows(
        UnsupportedOperationException.class,
        () -> ContractModel.obj(value.get("edgeSerials")).put("unexpected", 7));
    assertThrows(
        UnsupportedOperationException.class,
        () ->
            ContractModel.obj(ContractModel.obj(value.get("channels")).get("input"))
                .put("generation", 9));
  }

  @Test
  void concurrentRevisionForksKeepTheirOwnIdentitiesAndTheSameFrozenCollections() throws Exception {
    var source = document(64);
    var before = source.metadata();
    try (var executor = Executors.newFixedThreadPool(4)) {
      var work = new ArrayList<Callable<Map<String, Object>>>();
      for (int i = 0; i < 16; i++) {
        String identity = "fork-" + i;
        work.add(() -> source.withBlueId(identity).metadata());
      }
      var results = executor.invokeAll(work);
      for (int i = 0; i < results.size(); i++) {
        var value = results.get(i).get();
        assertEquals("fork-" + i, value.get("blueId"));
        assertSame(before.get("edgeSerials"), value.get("edgeSerials"));
      }
    }
    assertEquals("before", source.metadata().get("blueId"));
  }
}
