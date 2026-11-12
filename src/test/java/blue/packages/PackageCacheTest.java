package blue.packages;

import static blue.packages.PackageTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

import blue.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("packages")
class PackageCacheTest {
  @TempDir Path directory;

  @Test
  void identicalBytesReuseButChangedOrMissingFilesNeverHitTheCache() throws Exception {
    PackageLoader loader = PackageLoader.create();
    Path path = copy(directory, "geometry");
    BluePackage original = loader.open(path);
    var timestamp = Files.getLastModifiedTime(path);
    Files.write(path, new byte[bytes("geometry").length]);
    Files.setLastModifiedTime(path, timestamp);
    assertThrows(PackageException.class, () -> loader.open(path));
    Files.write(path, bytes("geometry"));
    assertSame(original, loader.open(path));
    Files.delete(path);
    assertTrue(assertThrows(PackageException.class, () -> loader.open(path)).incomplete());
    assertEquals(3, original.definition("Point").specializeYaml("x: 3\ny: 4").at("/x").integer());
  }

  @Test
  void cacheEvictionAndDisabledRetentionCannotInvalidateReturnedPackages() throws Exception {
    PackageLoader loader = PackageLoader.newBuilder().cache(1, 1 << 20).build();
    Path geometry = copy(directory, "geometry"), drawing = copy(directory, "drawing");
    BluePackage first = loader.open(geometry);
    loader.open(drawing);
    BluePackage second = loader.open(geometry);
    assertNotSame(first, second);
    assertEquals(first.blueId(), second.blueId());
    loader.clearCache();
    assertNotSame(second, loader.open(geometry));
    PackageLoader disabled = PackageLoader.newBuilder().cache(0, 0).build();
    assertNotSame(disabled.open(geometry), disabled.open(geometry));
    assertEquals(1, first.definition("Point").specializeYaml("x: 1\ny: 2").at("/x").integer());
  }

  @Test
  @Tag("concurrency")
  void sharedLoaderHasIndependentVerificationState() throws Exception {
    PackageLoader loader = PackageLoader.newBuilder().cache(1, 1 << 20).build();
    Path a = copy(directory, "geometry"), b = copy(directory, "drawing");
    List<Callable<BlueId>> calls = new ArrayList<>();
    for (int i = 0; i < 24; i++) {
      Path file = i % 2 == 0 ? a : b;
      calls.add(() -> loader.open(file).blueId());
    }
    try (var pool = Executors.newFixedThreadPool(4)) {
      List<Future<BlueId>> results = pool.invokeAll(calls, 15, TimeUnit.SECONDS);
      for (int i = 0; i < results.size(); i++) {
        assertEquals(IDS.get(i % 2 == 0 ? "geometry" : "drawing"), results.get(i).get());
      }
    }
  }
}
