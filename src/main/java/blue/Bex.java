package blue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Canonical BEX programs. Compilation checks static structure and function calls once; compiled
 * programs and immutable inputs are safe to share between threads. Every execution owns its gas,
 * variables and accumulated effects. Effects are data until a Contracts workflow applies them.
 */
public final class Bex {
  private final Language language;
  private final BexKernel kernel;
  private final long gasLimit;

  private Bex(Builder builder) {
    language = builder.language;
    gasLimit = builder.gasLimit;
    kernel = new BexKernel(builder.intrinsics);
  }

  /**
   * Returns the current canonical evaluator.
   *
   * @return the current canonical evaluator
   */
  public static Bex create() {
    return newBuilder().build();
  }

  /**
   * Returns a caller-confined configuration builder.
   *
   * @return a caller-confined configuration builder
   */
  public static Builder newBuilder() {
    return new Builder();
  }

  /**
   * Returns a caller-confined invocation input builder.
   *
   * @return a caller-confined invocation input builder
   */
  public static Inputs inputs() {
    return new Inputs();
  }

  /**
   * Returns the absence sentinel, distinct from Java null (BEX null).
   *
   * @return the absence sentinel, distinct from Java null (BEX null)
   */
  public static Object undefined() {
    return BexValues.UNDEFINED;
  }

  /**
   * Returns exact implemented BEX rules.
   *
   * @return exact implemented BEX rules
   */
  public BlueId rulesId() {
    return new BlueId(BexEdition.RULES);
  }

  /**
   * Returns the verified canonical BEX package.
   *
   * @return the verified canonical BEX package
   */
  public static BluePackage publication() {
    return ProtocolPublications.bex();
  }

  /**
   * Compiles authored YAML; a program has expr, do, or functions plus entry.
   *
   * @param yaml program source
   * @return reusable compiled program
   */
  public Program compileYaml(String yaml) {
    return Contracts.boundary(
        () -> compile(ContractModel.obj(ContractWire.data(ContractWire.parse(yaml, "yaml")))));
  }

  /**
   * Compiles a JSON program.
   *
   * @param json program source
   * @return reusable compiled program
   */
  public Program compileJson(String json) {
    return Contracts.boundary(
        () -> compile(ContractModel.obj(ContractWire.data(ContractWire.parse(json, "json")))));
  }

  /**
   * Copies and compiles a JSON-like program. Later mutation of source has no effect.
   *
   * @param source program data
   * @return reusable compiled program
   */
  public Program compile(Map<String, Object> source) {
    return Contracts.boundary(() -> new Program(kernel.compile(resolveTypes(source))));
  }

  /**
   * Compiles with shared constants/functions and an optional selected entry. Local definitions win.
   *
   * @param source program data
   * @param definition shared definition
   * @param entry selected no-argument function, or null for the program's selection
   * @return reusable compiled program
   */
  public Program compile(Map<String, Object> source, Map<String, Object> definition, String entry) {
    return Contracts.boundary(
        () -> new Program(kernel.compile(resolveTypes(source), resolveTypes(definition), entry)));
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> resolveTypes(Map<String, Object> source) {
    if (source == null) {
      return null;
    }
    Map<String, String> aliases = language.context().aliases;
    return (Map<String, Object>) resolveTypes(BexValues.freeze(source), aliases);
  }

  private static Object resolveTypes(Object value, Map<String, String> aliases) {
    if (value instanceof List<?> list) {
      return list.stream().map(v -> resolveTypes(v, aliases)).toList();
    }
    if (!(value instanceof Map<?, ?> map)) {
      return value;
    }
    Map<String, Object> result = new java.util.LinkedHashMap<>();
    map.forEach(
        (key, child) -> {
          if (!(key instanceof String name)) {
            throw new BexFailure("BEX_COMPILE_ERROR", "Text object keys required");
          }
          if (LanguageCore.TYPES.contains(name) && child instanceof String alias) {
            String id = aliases.get(alias);
            if (id == null) {
              throw new BexFailure("BEX_COMPILE_ERROR", "Unknown type alias: " + alias);
            }
            result.put(name, Map.of("blueId", id));
          } else {
            result.put(name, resolveTypes(child, aliases));
          }
        });
    return result;
  }

  /** A host intrinsic must be deterministic and charge before performing bounded work. */
  @FunctionalInterface
  public interface Intrinsic {
    /**
     * Evaluates an exact-type intrinsic.
     *
     * @param payload evaluated intrinsic fields
     * @param gas live invocation meter
     * @return a BEX value
     */
    Object evaluate(Map<String, Object> payload, Meter gas);
  }

  /** Restricted live meter for explicitly installed host capabilities. */
  @FunctionalInterface
  public interface Meter {
    /**
     * Admits a host charge, throwing before the work if the limit would be exceeded.
     *
     * @param amount nonnegative gas
     */
    void charge(long amount);
  }

  /** Configuration builder; built evaluators do not retain this mutable builder. */
  public static final class Builder {
    private Language language = Language.create();
    private long gasLimit = 10_000_000;
    private final Map<String, BexEngine.Intrinsic> intrinsics = new HashMap<>();

    private Builder() {}

    /**
     * Sets canonical matching and identity environment.
     *
     * @param value canonical matching and identity environment
     * @return this builder
     */
    public Builder language(Language value) {
      language = Objects.requireNonNull(value);
      return this;
    }

    /**
     * Sets nonnegative invocation budget.
     *
     * @param value nonnegative invocation budget
     * @return this builder
     */
    public Builder gasLimit(long value) {
      if (value < 0) {
        throw new IllegalArgumentException("Negative gas limit");
      }
      gasLimit = value;
      return this;
    }

    /**
     * Installs one deterministic intrinsic by exact type identity.
     *
     * @param type intrinsic type
     * @param implementation host implementation
     * @return this builder
     */
    public Builder intrinsic(BlueId type, Intrinsic implementation) {
      Objects.requireNonNull(implementation);
      if (intrinsics.putIfAbsent(
              type.value(),
              (payload, gas) ->
                  implementation.evaluate(payload, amount -> gas.amount("host", amount)))
          != null) {
        throw new IllegalArgumentException("Duplicate intrinsic: " + type);
      }
      return this;
    }

    /**
     * Returns an immutable evaluator.
     *
     * @return an immutable evaluator
     */
    public Bex build() {
      return new Bex(this);
    }
  }

  /** A reusable validated program, bound to this evaluator's exact intrinsic set. */
  public final class Program {
    private final BexKernel.Compiled compiled;

    private Program(BexKernel.Compiled compiled) {
      this.compiled = compiled;
    }

    /**
     * Returns statically encountered operators, sorted without duplicates.
     *
     * @return statically encountered operators, sorted without duplicates
     */
    public List<String> operators() {
      return compiled.operatorInventory();
    }

    /**
     * Returns diagnostics about local definitions shadowing shared functions or constants.
     *
     * @return diagnostics about local definitions shadowing shared functions or constants
     */
    public List<String> warnings() {
      return compiled.warnings();
    }

    /**
     * Returns an evaluation with empty inputs.
     *
     * @return an evaluation with empty inputs
     */
    public Result execute() {
      return execute(inputs().build());
    }

    /**
     * Runs this program with isolated variables, effects, and gas.
     *
     * @param input immutable invocation input
     * @return value, effects, admitted gas and any failure
     */
    public Result execute(Input input) {
      Objects.requireNonNull(input);
      ContractModel.Meter gas = new ContractModel.Meter(gasLimit, Map.of(), null, false);
      try {
        LanguageAccess access = new LanguageAccess(gas);
        BexContext context =
            new BexContext(
                input.document,
                input.event,
                input.currentContract,
                input.resolvedDocument,
                input.steps,
                input.bindings,
                input.scope,
                null,
                access::matches,
                access::identity);
        BexEngine.Result result = kernel.execute(compiled, context, gas);
        return new Result(
            result.value(),
            result.changeset(),
            result.events(),
            gas.used(),
            null,
            null,
            gas.rejected());
      } catch (BexFailure error) {
        return new Result(
            undefined(),
            List.of(),
            List.of(),
            gas.used(),
            error.code(),
            error.getMessage(),
            gas.rejected());
      } catch (ContractFault error) {
        return new Result(
            undefined(),
            List.of(),
            List.of(),
            gas.used(),
            error.code(),
            error.getMessage(),
            gas.rejected());
      }
    }
  }

  /** Per-evaluation capabilities are allocated only when an expression actually uses Language. */
  private final class LanguageAccess {
    private final ContractModel.Meter meter;
    private ContractBridge bridge;
    private ContractWork logical;

    LanguageAccess(ContractModel.Meter meter) {
      this.meter = meter;
    }

    private ContractBridge bridge() {
      if (bridge == null) {
        bridge = new ContractBridge(language);
      }
      return bridge;
    }

    boolean matches(Object value, Object pattern) {
      return bridge().matches(value, pattern, null);
    }

    String identity(Object value, BexGas gas) {
      if (logical == null) {
        logical = new ContractWork(bridge(), meter);
      }
      return logical.nodeBlueId(value, gas);
    }
  }

  /** Immutable invocation inputs; build once and reuse safely. */
  public static final class Input {
    private final Object document, event, currentContract, resolvedDocument;
    private final Map<String, Object> steps, bindings;
    private final String scope;

    @SuppressWarnings("unchecked")
    private Input(Inputs builder) {
      document = BexValues.freeze(builder.document);
      event = BexValues.freeze(builder.event);
      currentContract = BexValues.freeze(builder.currentContract);
      resolvedDocument = BexValues.freeze(builder.resolvedDocument);
      steps = (Map<String, Object>) BexValues.freeze(builder.steps);
      bindings = (Map<String, Object>) BexValues.freeze(builder.bindings);
      scope = builder.scope;
    }
  }

  /** Builder for immutable invocation inputs. */
  public static final class Inputs {
    private Object document = Map.of(),
        event = Map.of(),
        currentContract = Map.of(),
        resolvedDocument = undefined();
    private Map<String, Object> steps = Map.of(), bindings = Map.of();
    private String scope = "/";

    private Inputs() {}

    /**
     * Sets document data.
     *
     * @param value document data
     * @return this builder
     */
    public Inputs document(Object value) {
      document = value;
      return this;
    }

    /**
     * Sets event data.
     *
     * @param value event data
     * @return this builder
     */
    public Inputs event(Object value) {
      event = value;
      return this;
    }

    /**
     * Sets active contract data.
     *
     * @param value active contract data
     * @return this builder
     */
    public Inputs currentContract(Object value) {
      currentContract = value;
      return this;
    }

    /**
     * Sets resolved document data.
     *
     * @param value resolved document data
     * @return this builder
     */
    public Inputs resolvedDocument(Object value) {
      resolvedDocument = value;
      return this;
    }

    /**
     * Sets earlier named step results.
     *
     * @param value earlier named step results
     * @return this builder
     */
    public Inputs steps(Map<String, Object> value) {
      steps = value;
      return this;
    }

    /**
     * Sets named runtime bindings.
     *
     * @param value named runtime bindings
     * @return this builder
     */
    public Inputs bindings(Map<String, Object> value) {
      bindings = value;
      return this;
    }

    /**
     * Sets current contract scope.
     *
     * @param value current contract scope
     * @return this builder
     */
    public Inputs scope(String value) {
      scope = Objects.requireNonNull(value);
      return this;
    }

    /**
     * Returns immutable inputs independent of later changes to source containers.
     *
     * @return immutable inputs independent of later changes to source containers
     */
    public Input build() {
      return Contracts.boundary(() -> new Input(this));
    }
  }

  /**
   * Completed evaluation. A failed evaluation never exposes partial effects.
   *
   * @param value BEX result, including the undefined sentinel
   * @param changeset accumulated changes
   * @param events accumulated events
   * @param gas admitted gas
   * @param failure stable failure category, or null on success
   * @param message failure detail, or null
   * @param rejected first rejected charge, or null
   */
  public record Result(
      Object value,
      List<Object> changeset,
      List<Object> events,
      long gas,
      String failure,
      String message,
      Map<String, Object> rejected) {
    /** Copies caller-supplied containers into immutable result data. */
    @SuppressWarnings("unchecked")
    public Result {
      if (gas < 0) {
        throw new IllegalArgumentException("Negative admitted gas");
      }
      value = BexValues.freeze(value);
      changeset = (List<Object>) BexValues.freeze(Objects.requireNonNull(changeset));
      events = (List<Object>) BexValues.freeze(Objects.requireNonNull(events));
      if (rejected != null) {
        rejected = (Map<String, Object>) ContractData.freeze(rejected);
      }
    }

    /**
     * Returns whether evaluation completed successfully.
     *
     * @return whether evaluation completed successfully
     */
    public boolean succeeded() {
      return failure == null;
    }

    /**
     * Returns whether the result represents absence rather than null.
     *
     * @return whether the result represents absence rather than null
     */
    public boolean isUndefined() {
      return value == undefined();
    }

    /**
     * Returns this result.
     *
     * @return this result
     * @throws BlueException on evaluation failure
     */
    public Result requireSuccess() {
      if (!succeeded()) {
        throw new BlueException(failure, message);
      }
      return this;
    }
  }
}
