package blue;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Materializes bounded public callback values on demand; no internal view escapes its invocation.
 */
final class NativeValues {
  private final Map<Object, Object> snapshots = new IdentityHashMap<>();

  Object freeze(Object value) {
    if (snapshots.containsKey(value)) {
      return snapshots.get(value);
    }
    Object result =
        ContractNative.access(
            () -> snapshot(value, new ProjectionBudget(ProjectionLimits.defaults()), 0));
    if (value instanceof Map<?, ?>
        || value instanceof List<?>
        || value instanceof BexValues.HostValue) {
      snapshots.put(value, result);
    }
    return result;
  }

  Object select(Object value, String pointer) {
    Object selected =
        ContractNative.access(
            () -> {
              Pointer.parse(pointer);
              return BexValues.pointerGet(value, pointer);
            });
    return freeze(selected);
  }

  @SuppressWarnings("unchecked")
  Map<String, Object> object(Map<String, Object> value) {
    return (Map<String, Object>) freeze(value);
  }

  private Object snapshot(Object value, ProjectionBudget budget, int depth) {
    if (value instanceof ContractBridge.ExactView view) {
      return BexValues.freeze(view.bridge.project(view.recipe, budget, depth));
    }
    budget.value(depth);
    if (value instanceof Map<?, ?> map) {
      Map<String, Object> copy = new LinkedHashMap<>();
      map.forEach(
          (key, item) -> {
            BexFailure.require(key instanceof String, "BEX object keys must be Text");
            budget.entry((String) key);
            copy.put((String) key, snapshot(item, budget, depth + 1));
          });
      return BexValues.freeze(copy);
    }
    if (value instanceof List<?> list) {
      List<Object> copy = new ArrayList<>();
      for (Object item : list) {
        copy.add(snapshot(item, budget, depth + 1));
      }
      return BexValues.freeze(copy);
    }
    budget.text(value);
    return BexValues.freeze(value);
  }

  void clear() {
    snapshots.clear();
  }
}
