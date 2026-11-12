package blue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Scalar admitted gas and the first rejected charge. */
class BexGas {
  public static final Map<String, Long> DEFAULT;

  static {
    var s = new LinkedHashMap<String, Long>();
    String[] names = {
      "expressionBase",
      "statementBase",
      "documentRead",
      "eventRead",
      "stepsRead",
      "currentContractRead",
      "varRead",
      "resultValueRead",
      "pointerGetBase",
      "pointerSetBase",
      "objectSetBase",
      "appendChangeBase",
      "appendEventBase",
      "forEachItem",
      "functionCall",
      "integerWork",
      "textWork",
      "valueMember",
      "nodeIdentityRequested"
    };
    long[] prices = {1, 1, 2, 1, 1, 1, 1, 2, 1, 3, 2, 5, 5, 1, 2, 1, 1, 1, 5};
    for (int i = 0; i < names.length; i++) {
      s.put(names[i], prices[i]);
    }
    DEFAULT = Map.copyOf(s);
  }

  private final long limit;
  private long used;
  private final Map<String, Long> schedule;
  private Map<String, Object> rejected;
  private String owner;
  private final BexGas parent;

  public BexGas(long limit) {
    this(limit, DEFAULT, "BEX", null);
  }

  public BexGas(long limit, Map<String, Long> schedule, String owner, BexGas parent) {
    BexFailure.require(schedule.values().stream().allMatch(v -> v >= 0), "invalid tariff");
    this.limit = limit;
    this.schedule = Map.copyOf(schedule);
    this.owner = owner;
    this.parent = parent;
  }

  boolean boundedHost() {
    return false;
  }

  long remaining() {
    return (limit() < 0 ? Long.MAX_VALUE : limit()) - used();
  }

  public long used() {
    return used;
  }

  public long limit() {
    return limit;
  }

  public long tariff(String counter) {
    Long price = schedule.get(counter);
    if (price == null) {
      throw new BexFailure("unknown gas counter: " + counter);
    }
    return price;
  }

  public List<Map<String, Object>> trace() {
    return List.of();
  }

  /** Number of admitted charges, without taking a snapshot of the entire ledger. */
  public int traceSize() {
    // Preserve the trace contract of existing subclasses that supply their own ledger.
    return trace().size();
  }

  /** Immutable snapshot of charges at or after a previously obtained traceSize(). */
  public List<Map<String, Object>> traceFrom(int offset) {
    var rows = trace();
    return List.copyOf(rows.subList(offset, rows.size()));
  }

  public Map<String, Object> rejected() {
    return rejected == null ? null : Map.copyOf(rejected);
  }

  public long attempted() {
    long amount = rejected == null ? 0 : ((Number) rejected.get("amount")).longValue();
    return amount > Long.MAX_VALUE - used ? Long.MAX_VALUE : used + amount;
  }

  public void charge(String counter) {
    charge(counter, 1);
  }

  public void charge(String counter, long units) {
    charge(counter, units, cost(tariff(counter), units));
  }

  /** Reject unrepresentable positive costs before the operation starts. */
  static long cost(long price, long units) {
    BexFailure.require(price >= 0 && units >= 0, "invalid charge");
    if (price != 0 && units > Long.MAX_VALUE / price) {
      throw new BexFailure("GAS_LIMIT_EXCEEDED", "Unrepresentable charge");
    }
    return price * units;
  }

  public void amount(String counter, long amount) {
    charge(counter, 1, amount);
  }

  public void charge(String counter, long units, long amount) {
    BexFailure.require(units >= 0 && amount >= 0, "invalid charge");
    if (rejected != null) {
      throw new BexFailure("GAS_LIMIT_EXCEEDED", rejected.get("counter").toString());
    }
    long remaining = (limit < 0 ? Long.MAX_VALUE : limit) - used;
    if (amount > remaining) {
      rejected = chargeRecord(counter, units, amount);
      rejected.put("cap", parent == null ? "SHARED" : "LOCAL");
      rejected.put("remaining", remaining);
      if (parent != null) {
        parent.rejected = new LinkedHashMap<>(rejected);
        parent.rejected.put("before", parent.used);
        parent.rejected.put("localBefore", used);
      }
      throw new BexFailure(
          "GAS_LIMIT_EXCEEDED", counter + ": next " + amount + ", remaining " + remaining);
    }
    if (parent != null) {
      String old = parent.owner;
      parent.owner = owner;
      try {
        parent.charge(counter, units, amount);
      } finally {
        parent.owner = old;
      }
    }
    used = Math.addExact(used, amount);
  }

  private Map<String, Object> chargeRecord(String counter, long units, long amount) {
    var row = new LinkedHashMap<String, Object>();
    row.put("owner", owner);
    row.put("counter", counter);
    row.put("units", units);
    row.put("amount", amount);
    row.put("before", used);
    return row;
  }
}
