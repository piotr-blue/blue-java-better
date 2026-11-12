package blue;

import static blue.LanguageCore.CORE;
import static blue.LanguageCore.exactItems;
import static blue.LanguageCore.identity;
import static blue.LanguageCore.normalize;
import static blue.LanguageFailure.require;

import blue.ExactNode.Ref;
import blue.ExactNode.Value;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/** Working state confined to one operation; published evidence uses persistent maps. */
final class LanguageContext {
  final LanguageMap<String, Object> provider = new LanguageMap<>();
  final LanguageMap<String, String> aliases = new LanguageMap<>(CORE);
  final LanguageMap<String, String> directiveAliases = new LanguageMap<>();
  final LanguageMap<String, Value> bodies = new LanguageMap<>();
  final LanguageMap<String, Object> proofs = new LanguageMap<>();
  final Map<String, Long> limits = new HashMap<>();
  final List<String> demands = new ArrayList<>();
  Map<String, LanguageModel.Head> heads = new HashMap<>();
  final Set<String> activeHeads = new HashSet<>();

  record ValidationKey(String recipe, String goal, boolean root) {}

  Set<ValidationKey> validation = new HashSet<>();
  Map<String, Boolean> concreteFacts = new HashMap<>();
  boolean cacheVerifiedClosures = true;
  LanguageMemoScope memoScope;
  // Pure exact text, not admission evidence; installed by the native engine's retained scope.
  ContractCache<ExactNode.Value, String> recordTexts;
  // Native execution meters logical work separately; this budget bounds physical host work.
  boolean reuseWithinHostBudget;
  // Only the public immutable builder establishes this; fixture/raw contexts stay untrusted.
  boolean verifiedAliases;
  Set<String> verifiedClosures = new HashSet<>();
  // Optional scoped semantic facts. Ordinary portable snapshots never export this authority.
  LanguageFacts facts;

  Boolean concreteFact(String id) {
    Boolean local = concreteFacts.get(id);
    return local != null || facts == null ? local : facts.concrete(id);
  }

  boolean validated(ValidationKey key) {
    return validation.contains(key) || facts != null && facts.validated(key);
  }

  boolean verifiedClosure(String id) {
    return verifiedClosures.contains(id) || facts != null && facts.verified(id);
  }

  // Invocation-local cut points: consumer-verified entries or protected publisher recovery.
  // Producer checkpoints require a separately trusted committed identity and never issue consumer
  // facts. Each preserves prior strict validation; ordinary wire cannot supply these headers.
  Map<String, LanguageModel.Head> establishedValues = Map.of();
  Set<String> publisherRetained = Set.of();
  final java.util.concurrent.atomic.AtomicLong work = new java.util.concurrent.atomic.AtomicLong();
  Map<String, Long> measuredBounds;
  LanguageDiagnostics.Path diagnosticPath = LanguageDiagnostics.ROOT;
  String diagnosticPhase = "operation", diagnosticGoal;
  ExactNode diagnosticRoot;
  java.util.function.Function<String, Object> lookup = id -> null;

  /** Ordinary admission stays mutable; captured views switch once to persistent memo roots. */
  void snapshotHeadsTo(LanguageContext copy) {
    if (memoScope != null) {
      memoScope.attach(copy);
    }
    if (!(heads instanceof LanguageMap<?, ?>)) {
      heads = new LanguageMap<>(heads);
    }
    copy.heads = new LanguageMap<>(heads);
    // Both contexts retain the same verified immutable content. Completed semantic checks
    // are reusable too. Temporary Timeline facts require a shared revocation scope.
    if (establishedValues.isEmpty() || memoScope != null) {
      if (!(concreteFacts instanceof LanguageMap<?, ?>)) {
        concreteFacts = new LanguageMap<>(concreteFacts);
      }
      copy.concreteFacts = new LanguageMap<>(concreteFacts);
      if (!(validation instanceof LanguageSet<?>)) {
        validation = new LanguageSet<>(validation);
      }
      if (!(verifiedClosures instanceof LanguageSet<?>)) {
        verifiedClosures = new LanguageSet<>(verifiedClosures);
      }
      copy.validation = new LanguageSet<>(validation);
      copy.verifiedClosures = new LanguageSet<>(verifiedClosures);
    }
  }

  LanguageContext() {
    this(Map.of());
  }

  LanguageContext(Map<String, Long> limits) {
    this(limits, true);
  }

  LanguageContext(Map<String, Long> limits, boolean initialize) {
    this.limits.putAll(limits);
    if (initialize) {
      CoreRegistry.VERIFIED.bounds().forEach(this::bound);
      bodies.putAll(CoreRegistry.VERIFIED.bodies());
    }
  }

  LanguageContext(
      Map<String, Long> limits,
      Language.Snapshot snapshot,
      Map<String, String> aliases,
      Map<String, String> directives,
      Map<String, Object> records) {
    this(limits, false);
    CoreRegistry.VERIFIED.bounds().forEach(this::bound);
    this.bodies.reset(snapshot.bodies());
    this.proofs.reset(snapshot.proofs());
    this.aliases.reset(aliases);
    this.directiveAliases.reset(directives);
    this.provider.reset(records);
    verifiedAliases = true;
  }

  private void initializeRegistry() {
    for (var entry : CORE.entrySet()) {
      try (var stream =
          LanguageCore.class.getResourceAsStream("/blue/registry/" + entry.getKey() + ".json")) {
        if (stream == null) {
          throw new IllegalStateException("Missing core registry");
        }
        ExactNode value =
            normalize(
                LanguageCodec.parse(
                    new String(stream.readAllBytes(), StandardCharsets.UTF_8), "json"),
                this,
                "exact",
                false);
        require(identity(value).equals(entry.getValue()), "InvalidCoreRegistry", entry.getKey());
      } catch (java.io.IOException failure) {
        throw new IllegalStateException(failure);
      }
    }
    work.set(0);
  }

  void bound(String key, long value) {
    if (measuredBounds != null) {
      measuredBounds.merge(key, value, Math::max);
    }
    require(!limits.containsKey(key) || value <= limits.get(key), "OperationLimitExceeded", key);
  }

  void step() {
    bound("steps", work.incrementAndGet());
  }

  java.util.function.Consumer<ExactNode> onRemember;
  // Loading an immutable provider response is not construction by the executing document.
  java.util.function.Consumer<ExactNode> onConstruct;
  int providerDepth;
  // Protected local publishers guarantee closure retention. This is storage availability only,
  // never semantic admission or authority. Shared only inside one owner attempt.
  RetainedClosures durableClosures = new RetainedClosures();
  private final Set<String> expandedVerifiedClosures = new HashSet<>();
  private Set<String> expansionEvidence;
  private LanguageFacts expansionFacts;
  private RetainedClosures expansionStorage;
  private long expansionGeneration;

  /** Temporary evidence must not keep either its authority or its expansion memo alive. */
  void clearRetainedExpansion() {
    expandedVerifiedClosures.clear();
    expansionEvidence = null;
    expansionFacts = null;
    expansionStorage = null;
  }

  Value retainedBody(Value body) {
    if (durableClosures.isEmpty()) {
      return body;
    }
    String id = identity(body);
    if (!durableClosures.contains(id)) {
      return body;
    }
    if (expansionEvidence != verifiedClosures
        || expansionFacts != facts
        || expansionStorage != durableClosures
        || expansionGeneration != durableClosures.generation()) {
      clearRetainedExpansion();
      expansionEvidence = verifiedClosures;
      expansionFacts = facts;
      expansionStorage = durableClosures;
      expansionGeneration = durableClosures.generation();
    }
    boolean storage = !durableClosures.expanded(id);
    // Evidence is monotonic within this context. A new branch or revoked scope gets a fresh memo.
    boolean verified = verifiedClosures.contains(id) && !expandedVerifiedClosures.contains(id);
    if (!storage && !verified) {
      return body;
    }
    for (var field : body.fields().entrySet()) {
      if (field.getValue() instanceof ExactNode child) {
        retainedChild(child, storage, verified);
      } else if (field.getKey().equals("items")) {
        for (ExactNode child : exactItems(field.getValue())) {
          retainedChild(child, storage, verified);
        }
      }
    }
    if (storage) {
      durableClosures.complete(id);
    }
    if (verified) {
      expandedVerifiedClosures.add(id);
    }
    return body;
  }

  private void retainedChild(ExactNode child, boolean storage, boolean verified) {
    String id = identity(child);
    if (storage) {
      durableClosures.add(id);
    }
    if (verified) {
      verifiedClosures.add(id);
      if (facts != null) {
        facts.restoredClosure(id);
      }
    }
  }

  ExactNode remember(ExactNode value) {
    if (onRemember != null) {
      onRemember.accept(value);
    }
    if (providerDepth == 0 && onConstruct != null) {
      onConstruct.accept(value);
    }
    if (value instanceof Value body) {
      Value selected = rememberBody(body);
      if (body.has("items") && body.fields().size() > 1) {
        Value payload = ExactNode.list(exactItems(body.get("items")));
        rememberBody(payload);
      }
      return selected;
    }
    return value;
  }

  /** Reuse only identical materialization within this context's existing evidence snapshot. */
  private Value rememberBody(Value offered) {
    String id = identity(offered);
    Value previous = bodies.get(id);
    if (previous != null && sameMaterialization(previous, offered)) {
      return previous;
    }
    bodies.put(id, offered);
    return offered;
  }

  private static boolean sameMaterialization(Value left, Value right) {
    if (!left.fields().keySet().equals(right.fields().keySet())) {
      return false;
    }
    for (var field : left.fields().entrySet()) {
      if (!sameField(field.getValue(), right.get(field.getKey()))) {
        return false;
      }
    }
    return true;
  }

  private static boolean sameField(Object left, Object right) {
    if (left == right) {
      return true;
    }
    if (left instanceof Ref a && right instanceof Ref b) {
      return a.id().equals(b.id()) && a.body() == b.body();
    }
    if (left instanceof ExactNode || right instanceof ExactNode) {
      return false;
    }
    if (left instanceof List<?> a && right instanceof List<?> b) {
      if (a.size() != b.size()) {
        return false;
      }
      for (int i = 0; i < a.size(); i++) {
        if (!sameField(a.get(i), b.get(i))) {
          return false;
        }
      }
      return true;
    }
    return Objects.equals(left, right);
  }

  /** Verified once for this fixed profile; no shared semantic validation or metering state. */
  private static final class CoreRegistry {
    record Verified(Map<String, Value> bodies, Map<String, Long> bounds) {}

    static final Verified VERIFIED = initialize();

    private static Verified initialize() {
      LanguageContext c = new LanguageContext(Map.of(), false);
      c.measuredBounds = new TreeMap<>();
      c.initializeRegistry();
      return new Verified(Map.copyOf(c.bodies), Map.copyOf(c.measuredBounds));
    }
  }
}
