package blue;

/** A Language rejection or an incomplete operation; incomplete never means valid or absent. */
public final class LanguageException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  /** Stable specification category retained when serialized. */
  private final String category;

  /** Whether processing stopped without establishing validity or invalidity. */
  private final boolean incomplete;

  private final transient Node diagnostic;

  LanguageException(LanguageFailure failure) {
    super(failure.getMessage(), failure);
    category = failure.category();
    incomplete =
        category.equals("ProviderUnavailable") || category.equals("OperationLimitExceeded");
    diagnostic = Language.node(failure.outcome());
  }

  /**
   * Identifies the specification failure category.
   *
   * @return the stable specification failure category
   */
  public String category() {
    return category;
  }

  /**
   * Distinguishes missing evidence or exhausted resources from invalid content.
   *
   * @return true when evidence or resources were insufficient
   */
  public boolean incomplete() {
    return incomplete;
  }

  /**
   * Returns the structured failure details captured at the point of rejection.
   *
   * @return structured immutable diagnostics, including a path when established
   */
  public Node diagnostic() {
    return diagnostic;
  }
}
