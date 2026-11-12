package blue;

import static blue.LanguageCore.list;
import static blue.LanguageCore.map;
import static blue.LanguageCore.string;
import static blue.PackageIntegrity.ref;

import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * An immutable canonical package and its verified, self-contained evidence.
 *
 * <p>Loading checks the archive, package graph, definitions and protocol declarations. It does not
 * install native code or establish support for the declared protocols. Export names are separate
 * from document metadata. Instances are safe to share across threads.
 */
public final class BluePackage implements LanguageProvider {
  /** The canonical distribution carrier accepted by this loader. */
  public static final String FORMAT = "blue-package/0.3-local";

  /** The semantic package model interpreted by this Language edition. */
  public static final String SEMANTIC_FORMAT = "blue-package-model/2-specification-metadata";

  private final BlueId id;
  private final Evidence evidence;
  private final Map<String, Object> witness;
  private final Map<String, BlueId> exports;
  private final Map<String, BlueId> imports;

  private record Evidence(
      Language language,
      Language.Snapshot snapshot,
      Map<String, Object> packages,
      Map<String, Object> specifications,
      Map<BlueId, LanguageRecord> records) {}

  private BluePackage(BlueId id, Evidence evidence) {
    this.id = id;
    this.evidence = evidence;
    witness = map(evidence.packages.get(id.value()));
    exports = references(witness.get("exports"));
    imports = references(witness.get("imports"));
  }

  static BluePackage verified(
      String id,
      Map<String, Object> packages,
      Map<String, Object> specifications,
      Map<String, Object> responses,
      LanguageContext context) {
    Language.Snapshot snapshot = new Language.Snapshot(context.bodies, context.proofs);
    Language language = Language.withVerifiedEvidence(snapshot);
    Map<BlueId, LanguageRecord> records = new LinkedHashMap<>();
    responses.forEach(
        (key, response) -> records.put(new BlueId(key), new LanguageRecord(map(response))));
    return new BluePackage(
        new BlueId(id),
        new Evidence(
            language,
            snapshot,
            map(LanguageValues.freeze(packages)),
            map(LanguageValues.freeze(specifications)),
            Collections.unmodifiableMap(records)));
  }

  /**
   * Verifies a local archive or prepared directory using bounded defaults.
   *
   * @param path local package location
   * @return the verified package; use a pinned overload when the expected identity is known
   * @throws PackageException if loading or verification fails
   */
  public static BluePackage open(Path path) {
    return PackageLoader.create().open(path);
  }

  /**
   * Verifies a local package against a trusted expected identity.
   *
   * @param path local package location
   * @param expected expected semantic package identity, independent of archive compression
   * @return the verified package
   * @throws PackageException if loading, verification or identity selection fails
   */
  public static BluePackage open(Path path, BlueId expected) {
    return PackageLoader.create().open(path, expected);
  }

  /**
   * Returns the semantic package identity, not a checksum of ZIP bytes.
   *
   * @return the semantic package identity, not a checksum of ZIP bytes
   */
  public BlueId blueId() {
    return id;
  }

  /**
   * Returns the publisher's literal package name, not the consumer's import prefix.
   *
   * @return the publisher's literal package name, not the consumer's import prefix
   */
  public String name() {
    return string(witness.get("name"));
  }

  /**
   * Returns the descriptive release label; selection never substitutes versions.
   *
   * @return the descriptive release label; selection never substitutes versions
   */
  public String version() {
    return string(witness.get("version"));
  }

  /**
   * Returns the exact Language package governing this graph.
   *
   * @return the exact Language package governing this graph
   */
  public BlueId languageId() {
    return Boolean.TRUE.equals(witness.get("bootstrapLanguage"))
        ? id
        : new BlueId(ref(witness.get("language")));
  }

  /**
   * Returns immutable local export names and exact content identities.
   *
   * @return immutable local export names and exact content identities
   */
  public Map<String, BlueId> exports() {
    return exports;
  }

  /**
   * Returns immutable author-chosen import prefixes and pinned package identities.
   *
   * @return immutable author-chosen import prefixes and pinned package identities
   */
  public Map<String, BlueId> imports() {
    return imports;
  }

  /**
   * Selects one local export without registering an alias.
   *
   * @param name exact case-sensitive export name
   * @return its content identity
   * @throws NoSuchElementException if the package does not export the name
   */
  public BlueId export(String name) {
    BlueId found = exports.get(Objects.requireNonNull(name));
    if (found == null) {
      throw new NoSuchElementException("Unknown export: " + name);
    }
    return found;
  }

  /**
   * Returns an already verified definition with its supporting evidence.
   *
   * @param name exact local export name
   * @return a definition handle suitable for specialization or extension
   * @throws NoSuchElementException if the export is absent
   */
  public LanguageDocument definition(String name) {
    return admitted(export(name));
  }

  /**
   * Returns the admitted semantic package root; package roots use the definition role.
   *
   * @return the admitted semantic package root; package roots use the definition role
   */
  public LanguageDocument document() {
    return admitted(id);
  }

  private LanguageDocument admitted(BlueId identity) {
    ExactNode node = new ExactNode.Ref(identity.value());
    // Every package and export was certified before this Evidence instance was published.
    return new LanguageDocument(
        evidence.language,
        node,
        evidence.snapshot,
        Language.Goal.DEFINITION,
        identity.value(),
        List.of());
  }

  /**
   * Selects a direct dependency from the verified closure without new I/O or implicit mounting.
   *
   * @param prefix the publishing package's import prefix
   * @return the exact imported package, sharing the verified evidence
   * @throws NoSuchElementException if there is no such direct import
   */
  public BluePackage imported(String prefix) {
    BlueId dependency = imports.get(Objects.requireNonNull(prefix));
    if (dependency == null) {
      throw new NoSuchElementException("Unknown direct import: " + prefix);
    }
    return new BluePackage(dependency, evidence);
  }

  /**
   * Returns immutable exact records, including unnamed supporting content and cyclic proofs.
   *
   * @return immutable exact records, including unnamed supporting content and cyclic proofs
   */
  public Map<BlueId, LanguageRecord> records() {
    return evidence.records;
  }

  /**
   * Supplies evidence only; using this package as a provider introduces no aliases.
   *
   * @param identity the requested exact identity
   * @return its verified record when supplied by this archive
   */
  @Override
  public Optional<LanguageRecord> find(BlueId identity) {
    return Optional.ofNullable(evidence.records.get(Objects.requireNonNull(identity)));
  }

  /**
   * Returns identified documentation paths and Text identities.
   *
   * @return identified documentation paths and Text identities
   */
  public Map<String, BlueId> documentation() {
    Map<String, BlueId> documents = new TreeMap<>();
    map(witness.get("documentation"))
        .forEach((path, entry) -> documents.put(path, new BlueId(ref(map(entry).get("content")))));
    return Collections.unmodifiableMap(documents);
  }

  /**
   * Reads verified documentation, including documentation of an imported package.
   *
   * @param path exact logical documentation path
   * @return the original Text value, without normalization
   * @throws NoSuchElementException if the documentation is absent
   */
  public String documentation(String path) {
    BlueId target = documentation().get(Objects.requireNonNull(path));
    if (target == null) {
      throw new NoSuchElementException("Unknown documentation: " + path);
    }
    return evidence.language.reference(target).at("").text();
  }

  /**
   * Returns direct declared protocol requirements; these do not assert processor support.
   *
   * @return direct declared protocol requirements; these do not assert processor support
   */
  public Set<BlueId> declaredRequirements() {
    Set<BlueId> required = new LinkedHashSet<>();
    list(witness.get("declaredRequirements"))
        .forEach(value -> required.add(new BlueId(ref(value))));
    return Collections.unmodifiableSet(required);
  }

  /**
   * Returns this package's optional governing protocol declaration.
   *
   * @return this package's optional governing protocol declaration
   */
  public Optional<Protocol> protocol() {
    String protocol = ref(witness.get("protocol"));
    return protocol == null ? Optional.empty() : Optional.of(protocol(new BlueId(protocol)));
  }

  /**
   * Looks up a verified protocol descriptor in the closure.
   *
   * @param identity exact descriptor identity
   * @return immutable descriptor metadata
   * @throws NoSuchElementException if the descriptor is not part of this package graph
   */
  public Protocol protocol(BlueId identity) {
    Object found = evidence.specifications.get(Objects.requireNonNull(identity).value());
    if (found == null) {
      throw new NoSuchElementException("Unknown protocol: " + identity);
    }
    Map<String, Object> value = map(found);
    return new Protocol(
        identity,
        string(value.get("name")),
        new BlueId(ref(value.get("language"))),
        new BlueId(ref(value.get("rules"))),
        references(value.get("requires")),
        string(value.get("status")),
        optionalId(value.get("effectiveRules")),
        optionalId(value.get("schedulingPolicy")));
  }

  /**
   * Verified data describing a protocol, without granting execution authority.
   *
   * @param blueId descriptor identity
   * @param name descriptive name
   * @param language exact Language selection
   * @param rules identified normative Text
   * @param requires labeled prerequisite descriptor identities
   * @param status publisher's descriptive status
   * @param effectiveRules optional supplemental rules Text
   * @param schedulingPolicy optional scheduling policy Text
   */
  public record Protocol(
      BlueId blueId,
      String name,
      BlueId language,
      BlueId rules,
      Map<String, BlueId> requires,
      String status,
      Optional<BlueId> effectiveRules,
      Optional<BlueId> schedulingPolicy) {
    /** Copies requirements and rejects null fields. */
    public Protocol {
      Objects.requireNonNull(blueId);
      Objects.requireNonNull(name);
      Objects.requireNonNull(language);
      Objects.requireNonNull(rules);
      Objects.requireNonNull(status);
      requires = Map.copyOf(requires);
      Objects.requireNonNull(effectiveRules);
      Objects.requireNonNull(schedulingPolicy);
    }
  }

  private static Optional<BlueId> optionalId(Object value) {
    return value == null ? Optional.empty() : Optional.of(new BlueId(ref(value)));
  }

  private static Map<String, BlueId> references(Object value) {
    Map<String, BlueId> result = new TreeMap<>();
    map(value).forEach((name, reference) -> result.put(name, new BlueId(ref(reference))));
    return Collections.unmodifiableMap(result);
  }
}
