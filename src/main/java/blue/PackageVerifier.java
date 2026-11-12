package blue;

import static blue.LanguageCore.CORE;
import static blue.LanguageCore.CORE_NAME;
import static blue.LanguageCore.finalId;
import static blue.LanguageCore.identity;
import static blue.LanguageCore.list;
import static blue.LanguageCore.map;
import static blue.LanguageCore.normalize;
import static blue.LanguageCore.string;
import static blue.PackageIntegrity.check;
import static blue.PackageIntegrity.data;
import static blue.PackageIntegrity.equal;
import static blue.PackageIntegrity.ref;
import static blue.PackageIntegrity.repositoryCanonical;
import static blue.PackageIntegrity.sha;
import static blue.PackageIntegrity.utf8;
import static blue.PackageIntegrity.version;

import blue.ExactNode.Ref;
import blue.ExactNode.Value;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Package-role verification over the canonical Language engine. Mutable state belongs to one load.
 */
final class PackageVerifier {
  private static final String FORMAT = BluePackage.FORMAT;
  private static final String SEMANTIC_FORMAT = BluePackage.SEMANTIC_FORMAT;
  private static final String LANGUAGE_PROFILE = Language.EDITION;
  private static final Set<String> PACKAGE_FIELDS =
      Set.of(
          "type",
          "format",
          "name",
          "version",
          "identityProfile",
          "bootstrapLanguage",
          "language",
          "exports",
          "imports",
          "protocol",
          "specBindings",
          "declaredRequirements",
          "documentation");

  final Map<String, byte[]> files;
  final Map<String, Object> manifest, records, packages, protocols;
  final LanguageContext context;
  final LanguageMap<String, Object> recordProvider = new LanguageMap<>();
  final Set<String> verifiedPackages = new HashSet<>(),
      verifiedProtocols = new HashSet<>(),
      activePackages = new HashSet<>(),
      activeProtocols = new HashSet<>();
  final String packageId, packageType, protocolType;

  PackageVerifier(Map<String, byte[]> files, Language.Limits limits) {
    context = new LanguageContext(limits.wire());
    this.files = files;
    manifest = readJson(files.get("package.json"));
    check(FORMAT.equals(manifest.get("format")), "unsupported package format");
    check(LANGUAGE_PROFILE.equals(manifest.get("languageProfile")), "unsupported Language profile");
    check(manifest.get("toolchain") instanceof Map, "missing producer provenance");
    records = map(LanguageValues.freeze(readJson(files.get("package-records.json"))));
    recordProvider.putAll(records);
    context.provider.reset(recordProvider);
    packages = map(manifest.get("semanticPackages"));
    protocols = map(manifest.get("semanticSpecifications"));
    packageId = finalId(manifest.get("packageBlueId"));
    packageType = standard("Package");
    protocolType = standard("ProtocolSpecification");
  }

  private Map<String, Object> readJson(byte[] bytes) {
    check(bytes != null, "missing package JSON file");
    return map(data(LanguageOperations.parse(utf8(bytes), "json", context)));
  }

  BluePackage verify() {
    var inventory = map(manifest.get("files"));
    var actual = new TreeSet<>(files.keySet());
    actual.remove("package.json");
    check(actual.equals(inventory.keySet()), "file inventory mismatch");
    for (String name : actual) {
      PackagePaths.safe(name);
      if (name.endsWith(".json")) {
        LanguageOperations.parse(utf8(files.get(name)), "json", context);
      }
      Map<String, Object> desc = map(inventory.get(name));
      check(
          desc.keySet().equals(Set.of("size", "sha256"))
              && equal(desc.get("size"), files.get(name).length)
              && ("sha256:" + sha(files.get(name))).equals(desc.get("sha256")),
          "file integrity mismatch: " + name);
    }
    // Each producer record is checked cold: a previous inline body cannot mask a substituted
    // record.
    for (String id : new TreeSet<>(records.keySet())) {
      LanguageContext cold = new LanguageContext(context.limits);
      // Share only immutable producer responses, never another record's validated bodies.
      cold.provider.reset(recordProvider);
      Map<String, Object> response = map(records.get(id));
      check(
          id.contains("#")
              || (response.containsKey("text")
                  && "exact".equals(response.getOrDefault("mode", "exact"))
                  && Set.of("text", "format", "mode").containsAll(response.keySet())),
          "Invalid repository record mode");
      cold.bodies.remove(id);
      Value verified = LanguageCore.open(new Ref(finalId(id)), cold);
      context.bound("steps", context.work.addAndGet(cold.work.get()));
      context.bodies.putAll(cold.bodies);
      context.bodies.put(id, verified);
      context.proofs.putAll(cold.proofs);
    }
    ExactNode root =
        normalize(
            LanguageOperations.parse(utf8(files.get("package.blue.json")), "json", context),
            context,
            "exact",
            false);
    LanguageAdmission admitted = LanguageOperations.admit(root, context, "definition", "exact");
    check(packageId.equals(identity(admitted.exact())), "package BlueId mismatch");
    verifyPackage(packageId, null);
    check(
        packages.keySet().equals(verifiedPackages) && protocols.keySet().equals(verifiedProtocols),
        "unrelated semantic witnesses");
    var semantic = map(packages.get(packageId));
    var assets = new TreeSet<>(files.keySet());
    assets.removeAll(
        Set.of("package.json", "package.blue.json", "package-records.json", "repository.json"));
    check(assets.equals(map(semantic.get("documentation")).keySet()), "unidentified package asset");
    for (String k : List.of("name", "version"))
      check(Objects.equals(manifest.get(k), semantic.get(k)), "package metadata mismatch: " + k);
    check(
        Objects.equals(manifest.get("language"), ref(semantic.get("language"))),
        "Language declaration mismatch");
    check(
        Objects.equals(manifest.get("protocol"), ref(semantic.get("protocol"))),
        "protocol declaration mismatch");
    var imports = map(manifest.get("imports"));
    var selected = map(semantic.get("imports"));
    check(imports.keySet().equals(selected.keySet()), "import scope mismatch");
    for (String prefix : imports.keySet()) {
      var entry = map(imports.get(prefix));
      String id = ref(selected.get(prefix));
      check(id.equals(entry.get("packageBlueId")), "import identity mismatch");
      var dep = map(packages.get(id));
      check(
          Objects.equals(entry.get("name"), dep.get("name"))
              && Objects.equals(entry.get("version"), dep.get("version")),
          "import release mismatch");
    }
    check(
        manifest.get("specification") == null
            && list(manifest.get("importedDefinitions")).isEmpty(),
        "legacy specification association");
    var repository = readJson(files.get("repository.json"));
    verifyRepository(repository);
    check(
        map(manifest.get("repository"))
            .equals(
                Map.of(
                    "path",
                    "repository.json",
                    "identity",
                    repository.get("identity"),
                    "format",
                    "blue-python-repository/1")),
        "repository metadata mismatch");
    check(Objects.equals(repository.get("name"), manifest.get("name")), "repository name mismatch");
    var exportIds = new TreeMap<String, String>();
    var aliases = new TreeMap<String, Object>();
    var exportRefs = new TreeMap<String, Object>();
    for (Object raw : list(manifest.get("exports"))) {
      var e = map(raw);
      String name = string(e.get("name")), id = finalId(e.get("blueId"));
      check(exportIds.put(name, id) == null && !name.isEmpty(), "duplicate export");
      check(aliases.put(string(e.get("alias")), id) == null, "duplicate export alias");
      exportRefs.put(name, Map.of("blueId", id));
      check(e.get("specification") == null, "legacy export specification");
      String rules =
          manifest.get("protocol") == null
              ? null
              : ref(map(protocols.get(manifest.get("protocol"))).get("rules"));
      check(Objects.equals(rules, e.get("specificationMetadata")), "export protocol mismatch");
    }
    check(
        aliases.equals(repository.get("aliases")) && exportRefs.equals(semantic.get("exports")),
        "export identities or aliases mismatch");
    var validation = map(manifest.get("validation"));
    check(
        validation.keySet().equals(Set.of("kind", "definitions", "passed"))
            && Objects.equals(validation.get("kind"), "Repository.audit")
            && equal(validation.get("definitions"), exportIds.size())
            && equal(validation.get("passed"), exportIds.size()),
        "definition audit claim mismatch");
    check(
        LanguageOperations.records(admitted.exact(), context).keySet().equals(records.keySet()),
        "missing or unrelated content records");
    var repoRecords = map(repository.get("records"));
    check(records.keySet().containsAll(repoRecords.keySet()), "repository content outside package");
    for (var e : repoRecords.entrySet()) {
      check(
          recordEqual(e.getValue(), records.get(e.getKey())),
          "repository record mismatch: " + e.getKey());
      LanguageContext cold = new LanguageContext(context.limits);
      cold.provider.reset(recordProvider);
      cold.provider.put(e.getKey(), e.getValue());
      cold.bodies.remove(e.getKey());
      LanguageCore.open(new Ref(finalId(e.getKey())), cold);
      context.bound("steps", context.work.addAndGet(cold.work.get()));
    }
    return BluePackage.verified(packageId, packages, protocols, records, context);
  }

  void verifyWitness(String id, Object source) {
    ExactNode n =
        LanguageSource.prepare(
            LanguageOperations.parse(LanguageCodec.wire(source), "json", context), context);
    LanguageAdmission d = LanguageOperations.admit(n, context, "definition", "source");
    check(identity(d.exact()).equals(id), "semantic witness identity mismatch: " + id);
    Value actual = LanguageCore.open(new Ref(id), context);
    check(
        identity(actual).equals(identity(d.exact())), "semantic witness differs from exact record");
  }

  void verifyProtocol(String sid, String lang) {
    check(!activeProtocols.contains(sid), "cyclic specification prerequisites");
    check(protocols.containsKey(sid), "missing specification witness");
    var s = map(protocols.get(sid));
    check(Objects.equals(ref(s.get("language")), lang), "specification Language mismatch");
    if (verifiedProtocols.contains(sid)) return;
    check(
        s.keySet().containsAll(Set.of("type", "name", "language", "rules", "requires", "status"))
            && Set.of(
                    "type",
                    "name",
                    "language",
                    "rules",
                    "requires",
                    "status",
                    "effectiveRules",
                    "schedulingPolicy")
                .containsAll(s.keySet())
            && protocolType.equals(ref(s.get("type"))),
        "invalid specification witness");
    check(
        s.get("name") instanceof String && s.get("status") instanceof String,
        "Invalid protocol labels");
    verifyWitness(sid, s);
    requireText(ref(s.get("rules")));
    for (String k : List.of("effectiveRules", "schedulingPolicy"))
      if (s.containsKey(k)) requireText(ref(s.get(k)));
    activeProtocols.add(sid);
    for (Object need : map(s.get("requires")).values()) verifyProtocol(ref(need), lang);
    activeProtocols.remove(sid);
    verifiedProtocols.add(sid);
  }

  void verifyPackage(String id, String expectedLang) {
    check(!activePackages.contains(id), "cyclic package imports");
    check(packages.containsKey(id), "missing package witness");
    var p = map(packages.get(id));
    String lang = ref(p.get("language"));
    boolean bootstrap = Boolean.TRUE.equals(p.get("bootstrapLanguage"));
    check(
        expectedLang == null || Objects.equals(bootstrap ? id : lang, expectedLang),
        "cross-Language package graph");
    if (verifiedPackages.contains(id)) return;
    check(
        p.keySet().equals(PACKAGE_FIELDS)
            && packageType.equals(ref(p.get("type")))
            && SEMANTIC_FORMAT.equals(p.get("format")),
        "invalid package semantic record");
    check(LANGUAGE_PROFILE.equals(p.get("identityProfile")), "package Language profile mismatch");
    check(
        p.get("name") instanceof String && !string(p.get("name")).isEmpty(),
        "Missing package name");
    verifyWitness(id, p);
    version(p.get("version"));
    check(p.get("bootstrapLanguage") instanceof Boolean, "invalid bootstrap flag");
    var imports = map(p.get("imports"));
    if (bootstrap)
      check(
          lang == null && imports.isEmpty() && p.get("protocol") == null,
          "invalid Language bootstrap");
    else
      check(
          lang != null
              && packages.containsKey(lang)
              && Boolean.TRUE.equals(map(packages.get(lang)).get("bootstrapLanguage")),
          "missing Language release");
    activePackages.add(id);
    var importIds = new HashSet<String>();
    for (var e : imports.entrySet()) {
      check(PackagePaths.validPrefix(e.getKey()), "invalid import prefix");
      String target = ref(e.getValue());
      importIds.add(target);
      verifyPackage(target, lang);
    }
    if (lang != null) check(importIds.contains(lang), "Language must be explicit import");
    String protocol = ref(p.get("protocol"));
    if (protocol != null) verifyProtocol(protocol, lang);
    for (Object need : list(p.get("declaredRequirements"))) verifyProtocol(ref(need), lang);
    var exports = map(p.get("exports"));
    var bindings = map(p.get("specBindings"));
    var ids = new HashSet<String>();
    exports.values().forEach(x -> ids.add(ref(x)));
    check(
        bindings.keySet().equals(protocol == null ? Set.of() : ids),
        "protocol association coverage mismatch");
    for (var e : bindings.entrySet()) {
      var binding = map(e.getValue());
      check(
          binding.keySet().equals(Set.of("specification", "protocol", "binding"))
              && Objects.equals(p.get("protocol"), binding.get("protocol"))
              && "native-metadata".equals(binding.get("binding")),
          "specification binding mismatch");
      verifyProtocol(ref(binding.get("protocol")), lang);
      check(
          Objects.equals(binding.get("specification"), map(protocols.get(protocol)).get("rules")),
          "native rules differ from descriptor");
      Value definition = LanguageCore.open(new Ref(e.getKey()), context);
      check(
          definition.get("specification") instanceof Ref r
              && r.id().equals(ref(binding.get("specification"))),
          "native type specification mismatch");
    }
    for (var e : exports.entrySet()) {
      check(!e.getKey().isEmpty(), "empty export name");
      LanguageSemantics.strict(new Ref(ref(e.getValue())), context, "definition");
    }
    for (var e : map(p.get("documentation")).entrySet()) {
      PackagePaths.safe(e.getKey());
      var asset = map(e.getValue());
      check(
          asset.keySet().equals(Set.of("content", "sha256", "size")),
          "invalid documentation descriptor");
      byte[] raw = requireText(ref(asset.get("content"))).getBytes(StandardCharsets.UTF_8);
      check(
          ("sha256:" + sha(raw)).equals(asset.get("sha256"))
              && equal(asset.get("size"), raw.length),
          "documentation bytes mismatch");
      if (id.equals(packageId))
        check(
            Arrays.equals(raw, files.get(e.getKey())), "documentation file differs from identity");
    }
    activePackages.remove(id);
    verifiedPackages.add(id);
  }

  String requireText(String id) {
    Ref r = new Ref(id);
    LanguageSemantics.strict(r, context, "value");
    var h = LanguageSemantics.head(LanguageSemantics.recipe(r), context);
    check(
        h.value instanceof String && "Text".equals(h.core), "documentation or rules must be Text");
    return (String) h.value;
  }

  void verifyRepository(Map<String, Object> repository) {
    check(
        repository
                .keySet()
                .equals(Set.of("format", "name", "aliases", "records", "metadata", "identity"))
            && "blue-python-repository/1".equals(repository.get("format")),
        "invalid repository envelope");
    check(
        repository.get("name") instanceof String
            && !string(repository.get("name")).isEmpty()
            && repository.get("metadata") instanceof Map,
        "Invalid repository metadata");
    var payload = new TreeMap<>(repository);
    Object expected = payload.remove("identity");
    check(
        Objects.equals(
            expected,
            "sha256:" + sha(repositoryCanonical(payload).getBytes(StandardCharsets.UTF_8))),
        "repository checksum mismatch");
    for (var e : map(repository.get("aliases")).entrySet()) {
      String id = finalId(e.getValue());
      check(
          !e.getKey().isEmpty()
              && (!CORE.containsKey(e.getKey()) || CORE.get(e.getKey()).equals(id)),
          "repository alias conflict");
      check(
          map(repository.get("records")).containsKey(id) || CORE_NAME.containsKey(id),
          "repository alias has no evidence");
    }
  }

  private String standard(String name) {
    try (var input =
        PackageVerifier.class.getResourceAsStream("/blue/standard/" + name + ".json")) {
      if (input == null) {
        throw new IllegalStateException("Missing standard definition: " + name);
      }
      ExactNode node =
          LanguageSource.prepare(
              LanguageOperations.parse(utf8(input.readAllBytes()), "json", context), context);
      return identity(LanguageOperations.admit(node, context, "definition", "source").exact());
    } catch (java.io.IOException failure) {
      throw new java.io.UncheckedIOException(failure);
    }
  }

  private boolean recordEqual(Object left, Object right) {
    if (Objects.equals(left, right)) {
      return true;
    }
    Map<String, Object> a = map(left), b = map(right);
    if (!Objects.equals(a.get("mode"), b.get("mode"))
        || !Objects.equals(a.getOrDefault("format", "json"), b.getOrDefault("format", "json"))) {
      return false;
    }
    if (a.get("text") instanceof String at && b.get("text") instanceof String bt) {
      String format = string(a.getOrDefault("format", "json"));
      return Objects.equals(
          data(LanguageOperations.parse(at, format, context)),
          data(LanguageOperations.parse(bt, format, context)));
    }
    return false;
  }
}
