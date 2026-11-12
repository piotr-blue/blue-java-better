package blue;

import static blue.TimelineContent.require;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Tentative provider-side execution of the published Timeline Access program. The provider must
 * atomically publish the original control entry, this resulting state, effective permissions and
 * its owner-command receipt. An uncommitted result grants nothing.
 *
 * <p>This is not consumer history verification. Consumers use {@link TimelineAccess}. Restoring a
 * provider session requires an expected snapshot identity obtained from protected committed
 * storage, never from an untrusted request. This API neither authenticates owners nor signs control
 * history.
 */
public final class TimelineAccessProgram {
  private final TimelineAccessDefinition definition;

  private TimelineAccessProgram(TimelineAccessDefinition definition) {
    this.definition = definition;
  }

  /**
   * Selects the original exact access document and the provider's attested reserved control source.
   *
   * @param coordination exact supported package
   * @param provider provider selected by the publishing host
   * @param descriptor signed descriptor identifying the reserved public control timeline
   * @param initial original admitted Timeline Access document
   * @return immutable configuration for independent tentative sessions
   */
  public static TimelineAccessProgram create(
      BluePackage coordination,
      LanguageDocument provider,
      LanguageContent descriptor,
      LanguageDocument initial) {
    return Language.run(
        () ->
            new TimelineAccessProgram(
                new TimelineAccessDefinition(
                    Objects.requireNonNull(coordination), Objects.requireNonNull(provider),
                    Objects.requireNonNull(descriptor), Objects.requireNonNull(initial))));
  }

  /**
   * Opens an uninitialized independent session. Initialization must precede updates.
   *
   * @return caller-owned tentative session
   */
  public Session open() {
    return new Session(definition);
  }

  /**
   * Restores the actual canonical processing state from protected provider storage.
   *
   * @param snapshot complete exported Contracts snapshot and evidence
   * @param expectedIdentity trusted separately retained snapshot identity
   * @return restored session bound to the original access document
   */
  public Session restore(Map<String, Object> snapshot, String expectedIdentity) {
    return restore(snapshot, expectedIdentity, id -> Optional.empty());
  }

  /**
   * Restores incremental snapshot evidence using an explicitly selected retained-content source.
   * Every returned record is verified normally; the provider supplies data, never execution facts.
   * The temporary lookup is released when restoration finishes.
   *
   * @param snapshot canonical snapshot and newly supplied exact evidence
   * @param expectedIdentity separately trusted committed snapshot identity
   * @param retained resolver for already retained dependencies in this provider's storage scope
   * @return restored tentative provider session
   */
  public Session restore(
      Map<String, Object> snapshot, String expectedIdentity, LanguageProvider retained) {
    return restoreLocal(snapshot, expectedIdentity, retained, null);
  }

  /**
   * Restores protected provider state with its previously validated input checkpoint. The host must
   * atomically retain both identities after successful publication. The checkpoint must identify
   * exactly this document's prior consumed control entry (or be initial before updates). It never
   * stands in for a remote consumer's verified history.
   *
   * @param snapshot canonical stored Contracts snapshot
   * @param expectedIdentity independently trusted snapshot commitment
   * @param retained scoped exact-content resolver
   * @param checkpoint protected-restored validation state for the last consumed original input
   * @return tentative session with bounded historical schema validation
   */
  public Session restore(
      Map<String, Object> snapshot,
      String expectedIdentity,
      LanguageProvider retained,
      TimelinePublisher.Prepared checkpoint) {
    Objects.requireNonNull(checkpoint).checkSource(definition);
    return restoreLocal(snapshot, expectedIdentity, retained, checkpoint);
  }

  @SuppressWarnings("try")
  private Session restoreLocal(
      Map<String, Object> snapshot,
      String expectedIdentity,
      LanguageProvider retained,
      TimelinePublisher.Prepared checkpoint) {
    Objects.requireNonNull(snapshot);
    Objects.requireNonNull(expectedIdentity);
    Objects.requireNonNull(retained);
    return Contracts.boundary(
        () -> {
          Session session = open();
          try (var providerScope = session.processor.blue.context.withProvider(retained);
              var checkpointScope = session.processor.blue.context.withPublisherHeads(checkpoint)) {
            session.state = session.processor.importSnapshot(snapshot, expectedIdentity);
            var root = session.state.state.documents.get(session.state.state.root);
            ExactNode marker = session.processor.registry.marker(root.body, "initialized");
            Map<String, Object> initialized =
                session.processor.registry.markerRecord(marker, "initialized");
            require(
                Map.of("blueId", definition.initial.blueId().value())
                    .equals(initialized.get("document")),
                "ACCESS_SNAPSHOT_TARGET",
                "initial document");
            if (checkpoint != null) {
              Map<String, Object> prior =
                  ContractChannels.checkpoint(session.processor.registry, root, "provider");
              require(
                  prior == null
                      ? checkpoint.entryId().isEmpty()
                      : checkpoint.entryId().isPresent()
                          && Map.of("blueId", checkpoint.entryId().orElseThrow().value())
                              .equals(prior.get("event"))
                          && ContractModel.integer(prior.get("timestamp"))
                              == checkpoint.timestamp(),
                  "ACCESS_SNAPSHOT_CHECKPOINT",
                  "prior consumed entry");
              session.checkpoint = checkpoint;
            }
          }
          return session;
        });
  }

  /** A caller-confined tentative execution lifetime; no method publishes or grants access. */
  @SuppressWarnings("try")
  public static final class Session implements AutoCloseable {
    private final TimelineAccessDefinition definition;
    private ContractProcessor processor;
    private ContractProcessor.Document state;
    private Selection selected;
    private TimelinePublisher.Prepared checkpoint;

    private record Selection(String snapshot, BlueId entry, long timestamp) {}

    private Session(TimelineAccessDefinition definition) {
      this.definition = definition;
      processor = definition.processor(this::assess);
    }

    /**
     * Executes ordinary initialization without claiming that the initial document was published.
     *
     * @return real canonical outcome; failed initialization leaves the session uninitialized
     */
    public Contracts.Transition initialize() {
      checkOpen();
      if (state != null) throw new IllegalStateException("Access session already initialized");
      return Contracts.boundary(() -> apply(processor.initialize(definition.initial.semantic)));
    }

    /**
     * Executes one provider-prepared original control entry before its durable acceptance. The host
     * owns source order, current owner authority and atomic publication. This validates the exact
     * source, agent actor, original target and published operation; it is not a proof that an
     * arbitrary caller-supplied entry has been accepted.
     *
     * @param entry complete candidate Timeline Entry with its required dependency evidence
     * @return canonical outcome; rejection leaves the prior processing state unchanged
     */
    public Contracts.Transition process(LanguageContent entry) {
      return processLocal(entry, null);
    }

    /**
     * Executes an original candidate already schema-validated by this source's publishing host. The
     * host still owns acceptance order and must publish the resulting checkpoint/state together.
     *
     * @param entry unchanged complete candidate entry
     * @param prepared tentative validation of that exact candidate, not a consumer prefix
     * @return canonical outcome, with no publication or permission effect until the host commits
     */
    public Contracts.Transition process(
        LanguageContent entry, TimelinePublisher.Prepared prepared) {
      Objects.requireNonNull(prepared).checkSource(definition);
      require(
          prepared.entryId().filter(Objects.requireNonNull(entry).blueId()::equals).isPresent(),
          "ACCESS_PREPARATION_ENTRY",
          "candidate");
      return processLocal(entry, prepared);
    }

    private Contracts.Transition processLocal(
        LanguageContent entry, TimelinePublisher.Prepared prepared) {
      checkInitialized();
      Objects.requireNonNull(entry);
      return Language.run(
          () ->
              Contracts.boundary(
                  () -> {
                    TimelineContent fields =
                        new TimelineContent(
                            entry.owner.context(
                                entry,
                                definition.initial,
                                definition.control,
                                definition.provider,
                                definition.publication.document()),
                            definition.publication);
                    ExactNode node = entry.semantic;
                    fields.typed(node, "Timeline Entry");
                    require(
                        LanguageCore.identity(fields.required(node, "Timeline Entry", "timeline"))
                            .equals(definition.control.blueId().value()),
                        "ACCESS_CONTROL_TIMELINE",
                        "entry");
                    fields.typed(fields.required(node, "Timeline Entry", "actor"), "Agent Actor");
                    long time = fields.integer(node, "Timeline Entry", "timestamp");
                    require(
                        time >= 0 && time < TimelineVerifier.FINAL_BOUND, "TIMELINE_TIME", "entry");
                    ExactNode message = fields.required(node, "Timeline Entry", "message");
                    require(
                        LanguageCore.identity(message)
                            .equals(
                                LanguageCore.identity(
                                    fields.required(node, "Timeline Entry", "operation"))),
                        "ACCESS_OPERATION_MESSAGE",
                        "operation");
                    var scope =
                        processor.blue.context.withProvider(
                            id ->
                                Optional.ofNullable(fields.context.lookup.apply(id.value()))
                                    .map(raw -> new LanguageRecord(LanguageCore.map(raw))));
                    try (var checkpointScope =
                        processor.blue.context.withPublisherHeads(checkpoint, prepared)) {
                      processor.blue.context.install(entry);
                      Map<String, Object> operation =
                          ContractChannels.operationRequest(processor.registry, message);
                      require(
                          operation != null
                              && "provider".equals(operation.get("channel"))
                              && "update".equals(operation.get("operation"))
                              && Map.of("blueId", definition.initial.blueId().value())
                                  .equals(operation.get("document")),
                          "ACCESS_UPDATE_TARGET_REQUIRED",
                          "original document and update operation");
                      selected = new Selection(state.identity(), entry.blueId(), time);
                      try {
                        Contracts.Transition result =
                            apply(processor.process(state, node, time, Map.of()));
                        if (result.applied()) checkpoint = prepared;
                        return result;
                      } finally {
                        selected = null;
                      }
                    } finally {
                      scope.close();
                    }
                  }));
    }

    /**
     * Returns the actual tentative executed document, including ordinary processing markers.
     *
     * @return canonical current after-document; not a confirmed permission certificate
     */
    public LanguageDocument document() {
      checkInitialized();
      return processor.blue.context.document(state.state.documents.get(state.state.root).body);
    }

    /**
     * Returns the exact canonical snapshot commitment for protected storage.
     *
     * @return processing snapshot identity
     */
    public String identity() {
      checkInitialized();
      return state.identity();
    }

    /**
     * Exports the processing state for the provider's atomic durable publication.
     *
     * @return portable canonical snapshot with dependency evidence
     */
    public Map<String, Object> snapshot() {
      checkInitialized();
      return snapshot(id -> false);
    }

    /**
     * Exports the same snapshot commitment with only evidence not already retained by the host. The
     * predicate must be stable during this call and recognize complete stored closures. Restore
     * still verifies the snapshot and must have access to every omitted dependency. Omission grants
     * no permission or semantic validation shortcut.
     *
     * @param retained lookup for exact identities already retained with their supporting closures
     * @return canonical snapshot and incremental evidence; not a self-contained transport package
     */
    public Map<String, Object> snapshot(java.util.function.Predicate<BlueId> retained) {
      checkInitialized();
      Objects.requireNonNull(retained);
      return Contracts.boundary(
          () -> {
            try (var checkpointScope = processor.blue.context.withPublisherHeads(checkpoint)) {
              return ContractStorage.export(
                  processor, state.state, true, id -> retained.test(new BlueId(id)));
            }
          });
    }

    private Contracts.Transition apply(ContractProcessor.Result result) {
      Contracts.Transition transition = new Contracts.Transition(result, processor);
      if (result.commits()) {
        LanguageDocument after =
            processor.blue.context.document(
                result.document().state.documents.get(result.document().state.root).body);
        definition.permissions(
            new TimelineContent(after.owner.context(after), definition.publication),
            after.semantic);
        state = result.document();
      }
      return transition;
    }

    private List<TimelineChannels.Assessment> assess(TimelineChannels.Request request) {
      require(
          selected != null
              && selected.snapshot().equals(request.snapshotIdentity())
              && selected.entry().equals(request.inputId())
              && selected.entry().equals(request.entryId())
              && selected.timestamp() == request.timestamp()
              && definition.publicType.equals(request.channelTypeId().value()),
          "ACCESS_PREPARATION_CONTEXT",
          "candidate");
      return request.receivers().stream()
          .map(
              receiver -> {
                require(
                    receiver.channel().equals("provider")
                        && receiver.declarationId().value().equals(definition.declaration),
                    "ACCESS_ADMISSION_CHANNEL",
                    "provider");
                return new TimelineChannels.Assessment(
                    receiver,
                    TimelineChannels.Disposition.ELIGIBLE,
                    true,
                    "Authenticated direct source participation");
              })
          .toList();
    }

    private void checkOpen() {
      if (processor == null) throw new IllegalStateException("Access session is closed");
    }

    private void checkInitialized() {
      checkOpen();
      if (state == null) throw new IllegalStateException("Access session is not initialized");
    }

    /**
     * Releases tentative execution state; previously returned immutable documents remain usable.
     */
    @Override
    public void close() {
      state = null;
      selected = null;
      checkpoint = null;
      processor = null;
    }
  }
}
