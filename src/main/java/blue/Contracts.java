package blue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Canonical Contracts execution, including embedded documents and deterministic BEX workflows. A
 * processor configuration is immutable and shareable. Each opened session owns one independent
 * document graph; calls on that session are atomic and serialized. Different sessions run in
 * parallel.
 *
 * <pre>{@code
 * Contracts contracts = Contracts.create();
 * try (Contracts.Session agreement = contracts.openYaml(source)) {
 *   agreement.initialize().requireApplied();
 *   Contracts.Transition result = agreement.call("input", "submit", Map.of(), 1);
 *   result.requireApplied();
 *   System.out.println(agreement.document().canonicalJson());
 * }
 * }</pre>
 */
public final class Contracts {
  private final Language language;
  private final ContractPrograms programs = new ContractPrograms();
  private final long gasLimit;
  private final ContractBindings bindings;
  private final List<NativeStep> nativeSteps;
  private final List<NativeChannel> nativeChannels;
  private final Map<String, String> interpretedPrograms;

  private Contracts(Builder builder) {
    language = builder.language;
    gasLimit = builder.gasLimit;
    bindings = builder.bindings.withGasSchedule(builder.gasSchedule);
    nativeSteps = List.copyOf(builder.nativeSteps.values());
    nativeChannels = List.copyOf(builder.nativeChannels.values());
    interpretedPrograms = Map.copyOf(builder.interpretedPrograms);
    // Fail at configuration time without retaining a mutable template processor.
    if (!nativeSteps.isEmpty() || !nativeChannels.isEmpty() || !interpretedPrograms.isEmpty()) {
      processor();
    }
  }

  /**
   * Returns the current canonical edition with the standard gas limit.
   *
   * @return the current canonical edition with the standard gas limit
   */
  public static Contracts create() {
    return newBuilder().build();
  }

  /**
   * Inspects immutable content using Contracts paths without opening or executing a session. The
   * content's own Language edition and provider remain authoritative.
   *
   * @param content exact content and its retained evidence
   * @return a read-only inspector with independently bounded reads
   */
  public static ContractInspection inspect(LanguageContent content) {
    return new ContractInspection(Objects.requireNonNull(content), "");
  }

  /**
   * Returns a mutable, caller-confined configuration builder.
   *
   * @return a mutable, caller-confined configuration builder
   */
  public static Builder newBuilder() {
    return new Builder();
  }

  /**
   * Returns the selected accounting rules.
   *
   * @return deterministic gas schedule
   */
  public GasSchedule gasSchedule() {
    return bindings.gasSchedule();
  }

  /**
   * Copies this immutable configuration into a caller-confined builder.
   *
   * @return independently mutable builder retaining all explicit capabilities
   */
  public Builder toBuilder() {
    Builder builder = new Builder();
    builder.language = language;
    builder.gasLimit = gasLimit;
    builder.bindings = bindings;
    builder.gasSchedule = bindings.gasSchedule();
    nativeSteps.forEach(value -> builder.nativeSteps.put(value.typeId(), value));
    nativeChannels.forEach(value -> builder.nativeChannels.put(value.typeId(), value));
    builder.interpretedPrograms.putAll(interpretedPrograms);
    return builder;
  }

  /**
   * Returns the base published Contracts rules identity.
   *
   * @return base rules, before an explicit gas override
   */
  public BlueId rulesId() {
    return new BlueId(ContractsEdition.DETERMINISTIC.specificationBlueId());
  }

  /**
   * Returns the verified canonical Contracts package, suitable for Language mounts.
   *
   * @return the verified canonical Contracts package, suitable for Language mounts
   */
  public static BluePackage publication() {
    return ProtocolPublications.contracts();
  }

  /**
   * Admits a document graph without running initialization handlers.
   *
   * @param yaml authored document
   * @return a new independent session
   */
  public Session openYaml(String yaml) {
    return boundary(() -> openData(ContractWire.data(ContractWire.parse(yaml, "yaml"))));
  }

  /**
   * Admits a JSON document graph without running initialization handlers.
   *
   * @param json authored document
   * @return a new independent session
   */
  public Session openJson(String json) {
    return boundary(() -> openData(ContractWire.data(ContractWire.parse(json, "json"))));
  }

  /**
   * Opens an already admitted Language document, retaining its dependency evidence.
   *
   * @param document initial document
   * @return a new independent session
   */
  public Session open(LanguageDocument document) {
    Objects.requireNonNull(document);
    return boundary(
        () -> {
          ContractProcessor processor = processor();
          processor.blue.context.install(document);
          return new Session(
              this, processor, processor.prepare(Map.of("root", document.exact), "root"));
        });
  }

  /**
   * Restores a portable snapshot and verifies its expected identity and evidence.
   *
   * @param snapshot exported snapshot
   * @param expectedIdentity trusted snapshot identity, obtained separately
   * @return restored session
   */
  public Session restore(Map<String, Object> snapshot, String expectedIdentity) {
    return boundary(
        () -> {
          ContractProcessor processor = processor();
          return new Session(this, processor, processor.importSnapshot(snapshot, expectedIdentity));
        });
  }

  /**
   * Recovers one previously completed value from this host's protected snapshot publication. Unlike
   * {@link #restore}, this does not independently verify a portable import or open an execution
   * session. The expected identity must come from this host's successful durable publication, never
   * from a request or from the snapshot being supplied. The configured Language must resolve the
   * complete immutable closure retained with that publication.
   *
   * <p>Recovery authenticates the full metadata and supported Contracts release, selects the member
   * from those authenticated metadata, and verifies its exact body and owning proof. It reuses
   * prior semantic completion and grants no current membership, access or execution authority.
   * Embedded content in the supplied envelope is ignored; only configured providers may supply
   * bodies.
   *
   * @param snapshot previously committed snapshot envelope
   * @param expectedIdentity independently protected publication commitment
   * @param member exact member key in that publication
   * @return recovered completed Language value
   */
  public LanguageDocument recoverMember(
      Map<String, Object> snapshot, String expectedIdentity, String member) {
    Objects.requireNonNull(snapshot);
    Objects.requireNonNull(expectedIdentity);
    Objects.requireNonNull(member);
    return boundary(
        () ->
            LanguageCheckpoint.recover(
                language,
                ContractStorage.completedMember(
                    snapshot,
                    expectedIdentity,
                    member,
                    bindings.contractsEdition(),
                    bindings.gasSchedule())));
  }

  /**
   * Recognizes an exact operation request without opening an execution session. The message's
   * evidence and this configuration's providers supply its dependencies. This verifies the same
   * grammar as execution; it grants no routing, lineage, admission or authorization permit.
   *
   * @param message exact message with dependencies
   * @return the recognized operation, or empty for another message type
   */
  public java.util.Optional<Contracts.OperationRequest> operationRequest(LanguageContent message) {
    Objects.requireNonNull(message);
    return boundary(() -> ContractReception.operation(processor(), message));
  }

  /**
   * Recognizes a request with explicitly supplied prior completed values. New values and contextual
   * restrictions still use the same execution grammar. Evidence is confined to this call and grants
   * no account access, routing, channel authority or source-history permission.
   *
   * @param message exact request with dependencies
   * @param values completed values whose semantic checks may be reused
   * @return recognized operation, or empty for another message type
   */
  public java.util.Optional<Contracts.OperationRequest> operationRequest(
      LanguageContent message, LanguageDocument... values) {
    Objects.requireNonNull(message);
    LanguageDocument[] completed = Objects.requireNonNull(values).clone();
    return boundary(() -> ContractReception.operation(processor(), message, completed));
  }

  private Session openData(Object source) {
    return boundary(
        () -> {
          ContractProcessor processor = processor();
          return new Session(this, processor, processor.prepare(Map.of("root", source), "root"));
        });
  }

  ContractProcessor processor() {
    ContractProcessor processor =
        new ContractProcessor(language, bindings, gasLimit, Map.of(), programs);
    nativeSteps.forEach(step -> step.install(processor));
    nativeChannels.forEach(channel -> channel.install(processor));
    interpretedPrograms.forEach(processor::installInterpretedType);
    return processor;
  }

  static <T> T boundary(Supplier<T> action) {
    try {
      return action.get();
    } catch (ContractFault error) {
      throw failure(
          error.code(),
          error.code().equals("LOCAL_EXECUTION_UNAVAILABLE")
              ? error.context().toString()
              : error.getMessage(),
          error);
    } catch (BexFailure error) {
      throw failure(error.code(), error.getMessage(), error);
    } catch (LanguageFailure error) {
      throw failure(error.category(), error.getMessage(), error);
    } catch (StackOverflowError error) {
      BlueException failure =
          new BlueException("HOST_INCOMPLETE", "Host traversal stack exhausted");
      failure.initCause(error);
      throw failure;
    } catch (ContractBindings.Unsupported error) {
      throw failure("UNSUPPORTED_RUNTIME", error.getMessage(), error);
    }
  }

  private static BlueException failure(String code, String message, RuntimeException cause) {
    BlueException error = new BlueException(code, message);
    error.initCause(cause);
    return error;
  }

  /** Configuration for new sessions. Changing a builder never changes existing processors. */
  public static final class Builder {
    private Language language = Language.create();
    private long gasLimit = 10_000_000;
    private GasSchedule gasSchedule = GasSchedule.STANDARD;
    private ContractBindings bindings = ContractBindings.core();
    private final Map<BlueId, NativeStep> nativeSteps = new java.util.LinkedHashMap<>();
    private final Map<BlueId, NativeChannel> nativeChannels = new java.util.LinkedHashMap<>();
    private final Map<String, String> interpretedPrograms = new java.util.LinkedHashMap<>();

    private Builder() {}

    /**
     * Selects accounting for new histories. Compositional accounting is an explicit experimental
     * override of the published gas rules, not original-Java gas compatibility.
     *
     * @param value exact accounting schedule
     * @return this builder
     */
    public Builder gasSchedule(GasSchedule value) {
      gasSchedule = Objects.requireNonNull(value);
      return this;
    }

    /**
     * Supplies aliases and verified provider access. Contracts' standard aliases are always
     * available.
     *
     * @param value canonical Language context
     * @return this builder
     */
    public Builder language(Language value) {
      language = Objects.requireNonNull(value);
      return this;
    }

    /**
     * Installs the published deterministic Document Dispatch policy. Package loading alone never
     * enables native execution. Configure this before checking a package that requires Dispatch.
     *
     * @return this builder
     */
    public Builder enableDocumentDispatch() {
      bindings = ContractBindings.dispatch();
      return this;
    }

    /**
     * Sets the shared per-invocation budget under the current canonical tariff.
     *
     * @param value limit between zero and ten million inclusive
     * @return this builder
     */
    public Builder gasLimit(long value) {
      if (value < 0 || value > 10_000_000) {
        throw new IllegalArgumentException("Gas limit must be 0..10000000");
      }
      gasLimit = value;
      return this;
    }

    /**
     * Selects the published Coordination timeline channels, access-update program and Document
     * Dispatch before initialization. The adapter requires an explicit host history verifier; this
     * does not install acquisition, private-access or feeder services.
     *
     * @param value immutable timeline profile with its trusted history verifier
     * @return this builder
     */
    public Builder timelineChannels(TimelineChannels value) {
      Objects.requireNonNull(value).checkCompatible(nativeSteps, nativeChannels);
      value.registrations().forEach(this::nativeChannel);
      interpretedPrograms.putAll(value.interpretedPrograms());
      bindings = ContractBindings.dispatch();
      return this;
    }

    /**
     * Installs the exact Time request steps and timer channel. The mandatory verifier is owned by
     * the host, which must establish pending registration, time, ordering and source completeness.
     * Package installation alone never schedules a timer.
     *
     * @param value immutable Time profile with its trusted admission verifier
     * @return this builder
     */
    public Builder timeBindings(TimeBindings value) {
      Objects.requireNonNull(value).checkCompatible(nativeSteps, nativeChannels);
      value.steps().forEach(this::nativeStep);
      nativeChannel(value.channel());
      return this;
    }

    /**
     * Installs trusted native code for one exact step type and semantics identity. A different
     * registration for the same type is rejected. Importing its package alone does not install it.
     *
     * @param value immutable, deterministic registration
     * @return this builder
     */
    public Builder nativeStep(NativeStep value) {
      Objects.requireNonNull(value);
      if (nativeChannels.containsKey(value.typeId())) {
        throw new IllegalArgumentException(
            "Type already registered as a native channel: " + value.typeId());
      }
      NativeStep previous = nativeSteps.putIfAbsent(value.typeId(), value);
      if (previous != null && previous != value) {
        throw new IllegalArgumentException("Conflicting native step: " + value.typeId());
      }
      return this;
    }

    /**
     * Installs trusted event acceptance for one exact native channel type and semantics identity.
     * Accepted events still use ordinary channel checkpoints and workflows. Package loading alone
     * never installs code or establishes support for an entire native protocol.
     *
     * @param value immutable, deterministic channel registration
     * @return this builder
     */
    public Builder nativeChannel(NativeChannel value) {
      Objects.requireNonNull(value);
      if (nativeSteps.containsKey(value.typeId())) {
        throw new IllegalArgumentException(
            "Type already registered as a native step: " + value.typeId());
      }
      NativeChannel previous = nativeChannels.putIfAbsent(value.typeId(), value);
      if (previous != null && previous != value) {
        throw new IllegalArgumentException("Conflicting native channel: " + value.typeId());
      }
      return this;
    }

    /**
     * Checks a package's exact execution requirements before accepting work. Loading a package
     * never installs host code.
     *
     * @param value verified package
     * @return this builder
     */
    public Builder require(BluePackage value) {
      boundary(
          () -> {
            bindings.require(Objects.requireNonNull(value));
            return null;
          });
      return this;
    }

    /**
     * Returns an immutable processor configuration.
     *
     * @return an immutable processor configuration
     */
    public Contracts build() {
      return boundary(() -> new Contracts(this));
    }
  }

  /**
   * A managed member's stable key and current content identity.
   *
   * @param key stable key within this graph
   * @param blueId current exact content identity
   */
  public record Member(String key, BlueId blueId) {}

  /**
   * A current embedding edge.
   *
   * @param source owning member key
   * @param path relative embedding pointer
   * @param target embedded member key
   * @param generation lifetime at this path
   */
  public record Embedding(String source, String path, String target, long generation) {}

  /**
   * Immutable topology inspection, without expanding any document content.
   *
   * @param root root member key
   * @param members managed members in canonical order
   * @param embeddings current directed edges, including cycles
   */
  public record Graph(
      String root, java.util.List<Member> members, java.util.List<Embedding> embeddings) {
    /** Captures immutable topology containers. */
    public Graph {
      members = java.util.List.copyOf(members);
      embeddings = java.util.List.copyOf(embeddings);
    }
  }

  static Contracts.Reception reception(
      ContractProcessor processor, ContractProcessor.Document document) {
    String identity = document.identity();
    var channels = new java.util.ArrayList<Contracts.ReceivingChannel>();
    var graph = new ContractGraph(document.state, processor.registry, processor.archive);
    for (String member : ContractCanonical.keys(graph.reachable(true, java.util.Set.of()))) {
      channels.addAll(
          ContractReception.inspect(processor, document.state, member, identity).channels());
    }
    return new Contracts.Reception(identity, channels);
  }

  private static Graph graph(ContractModel.Snapshot state) {
    return new Graph(
        state.root,
        state.documents.values().stream()
            .map(d -> new Member(d.key, new BlueId(d.blueId)))
            .toList(),
        state.edges.values().stream()
            .map(e -> new Embedding(e.source(), e.path(), e.target(), e.generation()))
            .toList());
  }

  /** A single graph's in-memory execution lifetime. Close it to release retained evidence. */
  public static final class Session implements AutoCloseable {
    private Contracts configuration;
    private ContractProcessor processor;
    private ContractProcessor.Document document;
    private Contracts.Reception reception;

    private Session(
        Contracts configuration, ContractProcessor processor, ContractProcessor.Document document) {
      this.configuration = configuration;
      this.processor = processor;
      this.document = document;
    }

    /**
     * Returns initialization outcome; a failed invocation leaves the session unchanged.
     *
     * @return initialization outcome; a failed invocation leaves the session unchanged
     */
    public synchronized Transition initialize() {
      checkOpen();
      return boundary(() -> apply(processor.initialize(document)));
    }

    /**
     * Delivers an external event under canonical channel selection rules.
     *
     * @param event JSON-like event data
     * @param timestamp deterministic nonnegative integer time supplied by the host
     * @return the atomic outcome
     */
    public synchronized Transition process(Object event, long timestamp) {
      if (event instanceof LanguageContent content) {
        return process(content, timestamp);
      }
      checkOpen();
      return boundary(() -> apply(processor.process(document, event, timestamp)));
    }

    /**
     * Delivers an event with explicitly supplied native admission evidence. Installed verifiers run
     * again against this snapshot, including on replay; evidence is never retained for later calls.
     *
     * @param event JSON-like event data
     * @param timestamp deterministic nonnegative host timestamp
     * @param evidence untrusted evidence keyed by exact native channel type
     * @return the atomic outcome
     */
    public synchronized Transition process(
        Object event, long timestamp, AdmissionEvidence evidence) {
      if (event instanceof LanguageContent content) {
        return process(content, timestamp, evidence);
      }
      checkOpen();
      Objects.requireNonNull(evidence);
      return boundary(
          () -> apply(processor.process(document, event, timestamp, evidence.values())));
    }

    /**
     * Processes exact Language content with its supporting evidence.
     *
     * @param event exact original content
     * @param timestamp deterministic host timestamp
     * @return canonical atomic outcome
     */
    public synchronized Transition process(LanguageContent event, long timestamp) {
      return process(event, timestamp, AdmissionEvidence.empty());
    }

    /**
     * Processes exact content; retained content grants no native admission authority.
     *
     * @param event exact original content and dependency evidence
     * @param timestamp deterministic host timestamp
     * @param evidence native receiver admission evidence
     * @return canonical atomic outcome
     */
    @SuppressWarnings("try")
    public synchronized Transition process(
        LanguageContent event, long timestamp, AdmissionEvidence evidence) {
      checkOpen();
      Objects.requireNonNull(event);
      Objects.requireNonNull(evidence);
      return boundary(
          () -> {
            try (var scope = processor.blue.context.withContent(event)) {
              return apply(
                  processor.process(document, event.semantic, timestamp, evidence.values()));
            }
          });
    }

    /**
     * Processes a verifier-issued original, preserving its identity and timestamp. Native receiver
     * admission and completeness remain the host's responsibility.
     *
     * @param entry verified original occurrence
     * @param evidence native admission evidence
     * @return canonical atomic outcome
     */
    public synchronized Transition process(
        TimelineVerifier.Entry entry, AdmissionEvidence evidence) {
      checkOpen();
      Objects.requireNonNull(entry);
      var scope = processor.blue.context.withTimelineEntry(entry);
      try {
        return process(Map.of("blueId", entry.blueId().value()), entry.timestamp(), evidence);
      } finally {
        scope.close();
      }
    }

    /**
     * Delivers a standard Operation Request. Channel names select matching declarations in the
     * graph, not document paths. Use distinct names or channel filters to target a child.
     *
     * @param channel external channel name
     * @param operation workflow operation name
     * @param request request payload
     * @param timestamp deterministic time
     * @return the atomic outcome
     */
    public synchronized Transition call(
        String channel, String operation, Object request, long timestamp) {
      checkOpen();
      Objects.requireNonNull(channel);
      Objects.requireNonNull(operation);
      Map<String, Object> event =
          processor.typed(
              "Operation Request",
              Map.of(
                  "channel",
                  channel,
                  "operation",
                  operation,
                  "request",
                  Objects.requireNonNull(request)));
      return process(event, timestamp);
    }

    /**
     * Recognizes an operation in the exact message of a verified timeline occurrence. This reuses
     * verified value evidence and grants no routing or execution authority.
     *
     * @param entry verified occurrence
     * @param message its exact message
     * @return operation metadata, or empty for another message type
     */
    public synchronized Optional<OperationRequest> operationRequest(
        TimelineVerifier.Entry entry, LanguageContent message) {
      checkOpen();
      return Contracts.boundary(() -> ContractReception.operation(processor, entry, message));
    }

    /**
     * Begins a private canonical invocation. Changes become visible only after commit. The
     * transaction can yield between scheduling units, including complete cyclic units.
     *
     * @param event external input
     * @param timestamp deterministic nonnegative host timestamp
     * @return a transaction that must be committed or closed
     */
    public synchronized Transaction begin(Object event, long timestamp) {
      return begin(event, timestamp, AdmissionEvidence.empty());
    }

    /**
     * Begins a private invocation with snapshot-bound native evidence.
     *
     * @param event external input
     * @param timestamp deterministic nonnegative host timestamp
     * @param evidence evidence checked by installed native channels
     * @return a transaction that must be committed or closed
     */
    public synchronized Transaction begin(
        Object event, long timestamp, AdmissionEvidence evidence) {
      if (event instanceof LanguageContent content) {
        return begin(content, timestamp, evidence);
      }
      checkOpen();
      Objects.requireNonNull(event);
      Objects.requireNonNull(evidence);
      return boundary(
          () -> {
            ContractProcessor candidate = new ContractProcessor(processor, configuration.programs);
            processor.archive.copyTo(candidate.archive);
            ContractAttempt attempt =
                candidate.begin(
                    document, event, timestamp, candidate.gasPolicy(), evidence.values());
            return new Transaction(this, document, candidate, attempt);
          });
    }

    /**
     * Begins an invocation from exact content with its retained dependencies. Native admission
     * remains mandatory when declared; supplying content grants no authority.
     *
     * @param event exact event and its supporting content
     * @param timestamp deterministic host timestamp
     * @param evidence explicitly supplied native evidence
     * @return private canonical transaction
     */
    public synchronized Transaction begin(
        LanguageContent event, long timestamp, AdmissionEvidence evidence) {
      checkOpen();
      Objects.requireNonNull(event);
      Objects.requireNonNull(evidence);
      return boundary(
          () -> {
            ContractProcessor candidate = new ContractProcessor(processor, configuration.programs);
            processor.archive.copyTo(candidate.archive);
            var scope = candidate.blue.context.withContent(event);
            try {
              ContractAttempt attempt =
                  candidate.begin(
                      document,
                      event.semantic,
                      timestamp,
                      candidate.gasPolicy(),
                      evidence.values());
              Transaction transaction = new Transaction(this, document, candidate, attempt);
              transaction.contentScope = scope;
              return transaction;
            } catch (RuntimeException | Error failure) {
              scope.close();
              throw failure;
            }
          });
    }

    /**
     * Inspects native receivers throughout the reachable graph. These immutable descriptions share
     * the document runtime's inspection format and are not admission permits.
     *
     * @return receivers bound to the current canonical snapshot
     */
    public synchronized Contracts.Reception reception() {
      checkOpen();
      if (reception == null) {
        reception = boundary(() -> Contracts.reception(processor, document));
      }
      return reception;
    }

    /**
     * Returns exact current content for one managed member identified by native reception data.
     *
     * @param member managed member key
     * @return immutable content with retained dependencies
     */
    public synchronized LanguageDocument member(String member) {
      checkOpen();
      var selected = document.state.documents.get(Objects.requireNonNull(member));
      if (selected == null) {
        throw new IllegalArgumentException("Unknown graph member: " + member);
      }
      return processor.blue.context.document(selected.body);
    }

    /**
     * Lists callable operations declared by one managed member.
     *
     * @param member managed member key
     * @return immutable operation descriptions, without authorization claims
     */
    public synchronized java.util.List<Contracts.Operation> operations(String member) {
      checkOpen();
      return boundary(() -> ContractReception.operations(processor, document.state, member));
    }

    /**
     * Recognizes a message using the same operation grammar as execution.
     *
     * @param message exact message with dependencies
     * @return parsed operation, without any authorization claim
     */
    public synchronized java.util.Optional<Contracts.OperationRequest> operationRequest(
        LanguageContent message) {
      checkOpen();
      Objects.requireNonNull(message);
      return boundary(() -> ContractReception.operation(processor, message));
    }

    /**
     * Returns the root member key without enumerating the graph.
     *
     * @return graph-local root member key
     */
    public synchronized String rootMember() {
      checkOpen();
      return document.state.root;
    }

    /**
     * Inspects topology without exporting content or evidence.
     *
     * @return immutable current graph topology
     */
    public synchronized Graph graph() {
      checkOpen();
      return Contracts.graph(document.state);
    }

    /**
     * Returns current graph snapshot identity, including processing metadata.
     *
     * @return current graph snapshot identity, including processing metadata
     */
    public synchronized String identity() {
      checkOpen();
      return document.identity();
    }

    /**
     * Returns current root document's content identity.
     *
     * @return current root document's content identity
     */
    public synchronized BlueId blueId() {
      checkOpen();
      return new BlueId(document.blueId());
    }

    /**
     * Reads a projected value using a JSON pointer; no execution gas is charged for host
     * inspection. Each read has an independent Language host-work allowance.
     *
     * @param pointer path in the root document, including paths into embedded documents
     * @return detached immutable BEX data; missing values equal {@link Bex#undefined()}
     */
    public synchronized Object read(String pointer) {
      checkOpen();
      return boundary(() -> BexValues.freeze(processor.read(document, pointer)));
    }

    /**
     * Reads a projected value in one managed member, using ordinary BEX pointers.
     *
     * @param member managed member key
     * @param pointer relative pointer
     * @return immutable projected value
     */
    public synchronized Object readMember(String member, String pointer) {
      checkOpen();
      Objects.requireNonNull(member);
      Objects.requireNonNull(pointer);
      return boundary(() -> BexValues.freeze(processor.read(document, pointer, member)));
    }

    /**
     * Reads with explicit output bounds, checked as the projection is constructed.
     *
     * @param pointer path in the root document
     * @param limits host output bounds, independent of execution gas
     * @return detached immutable projected data
     * @throws BlueException with code {@code PROJECTION_LIMIT_EXCEEDED} if output exceeds a bound
     */
    public synchronized Object read(String pointer, ProjectionLimits limits) {
      checkOpen();
      Objects.requireNonNull(limits);
      return boundary(() -> BexValues.freeze(processor.read(document, pointer, limits)));
    }

    /**
     * Returns an immutable Language handle with its own retained dependency evidence.
     *
     * @return an immutable Language handle with its own retained dependency evidence
     */
    public synchronized LanguageDocument document() {
      checkOpen();
      return processor.blue.context.document(
          document.state.documents.get(document.state.root).body);
    }

    /**
     * Returns a detached portable graph snapshot, including evidence and processing metadata.
     *
     * @return a detached portable graph snapshot, including evidence and processing metadata
     */
    public synchronized Map<String, Object> snapshot() {
      checkOpen();
      return boundary(() -> processor.exportSnapshot(document));
    }

    /**
     * Rebuilds the session around its verified current graph, releasing obsolete working history.
     * The exact configured native registrations and reachable immutable evidence are retained. This
     * explicit maintenance boundary traverses current dependencies without a wire round trip.
     *
     * @throws BlueException if the snapshot cannot be verified; the current session is retained
     */
    public synchronized void compact() {
      checkOpen();
      boundary(
          () -> {
            ContractProcessor replacement = configuration.processor();
            ContractProcessor.Document selected =
                new ContractProcessor.Document(
                    ContractStorage.compact(processor, replacement, document.state));
            processor = replacement;
            document = selected;
            reception = null;
            return null;
          });
    }

    private Transition apply(ContractProcessor.Result result) {
      Transition transition = new Transition(result, processor);
      if (result.commits()) {
        document = result.document();
        reception = null;
      }
      return transition;
    }

    private void checkOpen() {
      if (processor == null) {
        throw new IllegalStateException("Contracts session is closed");
      }
    }

    /** Releases this session's working state. Previously returned immutable values remain valid. */
    @Override
    public synchronized void close() {
      configuration = null;
      processor = null;
      document = null;
      reception = null;
    }
  }

  /**
   * Private work over one pinned graph. Closing discards it; commit publishes the complete result
   * only if the originating session is still at the selected snapshot. A host may persist the
   * candidate snapshot atomically before committing its in-memory session. Methods serialize this
   * transaction; other sessions remain independent.
   */
  public static final class Transaction implements AutoCloseable {
    private Session owner;
    private ContractProcessor.Document before;
    private ContractProcessor processor;
    private ContractAttempt attempt;
    private ContractProcessor.Result result;
    private ContractProcessor.Document completed;
    private Contracts.Reception reception;
    private Transition transition;
    private ContractLanguage.ProviderScope contentScope;

    private Transaction(
        Session owner,
        ContractProcessor.Document before,
        ContractProcessor processor,
        ContractAttempt attempt) {
      this.owner = owner;
      this.before = before;
      this.processor = processor;
      this.attempt = attempt;
    }

    /**
     * Executes at most one canonical scheduling unit. Cycles use the canonical SCC scheduler.
     *
     * @return true if more work may remain; false when ready to finish
     */
    public synchronized boolean advance() {
      checkOpen();
      if (result != null) {
        return false;
      }
      return boundary(attempt::advanceLocal);
    }

    /**
     * Finishes all remaining private work. Repeated calls return the same immutable outcome.
     *
     * @return the complete canonical result, not yet published to the session
     */
    public synchronized Transition finish() {
      checkOpen();
      if (result == null) {
        result = boundary(attempt::finishLocal);
        transition = new Transition(result, processor);
        attempt = null;
      }
      return transition;
    }

    /**
     * Exports the completed candidate, including all graph members and cyclic proofs. Failed
     * attempts export the unchanged input snapshot.
     *
     * @return portable canonical snapshot
     */
    public synchronized Map<String, Object> snapshot() {
      finish();
      return boundary(() -> processor.exportSnapshot(document()));
    }

    /**
     * Exports the candidate metadata and only content not already retained by the recipient. A true
     * predicate result promises the complete verified closure for that identity is retained.
     * Restore requires combining the returned content with those retained records. This changes
     * transport only; snapshot identity, gas and execution are unchanged.
     *
     * @param retained recipient's immutable content catalogue membership test
     * @return canonical metadata with a partial content envelope
     */
    public synchronized Map<String, Object> snapshot(
        java.util.function.Predicate<BlueId> retained) {
      Objects.requireNonNull(retained);
      finish();
      return boundary(
          () ->
              ContractStorage.export(
                  processor, document().state, true, id -> retained.test(new BlueId(id))));
    }

    /**
     * Inspects native receivers in the completed candidate before publication.
     *
     * @return immutable receiver descriptions bound to the candidate snapshot
     */
    public synchronized Contracts.Reception reception() {
      finish();
      if (reception == null) {
        reception = boundary(() -> Contracts.reception(processor, document()));
      }
      return reception;
    }

    /**
     * Inspects completed candidate topology without exporting content or committing the candidate.
     *
     * @return immutable candidate graph topology
     */
    public synchronized Graph graph() {
      return Contracts.graph(document().state);
    }

    /**
     * Lists one candidate member's operations for publication-bound read models. These descriptions
     * grant no authorization and do not assert that the candidate has been committed.
     *
     * @param member candidate member key
     * @return immutable operation descriptions
     */
    public synchronized List<Contracts.Operation> operations(String member) {
      Objects.requireNonNull(member);
      return boundary(() -> ContractReception.operations(processor, document().state, member));
    }

    /**
     * Returns a completed candidate member for preparing durable content and local checkpoints.
     * This immutable value is semantically valid even if the transaction is later discarded; it
     * does not assert that this candidate was committed or grant membership in the live graph.
     *
     * @param member candidate graph member key
     * @return completed value with its supporting content
     */
    public synchronized LanguageDocument member(String member) {
      var selected = document().state.documents.get(Objects.requireNonNull(member));
      if (selected == null) {
        throw new IllegalArgumentException("Unknown graph member: " + member);
      }
      return processor.blue.context.document(selected.body);
    }

    // The public result returns a defensive snapshot. Materialize it once for this private
    // transaction, then share it across its serialized inspection and publication methods.
    private ContractProcessor.Document document() {
      finish();
      if (completed == null) {
        completed = result.document();
      }
      return completed;
    }

    /**
     * Returns the completed candidate's canonical snapshot identity.
     *
     * @return snapshot identity
     */
    public synchronized String identity() {
      finish();
      return document().identity();
    }

    /**
     * Returns the completed candidate's root document identity.
     *
     * @return the root BlueId, unchanged when the invocation failed
     */
    public synchronized BlueId blueId() {
      finish();
      return new BlueId(document().blueId());
    }

    /**
     * Atomically installs a successful candidate in the originating session. Rejected attempts
     * leave it unchanged. A stale candidate fails even when its business result was rejected.
     *
     * @return the canonical outcome
     * @throws IllegalStateException if the session changed or was closed
     */
    public synchronized Transition commit() {
      finish();
      synchronized (owner) {
        owner.checkOpen();
        if (owner.document != before) {
          throw new IllegalStateException("Stale Contracts transaction");
        }
        if (result.commits()) {
          owner.processor = processor;
          owner.document = document();
          owner.reception = reception;
        }
      }
      Transition committed = transition;
      close();
      return committed;
    }

    private void checkOpen() {
      if (processor == null) {
        throw new IllegalStateException("Contracts transaction is closed");
      }
    }

    /** Discards private state without changing the originating session. */
    @Override
    public synchronized void close() {
      if (contentScope != null) {
        contentScope.close();
        contentScope = null;
      }
      owner = null;
      before = null;
      transition = null;
      processor = null;
      attempt = null;
      result = null;
      completed = null;
      reception = null;
    }
  }

  /**
   * One committed native value and the member that emitted it, in invocation order.
   *
   * @param member graph-local emitting member key
   * @param event immutable exact event with its supporting content
   */
  public record Emission(String member, LanguageDocument event) {
    /** Requires a member identity and immutable admitted event. */
    public Emission {
      Objects.requireNonNull(member);
      Objects.requireNonNull(event);
    }

    /**
     * Projects this event using the same representation as {@link Transition#events()}. The exact
     * event and its identity remain available through {@link #event()}.
     *
     * @return immutable event value, retaining references at cyclic boundaries
     */
    public Object value() {
      return boundary(
          () -> {
            var bridge = new ContractBridge(event);
            return BexValues.freeze(bridge.project(event.semantic, false, Set.of(), true));
          });
    }
  }

  /** Immutable public outcome of one complete canonical invocation. */
  public static final class Transition {
    private final boolean admissionVerified;
    private final String status;
    private final long gas;
    private final String receipt;
    private final List<Object> events;
    private final List<Emission> emissions;
    private final Map<String, Object> observations;

    @SuppressWarnings("unchecked")
    Transition(ContractProcessor.Result result, ContractProcessor processor) {
      admissionVerified = result.admissionVerified;
      status = result.status();
      gas = result.gas();
      receipt = result.receipt();
      events = (List<Object>) BexValues.freeze(processor.emitted(result));
      observations = (Map<String, Object>) ContractData.freeze(result.outcome.observations());
      emissions =
          !result.commits()
              ? List.of()
              : result.outcome.events.stream()
                  .map(
                      raw -> {
                        var occurrence = ContractModel.obj(raw);
                        return new Emission(
                            (String) occurrence.get("document"),
                            processor.blue.context.document(
                                new ExactNode.Ref((String) occurrence.get("event"))));
                      })
                  .toList();
    }

    /**
     * Whether the native admission verifiers completed successfully for this attempt.
     *
     * @return verification disposition, independent of business success
     */
    public boolean admissionVerified() {
      return admissionVerified;
    }

    /**
     * Returns canonical outcome category, such as APPLIED or GAS_LIMIT_EXCEEDED.
     *
     * @return canonical outcome category, such as APPLIED or GAS_LIMIT_EXCEEDED
     */
    public String status() {
      return status;
    }

    /**
     * Returns whether the entire invocation committed.
     *
     * @return whether the entire invocation committed
     */
    public boolean applied() {
      return status.equals("APPLIED");
    }

    /**
     * Returns admitted canonical gas, independent of wall-clock time or physical caches.
     *
     * @return admitted canonical gas, independent of wall-clock time or physical caches
     */
    public long gas() {
      return gas;
    }

    /**
     * Returns canonical receipt committing this outcome.
     *
     * @return canonical receipt committing this outcome
     */
    public String receipt() {
      return receipt;
    }

    /**
     * Returns projected public event payloads in canonical emission order.
     *
     * @return projected public event payloads in canonical emission order
     */
    public List<Object> events() {
      return events;
    }

    /**
     * Returns exact events from every emitting member with their supporting content retained.
     * Unlike {@link #events()}, this includes embedded member emissions, without projecting them.
     *
     * @return immutable emissions in canonical invocation order
     */
    public List<Emission> emissions() {
      return emissions;
    }

    /**
     * Returns immutable canonical observations, including gas, work, events and diagnostics.
     *
     * @return immutable canonical observations, including gas, work, events and diagnostics
     */
    public Map<String, Object> observations() {
      return observations;
    }

    /**
     * Requires successful application without discarding the outcome on failure.
     *
     * @return this outcome
     * @throws BlueException when the invocation did not commit
     */
    public Transition requireApplied() {
      if (!applied()) {
        throw new BlueException(status, ContractWire.write(observations.get("diagnostic")));
      }
      return this;
    }
  }

  /**
   * An immutable description of the current receiving state. It grants no authority.
   *
   * @param snapshotIdentity exact input snapshot, including execution bindings
   * @param channels active native channels requiring admission in this state
   */
  public record Reception(String snapshotIdentity, List<ReceivingChannel> channels) {
    /** Captures an immutable description. */
    public Reception {
      Objects.requireNonNull(snapshotIdentity);
      channels = List.copyOf(channels);
    }
  }

  /**
   * One native channel and its routing relationships, resolved by the Contracts registry.
   *
   * @param typeId exact installed native binding
   * @param receiver original declaration, lifetime and checkpoint
   * @param headerIdentities exact identities of supplied header values, including inherited values
   * @param selectors primitive/composite channel names that contain this channel
   * @param observed whether a raw workflow or native handler observes this channel
   */
  public record ReceivingChannel(
      BlueId typeId,
      NativeAdmission.Receiver receiver,
      Map<String, BlueId> headerIdentities,
      Set<String> selectors,
      boolean observed) {
    /** Captures immutable routing data; it is not an admission permit. */
    public ReceivingChannel {
      Objects.requireNonNull(typeId);
      Objects.requireNonNull(receiver);
      headerIdentities = Map.copyOf(headerIdentities);
      selectors = Set.copyOf(selectors);
    }
  }

  /**
   * A recognized Contracts operation request, without interpreting it as authorization.
   *
   * @param operation operation name
   * @param channel selected channel, possibly composite
   * @param document optional exact target identity
   * @param requireExactDocumentVersion whether Contracts must reject a different current version
   */
  public record OperationRequest(
      String operation,
      String channel,
      java.util.Optional<BlueId> document,
      boolean requireExactDocumentVersion) {
    /** Validates the immutable description. */
    public OperationRequest {
      Objects.requireNonNull(operation);
      Objects.requireNonNull(channel);
      Objects.requireNonNull(document);
    }
  }

  /**
   * One declared callable operation, resolved by the same registry that executes it. This
   * description grants no channel authority.
   *
   * @param name contract key used when invoking the operation
   * @param channel declared channel selector
   * @param request optional projected request declaration
   * @param description optional authored description
   */
  public record Operation(
      String name,
      String channel,
      java.util.Optional<Object> request,
      java.util.Optional<String> description) {
    /** Retains immutable authoring information. */
    public Operation {
      Objects.requireNonNull(name);
      Objects.requireNonNull(channel);
      request = Objects.requireNonNull(request).map(BexValues::freeze);
      Objects.requireNonNull(description);
    }
  }

  /**
   * Host integration for canonical independently persisted owners. This is an adapter to Contracts
   * retained receiving. One instance is confined to one attempt. Closing it revokes borrowed
   * providers and admission evidence. Starting new work afterwards throws {@link
   * IllegalStateException}. Previously captured document identities remain readable. A cyclic
   * component remains an inseparable native unit. Snapshot roots never rename members.
   */
  public static final class Execution implements AutoCloseable {
    private final ContractProcessor processor;
    private ContractLanguage.ValidationScope validationScope;
    private final List<ContractLanguage.ProviderScope> contentScopes = new ArrayList<>();
    private boolean closed;

    /**
     * Protected prior semantic validation. Keys are opaque and already bind the Language edition.
     */
    @FunctionalInterface
    public interface ValidationReader {
      /**
       * Reads a fact previously issued by a successful native result in this host's storage.
       * Hashes, uploads and externally supplied booleans must never implement this authority.
       *
       * @param key exact opaque validation key
       * @return retained result, or empty to perform ordinary validation
       */
      Optional<Boolean> find(String key);
    }

    /**
     * Reads opaque preparation issued by a successful native commit, from protected storage only.
     */
    @FunctionalInterface
    public interface PreparationReader {
      /**
       * Reads a preparation artifact published atomically with this host's successful checkpoint.
       *
       * @param snapshot exact committed checkpoint identity
       * @return protected artifact, or empty for ordinary cold validation
       */
      Optional<Map<String, Object>> find(String snapshot);
    }

    /** Reads only this host's protected, atomically committed completion records; never uploads. */
    @FunctionalInterface
    public interface CompletionReader {
      /**
       * Reads a committed result from protected host storage.
       *
       * @param receipt native receipt identity selected by the host
       * @return the exact committed invocation, including its original cause
       */
      Retained read(String receipt);
    }

    /**
     * Protected storage data. The reader must establish publication provenance. Hashes alone do not
     * establish execution. The processor checks exact snapshots, receipt, cause and runtime
     * bindings.
     *
     * @param before exact original before-checkpoint
     * @param after exact committed after-checkpoint
     * @param observations native invocation observations
     * @param receipt native completion receipt
     * @param event original external event identity
     * @param timestamp original external event timestamp
     */
    public record Retained(
        Map<String, Object> before,
        Map<String, Object> after,
        Map<String, Object> observations,
        String receipt,
        String event,
        Long timestamp) {
      /** Validates required record fields. */
      public Retained {
        Objects.requireNonNull(before);
        Objects.requireNonNull(after);
        Objects.requireNonNull(observations);
        Objects.requireNonNull(receipt);
      }
    }

    /**
     * One whole source owner or cyclic unit, selected from a protected completion publication.
     *
     * @param receipt native receipt identity
     * @param members complete source unit, retaining its original member keys
     */
    public record Source(String receipt, List<String> members) {
      /** Copies the selected members. */
      public Source {
        Objects.requireNonNull(receipt);
        members = List.copyOf(members);
      }
    }

    /**
     * Native channel declaration; inspecting it grants no admission authority.
     *
     * @param type exact native type identity
     * @param receiver member, lifetime and checkpoint metadata
     * @param fields immutable header identities
     * @param selectors channel names whose selection includes this primitive channel
     * @param observed whether a raw workflow or native handler observes this channel
     */
    public record Channel(
        BlueId type,
        NativeAdmission.Receiver receiver,
        Map<String, BlueId> fields,
        Set<String> selectors,
        boolean observed) {
      /** Copies declaration metadata. */
      public Channel {
        Objects.requireNonNull(type);
        Objects.requireNonNull(receiver);
        fields = Map.copyOf(fields);
        selectors = Set.copyOf(selectors);
      }
    }

    /**
     * Callable operation metadata, without an authorization claim.
     *
     * @param name operation name under contracts
     * @param channel declared input channel
     */
    public record Operation(String name, String channel) {}

    private Execution(Contracts configuration, CompletionReader reader) {
      processor = configuration.processor();
      if (reader != null) {
        processor.installCompletionReader(
            (engine, receipt) -> {
              Retained record = Objects.requireNonNull(reader.read(receipt));
              if (!receipt.equals(record.receipt())) {
                throw new IllegalArgumentException("Wrong completion");
              }
              return new ContractProcessor.CompletedRecord(
                  engine.importSnapshot(record.before(), (String) record.before().get("identity")),
                  engine.importSnapshot(record.after(), (String) record.after().get("identity")),
                  record.observations(),
                  receipt,
                  record.event(),
                  record.timestamp());
            });
      }
    }

    /**
     * Creates an attempt-local processor, sharing the configuration's immutable compiled programs.
     *
     * @param configuration selected Language and native execution environment
     * @param reader protected completion storage, or null when receiving is unused
     * @return an adapter confined to the calling thread and this attempt
     */
    public static Execution using(Contracts configuration, CompletionReader reader) {
      return new Execution(Objects.requireNonNull(configuration), reader);
    }

    /**
     * Creates an attempt with durable semantic memoization and ordinary native authority checks.
     * The host must retain the validated immutable content closure. No document session is cached.
     *
     * @param configuration selected runtime
     * @param reader protected source completion reader, or null
     * @param validation protected semantic facts from previously published successful results
     * @return a thread-confined attempt that must be closed after publication preparation
     */
    public static Execution using(
        Contracts configuration, CompletionReader reader, ValidationReader validation) {
      Execution result = using(configuration, reader);
      result.validationScope =
          result.processor.blue.context.validationScope(Objects.requireNonNull(validation)::find);
      return result;
    }

    /**
     * Opens one attempt with protected preparation and semantic facts.
     *
     * @param configuration selected runtime
     * @param reader protected completion reader
     * @param validation protected semantic facts
     * @param preparations protected native preparation reader
     * @return attempt-local adapter
     */
    public static Execution using(
        Contracts configuration,
        CompletionReader reader,
        ValidationReader validation,
        PreparationReader preparations) {
      var result = using(configuration, reader, validation);
      result.processor.registry.preparations =
          new ContractPreparations(result.processor.registry, Objects.requireNonNull(preparations));
      return result;
    }

    /**
     * Borrows immutable value-validation evidence from one verified original occurrence until this
     * attempt closes. It does not establish permissions, source completeness or current membership.
     * Content needed by resulting checkpoints must still be retained by the host.
     *
     * @param entry verifier-issued original occurrence
     * @return this attempt-local adapter
     */
    public Execution withTimelineEntry(TimelineVerifier.Entry entry) {
      requireOpen();
      Objects.requireNonNull(entry);
      if (validationScope == null) {
        validationScope = processor.blue.context.validationScope(key -> Optional.empty());
      }
      validationScope.withTimelineEntry(entry);
      return this;
    }

    /**
     * Retains an exact message or document and its dependency resolver for this attempt.
     *
     * @param content immutable input content; this grants no admission authority
     * @return this attempt-local adapter
     */
    public Execution withContent(LanguageContent content) {
      requireOpen();
      contentScopes.add(processor.blue.context.withContent(Objects.requireNonNull(content)));
      return this;
    }

    /**
     * Supplies exact Language records lazily for this attempt. Records still undergo ordinary
     * identity and semantic checks; providing content grants no execution or admission authority.
     * The provider is released when this adapter closes, including its inherited resolver branches.
     *
     * @param provider immutable content lookup, scoped to a retained storage snapshot
     * @return this attempt-local adapter
     */
    public Execution withContent(LanguageProvider provider) {
      requireOpen();
      contentScopes.add(processor.blue.context.withProvider(Objects.requireNonNull(provider)));
      return this;
    }

    /** Protected reusable calculations issued by this exact native implementation. */
    @FunctionalInterface
    public interface CalculationReader {
      /**
       * Reads a calculation published with a successful local outcome. This is a trusted storage
       * boundary, never an API for accepting calculations supplied by document authors.
       *
       * @param key exact runtime and input binding
       * @return retained artifact, or empty to execute normally
       */
      Optional<Map<String, Object>> find(String key);
    }

    /**
     * Enables bounded reuse of pure BEX calculations. Native reads, accounting, routing, patches
     * and finalization still belong to this attempt. Near a gas limit the ordinary evaluator runs.
     *
     * @param reader protected local calculation storage
     * @return this attempt-local adapter
     */
    public Execution reuseCalculations(CalculationReader reader) {
      requireOpen();
      if (processor.calculations != null) {
        throw new IllegalStateException("Calculations already enabled");
      }
      processor.calculations = new ContractCalculations(Objects.requireNonNull(reader));
      return this;
    }

    /**
     * Returns bounded private artifacts to publish atomically with a successful outcome. They are
     * host implementation data, not Blue documents or canonical execution proofs.
     *
     * @return artifacts indexed by their exact input binding
     */
    public Map<String, Map<String, Object>> calculations() {
      return processor.calculations == null ? Map.of() : processor.calculations.completed();
    }

    /**
     * Physical calculation diagnostics; these never affect canonical results or gas.
     *
     * @param executed calculations evaluated normally
     * @param reused calculations supplied by protected storage
     * @param budgetFallback calculations replayed normally to preserve exact gas exhaustion
     */
    public record CalculationStatistics(long executed, long reused, long budgetFallback) {}

    /**
     * Returns physical reuse counters for this adapter.
     *
     * @return counters, all zero when calculation reuse is disabled
     */
    public CalculationStatistics calculationStatistics() {
      var cache = processor.calculations;
      return cache == null
          ? new CalculationStatistics(0, 0, 0)
          : new CalculationStatistics(cache.executed, cache.reused, cache.budgetFallback);
    }

    /**
     * Releases borrowed host readers and revokes facts across the attempt’s resolver branches.
     * Previously exported calculation artifacts and scalar diagnostics remain readable.
     */
    @Override
    public void close() {
      if (closed) {
        return;
      }
      closed = true;
      processor.releaseHostReaders();
      processor.blue.context.clearLocalRetention();
      if (validationScope != null) {
        validationScope.close();
      }
      if (processor.registry.preparations != null) {
        processor.registry.preparations.close();
      }
      for (int i = contentScopes.size() - 1; i >= 0; i--) {
        contentScopes.get(i).close();
      }
      contentScopes.clear();
    }

    /**
     * Inspects raw observers against the receiver's exact document, never an ambient latest view.
     * Only declaration headers are read; workflow bodies remain deferred.
     *
     * @param receiver original native admission receiver
     * @return whether a workflow or native handler observes the selected primitive channel
     */
    public boolean observes(NativeAdmission.Receiver receiver) {
      requireOpen();
      Objects.requireNonNull(receiver);
      return Contracts.boundary(
          () -> {
            var document =
                new ContractModel.Document(
                    receiver.member(),
                    new ExactNode.Ref(receiver.documentId().value()),
                    receiver.documentId().value(),
                    null);
            var declarations = processor.registry.contracts(document);
            var channel = declarations.get(receiver.channel());
            ContractModel.require(
                channel != null
                    && ContractLanguage.identity(processor.blue.exact(channel.get("_recipe")))
                        .equals(receiver.declarationId().value()),
                "DIFFERENT_RECEIVER_DECLARATION");
            for (var declaration : declarations.values()) {
              if (Set.of("workflow", "extension_handler").contains(declaration.get("_role"))
                  && declarations.containsKey(declaration.get("channel"))
                  && ContractRegistry.EXTERNAL.contains(
                      declarations.get(declaration.get("channel")).get("_role"))
                  && ContractChannels.leaves(
                          declarations, ContractModel.str(declaration.get("channel")))
                      .contains(receiver.channel())) {
                return true;
              }
            }
            return false;
          });
    }

    /**
     * Prepares a fresh root with a host-unique member key, retaining admitted Language evidence.
     *
     * @param member host-unique root key
     * @param document authored Language definition
     * @return prepared, uninitialized state
     */
    public State prepare(String member, LanguageDocument document) {
      requireOpen();
      return Contracts.boundary(
          () -> {
            processor.blue.context.install(document);
            return new State(processor.prepare(Map.of(member, document.exact), member));
          });
    }

    /**
     * Prepares an explicit owning graph. Links preserve sharing and cycles.
     *
     * @param members member keys and authored JSON-like definitions
     * @param root root member key
     * @return prepared, uninitialized graph
     */
    public State prepare(Map<String, Object> members, String root) {
      requireOpen();
      return Contracts.boundary(() -> new State(processor.prepare(members, root)));
    }

    /**
     * Creates an authoring link for {@link #prepare(Map, String)}; this is not a Blue type.
     *
     * @param member target member key
     * @return opaque graph-authoring operand
     */
    public static Object link(String member) {
      return ContractProcessor.link(member);
    }

    /**
     * Restores a checkpoint retained in this host's protected storage.
     *
     * @param checkpoint exact envelope previously exported by State.checkpoint
     * @return restored native state bound to this attempt
     */
    public State restore(Map<String, Object> checkpoint) {
      requireOpen();
      return Contracts.boundary(
          () ->
              new State(processor.importSnapshot(checkpoint, (String) checkpoint.get("identity"))));
    }

    /**
     * Initializes a prepared native graph.
     *
     * @param before prepared state from this adapter
     * @return the native initialization result
     */
    public Outcome initialize(State before) {
      check(before);
      return Contracts.boundary(
          () -> outcome(before, processor.initialize(before.value), null, null));
    }

    /**
     * Processes one original external entry. The host establishes source ordering and admission.
     *
     * @param before committed state from this adapter
     * @param entry exact original event and its supporting content
     * @param timestamp deterministic nonnegative source time
     * @param evidence selected native admission evidence
     * @return tentative result; the host must durably publish it before exposing completion
     */
    public Outcome process(
        State before, LanguageContent entry, long timestamp, AdmissionEvidence evidence) {
      check(before);
      return Contracts.boundary(
          () -> {
            // Protected semantic facts can avoid opening some input dependencies during execution.
            // The resulting checkpoint may still reference them, so retain this exact catalogue
            // until the host has published the outcome and closes its attempt. Content availability
            // does not prolong native admission authority.
            contentScopes.add(processor.blue.context.withContent(entry));
            return outcome(
                before,
                processor.process(before.value, entry.semantic, timestamp, evidence.values()),
                entry.blueId().value(),
                timestamp);
          });
    }

    /**
     * Install completed children through native Contracts receiving, without re-executing them.
     * LIVE also applies this entry's surviving receiver seeds; historical application is seedless.
     *
     * @param before committed receiver state from this adapter
     * @param sources nonempty list of protected source completions
     * @param live whether to apply surviving external receiver seeds
     * @param evidence selected native admission evidence
     * @return tentative native receiving result
     */
    public Outcome receive(
        State before, List<Source> sources, boolean live, AdmissionEvidence evidence) {
      check(before);
      if (sources.isEmpty()) {
        throw new IllegalArgumentException("Receiving requires a completed source");
      }
      return Contracts.boundary(
          () -> {
            List<ContractProcessor.SourceCompletion> completions =
                sources.stream()
                    .map(
                        source ->
                            processor.loadSourceCompletion(source.receipt(), source.members()))
                    .toList();
            var first = completions.getFirst().descriptor();
            ContractProcessor.Result result =
                live
                    ? processor.receiveLive(before.value, completions, evidence.values())
                    : processor.receive(before.value, completions);
            return outcome(
                before,
                result,
                (String) first.get("event"),
                first.get("timestamp") == null
                    ? null
                    : ((Number) first.get("timestamp")).longValue());
          });
    }

    /**
     * Materializes a historical graph for inspection or ordinary replay from protected owner
     * records and exact historical attachment edges. This deliberately does graph-wide work; it
     * must not be called on the local processing hot path. Ordinary restore validates it.
     *
     * @param root protected root checkpoint
     * @param members selected historical checkpoints, including root
     * @param edges historical attachments with original member keys
     * @return ordinary snapshot envelope without bundled content
     */
    public static Map<String, Object> materializeLocalCheckpoint(
        Map<String, Object> root, List<Map<String, Object>> members, List<Embedding> edges) {
      return ContractLocalCheckpoint.materialize(root, members, edges);
    }

    /**
     * Restores a historical graph from the host's committed owner checkpoints, retaining their
     * prior native preparation. This is the structural-edit fallback, not the ordinary leaf-update
     * path. It visits graph metadata and verifies attachment edges, but does not repeat each
     * owner's completed language admission. Checkpoints must come from protected local storage;
     * hashes alone do not grant that trust. Create this attempt with a {@link ValidationReader} (an
     * empty reader is sufficient) so borrowed preparation is revoked on close. Use ordinary {@link
     * #restore(Map)} for portable input.
     *
     * @param root protected root checkpoint
     * @param members exact historical checkpoints, including root
     * @param edges frozen historical attachments; never today's child heads
     * @return an ordinary graph state usable for structural processing
     */
    public State restoreLocalGraph(
        Map<String, Object> root, List<Map<String, Object>> members, List<Embedding> edges) {
      requireOpen();
      if (validationScope == null) {
        throw new IllegalStateException("Protected graph recovery requires a validation scope");
      }
      return Contracts.boundary(
          () ->
              new State(
                  new ContractProcessor.Document(
                      ContractLocalCheckpoint.restoreGraph(processor, root, members, edges))));
    }

    /**
     * Restores one protected owner for source admission before local processing. When the
     * checkpoint includes native semantic preparation, open the attempt with a {@link
     * ValidationReader}. An empty reader is sufficient when the host has no additional validation
     * facts.
     *
     * @param checkpoint host-owned checkpoint from localCheckpoint
     * @return own-member state, without descendant restoration
     */
    public State restoreLocal(Map<String, Object> checkpoint) {
      requireOpen();
      return Contracts.boundary(
          () -> {
            var selected = new LinkedHashMap<>(checkpoint);
            return new State(
                new ContractProcessor.Document(
                    ContractLocalCheckpoint.restore(processor, selected)),
                ContractModel.str(selected.get("identity")));
          });
    }

    /**
     * Reconstructs an ordinary envelope from a protected owner and its historical native metadata.
     * The returned envelope still requires ordinary restore validation; it is not a portable proof.
     *
     * @param checkpoint protected owner checkpoint
     * @param metadata complete historical native graph metadata
     * @return ordinary snapshot envelope without bundled content
     */
    public static Map<String, Object> materializeLocalMetadata(
        Map<String, Object> checkpoint, Map<String, Object> metadata) {
      return Contracts.boundary(
          () -> ContractLocalCheckpoint.materializeMetadata(checkpoint, metadata));
    }

    /**
     * Processes one already admitted acyclic owner using protected local storage. The host must
     * supply every source occurrence selected by its durable ordered frontier. Open the attempt
     * with a {@link ValidationReader} to consume checkpoints carrying semantic preparation. This
     * method accepts no foreign checkpoints or completions. Unsupported decomposition throws
     * LOCAL_EXECUTION_UNAVAILABLE so the host can replay the whole rooted invocation.
     *
     * @param checkpoint protected owner checkpoint issued by localCheckpoint
     * @param attachments selected attachment paths and their protected source results
     * @param entry original accepted event
     * @param timestamp original event time
     * @return owner-only result; persist with localCheckpoint, not portable checkpoint
     */
    public Outcome processLocal(
        Map<String, Object> checkpoint,
        List<Map<String, Object>> attachments,
        LanguageContent entry,
        long timestamp) {
      return processLocal(checkpoint, attachments, entry, timestamp, AdmissionEvidence.empty());
    }

    /**
     * Processes one protected owner with invocation-scoped native admission evidence. The installed
     * verifier must establish that evidence for this exact owner and original input. Checkpoints
     * carrying semantic preparation require an attempt opened with a {@link ValidationReader}.
     *
     * @param checkpoint protected owner checkpoint
     * @param attachments selected protected child completions
     * @param entry original event
     * @param timestamp original event time
     * @param evidence native evidence for this invocation
     * @return tentative owner-only result
     */
    public Outcome processLocal(
        Map<String, Object> checkpoint,
        List<Map<String, Object>> attachments,
        LanguageContent entry,
        long timestamp,
        AdmissionEvidence evidence) {
      return processLocal(checkpoint, attachments, entry, timestamp, evidence, null);
    }

    /**
     * Processes an owner with an optional protected lookup of live members in its pinned graph. The
     * lookup must return the unique member's local checkpoint, null for absence, or refuse
     * ambiguous or unavailable history. It is consulted only for a fresh collection insertion.
     * Checkpoints carrying semantic preparation require an attempt opened with a {@link
     * ValidationReader}.
     *
     * @param checkpoint protected checkpoint for the owner at the selected predecessor
     * @param attachments paths, generations and child completions selected by the ordered frontier
     * @param entry original admitted event
     * @param timestamp timestamp of that event
     * @param evidence native authorization evidence for this owner and entry
     * @param liveMember lookup by BlueId in the pinned graph, or null to disable local creation
     * @return tentative native result to publish against the same predecessor
     */
    public Outcome processLocal(
        Map<String, Object> checkpoint,
        List<Map<String, Object>> attachments,
        LanguageContent entry,
        long timestamp,
        AdmissionEvidence evidence,
        java.util.function.Function<BlueId, Map<String, Object>> liveMember) {
      requireOpen();
      return Contracts.boundary(
          () -> {
            contentScopes.add(processor.blue.context.withContent(entry));
            var result =
                ContractLocal.process(
                    processor,
                    checkpoint,
                    attachments,
                    entry.semantic,
                    timestamp,
                    evidence.values(),
                    liveMember);
            return outcome(
                new State(new ContractProcessor.Document(result.before())),
                new ContractProcessor.Result(result.outcome()),
                entry.blueId().value(),
                timestamp);
          });
    }

    /**
     * Processes an owner already restored by {@link #restoreLocal(Map)} in this execution attempt.
     * Source admission may inspect that state before this call. Reusing it avoids a second
     * checkpoint decode and validation; it does not bypass native authorization or selected child
     * completion checks. The state and its checkpoint identity are fixed at restoration, even if
     * the caller later modifies its checkpoint map. Ordinary graph states and states from another
     * or closed attempt are rejected. The original state remains unchanged after processing.
     *
     * <p>As with {@link #processLocal(Map, List, LanguageContent, long, AdmissionEvidence,
     * java.util.function.Function)}, all checkpoints and selected completions must come from
     * protected local storage. Unsupported decomposition throws LOCAL_EXECUTION_UNAVAILABLE.
     *
     * @param before owner state returned by this attempt's restoreLocal
     * @param attachments selected protected child completions and their paths and generations
     * @param entry original admitted event
     * @param timestamp timestamp of that event
     * @param evidence native authorization evidence for this owner and entry
     * @param liveMember pinned graph lookup, or null to disable local creation
     * @return tentative native result to publish against the restored predecessor
     */
    public Outcome processRestoredLocal(
        State before,
        List<Map<String, Object>> attachments,
        LanguageContent entry,
        long timestamp,
        AdmissionEvidence evidence,
        java.util.function.Function<BlueId, Map<String, Object>> liveMember) {
      check(before);
      if (before.localCheckpointIdentity == null) {
        throw new IllegalArgumentException("State was not restored from a local checkpoint");
      }
      return Contracts.boundary(
          () -> {
            contentScopes.add(processor.blue.context.withContent(entry));
            var result =
                ContractLocal.processRestored(
                    processor,
                    before.value.state,
                    before.localCheckpointIdentity,
                    attachments,
                    entry.semantic,
                    timestamp,
                    evidence.values(),
                    liveMember);
            return outcome(
                before,
                new ContractProcessor.Result(result.outcome()),
                entry.blueId().value(),
                timestamp);
          });
    }

    /**
     * Exports new content of a local result, cutting at protected retained closures without
     * checking every unchanged child against storage again.
     *
     * @param result unpublished owner result
     * @param retained fallback availability for newly encountered content
     * @return exact native records to commit atomically with the owner checkpoint
     */
    public Map<String, Object> localContent(Outcome result, Predicate<String> retained) {
      check(result.after());
      if (!result.after().value.state.ownerLocal) {
        throw new IllegalArgumentException("Not a local result");
      }
      var roots = new ArrayList<ExactNode>();
      roots.add(result.after().value.state.documents.get(result.after().root()).body);
      for (var emission : result.transition().emissions()) {
        roots.add(emission.event().exact);
      }
      return processor.blue.context.records(
          roots, id -> processor.blue.context.locallyRetained(id) || retained.test(id));
    }

    /**
     * Projects a successful whole invocation for an isolated source which gained one fresh
     * descendant with bounded, unchanged dependencies. Only the original, protected native result
     * may supply this projection. It retains the original graph receipt and never represents it as
     * an owner-local receipt. Empty means the receiver must use ordinary graph composition.
     *
     * @param result protected outcome of this execution
     * @return completion with the child and prior bindings, or an empty map if the shape is
     *     ineligible
     */
    public Map<String, Object> singleBirthCompletion(Outcome result) {
      check(result.before());
      check(result.after());
      return ContractBirth.completion(
          processor, result, result.before().value.state, result.after().value.state);
    }

    /**
     * Exports only the exact content missing at a protected destination. The caller must supply
     * complete durable closure membership, not merely a cache hit or a document identity.
     *
     * @param roots BlueIds whose reachable records the destination needs
     * @param retained predicate certifying a complete, durable closure at the destination
     * @return missing native records keyed by BlueId
     */
    public Map<String, Object> missingContent(List<String> roots, Predicate<String> retained) {
      requireOpen();
      List<ExactNode> nodes =
          roots.stream()
              .distinct()
              .map(id -> (ExactNode) new ExactNode.Ref(new BlueId(id).value()))
              .toList();
      return Contracts.boundary(() -> processor.blue.context.records(nodes, retained));
    }

    private Outcome outcome(
        State before, ContractProcessor.Result result, String event, Long time) {
      return new Outcome(
          before,
          new State(result.document()),
          new Contracts.Transition(result, processor),
          event,
          time);
    }

    private void requireOpen() {
      if (closed) {
        throw new IllegalStateException("Owner attempt is closed");
      }
    }

    private void check(State state) {
      requireOpen();
      if (state.owner() != this) {
        throw new IllegalArgumentException("State belongs to another attempt");
      }
    }

    /** Immutable snapshot handle; all business changes are produced by the native processor. */
    public final class State {
      private final ContractProcessor.Document value;
      private final String localCheckpointIdentity;

      private State(ContractProcessor.Document value) {
        this(value, null);
      }

      private State(ContractProcessor.Document value, String localCheckpointIdentity) {
        this.value = value;
        this.localCheckpointIdentity = localCheckpointIdentity;
      }

      private Execution owner() {
        return Execution.this;
      }

      /**
       * Exports one member's protected execution state. It contains no descendant metadata. Only
       * acyclic admitted graphs are eligible for independent owner execution. Content must be
       * retained atomically with this record; this is not a portable proof.
       *
       * @return protected owner record, or empty when the ordinary graph path is required
       */
      public Map<String, Object> localCheckpoint() {
        check(this);
        return Contracts.boundary(() -> ContractLocalCheckpoint.checkpoint(processor, value.state));
      }

      /**
       * Returns this history's persisted accounting.
       *
       * @return exact gas schedule
       */
      public GasSchedule gasSchedule() {
        return value.state.gasSchedule;
      }

      /**
       * Returns the root's stable member key.
       *
       * @return the root's stable member key
       */
      public String root() {
        return value.state.root;
      }

      /**
       * Returns native snapshot identity, including runtime metadata.
       *
       * @return native snapshot identity, including runtime metadata
       */
      public String identity() {
        return value.identity();
      }

      /**
       * Returns the root's Blue content identity.
       *
       * @return the root's Blue content identity
       */
      public String blueId() {
        return value.blueId();
      }

      /**
       * Returns the root as a Language document.
       *
       * @return the root as a Language document
       */
      public LanguageDocument document() {
        return processor.blue.context.document(value.state.documents.get(root()).body);
      }

      /**
       * Reads through canonical Language semantics and native embedding views.
       *
       * @param pointer JSON pointer from the root
       * @return the effective JSON-like value, or Bex.undefined when absent
       */
      public Object read(String pointer) {
        return boundary(() -> processor.read(value, pointer));
      }

      /**
       * Returns all retained reachable members with their exact identities.
       *
       * @return all retained reachable members with their exact identities
       */
      public List<Contracts.Member> members() {
        return value.state.documents.values().stream()
            .map(d -> new Contracts.Member(d.key, new BlueId(d.blueId)))
            .toList();
      }

      /**
       * Returns retained embedding occurrences, including path generations.
       *
       * @return retained embedding occurrences, including path generations
       */
      public List<Contracts.Embedding> embeddings() {
        return value.state.edges.values().stream()
            .map(e -> new Contracts.Embedding(e.source(), e.path(), e.target(), e.generation()))
            .toList();
      }

      /**
       * Inspects all native receiving channels in this frozen rooted view.
       *
       * @return declarations and exact snapshot identity, without admission authority
       */
      public Contracts.Reception reception() {
        return Contracts.boundary(() -> Contracts.reception(processor, value));
      }

      /**
       * Returns the retained document of one member in this frozen view.
       *
       * @param member managed member key
       * @return immutable member document
       */
      public LanguageDocument member(String member) {
        var selected = value.state.documents.get(Objects.requireNonNull(member));
        if (selected == null) {
          throw new IllegalArgumentException("Unknown member: " + member);
        }
        return processor.blue.context.document(selected.body);
      }

      /**
       * Lists operations using the same native grammar as execution.
       *
       * @param member managed member key
       * @return immutable operation descriptions, without authorization claims
       */
      public List<Contracts.Operation> operations(String member) {
        return Contracts.boundary(
            () -> ContractReception.operations(processor, value.state, member));
      }

      /**
       * Recognizes an original message without granting authorization.
       *
       * @param message exact message and its content dependencies
       * @return operation request, when the native grammar recognizes one
       */
      public Optional<Contracts.OperationRequest> operationRequest(LanguageContent message) {
        return Contracts.boundary(
            () -> ContractReception.operation(processor, Objects.requireNonNull(message)));
      }

      /**
       * Recognizes the exact message of a verified timeline occurrence. Its temporary value
       * evidence is reused only for grammar inspection, never routing or authorization.
       *
       * @param entry verified occurrence
       * @param message its exact original message
       * @return recognized request, or empty for an ordinary event
       */
      public Optional<Contracts.OperationRequest> operationRequest(
          TimelineVerifier.Entry entry, LanguageContent message) {
        return Contracts.boundary(() -> ContractReception.operation(processor, entry, message));
      }

      /**
       * Returns strongly connected native processing units.
       *
       * @return strongly connected native processing units
       */
      public List<List<String>> components() {
        return ContractTopology.of(value.state).components(value.state.documents.keySet());
      }

      /**
       * Reports the native protected termination marker of this root.
       *
       * @return whether ordinary root execution has terminated
       */
      public boolean terminated() {
        return Contracts.boundary(
            () ->
                processor.registry.marker(value.state.documents.get(root()), "terminated") != null);
      }

      /**
       * Returns the root's native admission channels, excluding descendant channels.
       *
       * @return the root's native admission channels, excluding descendant channels
       */
      public List<Channel> channels() {
        return ContractReception.inspect(processor, value.state).channels().stream()
            .map(
                row ->
                    new Channel(
                        row.typeId(),
                        row.receiver(),
                        row.headerIdentities(),
                        row.selectors(),
                        row.observed()))
            .toList();
      }

      /**
       * Returns the root's declared operations.
       *
       * @return the root's declared operations
       */
      public List<Operation> operations() {
        return ContractReception.operations(processor, value.state).stream()
            .map(o -> new Operation(o.name(), o.channel()))
            .toList();
      }

      /**
       * Retains original member keys, channel lifetimes, births and exact reachable child views.
       *
       * @param member existing member to select as root
       * @return a snapshot restricted to that member's reachable graph
       */
      public State rooted(String member) {
        if (!value.state.documents.containsKey(member)) {
          throw new IllegalArgumentException("Unknown member");
        }
        if (root().equals(member)) {
          return this;
        }
        var topology = ContractTopology.of(value.state);
        var selected = ContractGraph.reachable(member, topology.outgoing, key -> false);
        var s = new ContractModel.Snapshot(member);
        s.release = value.state.release;
        s.gasSchedule = value.state.gasSchedule;
        s.generation = value.state.generation;
        s.revision = value.state.revision;
        s.runtimes = new ArrayList<>(value.state.runtimes);
        for (String key : selected) {
          s.documents.put(key, value.state.documents.get(key).copy());
        }
        for (String key : selected) {
          for (var edge : topology.outgoingEdges(key)) {
            s.edges.put(edge.key(), edge);
          }
        }
        return new State(new ContractProcessor.Document(s));
      }

      /**
       * Produces opaque native preparation for atomic storage alongside this exact checkpoint.
       *
       * @param retained predicate confirming complete immutable dependency closures are retained
       * @return artifact, or an empty map if durable dependencies are incomplete
       */
      public Map<String, Object> preparation(Predicate<String> retained) {
        check(this);
        return ContractPreparations.export(processor, value.state, retained);
      }

      /**
       * Exports only changed native preparation records against a protected predecessor. The host
       * must retain the complete base and merge these opaque rows atomically with the new
       * checkpoint. This is physical metadata, not an authored Blue document patch. An empty result
       * requires the ordinary full {@link #preparation(Predicate)} export.
       *
       * @param previous predecessor restored with protected preparation in this attempt
       * @param retained whether each changed content closure is durable or staged for publication
       * @return base-bound metadata delta, or empty when a full export is required
       */
      public Map<String, Object> preparationDelta(State previous, Predicate<String> retained) {
        check(this);
        check(previous);
        return ContractPreparations.delta(
            processor, previous.value.state, value.state, Objects.requireNonNull(retained));
      }

      /**
       * Returns established semantic facts for atomic storage with a successful native publication.
       * These records grant no current access, ordering, membership or execution authority.
       *
       * @param retained predicate confirming a complete immutable closure is already durable or
       *     included in the same atomic publication; mere presence of a root record is insufficient
       * @return opaque validation keys whose dependencies are retained, and their established
       *     results
       */
      public Map<String, Boolean> validationFacts(Predicate<String> retained) {
        if (closed) {
          throw new IllegalStateException("Owner attempt is closed");
        }
        Objects.requireNonNull(retained);
        return processor.blue.context.validationFacts(
            id ->
                value.state.ownerLocal && processor.blue.context.locallyRetained(id)
                    || retained.test(id));
      }

      /**
       * Exports a physical checkpoint update for a small append-only topology change. A protected
       * store may apply it only to the exact predecessor from the same attempt. The result remains
       * a whole-graph snapshot with its original native identity.
       *
       * @param previous protected complete predecessor
       * @param retained complete content closures held by that predecessor
       * @return changed native metadata and content, or empty for ordinary checkpoint export
       */
      public Map<String, Object> appendedCheckpoint(State previous, Predicate<String> retained) {
        check(this);
        check(previous);
        return Contracts.boundary(
            () -> ContractStorage.appended(processor, previous.value.state, value.state, retained));
      }

      /**
       * Exports exact native state, omitting content already available to the restoring processor.
       *
       * @param stored predicate for durably available exact Language content identities
       * @return a checkpoint whose content must be atomically persisted with its envelope
       */
      public Map<String, Object> checkpoint(Predicate<String> stored) {
        return Contracts.boundary(
            () -> ContractStorage.export(processor, value.state, true, stored));
      }

      /**
       * Returns a checkpoint containing its complete exportable Language content closure.
       *
       * @return a checkpoint containing its complete exportable Language content closure
       */
      public Map<String, Object> checkpoint() {
        return checkpoint(id -> false);
      }
    }

    /**
     * One tentative canonical invocation, to be published atomically by the host.
     *
     * @param before original native state
     * @param after resulting native state
     * @param transition native receipt, gas, effects and diagnostics
     * @param event original external event, or null for initialization
     * @param timestamp original external time, or null for initialization
     */
    public record Outcome(
        State before, State after, Contracts.Transition transition, String event, Long timestamp) {
      /**
       * Returns whether the native invocation applied successfully.
       *
       * @return whether the native invocation applied successfully
       */
      public boolean applied() {
        return transition.applied();
      }

      /**
       * Returns this outcome, throwing a BlueException if the invocation did not apply.
       *
       * @return this outcome, throwing a BlueException if the invocation did not apply
       */
      public Outcome requireApplied() {
        transition.requireApplied();
        return this;
      }
    }
  }
}
