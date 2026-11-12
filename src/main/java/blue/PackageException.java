package blue;

/**
 * A rejected package or an incomplete load. No successful package or mount is published on failure.
 */
public final class PackageException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  /** Stable rejection or incompleteness category. */
  private final String category;

  PackageException(String category, String detail) {
    super(detail);
    this.category = category;
  }

  PackageException(String category, String detail, Throwable cause) {
    super(detail, cause);
    this.category = category;
  }

  /**
   * Returns the stable package or underlying Language failure category.
   *
   * @return the stable package or underlying Language failure category
   */
  public String category() {
    return category;
  }

  /**
   * Returns true when I/O, unavailable evidence or a resource limit prevented a decision.
   *
   * @return true when I/O, unavailable evidence or a resource limit prevented a decision
   */
  public boolean incomplete() {
    return category.equals("PackageUnavailable")
        || category.equals("ProviderUnavailable")
        || category.equals("OperationLimitExceeded");
  }
}
