package blue;

import static blue.ContractModel.integer;
import static blue.ContractModel.m;
import static blue.ContractModel.obj;
import static blue.ContractModel.str;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("contracts")
class CanonicalPortableTest {
  @Test
  void nativePortableAndColdInvocationAgreeForBothEditions() {
    for (var profile : List.of(ContractBindings.dispatch(), ContractBindings.current())) {
      var processor = new ContractProcessor(profile);
      var before = processor.create(counter());
      var event = m("tick", 1);
      var direct = processor.process(before, event, 10);
      assertEquals("APPLIED", direct.status());
      assertEquals("1", processor.read(direct.document(), "/count").toString());
      assertEquals(List.of(m("kind", "counted")), processor.emitted(direct));
      var attempt = processor.begin(before, event, 10);
      int chunks = 0;
      for (var token = attempt.nextChunk(); token != null; token = attempt.nextChunk()) {
        var detached = new ContractProcessor(profile).executeChunk(token.toData());
        var local = processor.executeChunk(token);
        assertEquals(ContractWire.write(local.toData()), ContractWire.write(detached.toData()));
        attempt.accept(token, detached.toData());
        chunks++;
      }
      assertTrue(chunks > 0);
      var chunked = attempt.finish();
      assertEquals(
          ContractWire.write(direct.observations()), ContractWire.write(chunked.observations()));
      assertEquals(direct.receipt(), chunked.receipt());
      var capsule = processor.exportInvocation(before, event, 10);
      var cold = new ContractProcessor(profile);
      var restored = cold.process(cold.importInvocation(capsule, str(capsule.get("identity"))));
      assertEquals(
          ContractWire.write(direct.observations()), ContractWire.write(restored.observations()));
      assertEquals(direct.receipt(), restored.receipt());
    }
  }

  static Map<String, Object> counter() {
    return m(
        "count",
        0,
        "contracts",
        m(
            "input",
            m("type", "Contracts/External Channel"),
            "go",
            m(
                "type",
                "Contracts/Sequential Workflow",
                "channel",
                "input",
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
                                "/count",
                                "val",
                                m("$add", List.of(m("$document", "/count"), 1))))),
                    m("type", "Contracts/Trigger Event", "event", m("kind", "counted"))))));
  }

  @Test
  void acceptanceRejectsForgedReplayAndDuplicateCapabilities() {
    var p = new ContractProcessor(ContractBindings.dispatch());
    var state = p.create(counter());
    var a = p.begin(state, m("tick", 1), 10);
    assertEquals("UNFINISHED_ATTEMPT", assertThrows(ContractFault.class, a::finish).code());
    var token = a.nextChunk();
    var r = a.execute(token);
    var bad = r.toData();
    bad.put("gas", integer(bad.get("gas")) + 1);
    bad.remove("identity");
    var forged = ContractRun.committed("chunk-result", bad);
    assertEquals(
        "INVALID_CHUNK_EXECUTION",
        assertThrows(ContractFault.class, () -> a.accept(token, forged)).code());
    assertEquals(
        "WRONG_CHUNK_EXECUTOR",
        assertThrows(
                ContractFault.class,
                () -> new ContractProcessor(ContractBindings.dispatch()).executeChunk(token))
            .code());
    var descriptor = token.descriptor();
    descriptor.put("phase", "forged");
    assertNotEquals(descriptor, token.descriptor());
    var returned = r.toData();
    returned.put("gas", 999);
    assertNotEquals(returned, r.toData());
    a.accept(token, r);
    assertThrows(ContractFault.class, () -> a.accept(token, r));
    assertThrows(ContractFault.class, token::toData);
    for (var next = a.nextChunk(); next != null; next = a.nextChunk()) {
      a.accept(next, a.execute(next));
    }
    assertEquals(
        ContractWire.write(p.process(state, m("tick", 1), 10).observations()),
        ContractWire.write(a.finish().observations()));
    assertEquals("ATTEMPT_FINISHED", assertThrows(ContractFault.class, a::finish).code());
  }

  @Test
  void capsulePinsPolicyAndRejectsAlteredCommitment() {
    var p = new ContractProcessor(ContractBindings.dispatch());
    var state = p.create(counter());
    var capsule = p.exportInvocation(state, m("tick", 1), 10);
    var cold = new ContractProcessor(Language.create(), ContractBindings.dispatch(), 0, Map.of());
    var invocation = cold.importInvocation(capsule, str(capsule.get("identity")));
    assertEquals(10000000, invocation.gasPolicy().sharedLimit());
    assertEquals(p.process(state, m("tick", 1), 10).receipt(), cold.process(invocation).receipt());
    obj(capsule.get("invocation")).put("eventTimestamp", 11);
    assertEquals(
        "INVALID_CAPSULE_COMMITMENT",
        assertThrows(
                ContractFault.class,
                () -> cold.importInvocation(capsule, str(capsule.get("identity"))))
            .code());
  }
}
