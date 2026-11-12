package blue;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

/** Semantic memo branches that may depend on temporary native facts; all are revoked together. */
final class LanguageMemoScope implements AutoCloseable {
  private record Saved(
      Map<String, LanguageModel.Head> heads,
      Map<String, Boolean> concrete,
      Set<LanguageContext.ValidationKey> validation,
      Set<String> closures,
      LanguageFacts facts) {}

  private final Map<LanguageContext, Saved> branches = new IdentityHashMap<>();
  private boolean closed;

  void attach(LanguageContext context) {
    if (closed) {
      throw new IllegalStateException("Semantic memo scope is closed");
    }
    if (context.memoScope == this) {
      return;
    }
    if (context.memoScope != null) {
      throw new IllegalStateException("Nested semantic memo scope");
    }
    branches.put(
        context,
        new Saved(
            context.heads,
            context.concreteFacts,
            context.validation,
            context.verifiedClosures,
            context.facts));
    context.heads = new LanguageMap<>(context.heads);
    context.concreteFacts = new LanguageMap<>(context.concreteFacts);
    context.validation = new LanguageSet<>(context.validation);
    context.verifiedClosures = new LanguageSet<>(context.verifiedClosures);
    context.clearRetainedExpansion();
    context.memoScope = this;
  }

  @Override
  public void close() {
    if (closed) {
      return;
    }
    closed = true;
    branches.forEach(
        (context, saved) -> {
          context.heads = saved.heads();
          context.concreteFacts = saved.concrete();
          context.validation = saved.validation();
          context.verifiedClosures = saved.closures();
          context.facts = saved.facts();
          context.clearRetainedExpansion();
          context.memoScope = null;
        });
    branches.clear();
  }
}
