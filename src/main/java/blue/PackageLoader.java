package blue;

import static blue.LanguageCore.finalId;
import static blue.LanguageCore.map;
import static blue.PackageIntegrity.check;
import static blue.PackageIntegrity.data;
import static blue.PackageIntegrity.digest;
import static blue.PackageIntegrity.require;
import static blue.PackageIntegrity.utf8;

import java.io.EOFException;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.zip.ZipException;

/**
 * Thread-safe canonical package loading with bounded I/O and optional verification reuse.
 *
 * <p>Every call reads and validates the supplied archive structure. Only identical member names and
 * bytes reuse a verified result; file names, timestamps and claimed BlueIds are never cache keys.
 * Identity pins are checked on every call. Concurrent misses may repeat verification, and I/O or
 * verification never holds the cache lock. No network acquisition occurs.
 */
public final class PackageLoader {
  private final PackageLimits limits;
  private final BlueId expectedLanguage;
  private final int maximumPackages;
  private final long maximumBytes;
  private final Map<String, Cached> cache = new LinkedHashMap<>(16, 0.75f, true);
  private long retainedBytes;

  private record Cached(BluePackage value, long bytes) {}

  private PackageLoader(Builder builder) {
    limits = builder.limits;
    expectedLanguage = builder.expectedLanguage;
    maximumPackages = builder.maximumPackages;
    maximumBytes = builder.maximumBytes;
  }

  /**
   * Returns a reusable loader with bounded defaults and 16 cached packages/64 MiB of source bytes.
   *
   * @return a reusable loader with bounded defaults and 16 cached packages/64 MiB of source bytes
   */
  public static PackageLoader create() {
    return newBuilder().build();
  }

  /**
   * Returns a new independent builder with the same defaults as {@link #create()}.
   *
   * @return a new independent builder with the same defaults as {@link #create()}
   */
  public static Builder newBuilder() {
    return new Builder();
  }

  /**
   * Verifies and inspects the package at a local location without an expected package pin.
   *
   * @param path archive or prepared directory
   * @return a verified immutable package
   * @throws PackageException if loading or verification fails
   */
  public BluePackage open(Path path) {
    return openSelected(path, null);
  }

  /**
   * Loads the exact package selected by the caller.
   *
   * @param path archive or prepared directory
   * @param expected trusted expected semantic package identity
   * @return the verified package
   * @throws PackageException if loading, verification or selection fails
   */
  public BluePackage open(Path path, BlueId expected) {
    return openSelected(path, Objects.requireNonNull(expected));
  }

  private BluePackage openSelected(Path path, BlueId expected) {
    Objects.requireNonNull(path);
    return guarded(
        () -> {
          check(!Files.isSymbolicLink(path), "Symbolic package root");
          if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            return verify(new PackageArchive(limits).read(path), expected);
          }
          if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new NoSuchFileException(path.toString());
          }
          check(
              Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS),
              "Package archive is not a regular file");
          try (InputStream input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            return readSnapshot(input, expected);
          }
        });
  }

  /**
   * Loads a package stream. This method consumes but does not close the caller's stream.
   *
   * @param input archive bytes, subject to the compressed byte limit
   * @param expected trusted expected semantic package identity
   * @return the verified package
   * @throws PackageException if input, verification or selection fails
   */
  public BluePackage read(InputStream input, BlueId expected) {
    Objects.requireNonNull(input);
    Objects.requireNonNull(expected);
    return guarded(() -> readSnapshot(input, expected));
  }

  /**
   * Inspects a package stream without an expected package pin; does not close the caller's stream.
   *
   * @param input archive bytes
   * @return the verified package
   * @throws PackageException if loading or verification fails
   */
  public BluePackage read(InputStream input) {
    Objects.requireNonNull(input);
    return guarded(() -> readSnapshot(input, null));
  }

  /**
   * Loads a classpath archive and closes its resource stream.
   *
   * @param loader resource class loader
   * @param resource classpath path without a leading slash
   * @param expected trusted expected semantic package identity
   * @return the verified package
   * @throws PackageException if loading, verification or selection fails
   */
  public BluePackage resource(ClassLoader loader, String resource, BlueId expected) {
    Objects.requireNonNull(loader);
    Objects.requireNonNull(expected);
    PackagePaths.safe(resource);
    return guarded(
        () -> {
          try (InputStream input = loader.getResourceAsStream(resource)) {
            if (input == null) {
              throw new FileNotFoundException("Missing package resource: " + resource);
            }
            return readSnapshot(input, expected);
          }
        });
  }

  /**
   * Releases retained verification results. Already returned packages remain usable. A concurrent
   * in-flight load may populate the cache after this call.
   */
  public void clearCache() {
    synchronized (cache) {
      cache.clear();
      retainedBytes = 0;
    }
  }

  private BluePackage readSnapshot(InputStream input, BlueId expected) throws IOException {
    // A private immutable snapshot avoids races between ZIP metadata and member-content reads.
    Path temporary = Files.createTempFile("blue-package-", ".bluepkg");
    try {
      try (OutputStream output = Files.newOutputStream(temporary)) {
        byte[] buffer = new byte[8192];
        long count = 0;
        for (int size; (size = input.read(buffer)) != -1; ) {
          count += size;
          require(
              count <= limits.archiveBytes(), "OperationLimitExceeded", "Package archive bytes");
          output.write(buffer, 0, size);
        }
      }
      return verify(new PackageArchive(limits).read(temporary), expected);
    } finally {
      Files.deleteIfExists(temporary);
    }
  }

  private BluePackage verify(Map<String, byte[]> files, BlueId expected) {
    LanguageContext context = new LanguageContext(limits.operations().wire());
    Map<String, Object> manifest =
        map(data(LanguageOperations.parse(utf8(files.get("package.json")), "json", context)));
    require(
        Language.EDITION.equals(manifest.get("languageProfile")),
        "PackageSelectionMismatch",
        "Unsupported Language profile");
    String packageId = finalId(manifest.get("packageBlueId"));
    String languageId =
        manifest.get("language") == null ? packageId : finalId(manifest.get("language"));
    require(
        expected == null || expected.value().equals(packageId),
        "PackageSelectionMismatch",
        "Unexpected package identity");
    require(
        expectedLanguage == null || expectedLanguage.value().equals(languageId),
        "PackageSelectionMismatch",
        "Unexpected Language identity");
    String fingerprint = fingerprint(files);
    synchronized (cache) {
      Cached present = cache.get(fingerprint);
      if (present != null) {
        return present.value();
      }
    }
    BluePackage verified = new PackageVerifier(files, limits.operations()).verify();
    retain(fingerprint, verified, files.values().stream().mapToLong(bytes -> bytes.length).sum());
    return verified;
  }

  private void retain(String key, BluePackage value, long bytes) {
    if (maximumPackages == 0 || bytes > maximumBytes) {
      return;
    }
    synchronized (cache) {
      Cached previous = cache.put(key, new Cached(value, bytes));
      retainedBytes += bytes - (previous == null ? 0 : previous.bytes());
      while (cache.size() > maximumPackages || retainedBytes > maximumBytes) {
        var iterator = cache.entrySet().iterator();
        retainedBytes -= iterator.next().getValue().bytes();
        iterator.remove();
      }
    }
  }

  private static String fingerprint(Map<String, byte[]> files) {
    MessageDigest digest = digest();
    ByteBuffer length = ByteBuffer.allocate(Long.BYTES);
    for (var entry : new TreeMap<>(files).entrySet()) {
      digest.update(entry.getKey().getBytes(StandardCharsets.UTF_8));
      digest.update((byte) 0);
      length.clear();
      length.putLong(entry.getValue().length);
      digest.update(length.array());
      digest.update(entry.getValue());
    }
    return HexFormat.of().formatHex(digest.digest());
  }

  private interface Load {
    BluePackage run() throws IOException;
  }

  private static BluePackage guarded(Load load) {
    try {
      return load.run();
    } catch (ZipException | EOFException failure) {
      throw new PackageException("InvalidPackage", "Invalid ZIP archive", failure);
    } catch (IOException failure) {
      throw new PackageException("PackageUnavailable", failure.getMessage(), failure);
    } catch (LanguageFailure failure) {
      throw new PackageException(failure.category(), failure.getMessage(), failure);
    } catch (StackOverflowError failure) {
      throw new PackageException("OperationLimitExceeded", "Package traversal depth", failure);
    }
  }

  /** Mutable configuration; each build captures independent immutable settings and cache state. */
  public static final class Builder {
    private PackageLimits limits = PackageLimits.defaults();
    private BlueId expectedLanguage;
    private int maximumPackages = 16;
    private long maximumBytes = 64L << 20;

    private Builder() {}

    /**
     * Sets the operation bounds.
     *
     * @param limits independent host bounds for every load
     * @return this builder
     */
    public Builder limits(PackageLimits limits) {
      this.limits = Objects.requireNonNull(limits);
      return this;
    }

    /**
     * Sets the required Language identity.
     *
     * @param identity trusted Language package pin required for every loaded graph
     * @return this builder
     */
    public Builder language(BlueId identity) {
      expectedLanguage = Objects.requireNonNull(identity);
      return this;
    }

    /**
     * Bounds retained results by entry count and expanded source bytes. Decoded Java objects can
     * occupy more heap than their source bytes. Zero entries or bytes disables retention.
     *
     * @param packages maximum retained packages
     * @param sourceBytes maximum sum of their expanded source sizes
     * @return this builder
     */
    public Builder cache(int packages, long sourceBytes) {
      if (packages < 0 || sourceBytes < 0) {
        throw new IllegalArgumentException("Negative cache bound");
      }
      maximumPackages = packages;
      maximumBytes = sourceBytes;
      return this;
    }

    /**
     * Returns an independent thread-safe loader.
     *
     * @return an independent thread-safe loader
     */
    public PackageLoader build() {
      return new PackageLoader(this);
    }
  }
}
