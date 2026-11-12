package blue;

import static blue.ContractModel.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Protected publication recovery is distinct from independent portable snapshot validation. */
@Tag("contracts")
class ContractMemberRecoveryTest {
  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void recoversEveryMemberWithTheSameExactBodyAndOwningProof(boolean cyclic) {
    var source = new ContractProcessor();
    ContractProcessor.Document document;
    if (cyclic) {
      document =
          source
              .admit(
                  m(
                      "a", CanonicalTransactionTest.cyclicMember("a", "b", false),
                      "b", CanonicalTransactionTest.cyclicMember("b", "a", false)),
                  "a")
              .document();
    } else {
      document =
          source.create(
              m(
                  "one",
                  m("count", 1),
                  "two",
                  m("count", 2),
                  "contracts",
                  m(
                      "embedded",
                      m("type", "Contracts/Process Embedded", "paths", List.of("/one", "/two")))));
    }
    var snapshot = source.exportSnapshot(document);
    var language = retained(snapshot);
    var contracts = Contracts.newBuilder().language(language).build();
    try (var independent = Contracts.create().restore(snapshot, document.identity())) {
      for (Object raw : arr(obj(snapshot.get("snapshot")).get("documents"))) {
        var row = obj(raw);
        String member = str(row.get("key"));
        var recovered = contracts.recoverMember(metadata(snapshot), document.identity(), member);
        var original = independent.member(member);
        assertEquals(original.blueId(), recovered.blueId());
        assertEquals(original.expand("").toJson(), recovered.expand("").toJson());
        assertEquals(records(original), records(recovered));
        assertEquals(Language.Goal.VALUE, recovered.goal());
      }
    }
  }

  @Test
  void selectedRecoveryDoesNotReadHistoryOrInstallEnvelopeContent() {
    Language language = Language.create();
    var history = language.readYaml("secretHistory: retained");
    language = language.toBuilder().content(history).build();
    var current = language.readYaml("count: 1\nhistory: {blueId: '" + history.blueId() + "'}");
    try (var session = Contracts.newBuilder().language(language).build().open(current)) {
      session.initialize().requireApplied();
      var snapshot = session.snapshot();
      var content = session.document().records();
      var reads = new AtomicInteger();
      var guarded =
          Language.newBuilder()
              .provider(
                  id -> {
                    if (id.equals(history.blueId()))
                      throw new AssertionError("Historical value fetched");
                    reads.incrementAndGet();
                    return Optional.ofNullable(content.get(id));
                  })
              .build();
      var contracts = Contracts.newBuilder().language(guarded).build();
      var recovered =
          contracts.recoverMember(metadata(snapshot), session.identity(), session.rootMember());
      assertEquals(session.blueId(), recovered.blueId());
      assertEquals(1, reads.get());
      assertEquals(1, recovered.at("/count").integer());
      assertThrows(AssertionError.class, () -> guarded.read(recovered.blueId()));
      assertThrows(
          AssertionError.class, () -> contracts.restore(metadata(snapshot), session.identity()));
      assertThrows(
          LanguageException.class,
          () ->
              Contracts.create().recoverMember(snapshot, session.identity(), session.rootMember()),
          "An envelope's body is not implicitly trusted or installed");
      var request =
          Language.Input.yaml("document: {blueId: '" + recovered.blueId() + "'}\nrequest: hello");
      var prepared = guarded.read(request, Language.Goal.DEFINITION, recovered);
      var ordinary = retained(snapshot).read(request, Language.Goal.DEFINITION);
      assertEquals(ordinary.blueId(), prepared.blueId());
    }
  }

  @Test
  void rejectsWrongCommitmentChangedMetadataUnknownMemberAndUnsupportedRelease() {
    try (var session = Contracts.create().openYaml("count: 1")) {
      session.initialize().requireApplied();
      var snapshot = session.snapshot();
      var contracts = Contracts.newBuilder().language(retained(snapshot)).build();
      assertThrows(
          BlueException.class,
          () -> contracts.recoverMember(snapshot, "wrong", session.rootMember()));
      assertThrows(
          BlueException.class,
          () -> contracts.recoverMember(snapshot, session.identity(), "absent"));
      var wire = new LinkedHashMap<>(obj(snapshot.get("snapshot")));
      wire.put("revision", 99);
      var altered = new LinkedHashMap<>(metadata(snapshot));
      altered.put("snapshot", wire);
      assertThrows(
          BlueException.class,
          () -> contracts.recoverMember(altered, session.identity(), session.rootMember()));
      wire.put("release", "unsupported-release");
      String future = digest("contracts.snapshot/completion-1", wire);
      altered.put("identity", future);
      assertThrows(
          BlueException.class,
          () -> contracts.recoverMember(altered, future, session.rootMember()));
      wire.put("release", obj(snapshot.get("snapshot")).get("release"));
      var rows = new ArrayList<>(arr(wire.get("documents")));
      rows.add(rows.getFirst());
      wire.put("documents", rows);
      String duplicate = digest("contracts.snapshot/completion-1", wire);
      altered.put("identity", duplicate);
      assertThrows(
          BlueException.class,
          () -> contracts.recoverMember(altered, duplicate, session.rootMember()));
    }
  }

  @Test
  void forgedSelectedBodyCannotAcquirePriorCompletion() {
    try (var session = Contracts.create().openYaml("count: 1")) {
      session.initialize().requireApplied();
      var language =
          Language.newBuilder()
              .provider(id -> Optional.of(LanguageRecord.json("{\"count\":99}")))
              .build();
      var contracts = Contracts.newBuilder().language(language).build();
      assertThrows(
          LanguageException.class,
          () ->
              contracts.recoverMember(
                  session.snapshot(), session.identity(), session.rootMember()));
    }
  }

  private static Map<String, Object> metadata(Map<String, Object> snapshot) {
    return Map.of(
        "snapshot",
        snapshot.get("snapshot"),
        "identity",
        snapshot.get("identity"),
        "content",
        Map.of());
  }

  private static Language retained(Map<String, Object> snapshot) {
    var content = obj(snapshot.get("content"));
    return Language.newBuilder()
        .provider(
            id -> {
              if (!content.containsKey(id.value())) return Optional.empty();
              var row = obj(content.get(id.value()));
              return Optional.of(
                  row.containsKey("proof")
                      ? LanguageRecord.cyclic(str(row.get("proof")), str(row.get("body")))
                      : LanguageRecord.json(str(row.get("text"))));
            })
        .build();
  }

  private static Map<BlueId, String> records(LanguageContent content) {
    var result = new HashMap<BlueId, String>();
    content.records().forEach((id, record) -> result.put(id, record.toJson()));
    return result;
  }
}
