package blue;

import java.util.Optional;

/** Supplies exact Language content or complete cyclic proofs. Results are verified before use. */
@FunctionalInterface
public interface LanguageProvider {
  /**
   * Looks up an identity without executing Source instructions.
   *
   * @param id the requested final identity
   * @return exact evidence, or empty when unavailable
   */
  Optional<LanguageRecord> find(BlueId id);
}
