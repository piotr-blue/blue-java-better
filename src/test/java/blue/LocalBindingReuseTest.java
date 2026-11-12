package blue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Attachment commitments must be unchanged by scalars and invalidated by exact binding inputs. */
class LocalBindingReuseTest {
  private final List<Fixture> fixtures = new ArrayList<>();

  private Fixture fixture(int count) {
    var fixture = new Fixture(count);
    fixtures.add(fixture);
    return fixture;
  }

  @AfterEach
  void closeFixtures() {
    fixtures.forEach(Fixture::close);
  }

  @ParameterizedTest
  @ValueSource(ints = {1, 32, 257})
  void scalarPublicationKeepsTheSameExactBindingsAfterColdRecovery(int count) {
    var f = fixture(count);
    var initial = f.publish();
    for (int i = 1; i <= 3; i++) {
      f.body(f.body().with("count", ExactNode.scalar("Integer", BigInteger.valueOf(i))));
      var next = f.publish();
      assertEquals(initial.get("bindings"), next.get("bindings"));
      assertNotEquals(initial.get("identity"), next.get("identity"));
      f.verify(next);
    }
  }

  @Test
  void changedAndRemovedCollectionEntriesCannotReusePriorBindings() {
    var f = fixture(3);
    var before = f.publish();
    var children = f.selected("/children");
    var different =
        f.engine.blue.exact(f.engine.blue.select(f.document().body, "/children/quiet1"));
    f.body(f.body().with("children", children.with("active", different)));
    var changed = f.publish();
    assertNotEquals(before.get("bindings"), changed.get("bindings"));
    f.verify(changed);
    f.body(f.body().with("children", new ExactNode.Value(Map.of())));
    var removed = f.publish();
    assertEquals(
        ContractModel.digest("contracts.local-bindings/1", Map.of()), removed.get("bindings"));
    assertNotEquals(changed.get("bindings"), removed.get("bindings"));
  }

  @Test
  void changedGenerationInvalidatesOtherwiseIdenticalSelectors() {
    var f = fixture(3);
    var before = f.publish();
    f.state.documents.put("root", f.document().withEdgeSerial("/children/active", 2L));
    var changed = f.publish();
    assertNotEquals(before.get("bindings"), changed.get("bindings"));
    f.verify(changed);
  }

  @Test
  void changingTheEmbeddingDeclarationChangesTheCommittedSelection() {
    var f = fixture(3);
    var before = f.publish();
    var declaration =
        f.engine.blue.source(
            Map.of("type", "Contracts/Process Embedded", "paths", List.of("/children/active")));
    var contracts = f.selected("/contracts");
    f.body(f.body().with("contracts", contracts.with("embedded", declaration)));
    var changed = f.publish();
    assertNotEquals(before.get("bindings"), changed.get("bindings"));
    String target =
        ContractLanguage.identity(
            f.engine.blue.exact(f.engine.blue.select(f.document().body, "/children/active")));
    assertEquals(
        ContractModel.digest(
            "contracts.local-bindings/1", Map.of("/children/active", List.of(1L, target))),
        changed.get("bindings"));
  }

  @Test
  void legacyCheckpointWithoutBindingsRebuildsAnExactCommitment() {
    var f = fixture(3);
    var expected = f.publish();
    var legacy = new LinkedHashMap<>(expected);
    legacy.remove("bindings");
    legacy.remove("identity");
    legacy.put("identity", ContractModel.digest("contracts.local-state/1", legacy));
    f.state = ContractLocalCheckpoint.restore(f.engine, legacy);
    assertEquals(expected, f.publish());
  }

  private static final class Fixture implements AutoCloseable {
    final ContractLanguage.ValidationScope scope;

    @Override
    public void close() {
      scope.close();
    }

    final ContractProcessor engine;
    ContractModel.Snapshot state;

    Fixture(int count) {
      var sources = ContractsFixtures.members(count);
      ContractModel.obj(
              ContractModel.obj(ContractModel.obj(sources.get("child")).get("contracts"))
                  .get("input"))
          .remove("event");
      var creator =
          new ContractProcessor(
              Language.create(), ContractBindings.core().withGasSchedule(GasSchedule.STANDARD));
      var initial = creator.admit(sources, "root");
      assertEquals("APPLIED", initial.status());
      var checkpoint = ContractLocalCheckpoint.checkpoint(creator, initial.document().state);
      var records = ContractModel.obj(creator.exportSnapshot(initial.document()).get("content"));
      engine =
          new ContractProcessor(
              Language.newBuilder()
                  .provider(
                      id ->
                          Optional.ofNullable(records.get(id.value()))
                              .map(raw -> new LanguageRecord(ContractModel.obj(raw))))
                  .build(),
              ContractBindings.core().withGasSchedule(GasSchedule.STANDARD));
      scope = engine.blue.context.validationScope(key -> Optional.empty());
      try {
        state = ContractLocalCheckpoint.restore(engine, checkpoint);
      } catch (RuntimeException | Error failure) {
        scope.close();
        throw failure;
      }
    }

    ContractModel.Document document() {
      return state.documents.get("root");
    }

    ExactNode.Value body() {
      return engine.blue.open(document().body);
    }

    ExactNode.Value selected(String path) {
      return engine.blue.open(engine.blue.exact(engine.blue.select(document().body, path)));
    }

    void body(ExactNode.Value body) {
      engine.blue.remember(body);
      state.documents.put("root", document().finalized(body));
    }

    Map<String, Object> publish() {
      return ContractLocalCheckpoint.checkpoint(engine, state);
    }

    void verify(Map<String, Object> checkpoint) {
      var expected = new TreeMap<String, Object>();
      var children = selected("/children");
      for (String name : children.ordinary()) {
        String path = "/children/" + name;
        expected.put(
            path,
            List.of(
                document().edgeSerials.get(path),
                ContractLanguage.identity(
                    engine.blue.exact(engine.blue.select(document().body, path)))));
      }
      assertEquals(
          ContractModel.digest("contracts.local-bindings/1", expected), checkpoint.get("bindings"));
    }
  }
}
