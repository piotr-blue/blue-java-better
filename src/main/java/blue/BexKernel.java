package blue;

import java.util.*;

/**
 * Small native, thread-safe BEX entry point. No I/O, dependency acquisition or implicit matcher.
 */
@SuppressWarnings({"unchecked", "serial", "try"})
final class BexKernel {
  private final BexEdition profile;
  private final BexEngine engine;
  private final Map<String, BexEngine.Intrinsic> intrinsics;

  public BexKernel(BexEdition profile) {
    this(profile, Map.of());
  }

  public BexKernel(BexEdition profile, Map<String, BexEngine.Intrinsic> intrinsics) {
    this.profile = Objects.requireNonNull(profile);
    this.intrinsics = Map.copyOf(intrinsics);
    engine = new BexEngine(this.intrinsics);
  }

  public BexEdition profile() {
    return profile;
  }

  public static final class Compiled {
    private final BexPlanCompiler.Program program;
    private final BexKernel owner;
    private final List<String> warnings;

    private Compiled(BexPlanCompiler.Program program, BexKernel owner, List<String> warnings) {
      this.program = program;
      this.owner = owner;
      this.warnings = List.copyOf(warnings);
    }

    public List<String> operatorInventory() {
      return program.operatorInventory();
    }

    public List<String> warnings() {
      return warnings;
    }

    public Map<String, Object> effectiveSource() {
      return program.source();
    }

    public BexEdition profile() {
      return owner.profile;
    }
  }

  public Compiled compile(Map<String, Object> source) {
    return compile(source, null, null);
  }

  public Compiled compile(
      Map<String, Object> source, Map<String, Object> definition, String entry) {
    var warnings = new ArrayList<String>();
    if (definition != null)
      for (String field : List.of("functions", "constants")) {
        if (source.get(field) instanceof Map<?, ?> local
            && definition.get(field) instanceof Map<?, ?> shared)
          local.keySet().stream()
              .filter(shared::containsKey)
              .map(Object::toString)
              .sorted()
              .forEach(
                  k ->
                      warnings.add(
                          "Local "
                              + field
                              + "/"
                              + k
                              + " overrides shared definition, including internal calls"));
      }
    try {
      return new Compiled(
          new BexPlanCompiler(intrinsics.keySet()).compile(source, definition, entry),
          this,
          warnings);
    } catch (StackOverflowError cause) {
      var error = new BexFailure("HOST_INCOMPLETE", "Compilation exceeded host stack capacity");
      error.initCause(cause);
      throw error;
    }
  }

  /** A caller-provided ledger is used directly, including its parent. Compilation is unmetered. */
  public BexEngine.Result execute(Compiled program, BexContext context, BexGas gas) {
    if (program.owner != this)
      throw new BexFailure(
          "INCOMPATIBLE_CONTEXT", "Compiled program belongs to another capability context");
    if (Thread.currentThread().isInterrupted())
      throw new BexFailure("HOST_INCOMPLETE", "Interrupted before execution");
    try {
      return engine.execute(
          program.program, context.engineContext(), gas, BexEngine.TraceMode.METER_ONLY);
    } catch (StackOverflowError cause) {
      var error = new BexFailure("HOST_INCOMPLETE", "Execution exceeded host stack capacity");
      error.initCause(cause);
      throw error;
    }
  }

  public BexEngine.Result execute(Compiled program, BexContext context, long gasLimit) {
    return execute(program, context, new BexGas(gasLimit));
  }
}
