package blue;

/** A BEX compilation or evaluation failure with its stable category. */
class BexFailure extends RuntimeException {
  private static final long serialVersionUID = 1L;
  private final String code;

  public BexFailure(String message) {
    this("BEX_RUNTIME_ERROR", message);
  }

  public BexFailure(String code, String message) {
    super(message);
    this.code = code;
  }

  public String code() {
    return code;
  }

  static void require(boolean test, String message) {
    if (!test) {
      throw new BexFailure(message);
    }
  }
}
