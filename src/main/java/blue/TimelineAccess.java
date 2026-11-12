package blue;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Replays one published Timeline Access document through canonical Contracts. The initial lists
 * grant nothing until their exact document is published by an Agent Actor on the adopted provider's
 * reserved control timeline. Updates execute the package's original BEX program.
 *
 * <p>One instance owns a process-local replay lifetime. Calls are serialized and publish progress
 * only after the entire newly verified suffix succeeds. The caller persists source evidence and
 * returned dispositions. This is not a provider, an HTTP authorization service or a durable store.
 */
public final class TimelineAccess implements AutoCloseable {
  private TimelineAccessReplay replay;

  private TimelineAccess(TimelineAccessReplay replay) {
    this.replay = replay;
  }

  /**
   * Selects an initial access document and its trusted provider without granting permissions.
   *
   * @param coordination exact supported Coordination publication
   * @param provider admitted provider selected by application trust policy
   * @param descriptor signed bootstrap descriptor with its reserved control timeline
   * @param initial admitted initial Timeline Access document, retaining its dependency evidence
   * @return an independent, initially unconfirmed replay
   */
  public static TimelineAccess track(
      BluePackage coordination,
      LanguageDocument provider,
      LanguageContent descriptor,
      LanguageDocument initial) {
    return Language.run(
        () ->
            new TimelineAccess(
                new TimelineAccessReplay(
                    Objects.requireNonNull(coordination), Objects.requireNonNull(provider),
                    Objects.requireNonNull(descriptor), Objects.requireNonNull(initial))));
  }

  /** Describes how one original control entry affected this access document. */
  public enum Kind {
    /** The original exact document was published and initialized through Contracts. */
    PUBLISHED,
    /** Its published update operation committed through Contracts. */
    UPDATED,
    /** The entry belongs to other content or another access document. */
    INAPPLICABLE
  }

  /**
   * One retained source disposition. Constructing this data record grants no authority.
   *
   * @param entry exact original control entry
   * @param timestamp original logical timestamp
   * @param kind publication, update or unrelated input
   * @param transition real Contracts outcome, absent for inapplicable input
   * @param document actual after-document, absent for inapplicable input; retain it for historical
   *     access checks instead of substituting the latest view
   */
  public record Disposition(
      BlueId entry,
      long timestamp,
      Kind kind,
      Optional<Contracts.Transition> transition,
      Optional<LanguageDocument> document) {
    /** Requires complete immutable observations. */
    public Disposition {
      Objects.requireNonNull(entry);
      Objects.requireNonNull(kind);
      Objects.requireNonNull(transition);
      Objects.requireNonNull(document);
      if (transition.isPresent() != (kind != Kind.INAPPLICABLE)
          || document.isPresent() != transition.isPresent())
        throw new IllegalArgumentException("Applied entries require both outcome and document");
    }
  }

  /** An established permission state at a particular closed control-history bound. */
  public static final class View {
    private final LanguageDocument document;
    private final long changedAt, completeBefore;
    private final Set<BlueId> timelines;
    private final Set<String> keys;

    View(
        LanguageDocument document,
        long changedAt,
        long completeBefore,
        Set<BlueId> timelines,
        Set<String> keys) {
      this.document = document;
      this.changedAt = changedAt;
      this.completeBefore = completeBefore;
      this.timelines = Set.copyOf(timelines);
      this.keys = Set.copyOf(keys);
    }

    /**
     * Returns the actual executed Timeline Access document, including Contracts processing state.
     *
     * @return actual executed Timeline Access document, including Contracts processing state
     */
    public LanguageDocument document() {
      return document;
    }

    /**
     * Returns the timestamp of the last applicable publication/update in this view.
     *
     * @return timestamp of the last applicable publication/update in this view
     */
    public long changedAt() {
      return changedAt;
    }

    /**
     * Returns the verified exclusive control-history bound; this is not wall-clock freshness.
     *
     * @return verified exclusive control-history bound; this is not wall-clock freshness
     */
    public long completeBefore() {
      return completeBefore;
    }

    /**
     * Returns the exact permitted timeline identities in this established state.
     *
     * @return exact permitted timeline identities in this established state
     */
    public Set<BlueId> allowedTimelines() {
      return timelines;
    }

    /**
     * Returns the canonical permitted reader keys in this established state.
     *
     * @return canonical permitted reader keys in this established state
     */
    public Set<String> allowedKeys() {
      return keys;
    }
  }

  /**
   * The newly published replay result. Earlier observations remain immutable.
   *
   * @param completeBefore exclusive bound of the verified control prefix
   * @param view empty until the initial document is confirmed by publication
   * @param dispositions every newly closed entry, in original control-history order
   */
  public record Update(long completeBefore, Optional<View> view, List<Disposition> dispositions) {
    /** Retains immutable observations. */
    public Update {
      Objects.requireNonNull(view);
      dispositions = List.copyOf(dispositions);
    }
  }

  /**
   * Verifies a new range or successor proof, then replays its newly closed entries. Missing data,
   * invalid evidence or contradictory accepted updates leave the previous published view intact.
   *
   * @param evidence prepared Timeline Range Evidence or Timeline Successor Evidence
   * @return newly established view and exact source dispositions
   */
  public synchronized Update advance(LanguageContent evidence) {
    checkOpen();
    return replay.advance(Objects.requireNonNull(evidence));
  }

  /**
   * Returns the last established state; absent while publication has not been verified.
   *
   * @return last established state; absent while publication has not been verified
   */
  public synchronized Optional<View> view() {
    checkOpen();
    return Optional.ofNullable(replay.view);
  }

  /** Releases process-local execution state. Previously returned immutable views remain usable. */
  @Override
  public synchronized void close() {
    replay = null;
  }

  private void checkOpen() {
    if (replay == null) throw new IllegalStateException("Timeline Access replay is closed");
  }
}
