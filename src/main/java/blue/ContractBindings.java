package blue;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Exact canonical package bindings. Capability selection never chooses older rules. */
final class ContractBindings {
  private final Set<String> packages;
  private final boolean canonicalWire;
  private final GasSchedule gasSchedule;

  private ContractBindings(Collection<String> packages, boolean canonicalWire) {
    this(packages, canonicalWire, GasSchedule.STANDARD);
  }

  private ContractBindings(
      Collection<String> packages, boolean canonicalWire, GasSchedule gasSchedule) {
    this.gasSchedule = Objects.requireNonNull(gasSchedule);
    this.packages = Set.copyOf(packages);
    this.canonicalWire = canonicalWire;
  }

  private static String id(String name, String field) {
    return DeterministicPublications.id(name, field);
  }

  public static ContractBindings core() {
    return new ContractBindings(
        Set.of(
            id("blue-language", "package"),
            id("blue-bex", "package"),
            id("blue-contracts", "package")),
        false);
  }

  public static ContractBindings current() {
    return core();
  }

  public static ContractBindings dispatch() {
    var selected = new HashSet<>(core().packages);
    selected.add(id("blue-dispatch", "package"));
    return new ContractBindings(selected, false);
  }

  public String id() {
    return dispatchEnabled() ? "dispatch" : "core";
  }

  public boolean dispatchEnabled() {
    return packages.contains(id("blue-dispatch", "package"));
  }

  public ContractBindings withCanonicalWireMeasurementV1() {
    return new ContractBindings(packages, true, gasSchedule);
  }

  public boolean canonicalWireMeasurementV1() {
    return canonicalWire;
  }

  ContractBindings withGasSchedule(GasSchedule value) {
    return new ContractBindings(packages, canonicalWire, value);
  }

  GasSchedule gasSchedule() {
    return gasSchedule;
  }

  public BexEdition bex() {
    return BexEdition.current();
  }

  public ContractsEdition contractsEdition() {
    return ContractsEdition.DETERMINISTIC;
  }

  public Set<String> supportedPackages() {
    return packages;
  }

  public Set<String> protocolBindings() {
    var protocols = new HashSet<String>();
    for (var name : List.of("blue-bex", "blue-contracts", "blue-dispatch")) {
      if (packages.contains(id(name, "package"))) {
        protocols.add(id(name, "protocol"));
      }
    }
    return Set.copyOf(protocols);
  }

  public BexEngine engine() {
    return new BexEngine(Map.of(), true);
  }

  public void require(BluePackage selected) {
    if (!bex().languageBlueId().equals(selected.languageId().value())) {
      throw new Unsupported(
          selected.languageId().value(), List.of(selected.blueId().value(), "language"));
    }
    Set<BlueId> seen = new HashSet<>();
    ArrayDeque<BlueId> pending = new ArrayDeque<>(selected.declaredRequirements());
    while (!pending.isEmpty()) {
      BlueId id = pending.pop();
      if (!seen.add(id)) {
        continue;
      }
      if (!protocolBindings().contains(id.value())) {
        throw new Unsupported(id.value(), List.of(selected.blueId().value()));
      }
      pending.addAll(selected.protocol(id).requires().values());
    }
  }

  public Map<String, Object> identities() {
    var ids = new LinkedHashMap<String, Object>();
    ids.put("gasSchedule", gasSchedule.id());
    ids.put("language", bex().languageBlueId());
    ids.put("bex", bex().protocolBlueId());
    ids.put("bexSpecification", bex().rulesBlueId());
    ids.put("contracts", id("blue-contracts", "protocol"));
    ids.put("contractsSpecification", id("blue-contracts", "rules"));
    if (canonicalWire) {
      ids.put("canonicalWireMeasurementV1", CanonicalWireMeasurementV1.TYPE_ID);
    }
    ids.put("supportedPackages", packages.stream().sorted().toList());
    return Collections.unmodifiableMap(ids);
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof ContractBindings b && identities().equals(b.identities());
  }

  @Override
  public int hashCode() {
    return identities().hashCode();
  }

  public static class Unsupported extends RuntimeException {
    private static final long serialVersionUID = 1L;

    /** The unsupported exact requirement. */
    private final String required;

    /** The dependency route that introduced the requirement; not part of the serialized form. */
    private final transient List<String> path;

    public Unsupported(String required, List<String> path) {
      super(
          "Unsupported exact runtime requirement "
              + required
              + " through "
              + String.join(" → ", path));
      this.required = required;
      this.path = List.copyOf(path);
    }

    public String requiredBlueId() {
      return required;
    }

    public List<String> dependencyPath() {
      return path;
    }

    public Map<String, Object> diagnostic() {
      return Map.of(
          "outcome",
          "Unsupported",
          "category",
          "UnsupportedRuntimeBindings",
          "required",
          required,
          "path",
          path);
    }
  }
}
