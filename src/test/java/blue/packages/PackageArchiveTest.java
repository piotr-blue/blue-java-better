package blue.packages;

import static blue.packages.PackageTestSupport.altered;
import static blue.packages.PackageTestSupport.copy;
import static blue.packages.PackageTestSupport.files;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import blue.BluePackage;
import blue.Language;
import blue.PackageException;
import blue.PackageFixtures;
import blue.PackageLimits;
import blue.PackageLoader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("packages")
class PackageArchiveTest {
  @TempDir Path directory;

  @ParameterizedTest
  @ValueSource(
      strings = {
        "../bad.md",
        "/bad.md",
        "docs/../bad.md",
        "docs//bad.md",
        "docs/a:b.md",
        "docs/a\\b.md",
        "docs/e\u0301.md",
        "payload.exe",
        "docs/bad.txt"
      })
  void rejectsUnsafeOrUnsupportedNames(String name) throws Exception {
    Map<String, byte[]> files = files("geometry");
    files.put(name, new byte[0]);
    assertInvalid(PackageFixtures.writeArchive(directory.resolve("bad.bluepkg"), files));
  }

  @ParameterizedTest
  @ValueSource(strings = {"readme.MD", "docs/STRASSE.md", "docs/σ.md"})
  void rejectsCaseFoldCollisions(String name) throws Exception {
    Map<String, byte[]> files = files("geometry");
    files.put("docs/Straße.md", new byte[0]);
    files.put("docs/ς.md", new byte[0]);
    files.put(name, new byte[0]);
    assertInvalid(PackageFixtures.writeArchive(directory.resolve("bad.bluepkg"), files));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "utf8",
        "bom",
        "crlf",
        "json",
        "duplicate-json-key",
        "truncated",
        "executable",
        "symlink",
        "local-name",
        "local-method",
        "encrypted"
      })
  void rejectsMalformedCarrier(String defect) throws Exception {
    Map<String, byte[]> files = files("geometry");
    switch (defect) {
      case "utf8" -> files.put("README.md", new byte[] {(byte) 0xc0, (byte) 0xaf});
      case "bom" -> files.put("README.md", "\uFEFF# Readme\n".getBytes(StandardCharsets.UTF_8));
      case "crlf" -> files.put("README.md", "# Readme\r\n".getBytes(StandardCharsets.UTF_8));
      case "json" -> files.put("examples/bad.json", "{".getBytes(StandardCharsets.UTF_8));
      case "duplicate-json-key" ->
          files.put("examples/bad.json", "{\"x\":1,\"x\":2}".getBytes(StandardCharsets.UTF_8));
      default -> {}
    }
    Path archive = altered(directory, files);
    byte[] bytes = Files.readAllBytes(archive);
    ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
    int central = firstCentral(buffer);
    switch (defect) {
      case "truncated" -> bytes = Arrays.copyOf(bytes, bytes.length - 8);
      case "executable" -> buffer.putInt(central + 38, 0100755 << 16);
      case "symlink" -> buffer.putInt(central + 38, 0120644 << 16);
      case "local-name" -> bytes[30] ^= 1;
      case "local-method" -> buffer.putShort(8, (short) 0);
      case "encrypted" -> buffer.putShort(central + 8, (short) (buffer.getShort(central + 8) | 1));
      default -> {}
    }
    Files.write(archive, bytes);
    assertThrows(PackageException.class, () -> BluePackage.open(archive));
  }

  @Test
  void enforcesEveryTransportBoundAndLanguageWorkBound() throws Exception {
    Path archive = copy(directory, "geometry");
    PackageLimits defaults = PackageLimits.defaults();
    List<PackageLimits> bounds =
        List.of(
            new PackageLimits(
                1,
                defaults.archiveBytes(),
                defaults.entryBytes(),
                defaults.expandedBytes(),
                defaults.operations()),
            new PackageLimits(
                4096, 10, defaults.entryBytes(), defaults.expandedBytes(), defaults.operations()),
            new PackageLimits(
                4096, defaults.archiveBytes(), 1, defaults.expandedBytes(), defaults.operations()),
            new PackageLimits(
                4096, defaults.archiveBytes(), defaults.entryBytes(), 1, defaults.operations()),
            new PackageLimits(
                4096,
                defaults.archiveBytes(),
                defaults.entryBytes(),
                defaults.expandedBytes(),
                new Language.Limits(1, 256, 2_000_000, 100_000, 32L << 20, 65536, 65536)));
    for (PackageLimits bound : bounds) {
      PackageLoader loader = PackageLoader.newBuilder().limits(bound).build();
      PackageException failure = assertThrows(PackageException.class, () -> loader.open(archive));
      assertEquals("OperationLimitExceeded", failure.category());
      assertTrue(failure.incomplete());
    }
  }

  @Test
  void rejectsSymlinksForArchivesAndDirectoryMembers() throws Exception {
    Path archive = copy(directory, "geometry");
    Path link = Files.createSymbolicLink(directory.resolve("link.bluepkg"), archive);
    assertInvalid(link);
    Path prepared = Files.createDirectory(directory.resolve("prepared"));
    for (var entry : files("geometry").entrySet()) {
      Files.write(prepared.resolve(entry.getKey()), entry.getValue());
    }
    Files.delete(prepared.resolve("README.md"));
    Files.createSymbolicLink(prepared.resolve("README.md"), archive);
    assertInvalid(prepared);
  }

  private void assertInvalid(Path path) {
    PackageException failure = assertThrows(PackageException.class, () -> BluePackage.open(path));
    assertEquals("InvalidPackage", failure.category());
    assertFalse(failure.incomplete());
  }

  private static int firstCentral(ByteBuffer bytes) {
    for (int at = 0; at < bytes.limit() - 4; at++) {
      if (bytes.getInt(at) == 0x02014b50) return at;
    }
    throw new AssertionError("No central header");
  }
}
