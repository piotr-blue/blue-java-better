package blue;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import org.pcollections.TreePMap;

/** Closed platform records and the shared invocation ledger. */
final class ContractModel {
  static final long SAFE = (1L << 53) - 1, DEFAULT_LIMIT = 10_000_000;
  // A host must not widen the maximum specified by the selected Contracts publication.
  static final long HOST_LIMIT = hostLimit();

  private static long hostLimit() {
    long value = Long.parseLong(System.getProperty("blue.hostGasLimit", "10000000"));
    if (value != DEFAULT_LIMIT) {
      throw new IllegalArgumentException(
          "Published Contracts requires blue.hostGasLimit=10000000; "
              + "select lower budgets per invocation, not a different host maximum");
    }
    return DEFAULT_LIMIT;
  }

  static Map<String, Object> m(Object... pairs) {
    var out = new LinkedHashMap<String, Object>();
    for (int i = 0; i < pairs.length; i += 2) {
      out.put((String) pairs[i], pairs[i + 1]);
    }
    return out;
  }

  @SuppressWarnings("unchecked")
  static Map<String, Object> obj(Object v) {
    require(v instanceof Map, "INVALID_RECORD");
    return (Map<String, Object>) v;
  }

  @SuppressWarnings("unchecked")
  static List<Object> arr(Object v) {
    require(v instanceof List, "INVALID_RECORD");
    return (List<Object>) v;
  }

  static String str(Object v) {
    require(v instanceof String, "INVALID_TEXT");
    return (String) v;
  }

  static class Error extends ContractFault {
    private static final long serialVersionUID = 1L;
    final String code;
    // Diagnostic detail for callers in this JVM; the serialized form keeps the superclass JSON.
    final transient Map<String, Object> context;

    Error(String code, Object... context) {
      super(code, m(context));
      this.code = code;
      this.context = m(context);
    }
  }

  static void require(boolean ok, String code, Object... context) {
    if (!ok) {
      throw new Error(code, context);
    }
  }

  static String token(Object v) {
    require(
        v instanceof String s
            && !s.isEmpty()
            && !s.contains("\0")
            && Normalizer.isNormalized(s, Normalizer.Form.NFC),
        "INVALID_TOKEN");
    return (String) v;
  }

  static long integer(Object v, long min, long max) {
    if (v instanceof Long n) {
      return integer(n.longValue(), min, max);
    }
    if (v instanceof Integer n) {
      return integer(n.longValue(), min, max);
    }
    require(v instanceof BigInteger, "INVALID_INTEGER", "minimum", min, "maximum", max);
    BigInteger n = (BigInteger) v;
    require(
        n.compareTo(BigInteger.valueOf(min)) >= 0
            && (max < 0 || n.compareTo(BigInteger.valueOf(max)) <= 0),
        "INVALID_INTEGER",
        "minimum",
        min,
        "maximum",
        max < 0 ? null : max);
    return n.longValueExact();
  }

  static long integer(long value, long min, long max) {
    if (value < min || max >= 0 && value > max) {
      throw new Error("INVALID_INTEGER", "minimum", min, "maximum", max < 0 ? null : max);
    }
    return value;
  }

  static long integer(Object v) {
    return integer(v, 0, SAFE);
  }

  static Map<String, Object> closed(Object v, Set<String> required, Set<String> optional) {
    var out = obj(v);
    var all = new HashSet<>(required);
    all.addAll(optional);
    require(out.keySet().containsAll(required) && all.containsAll(out.keySet()), "INVALID_RECORD");
    return out;
  }

  @SuppressWarnings("try") // The span only brackets the measured region.
  static String digest(String kind, Object value) {
    try (var ignored = ContractMetrics.span("digest." + kind)) {
      var digest = MessageDigest.getInstance("SHA-256");
      ContractCanonical.write(
          m("kind", kind, "value", value),
          new java.security.DigestOutputStream(java.io.OutputStream.nullOutputStream(), digest));
      return "sha256:" + HexFormat.of().formatHex(digest.digest());
    } catch (NoSuchAlgorithmException | java.io.IOException e) {
      throw new AssertionError(e);
    }
  }

  static List<String> segments(String p) {
    require(
        Normalizer.isNormalized(p, Normalizer.Form.NFC) && (p.isEmpty() || p.startsWith("/")),
        "INVALID_POINTER");
    var out = new ArrayList<String>();
    if (p.isEmpty()) {
      return out;
    }
    for (String s : p.substring(1).split("/", -1)) {
      require(!LanguageSyntax.INVALID_POINTER_ESCAPE.matcher(s).matches(), "INVALID_POINTER");
      out.add(s.replace("~1", "/").replace("~0", "~"));
    }
    return out;
  }

  static String pointer(List<String> p) {
    return p.stream()
        .map(k -> "/" + k.replace("~", "~0").replace("/", "~1"))
        .reduce("", String::concat);
  }

  static boolean prefix(String a, String b) {
    var x = segments(a);
    var y = segments(b);
    return y.size() >= x.size() && y.subList(0, x.size()).equals(x);
  }

  static final Map<String, Object> GAS_PROFILE;
  static final Map<String, Object> TARIFF;

  static {
    try (var in = ContractModel.class.getResourceAsStream("/contracts/GAS_MANIFEST.json")) {
      GAS_PROFILE = obj(ContractWire.json(new String(in.readAllBytes(), StandardCharsets.UTF_8)));
      TARIFF = obj(GAS_PROFILE.get("weights"));
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  record Life(long generation, String signature) {
    Map<String, Object> wire() {
      return m("generation", generation, "signature", signature);
    }
  }

  record EdgeKey(String source, String path) implements Comparable<EdgeKey> {
    public int compareTo(EdgeKey b) {
      int c = ContractCanonical.compare(source, b.source);
      return c == 0 ? ContractCanonical.compare(path, b.path) : c;
    }
  }

  record Edge(String source, String path, String target, long generation) {
    EdgeKey key() {
      return new EdgeKey(source, path);
    }

    List<Object> occurrence() {
      return List.of(source, path, target, generation);
    }

    Map<String, Object> wire() {
      return m("source", source, "path", path, "target", target, "generation", generation);
    }
  }

  /** Immutable business metadata; physical caches never participate in snapshot ownership. */
  static final class Document {
    final String key;
    final ExactNode body;
    // Transient authored provenance, not an additional canonical snapshot field.
    final String initialBlueId, blueId, birth;
    final TreePMap<String, Life> channelLives;
    final TreePMap<String, Long> channelSerials, edgeSerials;
    volatile ContractRegistry.Declarations declarations;
    volatile ContractRegistry.Markers markers;
    volatile ContractGraph.FinalizedLinks finalizedLinks;
    volatile Map<String, ContractFacts.Established> validation = Map.of();
    volatile ContractLocalCheckpoint.BindingMemo bindingMemo;

    Document(String key, ExactNode body) {
      this(
          key,
          body,
          "",
          null,
          ContractLanguage.identity(body),
          TreePMap.empty(),
          TreePMap.empty(),
          TreePMap.empty());
    }

    Document(String key, ExactNode body, String id, String birth) {
      this(key, body, id, birth, id, TreePMap.empty(), TreePMap.empty(), TreePMap.empty());
    }

    Document(
        String key,
        ExactNode body,
        String id,
        String birth,
        Map<String, Life> channels,
        Map<String, Long> channelSerials,
        Map<String, Long> edgeSerials) {
      this(
          key,
          body,
          id,
          birth,
          id,
          TreePMap.from(channels),
          TreePMap.from(channelSerials),
          TreePMap.from(edgeSerials));
    }

    private Document(
        String key,
        ExactNode body,
        String id,
        String birth,
        String initialBlueId,
        TreePMap<String, Life> channels,
        TreePMap<String, Long> channelSerials,
        TreePMap<String, Long> edgeSerials) {
      this.key = key;
      this.body = body;
      this.blueId = id;
      this.birth = birth;
      this.initialBlueId = initialBlueId;
      this.channelLives = channels;
      this.channelSerials = channelSerials;
      this.edgeSerials = edgeSerials;
    }

    Document copy() {
      return this;
    }

    Document withBody(ExactNode value) {
      return body == value
          ? this
          : replace(key, value, blueId, birth, channelLives, channelSerials, edgeSerials);
    }

    Document finalized(ExactNode value) {
      return replace(
          key,
          value,
          ContractLanguage.identity(value),
          birth,
          channelLives,
          channelSerials,
          edgeSerials);
    }

    Document withBlueId(String value) {
      return replace(key, body, value, birth, channelLives, channelSerials, edgeSerials);
    }

    Document withKey(String value) {
      return replace(value, body, blueId, birth, channelLives, channelSerials, edgeSerials);
    }

    Document withBirth(String value) {
      return replace(key, body, blueId, value, channelLives, channelSerials, edgeSerials);
    }

    Document withChannels(TreePMap<String, Life> lives, TreePMap<String, Long> serials) {
      return replace(key, body, blueId, birth, lives, serials, edgeSerials);
    }

    Document withChannelSerial(String name, long generation) {
      return withChannels(channelLives, channelSerials.plus(name, generation));
    }

    Document withEdgeSerial(String path, long generation) {
      return replace(
          key,
          body,
          blueId,
          birth,
          channelLives,
          channelSerials,
          edgeSerials.plus(path, generation));
    }

    boolean sameMetadata(Document other) {
      return key.equals(other.key)
          && blueId.equals(other.blueId)
          && Objects.equals(birth, other.birth)
          && channelLives == other.channelLives
          && channelSerials == other.channelSerials
          && edgeSerials == other.edgeSerials;
    }

    private Document replace(
        String key,
        ExactNode body,
        String id,
        String birth,
        TreePMap<String, Life> lives,
        TreePMap<String, Long> serials,
        TreePMap<String, Long> edges) {
      if (this.key.equals(key)
          && this.body == body
          && blueId.equals(id)
          && Objects.equals(this.birth, birth)
          && channelLives == lives
          && channelSerials == serials
          && edgeSerials == edges) {
        return this;
      }
      var d = new Document(key, body, id, birth, initialBlueId, lives, serials, edges);
      d.declarations = declarations;
      d.markers = markers;
      d.finalizedLinks = finalizedLinks;
      d.validation = validation;
      d.bindingMemo = bindingMemo;
      if (channelLives == lives && channelSerials == serials && edgeSerials == edges) {
        d.cachedCollections = cachedCollections;
      }
      if (sameMetadata(d)) {
        d.cachedMetadata = cachedMetadata;
        d.metadataEncoding = metadataEncoding;
        d.cachedView = cachedView;
        d.cachedIdleUnit = cachedIdleUnit;
      }
      return d;
    }

    private record View(List<Edge> edges, Map<String, Object> value) {}

    private record Idle(Map<String, Object> view, boolean finalGas, Map<String, Object> value) {}

    /** Immutable collection projections may be shared across scalar/body-only revisions. */
    private record MetadataCollections(
        Object channels, Object channelSerials, Object edgeSerials) {}

    private volatile MetadataCollections cachedCollections;
    private volatile Map<String, Object> cachedMetadata;
    private volatile ContractCanonical.Fragment metadataEncoding;
    private volatile View cachedView;
    private volatile Idle cachedIdleUnit;

    Map<String, Object> view(List<Edge> edges) {
      var previous = cachedView;
      if (previous != null && edges.equals(previous.edges)) {
        return previous.value;
      }
      // The same member view enters turn inputs, vectors and receipts. Encode its immutable
      // commitment once; every containing digest still consumes all of these exact bytes.
      var result =
          new ContractCanonical.EncodedRecord(
              m("document", metadata(), "edges", edges.stream().map(Edge::wire).toList()));
      cachedView = new View(List.copyOf(edges), result);
      return result;
    }

    Map<String, Object> idleUnit(Map<String, Object> view, boolean finalGas) {
      var previous = cachedIdleUnit;
      if (previous != null && view == previous.view && finalGas == previous.finalGas) {
        ContractMetrics.count("proof.idleUnits.reused");
        return previous.value;
      }
      ContractMetrics.count("proof.idleUnits.built");
      var vector = Map.of(key, view);
      var row =
          m(
              "members",
              List.of(key),
              "beforeViews",
              vector,
              "afterViews",
              vector,
              "turnResults",
              List.of(),
              "output",
              List.of(),
              finalGas ? "gas" : "workSegment",
              finalGas ? 0L : List.of());
      row.put("identity", digest("contracts.unit-result/completion-1", row));
      var result = new ContractCanonical.EncodedRecord(row);
      cachedIdleUnit = new Idle(view, finalGas, result);
      return result;
    }

    ContractCanonical.Fragment encodedMetadata() {
      var previous = metadataEncoding;
      if (previous != null) {
        return previous;
      }
      var result = new ContractCanonical.Fragment(metadata());
      metadataEncoding = result;
      return result;
    }

    Map<String, Object> metadata() {
      var previous = cachedMetadata;
      if (previous != null) {
        ContractMetrics.count("contracts.metadata.reused");
        return previous;
      }
      ContractMetrics.count("contracts.metadata.built");
      var collections = cachedCollections;
      if (collections == null) {
        var lives = new TreeMap<String, Object>();
        channelLives.forEach((k, v) -> lives.put(k, v.wire()));
        collections =
            new MetadataCollections(
                new ContractCanonical.EncodedRecord(lives),
                new ContractCanonical.EncodedRecord(obj(channelSerials)),
                new ContractCanonical.EncodedRecord(obj(edgeSerials)));
        cachedCollections = collections;
      }
      var result =
          obj(
              ContractWire.immutable(
                  m(
                      "key",
                      key,
                      "blueId",
                      blueId,
                      "channels",
                      collections.channels(),
                      "channelSerials",
                      collections.channelSerials(),
                      "edgeSerials",
                      collections.edgeSerials(),
                      "birth",
                      birth)));
      cachedMetadata = result;
      return result;
    }
  }

  static class Snapshot {
    String root, release = "";
    boolean ownerLocal;
    boolean verifyGraphBindings;
    GasSchedule gasSchedule = GasSchedule.STANDARD;
    final TopologyMap<String, Document> documents =
        new TopologyMap<>(true, ContractCanonical.CODE_POINTS, Document::sameMetadata);
    final TopologyMap<EdgeKey, Edge> edges = new TopologyMap<>(false);
    ContractTopology topology;
    long generation, revision;
    List<Object> runtimes = new ArrayList<>();
    private Map<String, Object> cachedWire;
    private String cachedIdentity;
    private Version wireVersion, identityVersion;
    private Map<Edge, ContractCanonical.Fragment> edgeEncodings = Map.of();
    private List<ContractCanonical.Fragment> encodedEdges = List.of();
    private long encodedEdgesRevision = -1;

    /** Exact inputs to either representation; the digest need not materialize the wire record. */
    private record Version(
        Object documents,
        long edges,
        String root,
        String release,
        GasSchedule gasSchedule,
        long generation,
        long revision,
        Object runtimes) {
      static Version of(Snapshot state) {
        return new Version(
            state.documents.content(),
            state.edges.revision(),
            state.root,
            state.release,
            state.gasSchedule,
            state.generation,
            state.revision,
            ContractWire.immutable(state.runtimes));
      }

      boolean matches(Snapshot state) {
        return documents == state.documents.content()
            && edges == state.edges.revision()
            && Objects.equals(root, state.root)
            && Objects.equals(release, state.release)
            && gasSchedule == state.gasSchedule
            && generation == state.generation
            && revision == state.revision
            && runtimes.equals(state.runtimes);
      }
    }

    Snapshot(String root) {
      this.root = root;
    }

    /** A stable list permits removal while reconciliation changes this member's outgoing edges. */
    List<Edge> outgoingEdges(String member) {
      return edges.valuesFrom(new EdgeKey(member, ""), key -> key.source().equals(member));
    }

    /** Immutable members and edges make snapshot ownership independent of graph size. */
    Snapshot copy() {
      ContractMetrics.count("contracts.snapshotCopies");
      var s = new Snapshot(root);
      s.release = release;
      s.ownerLocal = ownerLocal;
      s.verifyGraphBindings = verifyGraphBindings;
      s.gasSchedule = gasSchedule;
      s.generation = generation;
      s.revision = revision;
      s.runtimes = new ArrayList<>(runtimes);
      s.documents.copyFrom(documents);
      s.edges.copyFrom(edges);
      s.topology = topology;
      // Copy the cache and its validity markers without materializing unused wire data.
      s.cachedWire = cachedWire;
      s.cachedIdentity = cachedIdentity;
      s.wireVersion = wireVersion;
      s.identityVersion = identityVersion;
      s.edgeEncodings = edgeEncodings;
      s.encodedEdges = encodedEdges;
      s.encodedEdgesRevision = encodedEdgesRevision;
      return s;
    }

    /** A local turn replaces only its writer and any newly admitted members. */
    Snapshot copyForLocalTurn(String member) {
      require(documents.containsKey(member), "MISSING_LOCAL_WRITER");
      return copy();
    }

    Map<String, Object> wire() {
      if (wireVersion != null && wireVersion.matches(this)) {
        ContractMetrics.count("contracts.snapshotWire.reused");
        return cachedWire;
      }
      var metadata = documents.values().stream().map(Document::metadata).toList();
      ContractMetrics.add("contracts.snapshotWire.memberVisits", documents.size());
      ContractMetrics.count("contracts.snapshotWire.built");
      Map<String, Object> encoded =
          m(
              "root",
              root,
              "documents",
              metadata,
              "edges",
              edges.values().stream().map(Edge::wire).toList(),
              "generation",
              generation,
              "revision",
              revision,
              "release",
              release,
              "runtimes",
              runtimes);
      encoded.put("gasSchedule", gasSchedule.id());
      var result = obj(ContractWire.immutable(encoded));
      wireVersion = Version.of(this);
      return cachedWire = result;
    }

    String identity() {
      if (identityVersion == null || !identityVersion.matches(this)) {
        if (Boolean.getBoolean("blue.canonical.fragmentedSnapshotDisabled")) {
          cachedIdentity = digest("contracts.snapshot/completion-1", wire());
        } else {
          // Member keys depend on the exact growing snapshot. Reuse its unchanged record
          // encodings, not an earlier digest: every required byte still enters SHA-256.
          var encoded =
              m(
                  "root",
                  root,
                  "generation",
                  generation,
                  "revision",
                  revision,
                  "release",
                  release,
                  "runtimes",
                  runtimes,
                  "gasSchedule",
                  gasSchedule.id());
          encoded.put(
              "documents", documents.values().stream().map(Document::encodedMetadata).toList());
          if (encodedEdgesRevision != edges.revision()) {
            var nextEdges = new HashMap<Edge, ContractCanonical.Fragment>();
            var fragments = new ArrayList<ContractCanonical.Fragment>();
            for (Edge edge : edges.values()) {
              var fragment = edgeEncodings.get(edge);
              if (fragment == null) {
                fragment = new ContractCanonical.Fragment(edge.wire());
              }
              nextEdges.put(edge, fragment);
              fragments.add(fragment);
            }
            edgeEncodings = Map.copyOf(nextEdges);
            encodedEdges = List.copyOf(fragments);
            encodedEdgesRevision = edges.revision();
          }
          encoded.put("edges", encodedEdges);
          cachedIdentity = digest("contracts.snapshot/completion-1", encoded);
        }
        identityVersion = Version.of(this);
      }
      return cachedIdentity;
    }
  }

  static class Meter extends BexGas {
    private static final ContractWire.RecordShape TRACE =
        ContractWire.recordShape("ordinal", "namespace", "counter", "units", "amount", "owner");

    static Map<String, Object> relative(Map<String, Object> row, int ordinal) {
      if (TRACE.owns(row)) {
        // Local trace rows share the same layout and already validated scalar leaves.
        // Restored wire rows use the ordinary construction path below.
        return TRACE.with(row, "ordinal", ordinal);
      }
      return TRACE.record(
          ordinal,
          row.get("namespace"),
          row.get("counter"),
          row.get("units"),
          row.get("amount"),
          row.get("owner"));
    }

    final boolean recordCharges;
    ContractCalculations.Recording calculation;
    boolean deterministic;
    GasSchedule schedule = GasSchedule.STANDARD;
    boolean semantic;
    long limit, used, hostUsed;
    String owner = "";
    List<Map<String, Object>> trace = new ArrayList<>();
    Map<String, Object> rejected;
    Map<String, Long> localLimits = new TreeMap<>(), localUsed = new TreeMap<>();
    private final NavigableMap<String, ContractCanonical.Field> localFields =
        new TreeMap<>(ContractCanonical.CODE_POINTS);
    private final Set<String> changedLocalOwners = new HashSet<>();

    private void addLocalUsed(String owner, long amount) {
      localUsed.merge(owner, amount, Long::sum);
      changedLocalOwners.add(owner);
    }

    void copyLocalUsed(Meter source) {
      localUsed.putAll(source.localUsed);
      changedLocalOwners.addAll(source.localUsed.keySet());
    }

    private ContractCanonical.Fields localUsedEncoding() {
      for (String key : changedLocalOwners) {
        localFields.put(key, new ContractCanonical.Field(key, localUsed.get(key)));
      }
      changedLocalOwners.clear();
      return new ContractCanonical.Fields(localFields.values());
    }

    Meter(long limit) {
      this(limit, Map.of(), null);
    }

    Meter(long limit, Map<String, Long> locals, Set<String> known) {
      this(limit, locals, known, true);
    }

    Meter(long limit, Map<String, Long> locals, Set<String> known, boolean recordCharges) {
      super(limit);
      this.recordCharges = recordCharges;
      integer(limit, 0, HOST_LIMIT);
      this.limit = limit;
      localLimits.putAll(locals);
      locals.forEach(
          (k, v) -> {
            token(k);
            integer(v, 0, limit);
            require(known == null || known.contains(k), "UNKNOWN_CAP_TARGET", "member", k);
          });
    }

    Map<String, Object> policy() {
      return m(
          "shared",
          limit,
          "local",
          localLimits,
          "profile",
          deterministic ? schedule.id() : GAS_PROFILE.get("profile"));
    }

    void hostDebit(long amount) {
      require(
          amount >= 0 && amount <= HOST_LIMIT - hostUsed,
          "HOST_INCOMPLETE",
          "resource",
          "native-admission-work");
      hostUsed += amount;
    }

    void debit(String namespace, String counter, long units, long amount) {
      if (calculation != null) {
        calculation.charge(namespace);
      }
      if (rejected != null) {
        throw new BexFailure("GAS_LIMIT_EXCEEDED", counter);
      }
      integer(units, 0, -1);
      integer(amount, 0, -1);
      long shared = limit - used;
      Long local =
          localLimits.containsKey(owner)
              ? localLimits.get(owner) - localUsed.getOrDefault(owner, 0L)
              : null;
      if (amount > shared || local != null && amount > local) {
        boolean localFirst = local != null && local < shared;
        rejected =
            new LinkedHashMap<>(
                m(
                    "namespace",
                    namespace,
                    "counter",
                    counter,
                    "units",
                    units,
                    "amount",
                    amount,
                    "owner",
                    owner));
        if (recordCharges) {
          rejected.put("ordinal", trace.size());
        }
        rejected.putAll(
            m(
                "remaining",
                localFirst ? local : shared,
                "cap",
                localFirst ? "LOCAL" : "SHARED",
                "sharedRemaining",
                shared,
                "localRemaining",
                local));
        throw new BexFailure("GAS_LIMIT_EXCEEDED", counter);
      }
      if (recordCharges) {
        trace.add(TRACE.record(trace.size(), namespace, counter, units, amount, owner));
      }
      used += amount;
      if (!owner.isEmpty()) {
        addLocalUsed(owner, amount);
      }
    }

    @Override
    boolean boundedHost() {
      return true;
    }

    @Override
    long remaining() {
      return Math.min(
          limit - used, localLimits.getOrDefault(owner, limit) - localUsed.getOrDefault(owner, 0L));
    }

    @Override
    public void charge(String counter, long units, long amount) {
      debit("bex", counter, units, amount);
    }

    @Override
    public long used() {
      return used;
    }

    @Override
    public long limit() {
      return limit;
    }

    @Override
    public List<Map<String, Object>> trace() {
      return List.copyOf(trace);
    }

    @Override
    public int traceSize() {
      return trace.size();
    }

    @Override
    public List<Map<String, Object>> traceFrom(int offset) {
      return List.copyOf(trace.subList(offset, trace.size()));
    }

    @Override
    public Map<String, Object> rejected() {
      return rejected;
    }

    boolean finalGas() {
      return !recordCharges;
    }

    Object commitment() {
      return recordCharges ? trace : m("used", used, "localUsed", localUsedEncoding());
    }

    // A scalar checkpoint in the new edition; a row offset for legacy replay only.
    long position() {
      return recordCharges ? trace.size() : used;
    }

    Object segment(long start) {
      if (!recordCharges) {
        return used - start;
      }
      var rows = new ArrayList<Object>();
      for (int i = Math.toIntExact(start); i < trace.size(); i++) {
        rows.add(relative(trace.get(i), i - Math.toIntExact(start)));
      }
      return rows;
    }

    String segmentField() {
      return recordCharges ? "workSegment" : "gas";
    }

    boolean canAdmit(long amount) {
      return rejected == null
          && amount >= 0
          && amount <= limit - used
          && (!localLimits.containsKey(owner)
              || amount <= localLimits.get(owner) - localUsed.getOrDefault(owner, 0L));
    }

    void mergeAdmitted(long amount) {
      require(!recordCharges, "AGGREGATE_REQUIRES_FINAL_GAS");
      integer(amount, 0, -1);
      require(
          amount <= limit - used
              && (!localLimits.containsKey(owner)
                  || amount <= localLimits.get(owner) - localUsed.getOrDefault(owner, 0L)),
          "RUNTIME_GAS_ALLOWANCE_CHANGED");
      used += amount;
      addLocalUsed(owner, amount);
    }

    void contracts(String counter) {
      contracts(counter, 1);
    }

    void contracts(String counter, long units) {
      if (semantic) {
        SemanticGas.contracts(this, counter, units);
      }
    }
  }

  static class Outcome {
    // Established local proof is retained by a caller's Result, not an unbounded processor cache.
    ContractCompletions.Remembered established;
    String status, inputIdentity, invocation;
    Snapshot snapshot;
    List<Object> publicEvents = new ArrayList<>(),
        events = new ArrayList<>(),
        work = new ArrayList<>(),
        changes = new ArrayList<>(),
        checkpoints = new ArrayList<>(),
        completions = new ArrayList<>(),
        rounds = new ArrayList<>(),
        units = new ArrayList<>();
    long gas;
    Map<String, Long> gasByMember = Map.of();
    List<Map<String, Object>> gasTrace = new ArrayList<>();
    Object rejected, diagnostic;
    private Map<String, Object> sealedObservations;
    private String sealedReceipt;

    Outcome(String status, Snapshot state, String input, String invocation) {
      this.status = status;
      snapshot = state;
      inputIdentity = input;
      this.invocation = invocation;
    }

    Map<String, Object> observations() {
      if (sealedObservations != null) {
        return sealedObservations;
      }
      var observation =
          m(
              "status",
              status,
              "input",
              inputIdentity,
              "output",
              snapshot.identity(),
              "invocation",
              invocation,
              "publicEvents",
              publicEvents,
              "events",
              events,
              "work",
              work,
              "changes",
              changes,
              "checkpoints",
              checkpoints,
              "gas",
              gas,
              "gasTrace",
              gasTrace,
              "rejected",
              rejected,
              "diagnostic",
              diagnostic,
              "completions",
              completions,
              "rounds",
              rounds,
              "units",
              units);
      if (snapshot.release.equals(ContractsEdition.DETERMINISTIC.specificationBlueId())) {
        observation.remove("gasTrace");
      }
      {
        observation.put("gasSchedule", snapshot.gasSchedule.id());
        observation.put("gasByMember", gasByMember);
      }
      if (snapshot.ownerLocal) {
        observation.put("executionBoundary", "owner");
      }
      return observation;
    }

    private String receiptDomain() {
      return snapshot.ownerLocal ? "contracts.local-result/1" : "contracts.result/completion-1";
    }

    String receipt() {
      return sealedReceipt != null ? sealedReceipt : digest(receiptDomain(), observations());
    }

    /** Called only after execution/restoration has finalized every semantic field. */
    void seal() {
      if (sealedObservations != null) {
        return;
      }
      sealedObservations = obj(ContractWire.immutable(observations()));
      sealedReceipt = digest(receiptDomain(), sealedObservations);
    }
  }
}
