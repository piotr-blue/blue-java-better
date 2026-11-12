package blue;

import static blue.ContractModel.digest;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import blue.ContractModel.Document;
import blue.ContractModel.Edge;
import blue.ContractModel.Snapshot;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Immutable member sharing must preserve branch isolation and exact concurrent proof reads. */
@Tag("contracts")
class ContractSnapshotSharingTest {
  @Test
  void forkSharesTheIndexButAnEditCannotChangeTheOriginalMetadataOrCommitment() {
    var source = new Snapshot("root");
    for (int i = 0; i < 1000; i++) {
      String key = i == 0 ? "root" : "member-" + i;
      source.documents.put(key, new Document(key, ExactNode.value(Map.of()), "body-" + i, null));
    }
    String identity = source.identity();
    Map<String, Object> wire = source.wire();
    var fork = source.copy();
    assertSame(source.documents.content(), fork.documents.content());
    assertSame(wire, fork.wire());
    assertSame(source.documents.get("member-875"), fork.documents.get("member-875"));
    var member = fork.documents.get("member-875");
    Map<String, Long> serials = member.channelSerials;
    assertThrows(UnsupportedOperationException.class, () -> serials.put("input", 2L));
    fork.documents.put(member.key, member.withChannelSerial("input", 2L));
    assertSame(source.documents.structure(), fork.documents.structure());
    assertNotSame(source.documents.content(), fork.documents.content());
    assertEquals(identity, source.identity());
    assertSame(wire, source.wire());
    assertNotEquals(identity, fork.identity());
    assertTrue(source.documents.get(member.key).channelSerials.isEmpty());
    assertEquals(digest("contracts.snapshot/completion-1", fork.wire()), fork.identity());
  }

  @Test
  @Tag("concurrency")
  void sharedProofCachesCannotMixTheViewsOrTariffsOfDifferentSnapshotBranches() throws Exception {
    var member = new Document("root", ExactNode.value(Map.of()), "body", null);
    var start = new CountDownLatch(1);
    try (var workers = Executors.newFixedThreadPool(4)) {
      List<Future<?>> results = new ArrayList<>();
      for (int worker = 0; worker < 4; worker++) {
        int selected = worker;
        results.add(
            workers.submit(
                () -> {
                  start.await();
                  List<Edge> edges =
                      List.of(new Edge("root", "/child", "child-" + selected, selected + 1));
                  var expectedEdges = edges.stream().map(Edge::wire).toList();
                  boolean finalGas = selected % 2 == 0;
                  for (int i = 0; i < 500; i++) {
                    var view = member.view(edges);
                    assertEquals(expectedEdges, view.get("edges"));
                    var idle = member.idleUnit(view, finalGas);
                    assertEquals(Map.of("root", view), idle.get("beforeViews"));
                    assertEquals(finalGas, idle.containsKey("gas"));
                    var unsigned = new LinkedHashMap<>(idle);
                    Object identity = unsigned.remove("identity");
                    assertEquals(digest("contracts.unit-result/completion-1", unsigned), identity);
                  }
                  return null;
                }));
      }
      start.countDown();
      for (var result : results) {
        result.get(20, TimeUnit.SECONDS);
      }
    }
  }
}
