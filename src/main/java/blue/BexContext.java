package blue;

import java.util.Map;
import java.util.Objects;
import java.util.function.BiPredicate;

/** Read-only invocation data. Capability callbacks are borrowed and must be deterministic. */
record BexContext(
    Object document,
    Object event,
    Object currentContract,
    Object resolvedDocument,
    Map<String, Object> steps,
    Map<String, Object> bindings,
    String scope,
    BexEngine.DocumentReader reader,
    BiPredicate<Object, Object> matcher,
    BexEngine.Identity identity) {
  @SuppressWarnings("unchecked")
  public BexContext {
    document = BexValues.freeze(document);
    event = BexValues.freeze(event);
    currentContract = BexValues.freeze(currentContract);
    resolvedDocument = BexValues.freeze(resolvedDocument);
    steps = (Map<String, Object>) BexValues.freeze(steps);
    bindings = (Map<String, Object>) BexValues.freeze(bindings);
    Objects.requireNonNull(scope);
  }

  public static BexContext empty() {
    return new BexContext(
        Map.of(),
        Map.of(),
        Map.of(),
        BexValues.UNDEFINED,
        Map.of(),
        Map.of(),
        "/",
        null,
        null,
        null);
  }

  BexEngine.Context engineContext() {
    var c = new BexEngine.Context();
    c.document = document;
    c.event = event;
    c.currentContract = currentContract;
    c.resolvedDocument = resolvedDocument;
    c.steps = steps;
    c.bindings = bindings;
    c.scope = scope;
    c.readDocument = reader;
    c.matcher = matcher;
    c.nodeBlueId = identity;
    return c;
  }
}
