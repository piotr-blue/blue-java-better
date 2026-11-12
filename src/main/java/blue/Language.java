package blue;

import static blue.LanguageCore.ABSENT;
import static blue.LanguageCore.CORE;
import static blue.LanguageCore.LITERALS;
import static blue.LanguageCore.TYPES;
import static blue.LanguageCore.appendId;
import static blue.LanguageCore.exactItems;
import static blue.LanguageCore.export;
import static blue.LanguageCore.finalId;
import static blue.LanguageCore.identity;
import static blue.LanguageCore.map;
import static blue.LanguageCore.normalize;
import static blue.LanguageCore.open;
import static blue.LanguageCore.path;
import static blue.LanguageCore.plainId;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

/**
 * Thread-safe Blue Language operations under one fixed interpretation context. Exact content,
 * strict admission, and resolved observations have distinct APIs. Each operation owns its mutable
 * working state; returned content and supporting evidence are immutable.
 *
 * <p>Start with {@link #readYaml(String)} for values and {@link #defineYaml(String)} for reusable
 * definitions. The returned handles retain their dependencies and support validated immutable
 * edits. Use {@link #newBuilder()} when content needs external providers or export-name bindings.
 *
 * <pre>{@code
 * Language language = Language.create();
 * LanguageDocument point = language.defineYaml("x: {type: Integer}");
 * LanguageDocument value = point.specializeYaml("x: 2");
 * String canonical = value.canonicalJson();
 * }</pre>
 *
 * <p>Names supplied to {@link Builder#type(String, LanguageDocument)} are external aliases, not
 * inferred from document metadata. Admission here establishes Language validity; it does not
 * compile workflows or execute them; use {@link Bex} and {@link Contracts} for execution.
 */
public final class Language {
  /** The canonical Language edition implemented by these operations. */
  public static final String EDITION = "blue-language/2026-09-26-specification-metadata-1";

  /** A definition may leave required payloads unfilled; a completed value may not. */
  public enum Goal {
    /** Validate reusable declarations and their consistent requirements. */
    DEFINITION,
    /** Validate a completed value, including active payload requirements. */
    VALUE;

    String wire() {
      return name().toLowerCase(Locale.ROOT);
    }
  }

  /** Input roles are explicit; a failed Exact read is never retried as Source. */
  public enum Role {
    /** Authored content with aliases and one-time list construction. */
    SOURCE,
    /** Existing exact content. */
    EXACT,
    /** Exact content with reference-only type and specification slots. */
    DIRECT,
    /** Compact exact wire with verified list prefixes. */
    COMPACT;

    String wire() {
      return this == COMPACT ? "canonical" : name().toLowerCase(Locale.ROOT);
    }
  }

  /** Supported portable serializations. */
  public enum Format {
    /** Portable YAML. */
    YAML,
    /** JSON preserving numeric token categories. */
    JSON;

    String wire() {
      return name().toLowerCase(Locale.ROOT);
    }
  }

  /**
   * Original serialized input and its declared role.
   *
   * @param text original serialized text
   * @param format serialization format
   * @param role interpretation role
   */
  public record Input(String text, Format format, Role role) {
    /** Rejects null input components. */
    public Input {
      Objects.requireNonNull(text);
      Objects.requireNonNull(format);
      Objects.requireNonNull(role);
    }

    /**
     * Declares authored YAML input.
     *
     * @param text authored YAML
     * @return a Source input
     */
    public static Input yaml(String text) {
      return new Input(text, Format.YAML, Role.SOURCE);
    }

    /**
     * Declares authored JSON input.
     *
     * @param text authored JSON
     * @return a Source input
     */
    public static Input json(String text) {
      return new Input(text, Format.JSON, Role.SOURCE);
    }

    /**
     * Declares exact JSON that must not repeat Source construction.
     *
     * @param text exact JSON
     * @return an Exact input
     */
    public static Input exactJson(String text) {
      return new Input(text, Format.JSON, Role.EXACT);
    }

    /**
     * Declares exact YAML that must not repeat Source construction.
     *
     * @param text exact YAML
     * @return an Exact input
     */
    public static Input exactYaml(String text) {
      return new Input(text, Format.YAML, Role.EXACT);
    }

    /**
     * Declares compact exact JSON with verified list-prefix decoding.
     *
     * @param text compact JSON
     * @return a compact wire input
     */
    public static Input compactJson(String text) {
      return new Input(text, Format.JSON, Role.COMPACT);
    }
  }

  /**
   * Independent host limits for one operation. These are resource bounds, not execution gas.
   *
   * @param steps maximum abstract work steps
   * @param depth maximum input or semantic traversal depth
   * @param values maximum parsed/constructed values
   * @param fetches maximum provider requests
   * @param textBytes maximum original UTF-8 input bytes
   * @param numericCharacters maximum numeric token length
   * @param decimalScale maximum absolute decimal scale
   */
  public record Limits(
      long steps,
      int depth,
      long values,
      int fetches,
      long textBytes,
      int numericCharacters,
      int decimalScale) {
    /** Rejects negative limits; zero is an explicit zero budget. */
    public Limits {
      if (steps < 0
          || depth < 0
          || values < 0
          || fetches < 0
          || textBytes < 0
          || numericCharacters < 0
          || decimalScale < 0) {
        throw new IllegalArgumentException("Negative Language limit");
      }
    }

    /**
     * Returns conservative resource bounds for local processing.
     *
     * @return bounded defaults suitable for ordinary local documents
     */
    public static Limits defaults() {
      return new Limits(10_000_000, 256, 1_000_000, 100_000, 16L * 1024 * 1024, 65_536, 65_536);
    }

    Map<String, Long> wire() {
      return Map.of(
          "steps",
          steps,
          "depth",
          (long) depth,
          "values",
          values,
          "fetches",
          (long) fetches,
          "text_bytes",
          textBytes,
          "numeric_chars",
          (long) numericCharacters,
          "decimal_scale",
          (long) decimalScale);
    }
  }

  /**
   * A finalized symbolic set; members remain in the caller's original order.
   *
   * @param master identity of the canonical owning set
   * @param members admitted members with their complete supporting proofs
   * @param proofJson canonical owning-set proof
   */
  public record CyclicSet(BlueId master, List<LanguageDocument> members, String proofJson) {
    /** Copies the member list. */
    public CyclicSet {
      Objects.requireNonNull(master);
      members = List.copyOf(members);
      Objects.requireNonNull(proofJson);
    }
  }

  /**
   * Identity-preserving split with exact ancestor fragments and cyclic proof records.
   *
   * @param blueId original root identity
   * @param rootJson resulting root materialization
   * @param records exported supporting records
   */
  public record Fragments(BlueId blueId, String rootJson, Map<BlueId, LanguageRecord> records) {
    /** Copies exported records. */
    public Fragments {
      Objects.requireNonNull(blueId);
      Objects.requireNonNull(rootJson);
      records = Map.copyOf(records);
    }
  }

  record Snapshot(Map<String, ExactNode.Value> bodies, Map<String, Object> proofs) {
    Snapshot {
      bodies = LanguageMap.immutable(bodies);
      proofs = LanguageMap.immutable(proofs);
    }
  }

  private final Map<String, String> aliases;
  private final Map<String, String> directives;
  private final Map<String, Object> records;
  private final Snapshot known;
  private final Set<Snapshot> retainedSnapshots;
  private final List<LanguageProvider> providers;
  private final Limits limits;
  private final BlueId packageLanguage;

  private Language(Builder builder) {
    aliases = LanguageMap.immutable(builder.aliases);
    directives = LanguageMap.immutable(builder.directives);
    records = LanguageMap.immutable(builder.records);
    known =
        new Snapshot(
            builder.bodies.isEmpty() ? new LanguageContext().bodies : builder.bodies,
            builder.proofs);
    Set<Snapshot> retained = Collections.newSetFromMap(new IdentityHashMap<>());
    retained.addAll(builder.retainedSnapshots);
    retainedSnapshots = Collections.unmodifiableSet(retained);
    providers = List.copyOf(builder.providers);
    limits = builder.limits;
    packageLanguage = builder.packageLanguage;
  }

  /**
   * Preserves a native view's lazy resolver without carrying semantic authority into public reads.
   */
  Language withLookup(java.util.function.Function<String, Object> lookup) {
    return new Language(
        this,
        id -> {
          Object response = lookup.apply(id.value());
          if (response == null) {
            return Optional.empty();
          }
          if (response instanceof ExactNode.Value value) {
            return Optional.of(LanguageRecord.json(LanguageCore.exactText(value)));
          }
          return Optional.of(new LanguageRecord(LanguageCore.map(response)));
        });
  }

  private Language(Language source, LanguageProvider provider) {
    aliases = source.aliases;
    directives = source.directives;
    records = source.records;
    known = source.known;
    retainedSnapshots = source.retainedSnapshots;
    providers = List.of(provider);
    limits = source.limits;
    packageLanguage = source.packageLanguage;
  }

  /**
   * Copies this fixed configuration into an independent builder. Immutable evidence is shared;
   * edits to the builder cannot change this processor or its previously returned documents.
   * Providers added to the builder follow the existing providers in lookup order.
   *
   * @return a builder retaining this processor's aliases, evidence, providers and limits
   */
  public Builder toBuilder() {
    return new Builder(this);
  }

  static Language withVerifiedEvidence(Snapshot snapshot) {
    Builder builder = newBuilder();
    builder.bodies.putAll(snapshot.bodies());
    builder.proofs.putAll(snapshot.proofs());
    return builder.build();
  }

  /**
   * Creates a processor using the canonical registry and bounded defaults.
   *
   * @return a reusable standard Language context with bounded defaults
   */
  public static Language create() {
    return newBuilder().build();
  }

  /**
   * Creates an independent builder for a fixed interpretation context.
   *
   * @return an independent mutable builder
   */
  public static Builder newBuilder() {
    return new Builder();
  }

  /**
   * Constructs exact content without asserting semantic validity.
   *
   * @param input original content with an explicit role
   * @return exact content and the dependencies established while preparing it
   */
  public LanguageContent prepare(Input input) {
    Objects.requireNonNull(input);
    return run(
        () -> {
          LanguageContext c = context();
          ExactNode exact = input(input, c);
          return content(exact, c);
        });
  }

  /**
   * Prepares an immutable authored source tree without a text round trip. Integer nodes carry exact
   * arbitrary-precision values; double nodes carry finite binary64 values. Alias resolution,
   * structural limits and source transformations are the same as for authored text. Callers parsing
   * decimal text must reject unwanted rounding before constructing a double node.
   *
   * @param source immutable authored source, not an exact or canonical materialization
   * @return prepared content without an admission claim
   */
  public LanguageContent prepare(Node source) {
    Objects.requireNonNull(source);
    return run(
        () -> {
          LanguageContext c = context();
          return content(LanguageSource.prepare(LanguageOperations.source(source, c), c), c);
        });
  }

  /**
   * Prepares and strictly admits an immutable authored source tree.
   *
   * @param source authored source with exact integer and finite binary64 scalar values
   * @param goal definition or completed-value validation
   * @return an admitted immutable document
   * @see #prepare(Node)
   */
  public LanguageDocument read(Node source, Goal goal) {
    return read(source, goal, new LanguageDocument[0]);
  }

  /**
   * Admits authored source while reusing completed values issued by this library. Their exact
   * identities are certified only for this call; additional contextual restrictions and all new
   * content are checked normally. No membership, operation permission or source history is granted.
   * The returned document retains supporting evidence, not a reusable validation bypass.
   *
   * @param source authored source with exact integer and finite binary64 scalar values
   * @param goal definition or completed-value validation
   * @param values previously admitted completed values; definitions are not accepted
   * @return an admitted immutable document
   * @throws IllegalArgumentException if an evidence document is only a definition
   */
  public LanguageDocument read(Node source, Goal goal, LanguageDocument... values) {
    Objects.requireNonNull(source);
    Objects.requireNonNull(goal);
    return run(
        () -> {
          Language issuer = retaining(values);
          LanguageContext c = issuer.admissionContext(values);
          ExactNode exact = LanguageSource.prepare(LanguageOperations.source(source, c), c);
          return issuer.admitted(
              LanguageOperations.admit(exact, c, goal.wire(), "source"), c, goal);
        });
  }

  /**
   * Prepares an existing exact materialization using this processor's evidence, providers and
   * limits, without a serialization round trip. Only evidence in the materialization is imported;
   * unrelated evidence retained by its original owner is not transferred. Like preparing exact
   * JSON, this operation does not assert semantic validity or confer execution authority.
   *
   * @param input already prepared content, possibly from another Language processor
   * @return the same exact content and identity in this interpretation context
   */
  public LanguageContent prepare(LanguageContent input) {
    Objects.requireNonNull(input);
    // Expansion/collapse keeps a separate semantic origin. Reparse that uncommon presentation
    // path so a collapsed value cannot smuggle its hidden origin across a transport boundary.
    if (input.exact != input.semantic) {
      return prepare(Input.exactJson(input.toJson()));
    }
    return run(
        () -> {
          LanguageContext context = context();
          ExactNode imported = materialization(input.exact, context, new IdentityHashMap<>(), 1);
          return content(imported, context);
        });
  }

  /** Retains inline bodies just as exact parsing does, without importing the sender's snapshot. */
  private static ExactNode materialization(
      ExactNode node, LanguageContext context, Map<ExactNode, ExactNode> imported, int depth) {
    context.step();
    context.bound("depth", depth);
    if (node instanceof ExactNode.Ref ref) {
      // An attached cyclic body is not present in the reference's wire representation.
      return ref.body() == null ? ref : new ExactNode.Ref(ref.id());
    }
    ExactNode known = imported.get(node);
    if (known != null) {
      return known;
    }
    ExactNode.Value original = (ExactNode.Value) node;
    ExactNode.Value result = original;
    for (var field : original.fields().entrySet()) {
      Object value = field.getValue();
      if (value instanceof ExactNode child) {
        ExactNode retained = materialization(child, context, imported, depth + 1);
        if (retained != child) {
          result = result.with(field.getKey(), retained);
        }
      } else if (value instanceof List<?> items) {
        List<ExactNode> retained = null;
        for (int i = 0; i < items.size(); i++) {
          ExactNode item = (ExactNode) items.get(i);
          ExactNode child = materialization(item, context, imported, depth + 2);
          if (child != item) {
            if (retained == null) {
              retained = new ArrayList<>(exactItems(items));
            }
            retained.set(i, child);
          }
        }
        if (retained != null) {
          result = result.with(field.getKey(), retained);
        }
      }
    }
    context.remember(result);
    imported.put(node, result);
    return result;
  }

  /**
   * Computes an exact identity without claiming admission.
   *
   * @param input original content and role
   * @return its prepared identity
   */
  public BlueId blueId(Input input) {
    return prepare(input).blueId();
  }

  /**
   * Constructs and strictly admits content under the requested goal.
   *
   * @param input original content and role
   * @param goal definition or completed-value validation
   * @return the admitted immutable document
   */
  public LanguageDocument read(Input input, Goal goal) {
    return read(input, goal, new LanguageDocument[0]);
  }

  /**
   * Admits input with invocation-local evidence from previously completed values. This is the text
   * and exact-input equivalent of {@link #read(Node, Goal, LanguageDocument...)}. Source omission,
   * exact validation and physical work limits retain their ordinary behavior; already established
   * value subtrees need not be validated again.
   *
   * @param input original content and role
   * @param goal definition or completed-value validation
   * @param values previously admitted completed values; definitions are not accepted
   * @return an admitted immutable document retaining its supporting evidence
   * @throws IllegalArgumentException if an evidence document is only a definition
   */
  public LanguageDocument read(Input input, Goal goal, LanguageDocument... values) {
    Objects.requireNonNull(input);
    Objects.requireNonNull(goal);
    return run(
        () -> {
          Language issuer = retaining(values);
          LanguageContext c = issuer.admissionContext(values);
          return issuer.admitted(
              LanguageOperations.admit(input(input, c), c, goal.wire(), input.role().wire()),
              c,
              goal);
        });
  }

  /**
   * Parses authored YAML and strictly validates it under the requested admission goal.
   *
   * @param text authored YAML
   * @return an admitted completed value
   */
  public LanguageDocument readYaml(String text) {
    return read(Input.yaml(text), Goal.VALUE);
  }

  /**
   * Parses authored YAML and strictly validates it under the requested admission goal.
   *
   * @param text authored YAML
   * @param goal admission intent
   * @return an admitted document
   */
  public LanguageDocument readYaml(String text, Goal goal) {
    return read(Input.yaml(text), goal);
  }

  /**
   * Parses authored JSON and strictly validates it under the requested admission goal.
   *
   * @param text authored JSON
   * @return an admitted completed value
   */
  public LanguageDocument readJson(String text) {
    return read(Input.json(text), Goal.VALUE);
  }

  /**
   * Parses authored JSON and strictly validates it under the requested admission goal.
   *
   * @param text authored JSON
   * @param goal admission intent
   * @return an admitted document
   */
  public LanguageDocument readJson(String text, Goal goal) {
    return read(Input.json(text), goal);
  }

  /**
   * Parses authored YAML and strictly validates it under the requested admission goal.
   *
   * @param path UTF-8 YAML file
   * @param goal admission intent
   * @return admitted content
   * @throws IOException if reading fails
   */
  public LanguageDocument readYaml(Path path, Goal goal) throws IOException {
    return readYaml(Files.readString(path), goal);
  }

  /**
   * Parses authored JSON and strictly validates it under the requested admission goal.
   *
   * @param path UTF-8 JSON file
   * @param goal admission intent
   * @return admitted content
   * @throws IOException if reading fails
   */
  public LanguageDocument readJson(Path path, Goal goal) throws IOException {
    return readJson(Files.readString(path), goal);
  }

  /**
   * Reads a UTF-8 YAML file as a completed value.
   *
   * @param path file to read
   * @return a strictly validated completed value
   * @throws IOException if reading fails
   */
  public LanguageDocument readYaml(Path path) throws IOException {
    return readYaml(Files.readString(path));
  }

  /**
   * Reads a UTF-8 JSON file as a completed value.
   *
   * @param path file to read
   * @return a strictly validated completed value
   * @throws IOException if reading fails
   */
  public LanguageDocument readJson(Path path) throws IOException {
    return readJson(Files.readString(path));
  }

  /**
   * Parses authored YAML as a reusable definition; required values may remain unfilled.
   *
   * @param text authored YAML
   * @return a strictly validated definition
   */
  public LanguageDocument defineYaml(String text) {
    return read(Input.yaml(text), Goal.DEFINITION);
  }

  /**
   * Reads a UTF-8 YAML file as a reusable definition.
   *
   * @param path file to read
   * @return a strictly validated reusable definition
   * @throws IOException if reading fails
   */
  public LanguageDocument defineYaml(Path path) throws IOException {
    return defineYaml(Files.readString(path));
  }

  /**
   * Parses authored JSON as a reusable definition; required values may remain unfilled.
   *
   * @param text authored JSON
   * @return a strictly validated definition
   */
  public LanguageDocument defineJson(String text) {
    return read(Input.json(text), Goal.DEFINITION);
  }

  /**
   * Reads a UTF-8 JSON file as a reusable definition.
   *
   * @param path file to read
   * @return a strictly validated reusable definition
   * @throws IOException if reading fails
   */
  public LanguageDocument defineJson(Path path) throws IOException {
    return defineJson(Files.readString(path));
  }

  /**
   * Creates an opaque reference without fetching its body or claiming semantic validity.
   *
   * @param id exact content identity
   * @return lazy content; observations may request verified evidence from providers
   */
  public LanguageContent reference(BlueId id) {
    Objects.requireNonNull(id);
    return run(() -> content(new ExactNode.Ref(finalId(id.value())), context()));
  }

  /**
   * Fetches and validates a completed value and its required dependency closure.
   *
   * @param id exact content identity
   * @return an admitted value retaining verified evidence
   */
  public LanguageDocument read(BlueId id) {
    return admit(reference(id), Goal.VALUE);
  }

  /**
   * Recovers a previously completed value from protected local admission metadata. The expected
   * commitment must come from this host's committed storage after successful admission and complete
   * closure retention, never from the supplied record or a remote request. Providers must still
   * retain that immutable closure. Selected bodies and cyclic ownership are verified, but the full
   * semantic closure is not revalidated. Use ordinary read for untrusted content.
   *
   * @param checkpoint record previously issued by {@link LanguageDocument#checkpoint()}
   * @param expectedIdentity independently protected checkpoint commitment
   * @return recovered completed value; no access or execution permission is granted
   */
  public LanguageDocument restoreCheckpoint(
      Map<String, Object> checkpoint, String expectedIdentity) {
    return LanguageCheckpoint.restore(this, checkpoint, expectedIdentity);
  }

  /**
   * Fetches and validates a reusable definition and its required dependency closure.
   *
   * @param id exact content identity
   * @return an admitted definition retaining verified evidence
   */
  public LanguageDocument define(BlueId id) {
    return admit(reference(id), Goal.DEFINITION);
  }

  /**
   * Strictly admits already prepared exact content; it never reruns Source construction.
   *
   * @param content prepared content
   * @param goal validation intent
   * @return a validated document with the same identity
   */
  public LanguageDocument admit(LanguageContent content, Goal goal) {
    Objects.requireNonNull(content);
    Objects.requireNonNull(goal);
    return run(
        () -> {
          Language issuer = retaining(content);
          LanguageContext c = issuer.context(content);
          return issuer.admitted(
              LanguageOperations.admit(content.semantic, c, goal.wire(), "exact"), c, goal);
        });
  }

  /**
   * Creates a specialization from authored YAML. An explicit type must be the parent or a subtype.
   *
   * @param parent admitted reusable definition or value
   * @param yaml authored refinement
   * @param goal validation intent
   * @return the admitted specialization
   */
  public LanguageDocument specialize(LanguageDocument parent, String yaml, Goal goal) {
    return specialize(parent, Input.yaml(yaml), goal);
  }

  /**
   * Creates a specialization from authored input, retaining an explicit compatible type. The
   * parent's identity is inserted as the type only when no type was supplied.
   *
   * @param parent admitted reusable definition or value
   * @param input authored YAML or JSON with the Source role
   * @param goal validation intent
   * @return the admitted specialization
   * @throws IllegalArgumentException if input is not Source
   */
  public LanguageDocument specialize(LanguageDocument parent, Input input, Goal goal) {
    Objects.requireNonNull(parent);
    Objects.requireNonNull(input);
    Objects.requireNonNull(goal);
    if (input.role() != Role.SOURCE) {
      throw new IllegalArgumentException("Specialization requires authored Source input");
    }
    return run(
        () -> {
          LanguageContext c = context(parent);
          var source =
              new LinkedHashMap<>(
                  map(LanguageOperations.parse(input.text(), input.format().wire(), c)));
          source.putIfAbsent("type", Map.of("blueId", parent.blueId().value()));
          ExactNode exact = LanguageSource.prepare(source, c);
          ExactNode.Value body = open(exact, c);
          LanguageFailure.require(
              body.get("type") instanceof ExactNode.Ref type
                  && LanguageSemantics.subtype(type.id(), parent.blueId().value(), c),
              "IncompatibleType",
              "Explicit type must preserve the supplied parent");
          return admitted(LanguageOperations.admit(exact, c, goal.wire(), "source"), c, goal);
        });
  }

  LanguageDocument edit(LanguageDocument document, Consumer<Node.Editor> changes) {
    Objects.requireNonNull(changes);
    return run(
        () -> {
          LanguageContext c = context(document);
          // Editing one member cannot silently replace its owning-set identity with an acyclic ID.
          LanguageFailure.require(
              !document.blueId().value().contains("#"),
              "InvalidOperation",
              "Edit cyclic members through a new complete symbolic owning set");
          Node edited =
              document
                  .node()
                  .edit(
                      changes,
                      node -> {
                        if (node.kind() == Node.Kind.OBJECT
                            && node.size() == 1
                            && node.get("blueId").kind() == Node.Kind.TEXT) {
                          String id = node.get("blueId").text();
                          LanguageFailure.require(
                              !id.contains("#"),
                              "InvalidOperation",
                              "Edit cyclic members through a new complete symbolic owning set");
                          return node(open(new ExactNode.Ref(id), c));
                        }
                        return node;
                      });
          Object raw = NodeCodec.raw(edited);
          LanguageOperations.checkInput(raw, c);
          ExactNode exact = normalize(raw, c, "exact", false);
          return admitted(
              LanguageOperations.admit(exact, c, document.goal().wire(), "exact"),
              c,
              document.goal());
        });
  }

  /**
   * Materializes selected exact nodes without flattening inheritance.
   *
   * @param content exact content
   * @param paths complete-node JSON pointers; empty selects the root
   * @return a materialization with the same identity
   */
  public LanguageContent expand(LanguageContent content, String... paths) {
    return materialize(content, false, paths);
  }

  /**
   * Replaces selected complete nodes with exact references, retaining supporting evidence.
   *
   * @param content exact content
   * @param paths complete-node JSON pointers
   * @return a materialization with the same identity
   */
  public LanguageContent collapse(LanguageContent content, String... paths) {
    return materialize(content, true, paths);
  }

  /**
   * Expands selected nodes of an admitted document, preserving its admission and identity.
   *
   * @param document admitted document
   * @param paths complete-node JSON pointers
   * @return an admitted document with the requested presentation
   */
  public LanguageDocument expand(LanguageDocument document, String... paths) {
    return (LanguageDocument) materialize(document, false, paths);
  }

  /**
   * Collapses selected nodes of an admitted document, retaining all supporting evidence.
   *
   * @param document admitted document
   * @param paths complete-node JSON pointers
   * @return an admitted document with the requested presentation
   */
  public LanguageDocument collapse(LanguageDocument document, String... paths) {
    return (LanguageDocument) materialize(document, true, paths);
  }

  private LanguageContent materialize(LanguageContent value, boolean collapse, String[] paths) {
    Objects.requireNonNull(value);
    List<String> selected = List.of(paths);
    return run(
        () -> {
          LanguageContext c = context(value);
          ExactNode exact = LanguageCore.expand(value.exact, selected, c, collapse);
          if (value instanceof LanguageDocument document) {
            return new LanguageDocument(
                this,
                exact,
                value.semantic,
                snapshot(c),
                document.goal(),
                document.originalBlueId().value(),
                document.removedFields());
          }
          return new LanguageContent(this, exact, value.semantic, snapshot(c));
        });
  }

  /**
   * Creates portable exact fragments and the structural ancestors needed to reconnect them.
   *
   * @param content exact content
   * @param paths complete-node cuts
   * @return identity-preserving fragments
   */
  public Fragments split(LanguageContent content, String... paths) {
    Objects.requireNonNull(content);
    List<String> selected = List.of(paths);
    return run(
        () -> {
          var split = LanguageCore.split(content.exact, selected, context(content));
          var records = new LinkedHashMap<BlueId, LanguageRecord>();
          split
              .fragments()
              .forEach(
                  (id, text) ->
                      records.put(
                          new BlueId(id),
                          split.proofs().containsKey(id)
                              ? LanguageRecord.cyclic(split.proofs().get(id), text)
                              : LanguageRecord.json(text)));
          return new Fragments(new BlueId(split.blueId()), split.rootText(), records);
        });
  }

  /**
   * Checks the specified subtype relation using exact definitions and their supporting evidence.
   *
   * @param candidate admitted candidate
   * @param parent admitted required type
   * @return whether the candidate is a subtype
   */
  public boolean isSubtype(LanguageDocument candidate, LanguageDocument parent) {
    return run(
        () ->
            LanguageSemantics.subtype(
                candidate.blueId().value(), parent.blueId().value(), context(candidate, parent)));
  }

  /**
   * Refines exact content without running Source construction a second time.
   *
   * @param base exact content to refine
   * @param overlay exact restrictions to add
   * @param goal admission intent for the result
   * @return the strictly admitted refinement
   */
  public LanguageDocument refine(LanguageContent base, LanguageContent overlay, Goal goal) {
    Objects.requireNonNull(goal);
    return run(
        () -> {
          LanguageContext c = context(base, overlay);
          ExactNode exact = LanguageSemantics.refine(base.semantic, overlay.semantic, c, false);
          return admitted(LanguageOperations.admit(exact, c, goal.wire(), "exact"), c, goal);
        });
  }

  /**
   * Checks preservation of inherited restrictions independently of nominal type ancestry.
   *
   * @param candidate proposed definition
   * @param parent required definition
   * @return whether the candidate preserves the parent requirements
   */
  public boolean preserves(LanguageDocument candidate, LanguageDocument parent) {
    return run(
        () -> {
          LanguageContext c = context(candidate, parent);
          return LanguageSemantics.preserves(
              LanguageSemantics.head(LanguageSemantics.recipe(candidate.semantic), c),
              LanguageSemantics.head(LanguageSemantics.recipe(parent.semantic), c),
              c,
              false);
        });
  }

  /**
   * Compares effective meaning, which may be equal even when exact identities differ.
   *
   * @param left first admitted document
   * @param right second admitted document
   * @return whether their resolved views are equivalent
   */
  public boolean sameView(LanguageDocument left, LanguageDocument right) {
    return run(() -> LanguageOperations.same(left.semantic, right.semantic, context(left, right)));
  }

  /**
   * Materializes an inspection description of the complete resolved view. Recursive edges carry
   * contribution references; this description is not exact Blue content.
   *
   * @param document strictly admitted document
   * @return labels, constraints, effective fields and values
   */
  public Node inspect(LanguageDocument document) {
    return run(
        () ->
            node(
                LanguageOperations.view(
                    LanguageSemantics.recipe(document.semantic),
                    context(document),
                    new java.util.HashSet<>(),
                    0)));
  }

  /**
   * Extends a payload-list identity. No prefix body is needed for this structural operation.
   *
   * @param prefix existing payload-list identity
   * @param suffix prepared exact elements
   * @return the folded payload-list identity
   */
  public BlueId foldList(BlueId prefix, List<? extends LanguageContent> suffix) {
    Objects.requireNonNull(prefix);
    List<? extends LanguageContent> items = List.copyOf(suffix);
    return run(
        () -> {
          String id = plainId(prefix.value());
          for (LanguageContent item : items) {
            id = appendId(id, item.blueId().value());
          }
          return new BlueId(id);
        });
  }

  /**
   * Finalizes a complete symbolic owning set and strictly admits its members.
   *
   * @param members original documents using this#index references
   * @param goal validation intent for every member
   * @return final identities and the complete owning-set proof
   */
  public CyclicSet finalizeSet(List<Input> members, Goal goal) {
    List<Input> input = List.copyOf(members);
    Objects.requireNonNull(goal);
    return run(
        () -> {
          LanguageContext c = context();
          var raw = new ArrayList<Object>();
          for (Input item : input) {
            if (item.role() != Role.EXACT) {
              throw new IllegalArgumentException("Symbolic owning sets require Exact inputs");
            }
            raw.add(LanguageOperations.parse(item.text(), item.format().wire(), c));
          }
          var set = LanguageCore.finalizeSet(raw, c);
          String proof =
              LanguageCodec.wire(
                  Map.of("members", set.canonical().stream().map(LanguageCore::export).toList()));
          for (String id : set.ids()) {
            c.provider.put(id, Map.of("proof", proof));
          }
          var result = new ArrayList<LanguageDocument>();
          for (String id : set.ids()) {
            result.add(
                admitted(
                    LanguageOperations.admit(new ExactNode.Ref(id), c, goal.wire(), "exact"),
                    c,
                    goal));
          }
          return new CyclicSet(new BlueId(set.master()), result, proof);
        });
  }

  /**
   * Encodes verified compact list-prefix wire. Reload using COMPACT, never Source.
   *
   * @param content exact content
   * @return compact JSON with the same decoded identity
   */
  public String compactJson(LanguageContent content) {
    return run(() -> LanguageOperations.canonicalText(content.semantic, context(content)));
  }

  /**
   * Produces shorter Source with the same effective meaning. Its exact identity may differ.
   * Supporting content remains available through the original document's records.
   *
   * @param document admitted content
   * @return minimized authored JSON
   */
  public String minimize(LanguageDocument document) {
    return run(
        () ->
            LanguageOperations.minimize(
                document.semantic, context(document), document.goal().wire()));
  }

  /**
   * Performs preprocessing only; the result may still contain Source list controls.
   *
   * @param input authored text
   * @return preprocessed JSON, without claiming an exact identity
   */
  public String preprocess(Input input) {
    Objects.requireNonNull(input);
    if (input.role() != Role.SOURCE) {
      throw new IllegalArgumentException("Preprocessing requires Source");
    }
    return run(
        () -> {
          LanguageContext c = context();
          Object value =
              LanguageSource.preprocess(
                  LanguageOperations.parse(input.text(), input.format().wire(), c), c);
          return LanguageCodec.wire(show(value));
        });
  }

  /**
   * Refines schema declarations without weakening inherited predicates.
   *
   * @param inherited exact schema content
   * @param refinement exact authored predicates
   * @return the refined exact schema
   */
  public LanguageContent refineSchema(LanguageContent inherited, LanguageContent refinement) {
    return run(
        () -> {
          LanguageContext c = context(inherited, refinement);
          return content(
              LanguageSemantics.mergeSchema(inherited.semantic, refinement.semantic, c), c);
        });
  }

  /**
   * Conjoins independent schema predicates without treating either as an override.
   *
   * @param left first exact schema
   * @param right second exact schema
   * @return the combined constraint descriptor, not a new exact schema node
   */
  public Node conjoinSchemas(LanguageContent left, LanguageContent right) {
    return run(
        () -> {
          LanguageContext c = context(left, right);
          return node(
              LanguageSchema.conjoin(
                      LanguageSchema.read(left.semantic, c), LanguageSchema.read(right.semantic, c))
                  .describe());
        });
  }

  /**
   * Navigates exact content without applying inherited declarations.
   *
   * @param content exact content
   * @param pointer exact JSON pointer, including /items for sequences
   * @return exact materialization, literal, sequence, or missing
   */
  public Node lookupExact(LanguageContent content, String pointer) {
    return run(
        () -> {
          Object result = LanguageCore.at(content.exact, path(pointer), context(content));
          return result == ABSENT ? Node.missing() : node(show(result));
        });
  }

  Optional<LanguageContent> select(LanguageContent original, String pointer) {
    return run(
        () -> {
          LanguageContext c = context(original);
          var selected = LanguageSemantics.recipe(original.semantic);
          var parts = path(pointer);
          for (int i = 0; i < parts.size(); i++) {
            var head = LanguageSemantics.head(selected, c);
            String key = parts.get(i);
            if (key.equals("items")) {
              LanguageFailure.require(
                  i + 1 < parts.size(), "InvalidOperation", "Select a complete list item");
              if (head.items == null) {
                return Optional.empty();
              }
              int index = LanguageCore.index(parts.get(++i));
              if (index >= head.items.size()) {
                return Optional.empty();
              }
              selected = LanguageSemantics.child(head, index);
            } else if (key.equals("contracts")) {
              if (head.contracts == null) {
                return Optional.empty();
              }
              selected = head.contracts;
            } else if (TYPES.contains(key)) {
              String id =
                  switch (key) {
                    case "type" -> head.type;
                    case "itemType" -> head.itemType;
                    case "keyType" -> head.keyType;
                    default -> head.valueType;
                  };
              if (id == null) {
                return Optional.empty();
              }
              selected = LanguageSemantics.recipe(new ExactNode.Ref(id));
            } else if (key.equals("specification")) {
              if (head.specification == null) {
                return Optional.empty();
              }
              selected = LanguageSemantics.recipe(head.specification);
            } else {
              LanguageFailure.require(
                  !LITERALS.contains(key) && !key.equals("mergePolicy"),
                  "InvalidOperation",
                  "Select a complete node, not a literal");
              if (!head.fields.containsKey(key)) {
                return Optional.empty();
              }
              selected = LanguageSemantics.child(head, key);
            }
          }
          return Optional.of(content(LanguageSemantics.exact(selected, c), c));
        });
  }

  boolean concrete(LanguageContent content, String pointer) {
    return run(
        () -> {
          LanguageContext current = context(content);
          Object selected =
              LanguageSemantics.resolvedAt(
                  LanguageSemantics.recipe(content.semantic), path(pointer), current);
          if (selected == ABSENT) {
            return false;
          }
          if (!(selected instanceof LanguageModel.Head head)) {
            throw new IllegalArgumentException("Select a node to inspect concrete presence");
          }
          return LanguageSemantics.concrete(head, current);
        });
  }

  List<String> declaredFields(LanguageContent content, String pointer) {
    return run(
        () -> {
          Object selected =
              LanguageSemantics.resolvedAt(
                  LanguageSemantics.recipe(content.semantic), path(pointer), context(content));
          if (selected == ABSENT) {
            return List.of();
          }
          if (!(selected instanceof LanguageModel.Head head)) {
            throw new IllegalArgumentException("Select a node to inspect its field declarations");
          }
          return List.copyOf(head.fields.keySet());
        });
  }

  Node observe(LanguageContent content, String path, String question) {
    Objects.requireNonNull(question);
    if (!java.util.Set.of(
            "exactId",
            "kind",
            "type",
            "value",
            "constraints",
            "keys",
            "length",
            "present",
            "name",
            "description",
            "specification")
        .contains(question)) {
      throw new IllegalArgumentException("Unknown Language observation: " + question);
    }
    return run(
        () -> {
          var result =
              LanguageOperations.observe(content.semantic, path, question, context(content));
          if (result.get("outcome").equals("Absent")) {
            return Node.missing();
          }
          return node(result.containsKey("blueId") ? result.get("blueId") : result.get("value"));
        });
  }

  Map<BlueId, LanguageRecord> records(LanguageContent content) {
    return exportRecords(content, id -> false);
  }

  Map<BlueId, LanguageRecord> records(
      LanguageContent content, java.util.function.Predicate<BlueId> available) {
    return exportRecords(content, id -> available.test(new BlueId(id)));
  }

  private Map<BlueId, LanguageRecord> exportRecords(
      LanguageContent content, java.util.function.Predicate<String> available) {
    return run(
        () -> {
          Map<String, Object> records =
              LanguageOperations.records(content.semantic, context(content), available);
          var result = new LinkedHashMap<BlueId, LanguageRecord>();
          records.forEach(
              (id, response) -> result.put(new BlueId(id), new LanguageRecord(map(response))));
          return Map.copyOf(result);
        });
  }

  LanguageContext context(LanguageContent... contents) {
    // A document already retains this engine's fixed registry. Starting from its
    // persistent roots is constant-time even with a large unrelated catalogue.
    Snapshot base =
        contents.length > 0 && Objects.requireNonNull(contents[0]).owner == this
            ? contents[0].snapshot
            : known;
    LanguageContext c = new LanguageContext(limits.wire(), base, aliases, directives, records);
    for (LanguageContent content : contents) {
      Objects.requireNonNull(content);
      // Builder content is already in every snapshot issued by this engine. Re-merging a
      // mounted publication here costs its full catalogue on every small protocol request.
      if (content.snapshot != base && !retainedSnapshots.contains(content.snapshot)) {
        c.bodies.putAll(content.snapshot.bodies());
        c.proofs.putAll(content.snapshot.proofs());
      }
      c.remember(content.semantic);
    }
    c.lookup =
        id -> {
          for (LanguageProvider provider : providers) {
            Optional<LanguageRecord> result = Objects.requireNonNull(provider.find(new BlueId(id)));
            if (result.isPresent()) {
              return result.orElseThrow().response();
            }
          }
          return null;
        };
    return c;
  }

  private LanguageContext admissionContext(LanguageDocument[] values) {
    values = Objects.requireNonNull(values).clone();
    for (LanguageDocument value : values) {
      if (Objects.requireNonNull(value).goal() != Goal.VALUE) {
        throw new IllegalArgumentException("Completed Language value required");
      }
    }
    LanguageContext context = context(values);
    Map<String, LanguageModel.Head> established = new HashMap<>();
    for (LanguageDocument value : values) {
      String id = value.blueId().value();
      // Presentation changes must not attribute admission to a different semantic identity.
      if (id.equals(identity(value.semantic))) {
        established.put(
            id, LanguageSemantics.head(LanguageSemantics.recipe(value.semantic), context));
      }
    }
    context.establishedValues = established;
    return context;
  }

  /** Cross-processor values retain deferred evidence, never their temporary admission facts. */
  private Language retaining(LanguageContent... contents) {
    Builder builder = null;
    for (LanguageContent content : Objects.requireNonNull(contents)) {
      Objects.requireNonNull(content);
      if (content.owner == this || retainedSnapshots.contains(content.snapshot)) {
        continue;
      }
      if (builder == null) {
        builder = toBuilder();
      }
      builder.content(content);
    }
    return builder == null ? this : builder.build();
  }

  private static ExactNode input(Input input, LanguageContext c) {
    return LanguageOperations.input(
        Map.of("text", input.text(), "format", input.format().wire(), "mode", input.role().wire()),
        c);
  }

  private static Snapshot snapshot(LanguageContext c) {
    return new Snapshot(c.bodies, c.proofs);
  }

  private LanguageContent content(ExactNode exact, LanguageContext c) {
    return new LanguageContent(this, exact, snapshot(c));
  }

  private LanguageDocument admitted(LanguageAdmission value, LanguageContext c, Goal goal) {
    return new LanguageDocument(
        this, value.exact(), snapshot(c), goal, value.originalBlueId(), value.removedFields());
  }

  static <T> T run(Supplier<T> action) {
    try {
      return LanguageFailure.guard(action);
    } catch (LanguageFailure failure) {
      throw new LanguageException(failure);
    }
  }

  private static Object show(Object value) {
    if (value instanceof ExactNode exact) {
      return export(exact);
    }
    if (value instanceof List<?> items) {
      return items.stream().map(Language::show).toList();
    }
    if (value instanceof Map<?, ?> fields) {
      var result = new LinkedHashMap<String, Object>();
      fields.forEach((key, item) -> result.put((String) key, show(item)));
      return result;
    }
    return value;
  }

  static Node node(ExactNode exact) {
    if (!(exact instanceof ExactNode.Value value)) {
      return node(export(exact));
    }
    var fields = new LinkedHashMap<String, Node>();
    value
        .fields()
        .forEach(
            (key, child) -> {
              Node converted =
                  child instanceof ExactNode n
                      ? node(n)
                      : key.equals("items")
                          ? Node.list(exactItems(child).stream().map(Language::node).toList())
                          : node(child);
              fields.put(key, converted);
            });
    return Node.object(fields);
  }

  static Node node(Object value) {
    if (value instanceof Double number) {
      return Node.number(number);
    }
    if (value instanceof List<?> items) {
      return Node.list(items.stream().map(Language::node).toList());
    }
    if (value instanceof Map<?, ?> fields) {
      var result = new LinkedHashMap<String, Node>();
      fields.forEach((key, item) -> result.put((String) key, node(item)));
      return Node.object(result);
    }
    return NodeCodec.node(value, 0);
  }

  static String yaml(Object value) {
    var options = new DumperOptions();
    options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
    options.setIndent(2);
    options.setSplitLines(false);
    return new Yaml(options).dump(value);
  }

  /** Mutable authoring context builder. Build captures an immutable independent configuration. */
  public static final class Builder {
    private final LanguageMap<String, String> aliases = new LanguageMap<>(CORE);
    private final LanguageMap<String, String> directives = new LanguageMap<>();
    private final LanguageMap<String, Object> records = new LanguageMap<>();
    private final LanguageMap<String, ExactNode.Value> bodies = new LanguageMap<>();
    private final LanguageMap<String, Object> proofs = new LanguageMap<>();
    private final Set<Snapshot> retainedSnapshots =
        Collections.newSetFromMap(new IdentityHashMap<>());
    private final List<LanguageProvider> providers = new ArrayList<>();
    private Limits limits = Limits.defaults();
    private BlueId packageLanguage;

    private Builder() {}

    private Builder(Language source) {
      aliases.reset(source.aliases);
      directives.reset(source.directives);
      records.reset(source.records);
      bodies.reset(source.known.bodies());
      proofs.reset(source.known.proofs());
      retainedSnapshots.addAll(source.retainedSnapshots);
      retainedSnapshots.add(source.known);
      providers.addAll(source.providers);
      limits = source.limits;
      packageLanguage = source.packageLanguage;
    }

    /**
     * Binds an authoring name to an exact identity; conflicting bindings are rejected.
     *
     * @param name case-sensitive authoring alias
     * @param id exact final identity
     * @return this builder
     */
    public Builder alias(String name, BlueId id) {
      Objects.requireNonNull(name);
      Objects.requireNonNull(id);
      String previous = aliases.putIfAbsent(name, id.value());
      if (previous != null && !previous.equals(id.value())) {
        throw new IllegalArgumentException("Conflicting alias: " + name);
      }
      return this;
    }

    /**
     * Binds a Source transformation directive name to an exact identity.
     *
     * @param name Source directive alias
     * @param id exact directive identity
     * @return this builder
     */
    public Builder directive(String name, BlueId id) {
      Objects.requireNonNull(name);
      Objects.requireNonNull(id);
      String previous = directives.putIfAbsent(name, id.value());
      if (previous != null && !previous.equals(id.value())) {
        throw new IllegalArgumentException("Conflicting directive: " + name);
      }
      return this;
    }

    /**
     * Appends an exact-evidence provider, consulted in registration order.
     *
     * @param provider thread-safe exact-evidence provider
     * @return this builder
     */
    public Builder provider(LanguageProvider provider) {
      providers.add(Objects.requireNonNull(provider));
      return this;
    }

    /**
     * Adapts an existing provider of exact acyclic nodes. Cyclic proofs require {@link #provider}.
     *
     * @param provider thread-safe exact node provider
     * @return this builder
     */
    public Builder nodeProvider(NodeProvider provider) {
      Objects.requireNonNull(provider);
      return provider(
          id ->
              provider.find(id).map(node -> LanguageRecord.json(new NodeCodec().writeJson(node))));
    }

    /**
     * Registers unverified provider evidence to be checked when opened.
     *
     * @param id requested identity
     * @param record unverified evidence checked on use
     * @return this builder
     */
    public Builder record(BlueId id, LanguageRecord record) {
      records.put(Objects.requireNonNull(id).value(), Objects.requireNonNull(record).response());
      return this;
    }

    /**
     * Retains verified immutable content and its supporting dependencies. Deferred evidence
     * providers remain lazy and are still verified when opened; mounting content does not transfer
     * its admission to a later read.
     *
     * @param content retained exact content and dependencies
     * @return this builder
     */
    public Builder content(LanguageContent content) {
      Objects.requireNonNull(content);
      if (retainedSnapshots.add(content.snapshot)) {
        if (bodies.isEmpty()) {
          bodies.reset(content.snapshot.bodies());
        } else {
          bodies.putAll(content.snapshot.bodies());
        }
        if (proofs.isEmpty()) {
          proofs.reset(content.snapshot.proofs());
        } else {
          proofs.putAll(content.snapshot.proofs());
        }
        // A recovered checkpoint intentionally contains only selected bodies. Retain its
        // deferred evidence too, without traversing the closure or nesting resolver chains.
        if (records.isEmpty()) {
          records.reset(content.owner.records);
        } else {
          content.owner.records.forEach(records::putIfAbsent);
        }
        for (LanguageProvider provider : content.owner.providers) {
          if (providers.stream().noneMatch(existing -> existing == provider)) {
            providers.add(provider);
          }
        }
      }
      if (content.semantic instanceof ExactNode.Value value) {
        bodies.put(content.blueId().value(), value);
      }
      return this;
    }

    /**
     * Registers an admitted type under a case-sensitive authoring name, normally an imported
     * package prefix followed by an export name. The binding is independent of the definition's
     * optional name metadata. Multiple aliases may identify the same unchanged content.
     *
     * <p>This method registers resolved bindings; it does not construct or verify a package
     * artifact.
     *
     * @param name authoring alias
     * @param definition admitted node to use as a type
     * @return this builder
     */
    public Builder type(String name, LanguageDocument definition) {
      alias(name, definition.blueId());
      return content(definition);
    }

    /**
     * Mounts all of a verified package's own exports under a consumer-selected prefix. Supporting
     * content is retained; aliases used by the package's imports do not leak into this context.
     *
     * @param prefix consumer-selected prefix, independent of the package's name
     * @param pkg verified canonical package
     * @return this builder
     * @throws PackageException if the prefix, Language selection or aliases conflict
     */
    public Builder mount(String prefix, BluePackage pkg) {
      Objects.requireNonNull(pkg);
      return mountExports(prefix, pkg, pkg.exports().keySet());
    }

    /**
     * Mounts an explicit, nonempty selection of exports. All checks precede mutation; a failed
     * mount leaves this builder unchanged. Repeated identical bindings are permitted.
     *
     * @param prefix consumer-selected prefix
     * @param pkg verified canonical package
     * @param exports exact local export names, without duplicates
     * @return this builder
     * @throws PackageException if selection, Language identity or bindings conflict
     */
    public Builder mount(String prefix, BluePackage pkg, java.util.Collection<String> exports) {
      Objects.requireNonNull(exports);
      if (exports.isEmpty()) {
        throw new PackageException("InvalidPackage", "Select at least one export");
      }
      return mountExports(prefix, Objects.requireNonNull(pkg), exports);
    }

    private Builder mountExports(
        String prefix, BluePackage pkg, java.util.Collection<String> exports) {
      PackageIntegrity.require(
          PackagePaths.validPrefix(prefix), "InvalidPackage", "Invalid mount prefix");
      PackageIntegrity.require(
          packageLanguage == null || packageLanguage.equals(pkg.languageId()),
          "PackageSelectionMismatch",
          "Incompatible Language packages");
      java.util.Set<String> selected = new java.util.HashSet<>();
      Map<String, String> additions = new LinkedHashMap<>();
      for (String name : exports) {
        PackageIntegrity.check(
            name != null && selected.add(name) && pkg.exports().containsKey(name),
            "Unknown or duplicate export: " + name);
        String alias = prefix + "/" + name;
        String target = pkg.export(name).value();
        String previous = aliases.get(alias);
        PackageIntegrity.require(
            previous == null || previous.equals(target), "AliasCollision", alias);
        additions.put(alias, target);
      }
      content(pkg.document());
      aliases.putAll(additions);
      packageLanguage = pkg.languageId();
      return this;
    }

    /**
     * Sets the independent resource bounds for each operation.
     *
     * @param limits independent resource bounds
     * @return this builder
     */
    public Builder limits(Limits limits) {
      this.limits = Objects.requireNonNull(limits);
      return this;
    }

    /**
     * Captures the current configuration in a thread-safe processor.
     *
     * @return an immutable reusable Language context
     */
    public Language build() {
      return new Language(this);
    }
  }
}
