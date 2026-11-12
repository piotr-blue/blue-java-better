package blue;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * A completed value's local admission checkpoint. This is not a Blue document or a portable proof.
 * A host may persist it only together with its independently protected commitment and the value's
 * complete immutable content closure. Restoring it reuses that host's prior semantic validation; it
 * does not establish current access, document membership, execution authority or source history.
 *
 * <p>Never obtain the expected commitment from a request or from the checkpoint being restored.
 * Untrusted content must use ordinary {@link Language#read(BlueId)} instead.
 */
public final class LanguageCheckpoint {
  private static final String FORMAT = "blue.language.admission/1";
  private final Map<String, Object> record;
  private final String identity;

  private LanguageCheckpoint(BlueId value) {
    record = Map.of("format", FORMAT, "language", Language.EDITION, "blueId", value.value());
    identity = ContractModel.digest(FORMAT, record);
  }

  static LanguageCheckpoint capture(LanguageDocument value) {
    Objects.requireNonNull(value);
    if (value.goal() != Language.Goal.VALUE)
      throw new IllegalArgumentException("Completed Language value required");
    if (!value.blueId().value().equals(LanguageCore.identity(value.semantic)))
      throw new IllegalArgumentException(
          "A presentation with a different semantic identity cannot be checkpointed");
    return new LanguageCheckpoint(value.blueId());
  }

  /**
   * Returns immutable local recovery metadata. Content must be retained separately.
   *
   * @return exact edition and completed value identity
   */
  public Map<String, Object> record() {
    return record;
  }

  /**
   * Returns the commitment to store only after successful admission and durable closure retention.
   * This digest conveys no authority when supplied by an untrusted party.
   *
   * @return local checkpoint commitment, not a BlueId
   */
  public String identity() {
    return identity;
  }

  static LanguageDocument restore(Language language, Map<String, Object> record, String expected) {
    Objects.requireNonNull(language);
    Objects.requireNonNull(record);
    Objects.requireNonNull(expected);
    record = Map.copyOf(record);
    if (!record.keySet().equals(Set.of("format", "language", "blueId"))
        || !FORMAT.equals(record.get("format"))
        || !Language.EDITION.equals(record.get("language"))
        || !(record.get("blueId") instanceof String id))
      throw new IllegalArgumentException("Unsupported Language admission checkpoint");
    if (!ContractModel.digest(FORMAT, record).equals(expected))
      throw new IllegalArgumentException("Untrusted Language admission checkpoint");
    return recover(language, id);
  }

  /** Only a protected prior completion may establish this value without full admission. */
  static LanguageDocument recover(Language language, String id) {
    return Language.run(
        () -> {
          var context = language.context();
          var reference = new ExactNode.Ref(LanguageCore.finalId(id));
          // Exact body and cyclic ownership verification still happen at the read boundary. The
          // protected checkpoint, not this shallow read, supplies the previous semantic result.
          LanguageCore.open(reference, context);
          LanguageSemantics.head(LanguageSemantics.recipe(reference), context);
          return new LanguageDocument(
              language,
              reference,
              new Language.Snapshot(context.bodies, context.proofs),
              Language.Goal.VALUE,
              id,
              List.of());
        });
  }
}
