package blue;

import blue.ExactNode.NumberToken;
import blue.ExactNode.Ref;
import blue.ExactNode.Value;
import blue.LanguageModel.Head;
import blue.LanguageModel.Recipe;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.function.Predicate;

/** Invocation-confined native access to the same canonical Language implementation. */
final class ContractLanguage {
  private final Language language;
  private final LanguageContext context;
  private final EvidenceScope scope;
  // Completed body-presence facts share the immutable evidence snapshot, not verification
  // authority.
  private Set<String> retained = new LanguageSet<>(Set.of());
  private final Set<String> retainedWithPublisher = new HashSet<>();
  private Set<String> retentionPublisher = Set.of();

  static final class EvidenceScope {
    final ContractCache<ExactNode.Value, String> recordTexts =
        new ContractCache<>("language.exportText", 8L * 1024 * 1024, 16_384, 64 * 1024);
  }

  ContractLanguage(Language language) {
    this(language, language.context(), new EvidenceScope());
  }

  /** Reuse the event's immutable snapshot without merging its unrelated catalogue. */
  ContractLanguage(LanguageContent content) {
    this(content.owner, content.owner.context(content), new EvidenceScope());
  }

  private ContractLanguage(Language language, LanguageContext context, EvidenceScope scope) {
    this.language = language;
    this.context = context;
    context.reuseWithinHostBudget = !Boolean.getBoolean("blue.canonical.revalidateClosures");
    this.scope = scope;
    context.recordTexts = scope.recordTexts;
  }

  static final Map<String, String> CORE = LanguageCore.CORE, CORE_NAME = LanguageCore.CORE_NAME;
  static final Set<String> TYPES = LanguageCore.TYPES,
      RESERVED = LanguageCore.RESERVED,
      LITERALS = LanguageCore.LITERALS,
      LABELS = LanguageCore.LABELS;
  static final Object ABSENT = LanguageCore.ABSENT;

  EvidenceScope evidenceScope() {
    return scope;
  }

  /** Temporary evidence access; captured views release the provider when the attempt ends. */
  static final class ProviderScope implements AutoCloseable, Function<String, Object> {
    private final Function<String, Object> fallback;
    private final Set<String> supplied = new HashSet<>();
    private Function<String, Object> provider;
    private final boolean suppliedFirst;
    private LanguageContext owner;

    private ProviderScope(
        LanguageContext owner, Function<String, Object> provider, boolean suppliedFirst) {
      this.owner = owner;
      fallback = owner.lookup;
      this.provider = Objects.requireNonNull(provider);
      this.suppliedFirst = suppliedFirst;
      owner.lookup = this;
    }

    @Override
    public Object apply(String id) {
      if (suppliedFirst) {
        Object offered = offered(id);
        return offered != null ? offered : fallback.apply(id);
      }
      Object existing = fallback.apply(id);
      return existing != null ? existing : offered(id);
    }

    private Object offered(String id) {
      Object response = provider == null ? null : provider.apply(id);
      if (response != null) {
        supplied.add(id);
      }
      return response;
    }

    @Override
    public void close() {
      if (owner == null) {
        return;
      }
      provider = null;
      // Successfully verified bodies remain available. A rejected raw response must not poison
      // a later attempt using a different provider for the same identity.
      supplied.forEach(owner.provider::remove);
      supplied.clear();
      owner.lookup = fallback;
      owner = null;
    }
  }

  /** Used only by one protocol grammar check; these facts never become exported evidence. */
  void protocolValues(Map<String, Head> verified, LanguageFacts facts) {
    context.establishedValues = new HashMap<>(verified);
    context.facts = facts;
  }

  void rememberProtocolFacts() {
    if (context.facts != null) {
      context.facts.remember(context);
    }
  }

  ProviderScope withProvider(LanguageProvider provider) {
    Objects.requireNonNull(provider);
    return new ProviderScope(
        context,
        id ->
            Objects.requireNonNull(provider.find(new BlueId(id)))
                .map(LanguageRecord::response)
                .orElse(null),
        false);
  }

  /** Borrow the offered catalogue without copying unrelated history into this document. */
  ProviderScope withContent(LanguageContent content) {
    LanguageContext offered = content.owner.context(content);
    return new ProviderScope(
        context,
        id -> {
          Value body = offered.bodies.get(id);
          if (body == null) {
            return offered.lookup.apply(id);
          }
          if (!id.contains("#")) {
            return body;
          }
          Object proof = offered.proofs.get(id);
          return proof == null ? null : Map.of("proof", proof, "body", exactText(body));
        },
        true);
  }

  TimelineScope withTimelineEntry(TimelineVerifier.Entry entry) {
    return new TimelineScope(context, Objects.requireNonNull(entry));
  }

  /**
   * Protected semantic facts borrowed for one owner invocation and revoked with all memo branches.
   */
  final class ValidationScope implements AutoCloseable {
    private final LanguageMemoScope memos = new LanguageMemoScope();
    private final LanguageFacts facts;
    private final Map<String, Head> values = new HashMap<>();
    private BlueId occurrence;

    ValidationScope(java.util.function.Function<String, Optional<Boolean>> reader) {
      facts = new LanguageFacts(reader);
      memos.attach(context);
      context.facts = facts;
    }

    void withTimelineEntry(TimelineVerifier.Entry entry) {
      if (occurrence != null) {
        if (!occurrence.equals(entry.blueId())) {
          throw new IllegalStateException("An attempt may borrow only one original occurrence");
        }
        return;
      }
      if (!context.establishedValues.isEmpty()) {
        throw new IllegalStateException("A timeline prefix is already in scope");
      }
      occurrence = entry.blueId();
      values.putAll(TimelineScope.entryValues(entry));
      context.establishedValues = values;
    }

    @Override
    public void close() {
      values.clear();
      context.establishedValues = Map.of();
      facts.revoke();
      memos.close();
    }
  }

  ValidationScope validationScope(java.util.function.Function<String, Optional<Boolean>> reader) {
    return new ValidationScope(reader);
  }

  Map<String, Boolean> validationFacts(Predicate<String> retained) {
    return LanguageFacts.export(context, retained);
  }

  /** Borrow completed values only for this grammar check; neither providers nor facts escape. */
  CompletedScope withCompletedValues(LanguageDocument[] completed) {
    var providers = new ArrayList<ProviderScope>();
    var headers = new HashMap<String, Head>();
    try {
      for (LanguageDocument value : completed) {
        Objects.requireNonNull(value);
        if (value.goal() != Language.Goal.VALUE) {
          throw new IllegalArgumentException("Completed Language value required");
        }
        providers.add(withContent(value));
        String id = value.blueId().value();
        if (id.equals(identity(value.semantic))) {
          headers.put(
              id,
              LanguageSemantics.head(
                  LanguageSemantics.recipe(value.semantic), value.owner.context(value)));
        }
      }
      return new CompletedScope(providers, new TimelineScope(context, headers));
    } catch (RuntimeException | Error failure) {
      for (int i = providers.size() - 1; i >= 0; i--) {
        providers.get(i).close();
      }
      throw failure;
    }
  }

  static final class CompletedScope implements AutoCloseable {
    private final List<ProviderScope> providers;
    private final TimelineScope values;

    private CompletedScope(List<ProviderScope> providers, TimelineScope values) {
      this.providers = providers;
      this.values = values;
    }

    @Override
    public void close() {
      values.close();
      for (int i = providers.size() - 1; i >= 0; i--) {
        providers.get(i).close();
      }
    }
  }

  TimelineScope withPublisherHeads(TimelinePublisher.Prepared... checkpoints) {
    Map<String, Head> headers = new HashMap<>();
    for (TimelinePublisher.Prepared checkpoint : checkpoints) {
      if (checkpoint == null) {
        continue;
      }
      LanguageContent entry = checkpoint.content();
      if (entry != null) {
        install(entry);
      }
      headers.putAll(checkpoint.headers());
    }
    TimelineScope scope = new TimelineScope(context, headers);
    context.publisherRetained = scope.values.keySet();
    return scope;
  }

  static final class TimelineScope implements AutoCloseable {
    private final Map<String, Head> values = new HashMap<>();
    private final LanguageMemoScope memos = new LanguageMemoScope();
    private LanguageContext owner;

    private TimelineScope(LanguageContext owner, TimelineVerifier.Entry entry) {
      this(owner, entry == null ? Map.of() : entryValues(entry));
    }

    private static Map<String, Head> entryValues(TimelineVerifier.Entry entry) {
      Map<String, Head> values = new HashMap<>(entry.values);
      values.put(entry.blueId().value(), entry.header);
      return values;
    }

    private TimelineScope(LanguageContext owner, Map<String, Head> headers) {
      if (!owner.establishedValues.isEmpty()) {
        throw new IllegalStateException("A timeline prefix is already in scope");
      }
      this.owner = owner;
      memos.attach(owner);
      values.putAll(headers);
      owner.establishedValues = values;
    }

    @Override
    public void close() {
      if (owner == null) {
        return;
      }
      // Captured resolver views share this revocable scope, never a permanent permission
      // to omit evidence. Ordinary exported Language documents contain no such facts.
      values.clear();
      memos.close();
      owner.establishedValues = Map.of();
      owner.publisherRetained = Set.of();
      owner = null;
    }
  }

  // Immutable verified evidence is reusable. The context still enforces host bounds on new work.
  boolean nativeReuse() {
    return true;
  }

  String alias(String name) {
    return context.aliases.get(name);
  }

  Map<String, String> aliases() {
    return LanguageMap.immutable(context.aliases);
  }

  Set<String> demands() {
    return Set.copyOf(context.demands);
  }

  void install(BluePackage publication) {
    install(publication.document());
  }

  void install(LanguageContent document) {
    context.bodies.putAll(document.snapshot.bodies());
    context.proofs.putAll(document.snapshot.proofs());
  }

  boolean concrete(ExactNode node) {
    return LanguageSemantics.concreteNode(node, context);
  }

  void restoreConcrete(String id, boolean concrete) {
    context.concreteFacts.put(id, concrete);
    if (context.facts != null) {
      context.facts.restoredConcrete(id, concrete);
    }
  }

  void inheritUnchangedValidation(ExactNode next, ExactNode previous) {
    LanguageSemantics.inheritUnchangedValidation(next, previous, context);
  }

  void retainLocalClosure(String id) {
    context.durableClosures.add(id);
  }

  boolean locallyRetained(String id) {
    return context.durableClosures.contains(id);
  }

  void clearLocalRetention() {
    context.durableClosures.clear();
  }

  LanguageDocument document(ExactNode node) {
    // A detached value owns lazy retained records as well as already opened bodies.
    // Sharing the immutable trie keeps this independent of catalogue size.
    Map<String, Object> records = LanguageMap.immutable(context.provider);
    Function<String, Object> fallback = context.lookup;
    return new LanguageDocument(
        language.withLookup(
            id -> {
              Object retained = records.get(id);
              return retained == null ? fallback.apply(id) : retained;
            }),
        node,
        new Language.Snapshot(context.bodies, context.proofs),
        Language.Goal.VALUE,
        identity(node),
        List.of());
  }

  void installAliases(Map<String, String> aliases) {
    aliases.forEach(
        (name, id) -> {
          String previous = context.aliases.get(name);
          if (previous != null && !previous.equals(id)) {
            throw new LanguageFailure("AliasCollision", name);
          }
        });
    context.aliases.putAll(aliases);
  }

  /** Protected checkpoint content is verified on demand, just like a retained provider. */
  void supplyRetainedEvidence(Map<String, Object> records) {
    // Own the records: a caller cannot alter a later read after the checkpoint is admitted.
    // LanguageCore.open still checks every demanded response against its exact identity.
    context.provider.putAll(ContractModel.obj(ContractWire.immutable(records)));
  }

  /** Opens a retained member on demand, sharing only independently verified response bodies. */
  void openRetainedMember(ExactNode member) {
    String id = identity(member);
    if (context.bodies.containsKey(id)) {
      return;
    }
    Object response = context.provider.get(id);
    if (response == null) {
      try {
        response = context.lookup.apply(id);
      } catch (RuntimeException cause) {
        var failure = new LanguageFailure("ProviderUnavailable", id);
        failure.initCause(cause);
        throw failure;
      }
    }
    if (response == null) {
      LanguageCore.open(member, context);
    } else {
      addEvidence(Map.of(id, response));
    }
  }

  void addEvidence(Map<String, Object> records) {
    if (records.isEmpty()) {
      return;
    }
    LanguageContext verification = copied();
    verification.provider.putAll(records);
    Map<String, Long> limits = Map.copyOf(context.limits);
    for (var entry : records.entrySet()) {
      ContractEvidenceCache.Key cacheKey =
          ContractEvidenceCache.key(
              entry.getKey(), entry.getValue(), limits, context.reuseWithinHostBudget);
      Map<String, Value> cached = ContractEvidenceCache.get(cacheKey);
      if (cached != null) {
        verification.bodies.putAll(cached);
        continue;
      }
      LanguageContext cold = new LanguageContext(context.limits);
      cold.provider.reset(verification.provider);
      // Each preceding body has already been verified, but the selected response must still
      // be checked independently even when an earlier inline value populated the same identity.
      cold.bodies.reset(verification.bodies);
      cold.bodies.remove(entry.getKey());
      cold.lookup = context.lookup;
      Map<String, Value> normalized = cacheKey == null ? null : new HashMap<>();
      if (normalized != null) {
        cold.onRemember =
            node -> {
              if (node instanceof Value value) {
                normalized.put(identity(value), value);
                if (value.has("items") && value.fields().size() > 1) {
                  Value payload = ExactNode.list(LanguageCore.exactItems(value.get("items")));
                  normalized.put(identity(payload), payload);
                }
              }
            };
      }
      Value body = LanguageCore.open(new Ref(entry.getKey()), cold);
      if (normalized != null) {
        normalized.put(entry.getKey(), body);
        ContractEvidenceCache.put(cacheKey, normalized);
      }
      // These persistent maps share the verified prefix. Replacing the root avoids re-merging
      // every installed package definition once per incoming record.
      verification.bodies.reset(cold.bodies);
      verification.bodies.put(entry.getKey(), body);
      verification.proofs.putAll(cold.proofs);
    }
    context.provider.putAll(records);
    context.bodies.reset(verification.bodies);
    context.proofs.putAll(verification.proofs);
  }

  void mergeEvidence(Map<String, Object> records) {
    addEvidence(records);
  }

  void importEvidence(ContractLanguage source) {
    addEvidence(source.evidence());
  }

  void verifyEvidence(String id, Object response) {
    addEvidence(Map.of(id, response));
  }

  Map<String, Object> evidence() {
    Map<String, Object> records = new TreeMap<>(context.provider);
    for (var entry : context.bodies.entrySet()) {
      if (CORE_NAME.containsKey(entry.getKey())) {
        continue;
      }
      if (entry.getKey().contains("#")) {
        Object proof = context.proofs.get(entry.getKey());
        if (proof != null) {
          records.put(
              entry.getKey(),
              Map.of("proof", LanguageCodec.wire(proof), "body", exactText(entry.getValue())));
        }
      } else {
        records.put(entry.getKey(), Map.of("text", exactText(entry.getValue()), "mode", "exact"));
      }
    }
    return records;
  }

  void remember(ExactNode node) {
    context.remember(node);
  }

  ContractLanguage refreshed() {
    context.work.set(0);
    context.demands.clear();
    return this;
  }

  private LanguageContext copied() {
    LanguageContext copy =
        new LanguageContext(
            context.limits,
            new Language.Snapshot(context.bodies, context.proofs),
            context.aliases,
            context.directiveAliases,
            context.provider);
    copy.lookup = context.lookup;
    return copy;
  }

  ContractLanguage readSnapshot() {
    return snapshot(copiedView());
  }

  /** Grammar checks borrow content and lazy providers, never an active attempt's semantic memos. */
  ContractLanguage verificationSnapshot() {
    return snapshot(copied());
  }

  private ContractLanguage snapshot(LanguageContext copy) {
    ContractLanguage result = new ContractLanguage(language, copy, scope);
    result.retained = new LanguageSet<>(retained);
    return result;
  }

  boolean establishedValue(Recipe recipe) {
    return context.validation.contains(
            new LanguageContext.ValidationKey(recipe.key(), "value", true))
        && recipe.parts().stream()
            .allMatch(node -> context.verifiedClosures.contains(identity(node)));
  }

  /** Exact, already completed checks for one immutable member; no admission is inferred. */
  List<String> semanticPreparation(ExactNode body) {
    String recipe = LanguageSemantics.recipe(body).key();
    List<String> result = new ArrayList<>();
    if (context.verifiedClosures.contains(identity(body))) {
      result.add("closure");
    }
    for (String goal : List.of("definition", "value")) {
      for (boolean root : new boolean[] {false, true}) {
        if (context.validation.contains(new LanguageContext.ValidationKey(recipe, goal, root))) {
          result.add(goal + "/" + root);
        }
      }
    }
    return List.copyOf(result);
  }

  /** Protected native storage supplies these facts only inside a revocable owner attempt. */
  void restoreSemanticPreparation(ExactNode body, List<Object> facts) {
    if (context.memoScope == null) {
      throw new IllegalStateException("Protected semantic preparation needs an attempt scope");
    }
    String recipe = LanguageSemantics.recipe(body).key();
    for (Object fact : facts) {
      String selected = ContractModel.str(fact);
      if (selected.equals("closure")) {
        String id = identity(body);
        context.verifiedClosures.add(id);
        if (context.facts != null) {
          context.facts.restoredClosure(id);
        }
        continue;
      }
      LanguageContext.ValidationKey key =
          switch (selected) {
            case "definition/false" ->
                new LanguageContext.ValidationKey(recipe, "definition", false);
            case "definition/true" -> new LanguageContext.ValidationKey(recipe, "definition", true);
            case "value/false" -> new LanguageContext.ValidationKey(recipe, "value", false);
            case "value/true" -> new LanguageContext.ValidationKey(recipe, "value", true);
            default -> throw new IllegalArgumentException("Unsupported protected semantic fact");
          };
      context.validation.add(key);
      if (context.facts != null) {
        context.facts.restored(key);
      }
    }
  }

  private LanguageContext copiedView() {
    LanguageContext copy = copied();
    copy.establishedValues = context.establishedValues;
    copy.publisherRetained = context.publisherRetained;
    copy.durableClosures = context.durableClosures;
    // Heads are immutable projections of this exact evidence snapshot. Share their persistent
    // root with a captured view; later memo entries and evidence still belong to each context.
    // Verification of newly supplied evidence deliberately uses copied(), with empty memos.
    if (context.reuseWithinHostBudget && context.activeHeads.isEmpty()) {
      context.snapshotHeadsTo(copy);
    }
    if (context.memoScope != null) {
      context.memoScope.attach(copy);
      copy.facts = context.facts;
    }
    return copy;
  }

  ContractLanguage fork() {
    return readSnapshot();
  }

  ContractLanguage captured(
      Function<ExactNode, String> establish, BiConsumer<ExactNode, String> note) {
    LanguageContext copy = copiedView();
    copy.onConstruct =
        node -> {
          String id = establish.apply(node);
          if (node instanceof Value body) {
            copy.bodies.put(id, body);
            note.accept(node, id);
          }
        };
    return snapshot(copy);
  }

  ExactNode prepare(Object raw) {
    return LanguageSource.prepare(raw, context);
  }

  ExactNode admitLocal(ExactNode node, String goal) {
    LanguageSemantics.strict(node, context, goal);
    ExactNode admitted = LanguageOperations.omit(node, context).exact();
    if (admitted != node) {
      LanguageSemantics.strict(admitted, context, goal);
    }
    return admitted;
  }

  LanguageAdmission admit(ExactNode node, String goal) {
    return LanguageOperations.admit(node, context, goal, "source");
  }

  static ExactNode exactOf(LanguageAdmission document) {
    return document.exact();
  }

  Map<String, Object> records(ExactNode node) {
    return LanguageOperations.records(node, context);
  }

  Map<String, Object> records(ExactNode node, Set<String> supplied) {
    return LanguageOperations.records(node, context, supplied);
  }

  Map<String, Object> records(ExactNode node, java.util.function.Predicate<String> supplied) {
    return LanguageOperations.records(node, context, supplied);
  }

  Map<String, Object> records(
      Collection<ExactNode> nodes, java.util.function.Predicate<String> supplied) {
    return LanguageOperations.records(nodes, context, supplied);
  }

  Map<String, Object> records(Collection<ExactNode> nodes, boolean nativeRecords) {
    Map<String, Object> result = new TreeMap<>();
    for (ExactNode node : nodes) {
      result.putAll(records(node));
    }
    return result;
  }

  /** Copies the verified reachable bodies, without carrying old providers or resolver views. */
  void copyReachableTo(ContractLanguage target, Collection<ExactNode> roots) {
    ContractLanguage source = readSnapshot();
    Set<String> seen = new HashSet<>();
    ArrayDeque<ExactNode> pending = new ArrayDeque<>(roots);
    while (!pending.isEmpty()) {
      ExactNode node = pending.pop();
      String id = identity(node);
      if (!seen.add(id)) {
        continue;
      }
      Value body = source.open(node);
      target.context.bodies.put(id, body);
      if (id.contains("#")) {
        LanguageFailure.require(source.context.proofs.containsKey(id), "InvalidCyclicProof");
        target.context.proofs.put(id, source.context.proofs.get(id));
      }
      for (var field : body.fields().entrySet()) {
        if (field.getKey().equals("items")) {
          var items = LanguageCore.exactItems(field.getValue());
          target.context.remember(ExactNode.list(items));
          pending.addAll(items);
        } else if (field.getValue() instanceof ExactNode child) {
          pending.push(child);
        }
      }
    }
  }

  void retain(Collection<ExactNode> nodes) {
    // Retain immutable bodies directly. String encodings belong to explicit export only.
    Set<String> publisher = context.publisherRetained;
    if (publisher != retentionPublisher || publisher.isEmpty()) {
      retainedWithPublisher.clear();
      retentionPublisher = publisher;
    }
    boolean usesPublisher = false;
    ArrayDeque<ExactNode> pending = new ArrayDeque<>(nodes);
    Set<String> discovered = new HashSet<>();
    while (!pending.isEmpty()) {
      ExactNode node = pending.pop();
      String id = identity(node);
      // A protected publisher head is already retained in its durable source. Its current
      // body was installed by the invocation scope; retain new state without copying history.
      if (retained.contains(id)) {
        continue;
      }
      if (publisher.contains(id)
          || retainedWithPublisher.contains(id)
          || context.facts != null && context.facts.verified(id)) {
        usesPublisher = true;
        continue;
      }
      if (!discovered.add(id)) {
        continue;
      }
      Value body = open(node);
      context.bodies.put(id, body);
      for (var field : body.fields().entrySet()) {
        if (field.getValue() instanceof ExactNode child) {
          pending.push(child);
        } else if (field.getKey().equals("items")) {
          for (Object child : (List<?>) field.getValue()) {
            pending.push((ExactNode) child);
          }
        }
      }
    }
    // A cut at a protected publisher head does not establish permanent presence of its history.
    // Its ancestors remain reusable only in that live scope, and never propagate to a fork.
    if (usesPublisher) {
      retainedWithPublisher.addAll(discovered);
    } else {
      retained.addAll(discovered);
    }
  }

  Value open(ExactNode node) {
    return LanguageCore.open(node, context);
  }

  ExactNode admittedHandle(String id) {
    Value body = open(new Ref(id));
    return id.contains("#") ? new Ref(id, body) : body;
  }

  String canonicalRootText(ExactNode node) {
    return LanguageOperations.canonicalText(open(node), context);
  }

  List<ExactNode> finalizeComponent(List<?> raw) {
    var result = LanguageCore.finalizeSet(raw, context);
    Object proof =
        LanguageValues.freeze(
            Map.of("members", result.canonical().stream().map(LanguageCore::export).toList()));
    List<ExactNode> values = new ArrayList<>();
    for (String id : result.ids()) {
      Value body = (Value) result.bodies().get(Integer.parseInt(id.split("#")[1]));
      context.bodies.put(id, body);
      context.proofs.put(id, proof);
      values.add(new Ref(id, body));
    }
    return List.copyOf(values);
  }

  static final class Witnesses {
    private ContractIdentityMap<String> values = new ContractIdentityMap<>();
    private ContractIdentityMap<Boolean> opened = new ContractIdentityMap<>();

    Witnesses fork() {
      Witnesses copy = new Witnesses();
      copy.values = values.fork();
      copy.opened = opened.fork();
      return copy;
    }

    String get(ExactNode node) {
      return values.get(node);
    }

    boolean containsKey(ExactNode node) {
      return values.containsKey(node);
    }

    void put(ExactNode node, String id) {
      values.put(node, id);
    }

    int size() {
      return values.size();
    }

    int trim(int maximum) {
      int removed = values.trim(maximum);
      if (removed != 0) {
        opened = new ContractIdentityMap<>();
      }
      return removed;
    }

    boolean childrenKnown(ExactNode node) {
      return opened.containsKey(node);
    }

    void childrenOpened(ExactNode node) {
      opened.put(node, Boolean.TRUE);
    }

    void clear() {
      values = new ContractIdentityMap<>();
      opened = new ContractIdentityMap<>();
    }
  }

  public void validateConstraints(Head head, boolean root) {
    head.constraints.validate(head, true, "value", root, context);
  }

  public static ExactNode normalize(
      Object raw, ContractLanguage runtime, String mode, boolean symbolic) {
    return LanguageCore.normalize(raw, runtime.context, mode, symbolic);
  }

  public static String identity(ExactNode value) {
    return LanguageCore.identity(value);
  }

  public static String identity(ExactNode value, IntFunction<String> token) {
    return LanguageCore.identity(value, token);
  }

  public static String exactText(ExactNode value) {
    return LanguageCore.exactText(value);
  }

  public static Object export(ExactNode value) {
    return LanguageCore.export(value);
  }

  public static String finalId(Object id) {
    return LanguageCore.finalId(id);
  }

  public static String hash(Object value) {
    return LanguageCore.hash(value);
  }

  public static String appendId(String prefix, String element) {
    return LanguageCore.appendId(prefix, element);
  }

  public static String listId(List<ExactNode> items, IntFunction<String> token) {
    return LanguageCore.listId(items, token);
  }

  public static Object helper(Value value, IntFunction<String> token) {
    return LanguageCore.helper(value, token);
  }

  public static Map<String, Object> map(Object value) {
    return LanguageCore.map(value);
  }

  public static List<Object> list(Object value) {
    return LanguageCore.list(value);
  }

  public static List<String> path(String path) {
    return LanguageCore.path(path);
  }

  public static int index(String part) {
    return LanguageCore.index(part);
  }

  public static Set<Integer> locals(ExactNode value) {
    return LanguageCore.locals(value);
  }

  public static ExactNode localMap(ExactNode value, IntFunction<ExactNode> replace) {
    return LanguageCore.localMap(value, replace);
  }

  public static Recipe recipe(Object... parts) {
    return LanguageSemantics.recipe(parts);
  }

  public static Recipe recipeWith(List<String> requirements, Object... parts) {
    return LanguageSemantics.recipeWith(requirements, parts);
  }

  public static Head head(Recipe recipe, ContractLanguage runtime) {
    return LanguageSemantics.head(recipe, runtime.context);
  }

  public static Head readHead(Recipe recipe, ContractLanguage runtime) {
    return LanguageSemantics.readHead(recipe, runtime.context);
  }

  public static Recipe child(Head head, Object key) {
    return LanguageSemantics.child(head, key);
  }

  public static ExactNode exact(Recipe recipe, ContractLanguage runtime) {
    return LanguageSemantics.exact(recipe, runtime.context);
  }

  public static void strict(ExactNode value, ContractLanguage runtime, String goal) {
    LanguageSemantics.strict(value, runtime.context, goal);
  }

  public static boolean present(Recipe recipe, ContractLanguage runtime) {
    return LanguageSemantics.present(recipe, runtime.context);
  }

  public static boolean concrete(Head head, ContractLanguage runtime) {
    return LanguageSemantics.concrete(head, runtime.context);
  }

  public static ExactNode refine(
      ExactNode base, ExactNode value, ContractLanguage runtime, boolean replacement) {
    return LanguageSemantics.refine(base, value, runtime.context, replacement);
  }

  public static List<String> ancestry(String type, ContractLanguage runtime) {
    return LanguageSemantics.ancestry(type, runtime.context);
  }

  public static boolean subtype(String actual, String expected, ContractLanguage runtime) {
    return LanguageSemantics.subtype(actual, expected, runtime.context);
  }

  public static void validate(Recipe recipe, ContractLanguage runtime, String goal) {
    LanguageSemantics.validate(recipe, runtime.context, goal);
  }

  public record Fraction(java.math.BigInteger numerator, java.math.BigInteger denominator) {}

  public record Bound(Fraction value, boolean exclusive) {}

  public record Scalar(Object value) {}

  /**
   * Immutable constraint observations for deterministic logical metering; cannot validate evidence.
   */
  public static final class Constraints {
    private static final Constraints EMPTY = new Constraints(LanguageSchema.EMPTY);
    private final LanguageSchema schema;
    public final Boolean unique;
    public final Bound lower, upper;
    public final Fraction multiple;
    public final Map<String, java.math.BigInteger> counts;
    public final List<Set<String>> enums;
    public final Map<String, Scalar> enumValues;

    private static Fraction fraction(Rational r) {
      return r == null ? null : new Fraction(r.numerator(), r.denominator());
    }

    private static Bound bound(LanguageSchema.Bound b) {
      return b == null ? null : new Bound(fraction(b.value()), b.exclusive());
    }

    static Constraints of(LanguageSchema schema) {
      LanguageSchema snapshot = schema.frozen();
      return snapshot == LanguageSchema.EMPTY ? EMPTY : new Constraints(snapshot);
    }

    private Constraints(LanguageSchema schema) {
      this.schema = schema;
      unique = schema.unique;
      lower = bound(schema.lower);
      upper = bound(schema.upper);
      multiple = fraction(schema.multiple);
      counts = Collections.unmodifiableMap(schema.counts);
      enums = schema.enums;
      var values = new TreeMap<String, Scalar>();
      schema.enumValues.forEach((k, v) -> values.put(k, new Scalar(v.value())));
      enumValues = Collections.unmodifiableMap(values);
    }

    public Map<String, Object> describe() {
      if (schema == LanguageSchema.EMPTY) {
        return Map.of();
      }
      return LanguageCore.map(ContractData.freeze(schema.describe()));
    }
  }

  public String canonicalRootText(ExactNode value, BiConsumer<String, Long> charge) {
    var pending = new ArrayDeque<ExactNode>();
    var references = new HashSet<String>();
    pending.push(value);
    while (!pending.isEmpty()) {
      charge.accept("manifest", 1L);
      ExactNode node = pending.pop();
      if (node instanceof Ref ref) {
        charge.accept("textBlock", 8L * ((ref.id().length() + 63L) / 64));
        if (CORE_NAME.containsKey(ref.id()) || !references.add(ref.id())) {
          continue;
        }
      }
      Value body = open(node);
      int count = body.fields().size();
      long rounds = count <= 1 ? 0 : 32 - Integer.numberOfLeadingZeros(count - 1);
      charge.accept("member", 8L * count * (rounds + 1));
      for (var field : body.fields().entrySet()) {
        charge.accept("textBlock", 8L * (rounds + 1) * ((6L * field.getKey().length() + 63) / 64));
        Object part = field.getValue();
        if (part instanceof String text) {
          charge.accept("textBlock", 8L * ((6L * text.length() + 63) / 64));
          if (field.getKey().equals("value") && canonicalIntegerPayload(body, charge)) {
            long digits = text.length() - (text.startsWith("-") ? 1 : 0);
            long limbs = Math.max(1, (4L * digits + 31) / 32);
            charge.accept("integerLimbSquare", 8L * limbs * limbs);
          }
        } else if (part instanceof java.math.BigInteger integer) {
          long limbs = Math.max(1, (integer.abs().bitLength() + 31L) / 32);
          charge.accept("integerLimbSquare", 8L * limbs * limbs);
        } else if (part instanceof NumberToken token) {
          charge.accept("textBlock", 8L * ((6L * token.text().length() + 63) / 64));
        } else if (part instanceof List<?> items) {
          charge.accept("member", 8L * items.size());
          for (int i = items.size() - 1; i >= 0; i--) {
            pending.push((ExactNode) items.get(i));
          }
        } else if (part instanceof ExactNode child) {
          pending.push(child);
        }
      }
    }
    return canonicalRootText(value);
  }

  private boolean canonicalIntegerPayload(Value body, BiConsumer<String, Long> charge) {
    ExactNode type = body.get("type") instanceof ExactNode n ? n : null;
    var seen = new HashSet<String>();
    while (type != null) {
      charge.accept("integerTypeEdge", 1L);
      if (type instanceof Ref ref) {
        charge.accept("textBlock", 8L * ((ref.id().length() + 63L) / 64));
        if (CORE_NAME.containsKey(ref.id())) {
          return CORE.get("Integer").equals(ref.id());
        }
        if (!seen.add(ref.id())) {
          return false;
        }
      }
      charge.accept("manifest", 1L);
      Value parent = open(type);
      type = parent.get("type") instanceof ExactNode n ? n : null;
    }
    return false;
  }
}
