package blue;

import static blue.ContractModel.arr;
import static blue.ContractModel.closed;
import static blue.ContractModel.obj;
import static blue.ContractModel.require;
import static blue.ContractModel.segments;
import static blue.ContractModel.str;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One step's validated effects. A whole batch is checked before its first write. The original
 * changeset stays available to subsequent BEX steps, including lazy references in patch values.
 */
record ContractEffects(List<Object> changeset, List<Patch> patches, List<Object> events) {
  record Patch(String operation, String path, List<String> segments, Object value) {}

  static ContractEffects compute(ContractRun run, BexEngine.Result result) {
    Object changes = selectedEffect(result.value(), "changeset", result.changeset());
    Object events = selectedEffect(result.value(), "events", result.events());
    return validate(run, effectList(changes, true), effectList(events, false));
  }

  static ContractEffects update(ContractRun run, Object changes) {
    return validate(run, effectList(changes, true), List.of());
  }

  static ContractEffects validate(ContractRun run, Object changes, Object emitted) {
    require(changes instanceof List && emitted instanceof List, "INVALID_EFFECT_RESULT");
    var changeset = arr(changes);
    var events = arr(emitted);
    List<Patch> patches = changeset.isEmpty() ? List.of() : new ArrayList<>(changeset.size());
    for (Object raw : changeset) {
      var fields = obj(raw);
      require(
          Set.of("add", "replace", "remove").contains(fields.getOrDefault("op", "")),
          "INVALID_PATCH");
      String operation = str(fields.get("op"));
      boolean remove = operation.equals("remove");
      closed(fields, remove ? Set.of("op", "path") : Set.of("op", "path", "val"), Set.of());
      String path = str(fields.get("path"));
      var parts = segments(path);
      require(!parts.isEmpty(), "INVALID_PATCH_PATH");
      Object value = fields.get("val");
      if (!remove) {
        run.runtimeNode(value, false);
      }
      patches.add(new Patch(operation, path, parts, value));
    }
    for (Object event : events) {
      SemanticGas.output(run.meter, event);
      run.runtimeNode(event);
    }
    return new ContractEffects(changeset, patches, events);
  }

  private static Object selectedEffect(Object value, String field, Object fallback) {
    if (value instanceof Map<?, ?> map) {
      return map.containsKey(field) ? map.get(field) : fallback;
    }
    if (value instanceof BexValues.HostValue host && host.kind().equals("object")) {
      Object selected = host.get(field);
      return selected == BexValues.UNDEFINED ? fallback : selected;
    }
    return fallback;
  }

  /** Read only effect-container structure; keep exact payload views for the Language boundary. */
  private static Object effectList(Object value, boolean patches) {
    boolean lazy = value instanceof BexValues.HostValue;
    if (!lazy
        && (!(value instanceof List<?> list)
            || !patches
            || list.stream().noneMatch(item -> item instanceof BexValues.HostValue))) {
      return value;
    }
    require(BexValues.kind(value).equals("list"), "INVALID_EFFECT_RESULT");
    var result = new ArrayList<Object>();
    for (int i = 0; i < BexValues.size(value); i++) {
      Object item = BexValues.get(value, i);
      if (patches && item instanceof BexValues.HostValue) {
        require(BexValues.kind(item).equals("object"), "INVALID_PATCH");
        var fields = new LinkedHashMap<String, Object>();
        for (String name : BexValues.keys(item)) {
          fields.put(name, BexValues.get(item, name));
        }
        item = fields;
      }
      result.add(item);
    }
    return result;
  }
}
