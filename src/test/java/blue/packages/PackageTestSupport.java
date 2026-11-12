package blue.packages;

import blue.BlueId;
import blue.BluePackage;
import blue.PackageFixtures;
import blue.PackageLoader;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipInputStream;

final class PackageTestSupport {
  static final BlueId CONTRACTS = new BlueId("4Jmza3Z7eEDah9qtyYq5i2AqX5tKfUnLNKYAoAPoWzmF");
  static final BlueId CURRENT_LANGUAGE = new BlueId("8XGYNufx24r7Dc5bGoeobcGNCn9roLzSfZCQyHBTaTx4");
  static final Map<String, BlueId> IDS =
      Map.of(
          "language", new BlueId("9gmTH7AViM69tjyPBsczyFq9G6XZZCYPnDrkgCQCNeWZ"),
          "geometry", new BlueId("9Ru5h2T2c6N4daUET2LLHArEvQT6TR3QFPwTfteaq57v"),
          "drawing", new BlueId("DmgQQFoRoikCw8SpyzgHxef3CZsyCkxkVbFDWa3EVbuZ"),
          "geometry-v2", new BlueId("8g8Xq4RS4pQmyLReVMPHFpgfnEoT6GBfyW153hUrALtN"),
          "content", new BlueId("7QE5obau2Hu1pTrKtwCd1TBsgUUn5hGRdLbck4WbQmpg"));

  private PackageTestSupport() {}

  static BluePackage load(String name) {
    return PackageLoader.create()
        .resource(
            PackageTestSupport.class.getClassLoader(),
            "packages/" + name + ".bluepkg",
            IDS.get(name));
  }

  static byte[] bytes(String name) throws IOException {
    try (var input =
        Objects.requireNonNull(
            PackageTestSupport.class.getResourceAsStream("/packages/" + name + ".bluepkg"))) {
      return input.readAllBytes();
    }
  }

  static Path copy(Path directory, String name) throws IOException {
    return Files.write(directory.resolve(name + ".bluepkg"), bytes(name));
  }

  static Map<String, byte[]> files(String name) throws IOException {
    Map<String, byte[]> files = new TreeMap<>();
    try (var input = new ZipInputStream(new java.io.ByteArrayInputStream(bytes(name)))) {
      for (var entry = input.getNextEntry(); entry != null; entry = input.getNextEntry()) {
        files.put(entry.getName(), input.readAllBytes());
      }
    }
    return files;
  }

  static Path altered(Path directory, Map<String, byte[]> files) throws IOException {
    PackageFixtures.refreshInventory(files);
    return PackageFixtures.writeArchive(directory.resolve("altered.bluepkg"), files);
  }

  @SuppressWarnings("unchecked")
  static Map<String, Object> map(Object value) {
    return (Map<String, Object>) value;
  }
}
