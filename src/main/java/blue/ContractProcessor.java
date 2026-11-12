package blue;

import static blue.ContractLanguage.ABSENT;
import static blue.ContractLanguage.exactText;
import static blue.ContractLanguage.finalId;
import static blue.ContractLanguage.identity;
import static blue.ContractModel.DEFAULT_LIMIT;
import static blue.ContractModel.HOST_LIMIT;
import static blue.ContractModel.closed;
import static blue.ContractModel.digest;
import static blue.ContractModel.integer;
import static blue.ContractModel.m;
import static blue.ContractModel.obj;
import static blue.ContractModel.require;
import static blue.ContractModel.str;

import blue.ContractModel.Life;
import blue.ContractModel.Meter;
import blue.ContractModel.Outcome;
import blue.ContractModel.Snapshot;
import blue.ExactNode.Ref;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/** Native, atomic Contracts processor. All state is carried by immutable selected snapshots. */
final class ContractProcessor {
  final ContractBridge blue;
  final ContractRegistry registry;
  final ContractGraph.Archive archive = new ContractGraph.Archive();
  final BexEngine bex;
  final ContractBindings bindings;
  final ContractCompletions completions = new ContractCompletions();
  final long gasLimit;
  final Map<String, Long> localLimits;
  private CompletionReader completionReader;
  ContractCalculations calculations;

  /**
   * Privileged host adapter for protected, committed local storage. The adapter must authenticate
   * provenance and runtime bindings. Never connect this interface to uploaded or foreign results;
   * replay foreign invocations before publishing them locally.
   */
  @FunctionalInterface
  public interface CompletionReader {
    CompletedRecord readCompleted(ContractProcessor processor, String reference);
  }

  /** Detached retained data. Constructing a record does not issue a completion capability. */
  public record CompletedRecord(
      Document before,
      Document after,
      Map<String, Object> observations,
      String receipt,
      String eventBlueId,
      Long timestamp) {
    public CompletedRecord {
      Objects.requireNonNull(before);
      Objects.requireNonNull(after);
      Objects.requireNonNull(receipt);
      observations = obj(ContractWire.immutable(observations));
      require((eventBlueId == null) == (timestamp == null), "INVALID_STORED_COMPLETION");
      if (eventBlueId != null) {
        finalId(eventBlueId);
        integer(timestamp);
      }
    }
  }

  /** Install exactly once during host construction, before serving requests. */
  public ContractProcessor installCompletionReader(CompletionReader reader) {
    require(completionReader == null, "COMPLETION_READER_ALREADY_INSTALLED");
    completionReader = Objects.requireNonNull(reader);
    return this;
  }

  public SourceCompletion loadSourceCompletion(String reference, Collection<String> ownedMembers) {
    require(completionReader != null, "NO_LOCAL_COMPLETION_READER");
    var record = Objects.requireNonNull(completionReader.readCompleted(this, reference));
    validate(record.before.state, true);
    validate(record.after.state, true);
    var result =
        ContractCompletions.restore(record.after.state, record.observations, record.receipt);
    require(
        result.status.equals("APPLIED") && result.inputIdentity.equals(record.before.identity()),
        "INVALID_STORED_COMPLETION");
    completions.remember(record.before.state, result, record.eventBlueId, record.timestamp);
    return capture(new Result(result), ownedMembers);
  }

  public ContractProcessor() {
    this(Language.create(), HOST_LIMIT, Map.of());
  }

  public ContractProcessor(Language language) {
    this(language, HOST_LIMIT, Map.of());
  }

  public ContractProcessor(Language language, long gasLimit, Map<String, Long> localLimits) {
    this(language, ContractBindings.current(), gasLimit, localLimits);
  }

  public ContractProcessor(ContractBindings bindings) {
    this(Language.create(), bindings);
  }

  public ContractProcessor(Language language, ContractBindings bindings) {
    this(language, bindings, bindings.contractsEdition().maximumGas(), Map.of());
  }

  public ContractProcessor(
      Language language, ContractBindings bindings, long gasLimit, Map<String, Long> localLimits) {
    this(language, bindings, gasLimit, localLimits, new ContractPrograms());
  }

  ContractProcessor(
      Language language,
      ContractBindings bindings,
      long gasLimit,
      Map<String, Long> localLimits,
      ContractPrograms programs) {
    this(language, bindings, gasLimit, localLimits, programs, null);
  }

  /** Fresh mutable engine state over a privately owned, fully installed configuration. */
  ContractProcessor(ContractProcessor template, ContractPrograms programs) {
    this(
        template.blue.language,
        template.bindings,
        template.gasLimit,
        template.localLimits,
        programs,
        template);
  }

  private ContractProcessor(
      Language language,
      ContractBindings bindings,
      long gasLimit,
      Map<String, Long> localLimits,
      ContractPrograms programs,
      ContractProcessor template) {
    this.bindings = Objects.requireNonNull(bindings);
    blue =
        new ContractBridge(language, bindings.contractsEdition() == ContractsEdition.DETERMINISTIC);
    if (template != null) {
      blue.context = template.blue.context.fork();
    }
    bex =
        bindings.canonicalWireMeasurementV1()
            ? new BexEngine(
                Map.of(
                    CanonicalWireMeasurementV1.TYPE_ID,
                    new CanonicalWireMeasurementV1(blue)::measure))
            : bindings.engine();
    registry = new ContractRegistry(blue, bindings, programs);
    if (template == null) {
      if (bindings.dispatchEnabled()) {
        DocumentDispatchV1.installPublished(this);
      }
      if (bindings.canonicalWireMeasurementV1()) {
        CanonicalWireMeasurementV1.installMarker(this, blue);
      }
    } else {
      registry.extensions.putAll(template.registry.extensions);
      registry.interpretedTypes.putAll(template.registry.interpretedTypes);
      registry.inherit(template.registry);
    }
    if (!bindings.contractsEdition().specificationBlueId().equals(registry.specification)) {
      throw new IllegalStateException("Installed Contracts code and selected native rules differ");
    }
    integer(gasLimit, 0, bindings.contractsEdition().maximumGas());
    this.gasLimit = gasLimit;
    this.localLimits = Map.copyOf(localLimits);
  }

  public static long hostGasLimit() {
    return HOST_LIMIT;
  }

  public GasPolicy gasPolicy() {
    return new GasPolicy(gasLimit, localLimits);
  }

  public ContractBindings bindings() {
    return bindings;
  }

  /** An immutable execution budget. Limits belong to the invocation, never to a worker thread. */
  public record GasPolicy(long sharedLimit, Map<String, Long> localLimits) {
    public GasPolicy {
      integer(sharedLimit, 0, HOST_LIMIT);
      localLimits = Map.copyOf(localLimits);
      new Meter(sharedLimit, localLimits, null);
    }

    public static GasPolicy standard() {
      return new GasPolicy(DEFAULT_LIMIT, Map.of());
    }
  }

  /** Verified portable input together with its original gas policy. */
  public static final class Invocation {
    final Document document;
    final ExactNode event;
    final long timestamp;
    final GasPolicy policy;

    Invocation(Document document, ExactNode event, long timestamp, GasPolicy policy) {
      this.document = document;
      this.event = event;
      this.timestamp = timestamp;
      this.policy = policy;
    }

    public Document document() {
      return document;
    }

    public String eventBlueId() {
      return identity(event);
    }

    public long timestamp() {
      return timestamp;
    }

    public GasPolicy gasPolicy() {
      return policy;
    }
  }

  public long maximumGas() {
    return bindings.contractsEdition().maximumGas();
  }

  public ContractAttempt begin(Object document, Object event, long timestamp) {
    return begin(document, event, timestamp, new GasPolicy(gasLimit, localLimits), Map.of());
  }

  public ContractAttempt begin(
      Object document,
      Object event,
      long timestamp,
      GasPolicy policy,
      Map<String, Object> evidence) {
    integer(timestamp);
    Snapshot state;
    if (document instanceof Document d) {
      state = d.state;
    } else {
      state = ContractGraph.prepare(registry, Map.of("root", document), "root", Map.of(), archive);
      ContractChannels.refresh(new ContractGraph(state, registry, archive));
    }
    ExactNode original = event instanceof ExactNode n ? blue.node(n) : blue.source(event);
    var run =
        new ContractRun(
            this, state, original, timestamp, false, policy.sharedLimit(), policy.localLimits());
    run.admissionEvidence = ContractRunFork.data(evidence);
    return new ContractAttempt(run);
  }

  public ContractAttempt beginReceiving(Document document, List<SourceCompletion> sources) {
    return new ContractAttempt(
        ContractCompletions.run(this, document.state, sources, false, Map.of()));
  }

  public ContractAttempt beginLive(
      Document document, List<SourceCompletion> sources, Map<String, Object> evidence) {
    return new ContractAttempt(
        ContractCompletions.run(this, document.state, sources, true, evidence));
  }

  public ContractAttempt.ChunkResult executeChunk(ContractAttempt.ChunkToken token) {
    return ContractAttempt.execute(this, token);
  }

  public ContractAttempt.ChunkResult executeChunk(Map<String, Object> portableToken) {
    return ContractAttempt.execute(this, portableToken);
  }

  public Map<String, Object> exportInvocation(Document document, Object event, long timestamp) {
    return ContractPortable.exportInvocation(this, document, event, timestamp);
  }

  /**
   * Export an invocation with retained provider evidence for BlueIds constructed at runtime from
   * text. The capsule commits that inventory; imported records are still verified when opened.
   */
  public Map<String, Object> exportReplayInvocation(
      Document document, Object event, long timestamp) {
    var capsule = exportInvocation(document, event, timestamp);
    var invocation = obj(capsule.get("invocation"));
    var content = new TreeMap<>(blue.context.evidence());
    content.putAll(obj(invocation.get("content")));
    invocation.put("content", content);
    capsule.put("identity", digest("contracts.capsule/completion-1", invocation));
    return capsule;
  }

  public Invocation importInvocation(Map<String, Object> capsule, String expectedIdentity) {
    return ContractPortable.importInvocation(this, capsule, expectedIdentity);
  }

  public Result process(Invocation invocation) {
    return begin(
            invocation.document,
            invocation.event,
            invocation.timestamp,
            invocation.policy,
            Map.of())
        .finishLocal();
  }

  public static Map<String, Object> compareReplay(Map<String, Object> saved, Result actual) {
    closed(saved, Set.of("observations", "receipt"), Set.of());
    require(
        digest("contracts.result/completion-1", saved.get("observations"))
            .equals(saved.get("receipt")),
        "INVALID_RECEIPT");
    return m(
        "verified",
        ContractWire.write(saved.get("observations"))
            .equals(ContractWire.write(actual.observations())),
        "expected",
        saved.get("receipt"),
        "actual",
        actual.receipt());
  }

  void requireComputeDefinition(Object operand) {
    var specification = blue.open(blue.exact(operand)).get("specification");
    if (specification != null
        && !identity((ExactNode) specification).equals(registry.specification)) {
      throw new ContractBindings.Unsupported(
          identity((ExactNode) specification),
          List.of("Compute/definition", identity(blue.exact(operand)), "specification"));
    }
  }

  public Map<String, String> types() {
    return Map.copyOf(registry.types);
  }

  public String specification() {
    return registry.specification;
  }

  public void installRuntime(
      ContractNative.Binding binding, ContractNative.Implementation implementation) {
    registry.install(binding, implementation);
  }

  /**
   * Approve one exact interpreted program and its protocol rules; it retains ordinary semantics.
   */
  public void installInterpretedType(String definitionBlueId, String rulesBlueId) {
    registry.interpretedTypes.put(
        Objects.requireNonNull(definitionBlueId), Objects.requireNonNull(rulesBlueId));
    registry.clearDeclarations();
    registry.facts.clear();
  }

  public Map<String, Object> typed(String type, Map<String, Object> fields) {
    var out = registry.typed(type);
    out.putAll(fields);
    return out;
  }

  public static Object link(String member) {
    return new ContractGraph.Link(member);
  }

  public static final class Document {
    final Snapshot state;

    Document(Snapshot state) {
      this.state = state.copy();
    }

    public String identity() {
      return state.identity();
    }

    public String blueId() {
      return state.documents.get(state.root).blueId;
    }

    public Map<String, Object> metadata() {
      return obj(ContractWire.json(ContractWire.write(state.wire())));
    }
  }

  public static final class Result {
    final Outcome outcome;
    boolean admissionVerified;

    Result(Outcome outcome) {
      this.outcome = outcome;
    }

    public String status() {
      return outcome.status;
    }

    public boolean commits() {
      return status().equals("APPLIED");
    }

    public Document document() {
      return new Document(outcome.snapshot);
    }

    public long gas() {
      return outcome.gas;
    }

    public String receipt() {
      return outcome.receipt();
    }

    /** Exact input identity used by a storage adapter's conditional publication. */
    public String inputIdentity() {
      return outcome.inputIdentity;
    }

    /** Immutable public event occurrences for an atomic durable outbox. */
    public List<Map<String, Object>> publicEvents() {
      return outcome.publicEvents.stream().map(e -> obj(ContractWire.immutable(e))).toList();
    }

    public Map<String, Object> observations() {
      return obj(ContractWire.json(ContractWire.write(outcome.observations())));
    }
  }

  /** Alias metadata for editors; these names never select native runtime semantics. */
  public Map<String, String> displayAliases() {
    var aliases = new LinkedHashMap<String, String>();
    registry.types.forEach((k, v) -> aliases.put(v, "Contracts/" + k));
    ContractLanguage.CORE.forEach((k, v) -> aliases.put(v, k));
    blue.context.aliases().forEach((k, v) -> aliases.putIfAbsent(v, k));
    return Collections.unmodifiableMap(aliases);
  }

  /** Prepare graph structure without executing initialization handlers. */
  public Document prepare(Map<String, Object> members, String root) {
    var state = ContractGraph.prepare(registry, members, root, Map.of(), archive);
    ContractChannels.refresh(new ContractGraph(state, registry, archive));
    return new Document(state);
  }

  public Result initialize(Document document, GasPolicy policy) {
    return new Result(
        new ContractRun(
                this, document.state, null, null, true, policy.sharedLimit(), policy.localLimits())
            .attempt());
  }

  public void validate(Document document) {
    validate(document.state, true);
  }

  public Result initialize(Object source) {
    if (source instanceof Document d) {
      return new Result(new ContractRun(this, d.state, null, null, true).attempt());
    }
    return admit(Map.of("root", source), "root");
  }

  public Result admit(Map<String, Object> members, String root) {
    Snapshot s = ContractGraph.prepare(registry, members, root, Map.of(), archive);
    ContractChannels.refresh(new ContractGraph(s, registry, archive));
    return new Result(new ContractRun(this, s, null, null, true).attempt());
  }

  public Document create(Object source) {
    Result r = initialize(source);
    require(
        r.commits(), "ADMISSION_FAILED", "status", r.status(), "diagnostic", r.outcome.diagnostic);
    return r.document();
  }

  public Document createYaml(String source) {
    return create(ContractWire.data(ContractWire.parse(source, "yaml")));
  }

  public Document createJson(String source) {
    return create(ContractWire.data(ContractWire.parse(source, "json")));
  }

  /** Exact root document JSON; exportSnapshot also retains graph history and runtime bindings. */
  public String toJson(Document document) {
    // A restored or rolled-back snapshot can retain its root as a Ref. Resolve
    // that physical representation so the public root export is cache-neutral.
    return exactText(blue.open(document.state.documents.get(document.state.root).body));
  }

  public String toYaml(Document document) {
    return ContractWire.yamlText(ContractWire.json(toJson(document)));
  }

  public Result process(
      Document document, Object event, long timestamp, Map<String, Object> evidence) {
    integer(timestamp);
    ExactNode original = event instanceof ExactNode n ? blue.node(n) : blue.source(event);
    var run = new ContractRun(this, document.state, original, timestamp, false);
    run.admissionEvidence.putAll(evidence);
    var result = new Result(run.attempt());
    result.admissionVerified = run.nativeAdmissionVerified;
    return result;
  }

  public static final class SourceCompletion {
    final ContractCompletions issuer;
    final String token;
    final ContractCompletions.Record retained;

    private SourceCompletion(
        ContractCompletions issuer, String token, ContractCompletions.Record retained) {
      this.issuer = issuer;
      this.token = token;
      this.retained = retained;
    }

    public Map<String, Object> descriptor() {
      return obj(ContractWire.copy(issuer.read(this).descriptor()));
    }
  }

  SourceCompletion issue(String token, ContractCompletions.Record retained) {
    return new SourceCompletion(completions, token, retained);
  }

  public SourceCompletion capture(Result result, Collection<String> members) {
    return completions.capture(this, result.outcome, members);
  }

  public Result receive(Document document, List<SourceCompletion> sources) {
    return new Result(
        ContractCompletions.run(this, document.state, sources, false, Map.of()).attempt());
  }

  public Result receiveLive(
      Document document, List<SourceCompletion> sources, Map<String, Object> evidence) {
    return new Result(
        ContractCompletions.run(this, document.state, sources, true, evidence).attempt());
  }

  public Result process(Document document, Object event, long timestamp) {
    integer(timestamp);
    ExactNode original = event instanceof ExactNode n ? blue.node(n) : blue.source(event);
    return new Result(new ContractRun(this, document.state, original, timestamp, false).attempt());
  }

  public Result process(Object source, Object event, long timestamp) {
    if (source instanceof Document d) {
      return process(d, event, timestamp);
    }
    Snapshot s = ContractGraph.prepare(registry, Map.of("root", source), "root", Map.of(), archive);
    ContractChannels.refresh(new ContractGraph(s, registry, archive));
    ExactNode original = event instanceof ExactNode n ? blue.node(n) : blue.source(event);
    return new Result(new ContractRun(this, s, original, timestamp, false).attempt());
  }

  public Object read(Document document, String path) {
    return read(document, path, document.state.root);
  }

  public Object read(Document document, String path, String member) {
    // Host inspection is a new bounded operation, not part of the previous read's allowance.
    // Keep verified immutable content and semantic memo entries; reset only physical work.
    blue.context.refreshed();
    return blue.project(blue.select(document.state.documents.get(member).body, path));
  }

  Object read(Document document, String path, ProjectionLimits limits) {
    blue.context.refreshed();
    return blue.project(
        blue.select(document.state.documents.get(document.state.root).body, path), limits);
  }

  public List<Object> emitted(Result result) {
    return result.outcome.publicEvents.stream()
        .map(e -> blue.project(new Ref(str(obj(e).get("event"))), false, Set.of(), true))
        .toList();
  }

  public Map<String, Object> exportSnapshot(Document document) {
    return ContractStorage.export(this, document.state);
  }

  public Document importSnapshot(Map<String, Object> data, String expectedIdentity) {
    return new Document(ContractStorage.restore(this, data, expectedIdentity));
  }

  void validate(Snapshot s, boolean initialized) {
    require(s.release.equals(registry.specification), "INCOMPATIBLE_RELEASE");
    require(s.gasSchedule == bindings.gasSchedule(), "INCOMPATIBLE_GAS_SCHEDULE");
    require(s.documents.containsKey(s.root), "MISSING_ROOT");
    integer(s.revision);
    integer(s.generation);
    require(
        registry.compatibleBindings(s.runtimes, registry.runtimeBindings()),
        "INCOMPATIBLE_RUNTIME_BINDINGS");
    var g = new ContractGraph(s, registry, archive);
    g.verifyEdges();
    for (var entry : s.documents.entrySet()) {
      String key = entry.getKey();
      var d = entry.getValue();
      var fact = registry.facts.check("markers", key, d, List.of());
      if (fact.known()) {
        continue;
      }
      ContractMetrics.count("contracts.validation.bodyChecks");
      var h = blue.head(d.body);
      require(
          Set.of("object", "none").contains(h.kind) && h.items == null && h.value == ABSENT,
          "PROCESSING_DOCUMENT_NOT_OBJECT");
      for (String reserved : ContractRegistry.MARKERS) {
        ExactNode marker = registry.marker(d, reserved);
        if (marker == null) {
          continue;
        }
        var value = registry.markerRecord(marker, reserved);
        if (reserved.equals("initialized")) {
          closed(value, Set.of("type", "document"), Set.of());
          String initial =
              str(closed(value.get("document"), Set.of("blueId"), Set.of()).get("blueId"));
          // Protected preparation already checked this exact marker's historical target.
          // A business-state update does not invalidate that independent existence check.
          if (registry.preparations == null
              || !registry.preparations.verifiedInitialization(marker)) {
            blue.open(new Ref(initial));
          }
        } else if (reserved.equals("terminated")) {
          closed(value, Set.of("type", "cause"), Set.of("reason"));
          require(
              value.get("cause") instanceof String
                  && (!value.containsKey("reason") || value.get("reason") instanceof String),
              "INVALID_TERMINATION");
        } else {
          closed(value, Set.of("type", "entries"), Set.of());
          for (var row : obj(value.get("entries")).entrySet()) {
            require(d.channelLives.containsKey(row.getKey()), "INVALID_CHECKPOINT_CHANNEL");
            var cp = closed(row.getValue(), Set.of("timestamp", "event"), Set.of());
            integer(cp.get("timestamp"));
            blue.open(
                new Ref(str(closed(cp.get("event"), Set.of("blueId"), Set.of()).get("blueId"))));
          }
        }
      }
      registry.facts.established(fact);
    }
    for (String key : g.reachable(true, Set.of())) {
      var d = s.documents.get(key);
      var fact = registry.facts.check("lifetimes", key, d, initialized);
      if (fact.known()) {
        continue;
      }
      var cs = registry.contracts(d);
      var actual = new TreeMap<String, String>();
      cs.forEach(
          (k, c) -> {
            if (ContractRegistry.EXTERNAL.contains(c.get("_role"))) {
              actual.put(k, ContractChannels.signature(cs, k));
            }
          });
      require(actual.keySet().equals(d.channelLives.keySet()), "INVALID_CHANNEL_LIFETIMES");
      actual.forEach(
          (k, sig) -> {
            Life life = d.channelLives.get(k);
            require(
                life.signature().equals(sig) && life.generation() == d.channelSerials.get(k),
                "INVALID_CHANNEL_LIFETIME");
            ContractChannels.checkpoint(registry, d, k);
          });
      if (initialized) {
        require(registry.marker(d, "initialized") != null, "UNINITIALIZED_SNAPSHOT");
      }
      registry.facts.established(fact);
    }
  }
}
