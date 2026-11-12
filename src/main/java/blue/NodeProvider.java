package blue;

import java.util.Optional;

/**
 * Supplies immutable content by exact identity.
 *
 * <p>Implementations shared by an engine must be thread-safe. A lookup may be repeated or run
 * concurrently, including for the same identity. Return the same content for a given identity; use
 * an empty result for unavailable content. The engine verifies supplied identities.
 */
@FunctionalInterface
public interface NodeProvider {
  /**
   * Looks up content without changing it.
   *
   * @param id the requested identity
   * @return the content, or empty if unavailable; never null
   */
  Optional<Node> find(BlueId id);
}
