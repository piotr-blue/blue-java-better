package blue;

/**
 * Host bounds for constructing one projected read result, independent of Blue execution gas.
 * Language verification and its work bounds still apply. These limits constrain returned structure,
 * not the complete heap used by the runtime or provider.
 *
 * @param values maximum containers, scalar values and object entries, including undefined values
 * @param characters maximum UTF-16 code units in object keys and text values
 * @param depth maximum value nesting depth; the selected root has depth zero
 */
public record ProjectionLimits(long values, long characters, int depth) {
  /** Rejects negative bounds; zero is an explicit zero allowance. */
  public ProjectionLimits {
    if (values < 0 || characters < 0 || depth < 0) {
      throw new IllegalArgumentException("Negative projection limit");
    }
  }

  /**
   * Returns 262,144 values, 4,194,304 UTF-16 code units and 256 levels.
   *
   * @return bounded defaults for projected reads
   */
  public static ProjectionLimits defaults() {
    return new ProjectionLimits(262_144, 4L * 1024 * 1024, 256);
  }
}
