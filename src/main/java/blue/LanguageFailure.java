package blue;

import java.util.LinkedHashMap;
import java.util.Map;

/** Internal semantic failure; unavailable evidence is distinct from invalid content. */
final class LanguageFailure extends RuntimeException {
  private static final long serialVersionUID = 1L;
  private final String category;
  final transient Map<String, Object> data = new LinkedHashMap<>();

  LanguageFailure(String category) {
    this(category, "");
  }

  LanguageFailure(String category, String detail) {
    super(detail);
    this.category = category;
  }

  String category() {
    return category;
  }

  Map<String, Object> outcome() {
    var result = new LinkedHashMap<String, Object>();
    result.put(
        "outcome",
        category.equals("ProviderUnavailable") || category.equals("OperationLimitExceeded")
            ? "Incomplete"
            : "Invalid");
    result.put("category", category);
    result.put("detail", getMessage());
    result.put("path", null);
    result.put(
        "message", (getMessage().isEmpty() ? category : getMessage()) + " at the requested node.");
    result.putAll(data);
    return result;
  }

  /** Host exhaustion never certifies invalidity or a partial successful graph. */
  static <T> T guard(java.util.function.Supplier<T> operation) {
    try {
      return operation.get();
    } catch (StackOverflowError error) {
      var failure = new LanguageFailure("OperationLimitExceeded", "host traversal recursion");
      failure.initCause(error);
      throw failure;
    }
  }

  static void require(boolean condition, String category) {
    if (!condition) {
      throw new LanguageFailure(category);
    }
  }

  static void require(boolean condition, String category, String detail) {
    if (!condition) {
      throw new LanguageFailure(category, detail);
    }
  }
}
