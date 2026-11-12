package blue;

/** A machine-readable failure at the language or execution boundary. */
public final class BlueException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  /** Machine-readable failure category. */
  private final String code;

  /**
   * Creates a failure with a stable category and diagnostic detail.
   *
   * @param code the machine-readable category
   * @param message the human-readable detail
   */
  public BlueException(String code, String message) {
    super(message);
    this.code = code;
  }

  /**
   * Returns the failure category, independent of the diagnostic wording.
   *
   * @return the machine-readable code
   */
  public String code() {
    return code;
  }
}
