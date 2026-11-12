package blue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** Safe portable package paths and the archive profile's frozen Unicode 15.1 case folding. */
final class PackagePaths {
  private static final Map<Integer, String> FOLD = load();

  private static Map<Integer, String> load() {
    var values = new HashMap<Integer, String>();
    try (var input =
        new BufferedReader(
            new InputStreamReader(
                Objects.requireNonNull(
                    PackagePaths.class.getResourceAsStream("/blue/casefold-15.1.txt")),
                StandardCharsets.US_ASCII))) {
      for (String line; (line = input.readLine()) != null; ) {
        if (line.startsWith("#") || line.isEmpty()) {
          continue;
        }
        var parts = line.split(";", 2);
        var folded = new StringBuilder();
        for (String value : parts[1].split(",")) {
          folded.appendCodePoint(Integer.parseInt(value, 16));
        }
        values.put(Integer.parseInt(parts[0], 16), folded.toString());
      }
      return Map.copyOf(values);
    } catch (IOException error) {
      throw new ExceptionInInitializerError(error);
    }
  }

  static String fold(String name) {
    var result = new StringBuilder();
    name.codePoints()
        .forEach(
            cp -> {
              String folded = FOLD.get(cp);
              if (folded == null) {
                result.appendCodePoint(cp);
              } else {
                result.append(folded);
              }
            });
    return result.toString();
  }

  private PackagePaths() {}

  static String safe(String name) {
    PackageIntegrity.check(
        name != null
            && !name.isBlank()
            && !name.contains("\\")
            && !name.startsWith("/")
            && !name.contains(":")
            && !name.contains("\u0000")
            && java.text.Normalizer.isNormalized(name, java.text.Normalizer.Form.NFC),
        "Unsafe package path");
    for (String part : name.split("/", -1)) {
      PackageIntegrity.check(
          !part.isEmpty() && !part.equals(".") && !part.equals(".."), "Unsafe package path");
    }
    return name;
  }

  static boolean validPrefix(String prefix) {
    return prefix != null
        && prefix.matches("@?[A-Za-z0-9][A-Za-z0-9_.-]*(?:/[A-Za-z0-9][A-Za-z0-9_.-]*)*");
  }

  static void allowed(String name) {
    safe(name);
    PackageIntegrity.check(
        java.util.Set.of(
                    "package.json",
                    "package.blue.json",
                    "package-records.json",
                    "repository.json",
                    "README.md",
                    "THIRD-PARTY-NOTICES.md")
                .contains(name)
            || (java.util.Set.of("docs", "spec", "examples", "conformance")
                    .contains(name.split("/")[0])
                && name.matches(".*\\.(md|blue|json)")),
        "Unexpected package asset: " + name);
  }
}
