package blue.packages;

import static blue.packages.PackageTestSupport.CONTRACTS;
import static blue.packages.PackageTestSupport.CURRENT_LANGUAGE;
import static blue.packages.PackageTestSupport.IDS;
import static blue.packages.PackageTestSupport.bytes;
import static blue.packages.PackageTestSupport.copy;
import static blue.packages.PackageTestSupport.files;
import static blue.packages.PackageTestSupport.load;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import blue.BluePackage;
import blue.Language;
import blue.LanguageException;
import blue.PackageException;
import blue.PackageLoader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("packages")
class PackageLoadingTest {
  @TempDir Path directory;

  @ParameterizedTest(name = "publication={0}")
  @ValueSource(strings = {"language", "geometry", "drawing", "geometry-v2", "content"})
  void archiveDirectoryStreamAndResourceHaveIdenticalMeaning(String name) throws Exception {
    PackageLoader loader = PackageLoader.create();
    BluePackage archive = loader.open(copy(directory, name), IDS.get(name));
    Path folder = Files.createDirectory(directory.resolve("folder"));
    for (var file : files(name).entrySet()) {
      Path path = folder.resolve(file.getKey());
      Files.createDirectories(path.getParent());
      Files.write(path, file.getValue());
    }
    BluePackage unpacked = loader.open(folder, IDS.get(name));
    BluePackage streamed = loader.read(new ByteArrayInputStream(bytes(name)), IDS.get(name));
    BluePackage resource =
        loader.resource(
            getClass().getClassLoader(), "packages/" + name + ".bluepkg", IDS.get(name));
    assertSame(archive, unpacked);
    assertSame(archive, streamed);
    assertSame(archive, resource);
    assertEquals(archive.blueId(), archive.document().blueId());
    assertFalse(archive.documentation("README.md").isEmpty());
    assertThrows(UnsupportedOperationException.class, () -> archive.imports().clear());
    assertThrows(UnsupportedOperationException.class, () -> archive.documentation().clear());
  }

  @Test
  void callerOwnsStreamsAndPinsApplyOnEveryLoad() throws Exception {
    final class Input extends ByteArrayInputStream {
      boolean closed;

      Input(byte[] bytes) {
        super(bytes);
      }

      @Override
      public void close() throws IOException {
        closed = true;
        super.close();
      }
    }
    PackageLoader loader = PackageLoader.create();
    Input input = new Input(bytes("geometry"));
    assertEquals(IDS.get("geometry"), loader.read(input, IDS.get("geometry")).blueId());
    assertFalse(input.closed);
    Path file = copy(directory, "geometry");
    assertEquals(
        "PackageSelectionMismatch",
        assertThrows(PackageException.class, () -> loader.open(file, IDS.get("drawing")))
            .category());
    PackageLoader foreign = PackageLoader.newBuilder().language(CURRENT_LANGUAGE).build();
    assertEquals(
        "PackageSelectionMismatch",
        assertThrows(PackageException.class, () -> foreign.open(file)).category());
    assertEquals(
        "PackageUnavailable",
        assertThrows(PackageException.class, () -> loader.open(directory.resolve("absent")))
            .category());
  }

  @Test
  void mountingDifferentLanguageSelectionsFailsAtomically() {
    BluePackage tutorial = load("geometry");
    BluePackage published =
        PackageLoader.create()
            .resource(getClass().getClassLoader(), "packages/contracts.bluepkg", CONTRACTS);
    Language.Builder builder = Language.newBuilder().mount("Tutorial", tutorial);
    assertEquals(
        "PackageSelectionMismatch",
        assertThrows(PackageException.class, () -> builder.mount("Contracts", published))
            .category());
    assertThrows(
        LanguageException.class, () -> builder.build().defineYaml("type: Contracts/Compute"));
    assertEquals(
        1, builder.build().readYaml("type: Tutorial/Point\nx: 1\ny: 2").at("/x").integer());
  }
}
