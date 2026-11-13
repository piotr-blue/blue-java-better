package blue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * An explicitly installed native step for canonical Contracts. Its type must extend
 * Contracts/Contract. Its semantics document identifies the behavior promised by trusted host code;
 * an identity does not prove that Java implements that behavior.
 *
 * <p>Handlers must be deterministic, thread-safe and free of external effects, clocks and
 * randomness. They record patches and events through an invocation-scoped context. Those effects
 * participate in the enclosing workflow's atomic commit. Importing a package never installs a
 * handler.
 */
public final class NativeStep {
  /** A deterministic implementation, safe to share between independent sessions. */
  @FunctionalInterface
  public interface Handler {
    /**
     * Executes one step. Do not retain the context or pass it to another thread.
     *
     * @param context immutable inputs, metered reads and buffered effects
     */
    void execute(Context context);
  }

  private final LanguageDocument type;
  private final LanguageDocument semantics;
  private final ContractNative.Binding binding;
  private final ContractNative.Implementation implementation;

  /** Exact bundled protocols use their published tariff instead of the generic callback tariff. */
  NativeStep(
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

  private NativeStep(Builder builder) {
    type = builder.type;
    semantics = builder.semantics;
    binding =
        Contracts.boundary(
            () ->
                new ContractNative.Binding(
                    type.blueId().value(),
                    semantics.blueId().value(),
                    "step",
                    builder.headerFields,
                    builder.bodyFields,
                    builder.namespace,
                    builder.tariff,
                    builder.inherited,
                    List.of(),
                    "live"));
    Handler handler = Objects.requireNonNull(builder.handler, "A handler is required");
    implementation =
        new ContractNative.Implementation() {
          @Override
          public Object execute(ContractNative.Context input) {
            Context context = new Context(input);
            try {
              // Every admitted callback has an explicit, nonzero minimum tariff.
              input.gas().charge("invocation");
              handler.execute(context);
              return context.result();
            } finally {
              context.close();
            }
          }
        };
  }

  /**
   * Creates a caller-confined builder with an invocation tariff of one unit.
   *
   * @param type exact step definition and its evidence
   * @param semantics exact behavior definition and its evidence
   * @param namespace unique gas counter namespace, other than bex or contracts
   * @return new builder
   */
  public static Builder newBuilder(
      LanguageDocument type, LanguageDocument semantics, String namespace) {
    return new Builder(type, semantics, namespace);
  }

  /**
   * Returns the exact registered step type.
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
    private List<String> bodyFields = List.of();
    private final Map<String, Long> tariff = new LinkedHashMap<>(Map.of("invocation", 1L));
    private boolean inherited;
    private Handler handler;

    private Builder(LanguageDocument type, LanguageDocument semantics, String namespace) {
      this.type = Objects.requireNonNull(type);
      this.semantics = Objects.requireNonNull(semantics);
      this.namespace = Objects.requireNonNull(namespace);
    }

    /**
     * Declares eagerly admitted fields, excluding standard metadata and type.
     *
     * @param names distinct header fields
     * @return this builder
     */
    public Builder headerFields(String... names) {
      headerFields = List.of(names);
      return this;
    }

    /**
     * Declares body fields, resolved only when the step executes.
     *
     * @param names distinct body fields, disjoint from header fields
     * @return this builder
     */
    public Builder bodyFields(String... names) {
      bodyFields = List.of(names);
      return this;
    }

    /**
     * Adds a deterministic charge, or changes its cost. The automatic invocation cost must stay
     * positive. Handlers charge any other declared counters explicitly.
     *
     * @param name counter name
     * @param cost nonnegative units per occurrence
     * @return this builder
     */
    public Builder counter(String name, long cost) {
      Objects.requireNonNull(name);
      if (cost < 0 || name.equals("invocation") && cost == 0) {
        throw new IllegalArgumentException("Invalid native counter cost");
      }
      tariff.put(name, cost);
      return this;
    }

    /**
     * Allows this binding to implement subtypes too. The default binds only the exact type.
     *
     * @param value whether subtype execution is supported
     * @return this builder
     */
    public Builder inherited(boolean value) {
      inherited = value;
      return this;
    }

    /**
     * Supplies the trusted deterministic implementation.
     *
     * @param value thread-safe handler
     * @return this builder
     */
    public Builder execute(Handler value) {
      handler = Objects.requireNonNull(value);
      return this;
    }

    /**
     * Builds an immutable registration. Type ancestry is verified when installing it in a runtime.
     *
     * @return independent registration
     */
    public NativeStep build() {
      return new NativeStep(this);
    }
  }

  /**
   * One callback's authority. Every method rejects calls after return or from another thread. Reads
   * observe the step's input; queued patches become visible to the next workflow step. JSON-like
   * inputs are immutable; null and Bex.undefined() remain distinct.
   */
  public static final class Context {
    private final long thread = Thread.currentThread().threadId();
    private ContractNative.Context input;
    private final NativeValues values = new NativeValues();
    private List<Object> patches = new ArrayList<>();
    private List<Object> events = new ArrayList<>();
    private Map<String, Object> termination;

    private Context(ContractNative.Context input) {
      this.input = input;
    }

    /**
     * Returns immutable admitted header fields.
     *
     * @return immutable admitted header fields
     */
    public Map<String, Object> header() {
      return values.object(input().header());
    }

    /**
     * Returns immutable resolved body fields.
     *
     * @return immutable resolved body fields
     */
    public Map<String, Object> body() {
      return values.object(input().body());
    }

    /**
     * Returns immutable event delivered to this workflow.
     *
     * @return immutable event delivered to this workflow
     */
    public Object event() {
      return values.freeze(input().event());
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
     * Returns immutable preceding named step results.
     *
     * @return immutable preceding named step results
     */
    public Map<String, Object> steps() {
      return values.object(input().steps());
    }

    /**
     * Returns immutable operation call, or null outside an operation.
     *
     * @return immutable operation call, or null outside an operation
     */
    public Object call() {
      return values.freeze(input().call());
    }

    /**
     * Returns host-supplied deterministic timestamp, or null during initialization.
     *
     * @return host-supplied deterministic timestamp, or null during initialization
     */
    public Long timestamp() {
      return input().timestamp();
    }

    /**
     * Reads the document through the same logical and host-pinned view used by BEX. Native read
     * cost is the responsibility of the declared tariff; BEX evaluation charges its own standard
     * gas.
     *
     * @param pointer absolute document pointer, empty for the root
     * @return immutable selected value, or Bex.undefined() when absent
     */
    public Object read(String pointer) {
      // Project the selected exact view once, while the output budget can still stop expansion.
      return values.freeze(input().read().apply(pointer));
    }

    /**
     * Evaluates a read-only BEX expression with the workflow bindings and shared gas budget.
     *
     * @param expression JSON-like expression
     * @return immutable expression value
     */
    public Object evaluate(Object expression) {
      return values.freeze(input().evaluate().apply(expression));
    }

    /**
     * Charges a declared counter in the current invocation's shared gas budget.
     *
     * @param counter declared name
     * @param occurrences nonnegative occurrence count
     */
    public void charge(String counter, long occurrences) {
      input().gas().charge(counter, occurrences);
    }

    /**
     * Returns remaining shared allowance at this native meter.
     *
     * @return remaining shared allowance at this native meter
     */
    public long remainingGas() {
      return input().gas().remaining();
    }

    /**
     * Queues addition at a non-root pointer, using canonical Contracts patch rules.
     *
     * @param pointer patch destination
     * @param value JSON-like value, copied immediately
     */
    public void add(String pointer, Object value) {
      patch("add", pointer, value);
    }

    /**
     * Queues replacement at a non-root pointer.
     *
     * @param pointer existing destination
     * @param value JSON-like value, copied immediately
     */
    public void replace(String pointer, Object value) {
      patch("replace", pointer, value);
    }

    /**
     * Queues removal at a non-root pointer.
     *
     * @param pointer existing destination
     */
    public void remove(String pointer) {
      patch("remove", pointer, null);
    }

    /**
     * Queues an ordinary document event; listeners run under the same atomic invocation.
     *
     * @param event JSON-like event, copied immediately
     */
    public void emit(Object event) {
      input();
      events.add(BexValues.freeze(event));
    }

    /**
     * Requests termination after the queued patches and events.
     *
     * @param cause deterministic cause
     * @param reason optional reason, or null
     */
    public void terminate(String cause, String reason) {
      input();
      if (termination != null) {
        throw new IllegalStateException("Termination already requested");
      }
      termination =
          reason == null ? Map.of("cause", cause) : Map.of("cause", cause, "reason", reason);
    }

    /**
     * Rejects this invocation with a deterministic diagnostic. No queued effects are published.
     *
     * @param reason application-readable rejection reason
     * @throws IllegalStateException when called outside the callback
     */
    public void reject(String reason) {
      input();
      throw new ContractModel.Error(
          "NATIVE_STEP_REJECTED", "reason", Objects.requireNonNull(reason));
    }

    private void patch(String operation, String pointer, Object value) {
      input();
      Pointer path = Pointer.parse(pointer);
      if (path.segments().isEmpty()) {
        throw new IllegalArgumentException("Root patch is not supported");
      }
      Map<String, Object> patch = new LinkedHashMap<>();
      patch.put("op", operation);
      patch.put("path", pointer);
      if (!operation.equals("remove")) {
        patch.put("val", BexValues.freeze(value));
      }
      patches.add(patch);
    }

    private ContractNative.Context input() {
      if (input == null || thread != Thread.currentThread().threadId()) {
        throw new IllegalStateException("Native context is outside its invocation");
      }
      return input;
    }

    private Object result() {
      Map<String, Object> result = new LinkedHashMap<>();
      result.put("patches", patches);
      result.put("events", events);
      if (termination != null) {
        result.put("termination", termination);
      }
      return result;
    }

    private void close() {
      input = null;
      values.clear();
      patches = null;
      events = null;
      termination = null;
    }
  }
}
