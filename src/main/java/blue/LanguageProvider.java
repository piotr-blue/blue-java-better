package blue;

import java.util.Optional;

/**
 * Supplies exact Language content or complete cyclic proofs. Results are verified before use.
 *
 * <p>A provider shared by a {@link Language} must be thread-safe. Independent operations may call
 * it concurrently or repeatedly for the same identity. Calls are synchronous; a provider that
 * performs I/O owns its deadlines and cancellation. Language resource limits count requests but
 * cannot interrupt a blocked callback.
 *
 * <p>Providers are consulted in registration order. An empty result allows the next provider to
 * answer. A returned record is verified under the requested identity; incorrect evidence is not
 * replaced by another provider's answer. A runtime exception stops that lookup and is reported as
 * incomplete {@link LanguageException} with category {@code ProviderUnavailable}, retaining its
 * cause. Neither absence nor a transport failure certifies that a document is invalid.
 */
@FunctionalInterface
public interface LanguageProvider {
  /**
   * Looks up an identity without executing Source instructions.
   *
   * @param id the requested final identity
   * @return immutable exact evidence, or empty when unavailable; never null
   */
  Optional<LanguageRecord> find(BlueId id);
}
