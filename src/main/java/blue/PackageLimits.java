package blue;

import java.util.Objects;

/**
 * Host bounds for one package load, independent of package identity and execution gas.
 *
 * @param entries maximum archive or directory entries
 * @param archiveBytes maximum compressed archive or stream bytes
 * @param entryBytes maximum expanded bytes in a single file
 * @param expandedBytes maximum total expanded bytes
 * @param operations bounds used during Language verification; work is accumulated across records
 */
public record PackageLimits(
    int entries,
    long archiveBytes,
    long entryBytes,
    long expandedBytes,
    Language.Limits operations) {
  /** Rejects negative limits; zero means no allowance, not unlimited. */
  public PackageLimits {
    if (entries < 0 || archiveBytes < 0 || entryBytes < 0 || expandedBytes < 0) {
      throw new IllegalArgumentException("Negative package limit");
    }
    Objects.requireNonNull(operations);
  }

  /**
   * Returns 4,096 entries, 64 MiB archive/expanded, 32 MiB per file and bounded Language.
   *
   * @return 4,096 entries, 64 MiB archive/expanded, 32 MiB per file and bounded Language
   *     verification
   */
  public static PackageLimits defaults() {
    return new PackageLimits(
        4096,
        64L << 20,
        32L << 20,
        64L << 20,
        new Language.Limits(100_000_000, 256, 2_000_000, 100_000, 32L << 20, 65_536, 65_536));
  }
}
