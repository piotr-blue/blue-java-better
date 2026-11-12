package blue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * An explicitly installed deterministic external channel. Its exact type must extend
 * Contracts/Channel. An accepted event enters the ordinary checkpoint, operation and workflow
 * machinery; the callback cannot patch the document or emit events.
 *
 * <p>Handlers are trusted Java code: they must be deterministic, thread-safe and free of external
 * effects, clocks and randomness. They must charge their declared work. The runtime does not
 * preempt arbitrary Java computation. A semantics identity records the implementation's promise; it
 * does not prove that the callback implements an entire published protocol.
 */
public final class NativeChannel {
  /** A deterministic decision, safe to share between independent sessions. */
  @FunctionalInterface
  public interface Handler {
    /**
     * Tests an incoming event before ordinary channel checkpoint deduplication.
     *
     * @param context immutable inputs and the invocation's gas allowance
     * @return whether this channel accepts the event
     */
    boolean accept(Context context);
  }

  /** Verifies one type's evidence before any external channel is selected. */
  @FunctionalInterface
  public interface AdmissionVerifier {
    /**
     * Returns a deterministic verified projection, or rejects the complete invocation.
     *
     * @param context snapshot-bound inputs and the invocation's gas allowance
     * @return immutable-compatible JSON-like fields available to this type's channel handlers
     */
    Map<String, Object> verify(NativeAdmission context);
  }

  /**
   * The last event consumed by this channel generation.
   *
   * @param eventId exact consumed event identity
   * @param timestamp logical timestamp of that event
   */
  public record Checkpoint(BlueId eventId, long timestamp) {
    /** Requires an exact event identity. */
    public Checkpoint {
      Objects.requireNonNull(eventId);
    }
  }

  /** Result of looking up a declared peer channel. */
  public enum PeerStatus {
    /** No declaration exists under the selected name. */
    ABSENT,
    /** A declaration exists but is not a channel. */
    NON_CHANNEL,
    /** The peer is a channel; its admitted header is available. */
    PRESENT
  }

  /**
   * Immutable peer information, distinct from a channel's execution state.
   *
   * @param status whether the declaration exists and is a channel
   * @param header immutable admitted header, empty unless the peer is present
   */
  public record Peer(PeerStatus status, Map<String, Object> header) {
    /** Copies header containers and validates the status. */
    @SuppressWarnings("unchecked")
    public Peer {
      Objects.requireNonNull(status);
      header = (Map<String, Object>) BexValues.freeze(Objects.requireNonNull(header));
      if (status != PeerStatus.PRESENT && !header.isEmpty()) {
        throw new IllegalArgumentException("Only a present peer has a header");
      }
    }
  }

  private final LanguageDocument type;
  private final LanguageDocument semantics;
  private final ContractNative.Binding binding;
  private final ContractNative.Implementation implementation;

  /** Exact bundled protocols use their published tariffs, not the generic callback tariff. */
  NativeChannel(
      LanguageDocument type,
      LanguageDocument semantics,
      ContractNative.Binding binding,
      ContractNative.Implementation implementation) {
    this.type = Objects.requireNonNull(type);
    this.semantics = Objects.requireNonNull(semantics);
    this.binding = Objects.requireNonNull(binding);
    this.implementation = Objects.requireNonNull(implementation);
    if (!type.blueId().value().equals(binding.typeId())
        || !semantics.blueId().value().equals(binding.semanticsId())) {
      throw new IllegalArgumentException("Registration content disagrees with its binding");
    }
  }

  private NativeChannel(Builder builder) {
    type = builder.type;
    semantics = builder.semantics;
    binding =
        Contracts.boundary(
            () ->
                new ContractNative.Binding(
                    type.blueId().value(),
                    semantics.blueId().value(),
                    "external-channel",
                    builder.headerFields,
                    List.of(),
                    builder.namespace,
                    builder.tariff,
                    builder.inherited,
                    builder.peerFields,
                    "live"));
    Handler handler = Objects.requireNonNull(builder.handler, "A handler is required");
    AdmissionVerifier verifier = builder.verifier;
    implementation =
        new ContractNative.Implementation() {
          @Override
          public boolean verifiesAdmission() {
            return verifier != null;
          }

          @Override
          public Map<String, Object> verifyAdmission(ContractNative.AdmissionContext input) {
            NativeAdmission context = new NativeAdmission(input);
            try {
              input.gas().charge("admission");
              ContractModel.require(input.evidence() != null, "MISSING_ADMISSION_EVIDENCE");
              return context.verified(Objects.requireNonNull(verifier.verify(context)));
            } finally {
              context.close();
            }
          }

          @Override
          public boolean accept(ContractNative.ExternalContext input) {
            Context context = new Context(input);
            try {
              input.gas().charge("invocation");
              return handler.accept(context);
            } finally {
              context.close();
            }
          }
        };
  }

  /**
   * Creates a caller-confined builder with a minimum invocation tariff of one unit.
   *
   * @param type exact channel definition with retained evidence
   * @param semantics exact behavior definition with retained evidence
   * @param namespace distinct gas namespace, other than bex or contracts
   * @return new builder
   */
  public static Builder newBuilder(
      LanguageDocument type, LanguageDocument semantics, String namespace) {
    return new Builder(type, semantics, namespace);
  }

  /**
   * Returns the exact implemented channel type.
   *
   * @return type identity
   */
  public BlueId typeId() {
    return type.blueId();
  }

  /**
   * Returns the exact promised behavior.
   *
   * @return semantics identity
   */
  public BlueId semanticsId() {
    return semantics.blueId();
  }

  void install(ContractProcessor processor) {
    processor.blue.context.install(type);
    processor.blue.context.install(semantics);
    processor.blue.refresh();
    processor.installRuntime(binding, implementation);
  }

  /** Mutable configuration; built registrations retain independent snapshots. */
  public static final class Builder {
    private final LanguageDocument type;
    private final LanguageDocument semantics;
    private final String namespace;
    private List<String> headerFields = List.of();
    private List<String> peerFields = List.of();
    private final Map<String, Long> tariff = new LinkedHashMap<>(Map.of("invocation", 1L));
    private boolean inherited;
    private Handler handler;
    private AdmissionVerifier verifier;

    private Builder(LanguageDocument type, LanguageDocument semantics, String namespace) {
      this.type = Objects.requireNonNull(type);
      this.semantics = Objects.requireNonNull(semantics);
      this.namespace = Objects.requireNonNull(namespace);
    }

    /**
     * Declares eagerly admitted fields, excluding standard type and metadata fields.
     *
     * @param names distinct header fields
     * @return this builder
     */
    public Builder headerFields(String... names) {
      headerFields = List.of(names);
      return this;
    }

    /**
     * Declares header fields containing lists of peer channel names. Only these named peers may be
     * queried.
     *
     * @param names distinct fields also declared by headerFields
     * @return this builder
     */
    public Builder peerFields(String... names) {
      peerFields = List.of(names);
      return this;
    }

    /**
     * Adds a deterministic work counter. The automatic invocation cost must stay positive.
     *
     * @param name counter name
     * @param cost nonnegative units per occurrence
     * @return this builder
     */
    public Builder counter(String name, long cost) {
      Objects.requireNonNull(name);
      if (cost < 0
          || name.equals("invocation") && cost == 0
          || verifier != null && name.equals("admission") && cost == 0) {
        throw new IllegalArgumentException("Invalid native counter cost");
      }
      tariff.put(name, cost);
      return this;
    }

    /**
     * Allows this binding to accept events for subtypes. The default binds only the exact type.
     *
     * @param value whether subtypes are supported
     * @return this builder
     */
    public Builder inherited(boolean value) {
      inherited = value;
      return this;
    }

    /**
     * Supplies the trusted deterministic implementation.
     *
     * @param value thread-safe decision function
     * @return this builder
     */
    public Builder accept(Handler value) {
      handler = Objects.requireNonNull(value);
      return this;
    }

    /**
     * Builds an immutable registration. Type ancestry is verified during runtime installation.
     *
     * @return independent registration
     */
    public NativeChannel build() {
      return new NativeChannel(this);
    }

    /**
     * Requires evidence verified once per channel type, before channel selection. Admission charges
     * the root's shared invocation allowance; the automatic admission tariff is at least one.
     * Changing this protocol requires a corresponding semantics identity.
     *
     * @param value deterministic, thread-safe verifier with no external effects
     * @return this builder
     */
    public Builder verifyAdmission(AdmissionVerifier value) {
      if (Long.valueOf(0).equals(tariff.get("admission"))) {
        throw new IllegalArgumentException("Positive admission tariff required");
      }
      verifier = Objects.requireNonNull(value);
      tariff.putIfAbsent("admission", 1L);
      return this;
    }
  }

  /**
   * One decision's read-only inputs. Every method rejects use after return or from another thread.
   * Returned immutable values may be retained; the context and its metering authority may not.
   */
  public static final class Context {
    private final long thread = Thread.currentThread().threadId();
    private ContractNative.ExternalContext input;
    private final NativeValues values = new NativeValues();

    private Context(ContractNative.ExternalContext input) {
      this.input = input;
    }

    /**
     * Returns immutable admitted header fields.
     *
     * @return header fields
     */
    public Map<String, Object> header() {
      return values.object(input().header());
    }

    /**
     * Returns the immutable incoming event, unchanged for ordinary workflow evaluation.
     *
     * @return incoming event
     */
    public Object event() {
      return values.freeze(input().event());
    }

    /**
     * Returns only the installed verifier's projection, never the supplied unverified evidence.
     *
     * @return verified fields, empty for a channel with no admission verifier
     */
    public Map<String, Object> admission() {
      Object evidence = input().evidence();
      return evidence == null ? Map.of() : values.object(ContractModel.obj(evidence));
    }

    /**
     * Selects an event field without materializing unrelated fields. Output bounds apply to the
     * selected value; native read work remains the responsibility of the declared tariff.
     *
     * @param pointer RFC 6901 pointer; empty selects the complete event
     * @return detached immutable value, or Bex.undefined() when absent
     */
    public Object event(String pointer) {
      return values.select(input().event(), pointer);
    }

    /**
     * Returns the explicit logical timestamp supplied by the caller.
     *
     * @return logical timestamp
     */
    public long timestamp() {
      return input().timestamp();
    }

    /**
     * Returns the current channel name within its document.
     *
     * @return channel name
     */
    public String channel() {
      return input().channel();
    }

    /**
     * Returns the input document's exact local or canonical identity, according to the processor.
     *
     * @return input document identity
     */
    public BlueId documentId() {
      return new BlueId(input().document());
    }

    /**
     * Returns the channel lifetime generation; redeclaration may start a new generation.
     *
     * @return channel generation
     */
    public long generation() {
      return input().generation();
    }

    /**
     * Returns the previous consumed event, or empty before this channel consumes an event.
     *
     * @return previous channel checkpoint
     */
    public Optional<Checkpoint> checkpoint() {
      Object checkpoint = input().checkpoint();
      if (checkpoint == null) {
        return Optional.empty();
      }
      Map<String, Object> value = ContractModel.obj(checkpoint);
      return Optional.of(
          new Checkpoint(
              new BlueId(ContractModel.str(ContractModel.obj(value.get("event")).get("blueId"))),
              ContractModel.integer(value.get("timestamp"))));
    }

    /**
     * Reads a peer named by a declared peer field. The argument is the channel name, not the field
     * name. Undeclared lookups fail the invocation.
     *
     * @param channel channel name authorized by a peer field
     * @return immutable peer status and header
     */
    public Peer peer(String channel) {
      Map<String, Object> peer =
          ContractModel.obj(input().peers().apply(Objects.requireNonNull(channel)));
      PeerStatus status =
          switch (ContractModel.str(peer.get("status"))) {
            case "absent" -> PeerStatus.ABSENT;
            case "non-channel" -> PeerStatus.NON_CHANNEL;
            case "present" -> PeerStatus.PRESENT;
            default -> throw new IllegalStateException("Unknown peer status");
          };
      return new Peer(
          status, values.object(ContractModel.obj(peer.getOrDefault("header", Map.of()))));
    }

    /**
     * Charges a declared counter in the invocation's shared gas budget.
     *
     * @param counter declared counter name
     * @param occurrences nonnegative occurrence count
     */
    public void charge(String counter, long occurrences) {
      input().gas().charge(counter, occurrences);
    }

    /**
     * Returns remaining shared gas at this decision.
     *
     * @return remaining allowance
     */
    public long remainingGas() {
      return input().gas().remaining();
    }

    private ContractNative.ExternalContext input() {
      if (input == null || thread != Thread.currentThread().threadId()) {
        throw new IllegalStateException("Native context is outside its invocation");
      }
      return input;
    }

    private void close() {
      input = null;
      values.clear();
    }
  }
}
