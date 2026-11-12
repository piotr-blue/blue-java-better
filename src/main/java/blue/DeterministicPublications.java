package blue;

import static blue.ContractModel.obj;
import static blue.ContractModel.str;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Generated selection data is checked against each actual canonical archive. */
final class DeterministicPublications {
  private DeterministicPublications() {}

  private static final String ROOT = "runtime/deterministic/";
  private static final Map<String, Object> SELECTION = load();
  private static final Map<String, Object> PACKAGES = obj(SELECTION.get("packages"));

  private static Map<String, Object> load() {
    try (var in =
        Objects.requireNonNull(
            DeterministicPublications.class
                .getClassLoader()
                .getResourceAsStream(ROOT + "selection.json"))) {
      return obj(ContractWire.json(new String(in.readAllBytes(), StandardCharsets.UTF_8)));
    } catch (java.io.IOException e) {
      throw new IllegalStateException(e);
    }
  }

  public static Map<String, Object> entry(String name) {
    return obj(ContractWire.detached(PACKAGES.get(name)));
  }

  public static String id(String name, String field) {
    return str(obj(PACKAGES.get(name)).get(field));
  }

  private static final PackageLoader LOADER = PackageLoader.create();

  public static Map<String, Object> records(String name) {
    Map<String, Object> result = new TreeMap<>();
    publication(name).records().forEach((id, record) -> result.put(id.value(), record.response()));
    return result;
  }

  private static final Map<String, BluePackage> VERIFIED =
      new java.util.concurrent.ConcurrentHashMap<>();

  public static BluePackage publication(String name) {
    return VERIFIED.computeIfAbsent(name, DeterministicPublications::loadPublication);
  }

  private static BluePackage loadPublication(String name) {
    return LOADER.resource(
        DeterministicPublications.class.getClassLoader(),
        ROOT + name + ".bluepkg",
        new BlueId(id(name, "package")));
  }
}
