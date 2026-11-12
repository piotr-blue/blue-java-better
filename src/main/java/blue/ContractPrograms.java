package blue;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/** Configuration-owned compiled code. No document, invocation context or validation authority. */
final class ContractPrograms {
  private static final long MAX_ENTRY_BYTES = 2L * 1024 * 1024;

  private record Key(String step, String definition) {}

  private final ContractCache<Key, BexPlanCompiler.Program> programs =
      new ContractCache<>("bex.programs", 8L * 1024 * 1024, 512, MAX_ENTRY_BYTES);

  private final ContractCache<String, Object> definitionValues =
      new ContractCache<>("bex.definitionValues", 8L * 1024 * 1024, 512, MAX_ENTRY_BYTES);

  /** Pure projection of an already logically visited constants/functions field. */
  Object definitionValue(String identity, Supplier<Object> projection) {
    if (Boolean.getBoolean("blue.canonical.recompile")) {
      return projection.get();
    }
    Object cached = definitionValues.get(identity);
    if (cached != null) {
      return cached;
    }
    Object projected = projection.get();
    // Invalid field shapes are diagnosed by the caller, in the original logical-read order.
    if (!(projected instanceof Map<?, ?>)) {
      return projected;
    }
    Object frozen = BexValues.freeze(projected);
    definitionValues.put(
        identity, frozen, ContractCache.weight(List.of(identity, frozen), MAX_ENTRY_BYTES));
    return frozen;
  }

  /** The caller must select, verify and logically read both declarations before lookup. */
  BexPlanCompiler.Program compile(
      String step, String definition, Supplier<BexPlanCompiler.Program> compiler) {
    if (Boolean.getBoolean("blue.canonical.recompile")) {
      return compiler.get();
    }
    Key key = new Key(step, definition);
    BexPlanCompiler.Program cached = programs.get(key);
    if (cached != null) {
      return cached;
    }
    // Compilation happens outside the cache lock. Concurrent misses may duplicate pure work;
    // they never serialize unrelated document executions or retain a caller's compiler closure.
    BexPlanCompiler.Program compiled = compiler.get();
    long weight =
        ContractCache.weight(
            List.of(
                key.step(),
                key.definition(),
                compiled.source(),
                compiled.operatorInventory(),
                compiled.intrinsicIds(),
                compiled.selectedEntry() == null ? "" : compiled.selectedEntry()),
            MAX_ENTRY_BYTES);
    programs.put(key, compiled, weight);
    return compiled;
  }
}
