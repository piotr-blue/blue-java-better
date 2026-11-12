package blue;

import static blue.ContractLanguage.identity;

import blue.ContractModel.Document;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Positive structural checks under one exact evidence context. Never caches authority/inputs. */
final class ContractFacts {
  private final ContractBridge blue;
  private ContractLanguage.EvidenceScope scope;
  private Object configuration = new Object();
  private final ContractCache<Object, Boolean> facts =
      new ContractCache<>(
          "contracts.facts",
          Long.getLong("blue.physical.contractFactsBytes", 8L * 1024 * 1024),
          8192,
          256 * 1024);

  ContractFacts(ContractBridge blue) {
    this.blue = blue;
  }

  void inherit(ContractFacts template) {
    configuration = template.configuration;
    scope = blue.context.evidenceScope();
  }

  void clear() {
    configuration = new Object();
    facts.clear();
  }

  record Established(Object configuration, Object scope, Object key) {}

  record Check(
      Object key,
      ContractLanguage.EvidenceScope scope,
      boolean known,
      String kind,
      Document document) {}

  Check check(String kind, String key, Document document, Object extra) {
    var current = blue.context.evidenceScope();
    if (scope != current) {
      facts.clear();
      scope = current;
    }
    Object fact = List.of(kind, key, identity(document.body), document.metadata(), extra);
    boolean reusable =
        blue.context.nativeReuse() && !Boolean.getBoolean("blue.physical.nativeDisabled");
    // The initialized and pre-initialization lifetime checks are distinct facts. Keeping both
    // avoids alternately evicting one while installing protected preparation.
    String slot = kind.equals("lifetimes") ? kind + ":" + extra : kind;
    Established established = document.validation.get(slot);
    boolean local =
        established != null
            && established.configuration == configuration
            && established.scope == current
            && established.key.equals(fact);
    return new Check(
        fact,
        current,
        reusable && (local || kind.equals("restore-body") && facts.get(fact) != null),
        slot,
        document);
  }

  void established(Check check) {
    if (blue.context.nativeReuse()
        && !Boolean.getBoolean("blue.physical.nativeDisabled")
        && check.scope() == blue.context.evidenceScope()) {
      var updated = new HashMap<>(check.document.validation);
      updated.put(check.kind, new Established(configuration, check.scope, check.key));
      check.document.validation = Map.copyOf(updated);
      // Structural facts already live on the snapshot-owned document and survive its copies.
      // Only restore-body uses a temporary document, so only that check needs the bounded LRU.
      if (check.kind.equals("restore-body") && !check.known) {
        facts.put(check.key(), Boolean.TRUE, ContractCache.weight(check.key()));
      }
    }
  }
}
