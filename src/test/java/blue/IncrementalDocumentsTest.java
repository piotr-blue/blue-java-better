package blue;

import static blue.ContractModel.m;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The shipped Blue functions execute inside ordinary canonical Contracts workflows. */
@Tag("contracts")
@Tag("packages")
class IncrementalDocumentsTest {
  static final BluePackage PACKAGE = load();
  static final Language LANGUAGE =
      Language.newBuilder().mount("IncrementalDocuments", PACKAGE).build();
  static final Contracts CONTRACTS = Contracts.newBuilder().language(LANGUAGE).build();
  static final String ORIGIN = LANGUAGE.readYaml("label: Original child").blueId().value();

  static BluePackage load() {
    try (var stream =
        IncrementalDocumentsTest.class.getResourceAsStream(
            "/clauses/incremental-documents.bluepkg")) {
      return PackageLoader.create().read(stream);
    } catch (java.io.IOException e) {
      throw new java.io.UncheckedIOException(e);
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"one", "a/b", "a~b", "a~1/b"})
  void selectsOnlyTheChangedEntryIncludingEscapedKeys(String key) {
    try (Contracts.Session session = open(key, 12, false)) {
      var result =
          session.process(
              m("sourcePath", "/documents/" + escape(key), "event", m("kind", "Changed")), 1);
      result.requireApplied();
      assertEquals(
          "usable",
          session.read(itemPath(key) + "/status"),
          () -> session.read("/documents/" + escape(key)).toString() + result.events());
      for (int i = 0; i < 12; i++) {
        assertEquals("selected", session.read(itemPath("other" + i) + "/status"));
      }
      assertEquals(1, result.events().size());
      var repeated =
          session
              .process(
                  m(
                      "sourcePath",
                      "/documents/" + escape(key),
                      "event",
                      m("kind", "Changed"),
                      "sequence",
                      2),
                  2)
              .requireApplied();
      assertTrue(repeated.events().isEmpty());
    }
  }

  @Test
  void aWholeCollectionUpdateRefreshesAllEntries() {
    try (Contracts.Session session = open("one", 4, false)) {
      session.process(m("path", "/documents", "op", "replace"), 1).requireApplied();
      assertEquals("usable", session.read(itemPath("one") + "/status"));
      for (int i = 0; i < 4; i++) {
        assertEquals("usable", session.read(itemPath("other" + i) + "/status"));
      }
    }
  }

  @Test
  void unrelatedEmbeddedEventsDoNotTouchTheCollection() {
    try (Contracts.Session session = open("one", 4, false)) {
      var result = session.process(m("sourcePath", "/documents-other/one"), 1).requireApplied();
      assertTrue(result.events().isEmpty());
      assertEquals("selected", session.read(itemPath("one") + "/status"));
    }
  }

  @Test
  void unknownHooksKeepCollectionWideSemantics() {
    try (Contracts.Session session = open("one", 4, true)) {
      session.process(m("sourcePath", "/documents/one"), 1).requireApplied();
      for (int i = 0; i < 4; i++) {
        assertEquals("usable", session.read(itemPath("other" + i) + "/status"));
      }
    }
  }

  @Test
  void publishedClauseInstallsItsOwnHandlersAndCreatesAnEmbeddedDocument() {
    Language language =
        Language.newBuilder()
            .mount("IncrementalDocuments", PACKAGE)
            .mount("Clauses", PACKAGE.imported("Clauses"))
            .mount("Activation", PACKAGE.imported("Activation"))
            .mount("Parties", PACKAGE.imported("Activation").imported("Parties"))
            .mount("Agreements", PACKAGE.imported("Connections").imported("Agreements"))
            .build();
    Contracts contracts =
        Contracts.newBuilder().language(language).enableDocumentDispatch().build();
    String yaml =
        """
        type: Agreements/Agreement with Clauses
        documents: {}
        clauses:
          activation:
            type: Activation/Activation Clause
            parameters:
              parties:
                owner:
                  type: Parties/Party
                  readerKeys: [test-reader]
                  lead:
                    channel: {type: Contracts/External Channel, event: {source: owner}}
              required: []
          documents:
            type: IncrementalDocuments/Documents Clause
            parameters:
              obtain: create
              template:
                type: Agreements/Agreement with Clauses
                label: Managed child
                count: 0
                contracts:
                  counter: {type: Contracts/External Channel, event: {target: child}}
                  increment:
                    type: Contracts/Sequential Workflow Operation
                    channel: counter
                    steps:
                    - type: Contracts/Update Document
                      changeset:
                      - op: replace
                        path: /count
                        val: {$add: [{$document: /count}, 1]}
                    - type: Contracts/Trigger Event
                      event: {kind: Counter Changed}
                clauses:
                  activation:
                    type: Activation/Activation Clause
                    parameters:
                      parties:
                        owner:
                          type: Parties/Party
                          readerKeys: [test-reader]
                          lead:
                            channel: {type: Contracts/External Channel, event: {source: owner}}
                      required: []
            bindings:
              collection: /documents
              parties: {owner: owner}
              prepare:
                first:
                  on: initialized
                  params: {key: first}
        contracts:
          owner: {type: Contracts/External Channel, event: {source: owner}}
        """;
    try (Contracts.Session session = contracts.openYaml(yaml)) {
      var initialized = session.initialize();
      assertTrue(initialized.applied(), initialized.observations().toString());
      assertEquals("Managed child", session.read("/documents/first/label"));
      assertEquals("usable", session.read(itemPath("first") + "/status"));
      try (Contracts.Session candidate =
          contracts.restore(session.snapshot(), session.identity())) {
        Object input =
            m(
                "target",
                "child",
                "operation",
                m(
                    "type",
                    "Contracts/Operation Request",
                    "channel",
                    "counter",
                    "operation",
                    "increment",
                    "request",
                    m()));
        CanonicalTransactionTest.compare(session, candidate, input, 1, true);
        assertEquals("1", candidate.read("/documents/first/count").toString());
        assertEquals("usable", candidate.read(itemPath("first") + "/status"));
      }
    }
  }

  static Contracts.Session open(String selected, int others, boolean global) {
    Map<String, Object> documents = new LinkedHashMap<>(), entries = new LinkedHashMap<>();
    add(documents, entries, selected);
    for (int i = 0; i < others; i++) {
      add(documents, entries, "other" + i);
    }
    Object clauseType =
        global
            ? m(
                "cardinality",
                "keyed",
                "destinationBinding",
                "collection",
                "actionDefinition",
                m("type", m("refreshScope", "collection")))
            : "IncrementalDocuments/Documents Clause";
    Object compute =
        m(
            "type",
            "Contracts/Compute",
            "definition",
            "/logic",
            "constants",
            m("clauseKey", "documents", "bindings", m()),
            "do",
            List.of(m("$call", m("function", "refreshChanged", "args", m()))));
    Object source =
        m(
            "documents",
            documents,
            "process_documents",
            m("entries", entries),
            "clauses",
            m("documents", m("type", clauseType, "bindings", m("collection", "/documents"))),
            "logic",
            m("type", "IncrementalDocuments/Document Preparation Functions"),
            "contracts",
            m(
                "input",
                m("type", "Contracts/External Channel"),
                "refresh",
                m(
                    "type",
                    "Contracts/Sequential Workflow",
                    "channel",
                    "input",
                    "steps",
                    List.of(compute))));
    Contracts.Session session = CONTRACTS.openJson(ContractWire.write(source));
    session.initialize().requireApplied();
    return session;
  }

  static void add(Map<String, Object> documents, Map<String, Object> entries, String key) {
    documents.put(
        key,
        m(
            "activation",
            m("active", true, "parties", m()),
            "contracts",
            m("initialized", m("document", m("label", "Original child")))));
    entries.put(
        LANGUAGE.readJson(ContractWire.write(key)).blueId().value(),
        m(
            "key",
            key,
            "path",
            "/documents/" + escape(key),
            "itemPath",
            itemPath(key),
            "documentOrigin",
            ORIGIN,
            "requestId",
            key,
            "status",
            "selected"));
  }

  static String itemPath(String key) {
    return "/process_documents/entries/"
        + LANGUAGE.readJson(ContractWire.write(key)).blueId().value();
  }

  static String escape(String key) {
    return key.replace("~", "~0").replace("/", "~1");
  }
}
