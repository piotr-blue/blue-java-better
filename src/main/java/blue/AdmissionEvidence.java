package blue;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable, untrusted evidence for one external invocation, keyed by exact native channel type.
 * Supplying evidence grants no authority: the installed channel verifier must validate it against
 * the current invocation. Evidence is separate from the business event and is never installed as
 * package content or reused implicitly by a session.
 */
public final class AdmissionEvidence {
  private static final AdmissionEvidence EMPTY = new AdmissionEvidence(Map.of());
  private final Map<String, Object> values;

  private AdmissionEvidence(Map<String, Object> values) {
    this.values = values;
  }

  /**
   * Returns an empty evidence set.
   *
   * @return empty evidence
   */
  public static AdmissionEvidence empty() {
    return EMPTY;
  }

  /**
   * Captures bounded immutable evidence for one exact channel type.
   *
   * @param channelType registered native channel type
   * @param evidence nonnull protocol record data, interpreted only by its verifier
   * @return a new evidence set
   */
  public static AdmissionEvidence of(BlueId channelType, Object evidence) {
    return EMPTY.with(channelType, evidence);
  }

  /**
   * Captures a complete evidence set under one shared resource bound.
   *
   * @param evidence nonnull proofs keyed by exact channel type
   * @return an independent immutable evidence set
   */
  public static AdmissionEvidence copyOf(Map<BlueId, ?> evidence) {
    Map<String, Object> copy = new LinkedHashMap<>();
    Objects.requireNonNull(evidence)
        .forEach(
            (type, proof) ->
                copy.put(Objects.requireNonNull(type).value(), Objects.requireNonNull(proof)));
    return capture(copy);
  }

  /**
   * Returns immutable record data for durable transport. Capturing it grants no authority.
   *
   * @return proofs keyed by exact channel type
   */
  public Map<BlueId, Object> asMap() {
    Map<BlueId, Object> result = new LinkedHashMap<>();
    values.forEach((type, proof) -> result.put(new BlueId(type), proof));
    return java.util.Collections.unmodifiableMap(result);
  }

  /**
   * Returns a new set with this type's evidence added or replaced. Other types remain unchanged.
   * The complete set uses {@link ProjectionLimits#defaults()} as a host resource bound.
   *
   * @param channelType registered native channel type
   * @param evidence nonnull protocol record data: integers, text, booleans, null, lists and maps
   * @return an independent immutable evidence set
   */
  public AdmissionEvidence with(BlueId channelType, Object evidence) {
    Map<String, Object> copy = new LinkedHashMap<>(values);
    copy.put(Objects.requireNonNull(channelType).value(), Objects.requireNonNull(evidence));
    return capture(copy);
  }

  @SuppressWarnings("unchecked")
  private static AdmissionEvidence capture(Map<String, Object> copy) {
    if (copy.isEmpty()) {
      return EMPTY;
    }
    return Contracts.boundary(
        () -> {
          try {
            Map<String, Object> snapshot = (Map<String, Object>) new NativeValues().freeze(copy);
            // Evidence must survive portable replay in the same private record domain. Validate
            // without retaining another serialized copy; the projection budget already bounds work.
            ContractCanonical.write(snapshot, java.io.OutputStream.nullOutputStream());
            return new AdmissionEvidence(snapshot);
          } catch (ContractNative.AccessFailure failure) {
            throw failure.failure;
          } catch (java.io.IOException failure) {
            throw new java.io.UncheckedIOException(failure);
          }
        });
  }

  Map<String, Object> values() {
    return values;
  }
}
