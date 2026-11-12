package blue;

import static blue.LanguageCore.CORE;
import static blue.LanguageCore.TYPES;
import static blue.LanguageCore.identity;
import static blue.LanguageCore.open;
import static blue.LanguageSemantics.ancestry;
import static blue.LanguageSemantics.checkKey;
import static blue.LanguageSemantics.child;
import static blue.LanguageSemantics.exact;
import static blue.LanguageSemantics.head;
import static blue.LanguageSemantics.recipe;
import static blue.LanguageSemantics.strict;

import blue.ExactNode.Ref;
import blue.ExactNode.Value;
import blue.LanguageModel.Head;
import blue.LanguageModel.Recipe;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Typed protocol reads preserve supplied content separately from contextual declarations. */
final class TimelineContent {
  final LanguageContext context;
  // Grammar facts live only in this verification context. Every containing entry is still
  // validated, and its operation marker must identify this exact request before reuse.
  final Map<String, Map<String, Head>> operationRequests = new HashMap<>();
  private final BluePackage publication;
  private ContractRegistry operations;

  TimelineContent(LanguageContext context, BluePackage publication) {
    this.context = context;
    this.publication = publication;
    // The protocol's resource budget measures physical verification work, not Contracts gas.
    // Repeated checks in this one evidence view may reuse completed closure verification.
    // Snapshots export content only; these facts never authorize another verification request.
    context.reuseWithinHostBudget = true;
  }

  /** Grammar checks share admitted immutable definitions only within this evidence view. */
  ContractRegistry operations(LanguageContent request) {
    if (operations == null) {
      // Grammar inspection may need a type that ordinary transport-header inspection left
      // unopened. Keep this verification attempt's resolver, not only its bodies loaded so far.
      // The bridge still verifies requested identities; this supplies no admission authority.
      Language language =
          Language.newBuilder()
              .content(request)
              .build()
              .withLookup(
                  id -> {
                    Object supplied = context.provider.get(id);
                    return supplied == null ? context.lookup.apply(id) : supplied;
                  });
      ContractBridge bridge = new ContractBridge(language);
      bridge.context.protocolValues(context.establishedValues, context.facts);
      operations = new ContractRegistry(bridge, ContractBindings.current(), new ContractPrograms());
    } else {
      operations.blue.context.install(request);
      operations.blue.context.protocolValues(context.establishedValues, context.facts);
      operations.blue.refresh();
    }
    return operations;
  }

  void rememberFacts() {
    if (context.facts != null) {
      context.facts.remember(context);
    }
    if (operations != null) {
      operations.blue.context.rememberProtocolFacts();
    }
  }

  Head typed(ExactNode node, String name) {
    Head result = head(recipe(node), context);
    require(
        result.type != null && ancestry(result.type, context).contains(type(name)),
        "TIMELINE_TYPE",
        name);
    return result;
  }

  String type(String name) {
    return publication.exports().get(name).value();
  }

  ExactNode required(ExactNode node, String type, String field) {
    ExactNode result = supplied(node, type, field);
    require(result != null, "TIMELINE_FIELD_REQUIRED", field);
    return result;
  }

  ExactNode supplied(ExactNode node, String type, String field) {
    typed(node, type);
    String base = type(type);
    var parts = new ArrayList<ExactNode>();
    ExactNode cursor = node;
    // Stop at the published declaration: its field descriptions are not supplied messages,
    // predecessor links or optional values. A domain subtype may supply inherited values.
    while (!identity(cursor).equals(base)) {
      context.step();
      Value body = open(cursor, context);
      if (body.get(field) instanceof ExactNode value) {
        parts.add(value);
      }
      require(body.get("type") instanceof Ref, "TIMELINE_TYPE", type);
      cursor = (Ref) body.get("type");
    }
    if (parts.isEmpty()) {
      return null;
    }
    Collections.reverse(parts);
    return exact(recipe(parts.toArray()), context);
  }

  long integer(ExactNode node, String type, String field) {
    BigInteger value = bigInteger(node, type, field);
    try {
      return value.longValueExact();
    } catch (ArithmeticException invalid) {
      throw new BlueException("TIMELINE_INTEGER", field);
    }
  }

  BigInteger bigInteger(ExactNode node, String type, String field) {
    required(node, type, field);
    Recipe value = child(typed(node, type), field);
    Head h = head(value, context);
    require(h.value instanceof BigInteger, "TIMELINE_INTEGER", field);
    return (BigInteger) h.value;
  }

  String text(ExactNode node, String type, String field) {
    required(node, type, field);
    Object value = head(child(typed(node, type), field), context).value;
    require(value instanceof String, "TIMELINE_TEXT", field);
    return (String) value;
  }

  boolean bool(ExactNode node, String type, String field) {
    required(node, type, field);
    Object value = head(child(typed(node, type), field), context).value;
    require(value instanceof Boolean, "TIMELINE_BOOLEAN", field);
    return (Boolean) value;
  }

  /** Partial transport inspection, never a Language admission certificate for the whole node. */
  void inspect(ExactNode node, String type, Set<String> deferred) {
    Head h = typed(node, type);
    Value body = open(node, context);
    for (String key : TYPES) {
      if (body.get(key) instanceof Ref value) {
        strict(value, context, "definition");
      }
    }
    if (body.get("specification") instanceof Ref value) {
      strict(value, context, "definition");
    }
    h.constraints.consistent();
    // This is a known-present object header. With only required/no predicates there is no
    // root predicate to inspect; ordinary full validation also counts all fields, which would
    // incorrectly fetch the deliberately deferred checkpoint head. Real count/value predicates
    // still run and may demand that content. This does not certify deferred fields.
    if (h.constraints.kinds() != null) {
      h.constraints.validate(h, true, "definition", true, context);
      h.constraints.validate(h, true, "value", true, context);
    }
    for (String field : h.fields.keySet()) {
      if ("Dictionary".equals(h.core) || h.keyType != null) {
        checkKey(field, h.keyType == null ? CORE.get("Text") : h.keyType, context);
      }
      if (deferred.contains(field)) {
        continue;
      }
      Recipe value = child(h, field);
      for (ExactNode part : value.parts) {
        strict(part, context, "definition");
      }
      LanguageSemantics.validate(value, context, "definition", false);
      LanguageSemantics.validate(value, context, "value", false);
    }
    if (h.contracts != null || h.items != null) {
      throw new BlueException("TIMELINE_HEADER_SHAPE", type);
    }
  }

  List<ExactNode> entries(ExactNode evidence, String type) {
    ExactNode supplied = required(evidence, type, "entries");
    Head list = head(recipe(supplied), context);
    require(list.items != null, "TIMELINE_ENTRIES_REQUIRED", "entries");
    return list.items.stream().map(item -> exact(item, context)).toList();
  }

  void validate(ExactNode node) {
    strict(node, context, "value");
  }

  static LanguageContent detachedContent(ExactNode node) {
    Language owner = Language.create();
    LanguageContext context = owner.context();
    context.remember(node);
    return new LanguageContent(owner, node, new Language.Snapshot(context.bodies, context.proofs));
  }

  static Head detached(ExactNode node, Head head) {
    // Keep the latest typed header, never its inline payloads, provider, snapshot or history.
    var out = head.copy();
    out.origin = new Ref(identity(node));
    out.origins = List.of(out.origin);
    out.fields.replaceAll((key, value) -> detached(value));
    if (out.items != null) {
      out.items = out.items.stream().map(TimelineContent::detached).toList();
    }
    if (out.contracts != null) {
      out.contracts = detached(out.contracts);
    }
    return out.build();
  }

  private static Recipe detached(Recipe recipe) {
    return new Recipe(
        recipe.parts.stream().map(n -> (ExactNode) new Ref(identity(n))).toList(),
        recipe.requirements);
  }

  static void require(boolean condition, String code, String detail) {
    if (!condition) {
      throw new BlueException(code, detail);
    }
  }
}
