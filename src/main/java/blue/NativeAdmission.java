package blue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * One native verifier's snapshot-bound inputs and shared gas allowance. Every method rejects use
 * from another thread or after the verifier returns. No document mutation, host I/O or clock is
 * provided. Detached immutable values may be retained; retaining this context grants no authority.
 */
public final class NativeAdmission {
  /**
   * One receiver of this native channel type in the exact input snapshot.
   *
   * @param member graph-local member identifier, not a durable host document identifier
   * @param documentId input document content identity
   * @param channel local declaration name
   * @param generation current channel lifetime generation
   * @param declarationId exact channel declaration identity
   * @param header immutable admitted header fields
   * @param checkpoint last consumed event, or empty
   */
  public record Receiver(
      String member,
      BlueId documentId,
      String channel,
      long generation,
      BlueId declarationId,
      Map<String, Object> header,
      Optional<NativeChannel.Checkpoint> checkpoint) {
    /** Captures immutable receiver data; this record is not itself admission evidence. */
    @SuppressWarnings("unchecked")
    public Receiver {
      Objects.requireNonNull(member);
      Objects.requireNonNull(documentId);
      Objects.requireNonNull(channel);
      Objects.requireNonNull(declarationId);
      Objects.requireNonNull(checkpoint);
      header = (Map<String, Object>) BexValues.freeze(Objects.requireNonNull(header));
      if (generation < 1) {
        throw new IllegalArgumentException("Positive channel generation required");
      }
    }
  }

  private final long thread = Thread.currentThread().threadId();
  private final NativeValues values = new NativeValues();
  private ContractNative.AdmissionContext input;
  private List<Receiver> receivers;

  NativeAdmission(ContractNative.AdmissionContext input) {
    this.input = input;
  }

  /**
   * Returns the exact input snapshot identity, including processing metadata and runtime bindings.
   *
   * @return snapshot identity
   */
  public String snapshotIdentity() {
    return input().snapshot();
  }

  /**
   * Returns the unchanged business payload identity.
   *
   * @return payload identity
   */
  public BlueId eventId() {
    return new BlueId(input().payload());
  }

  /**
   * Returns the execution input identity. A command-identified local invocation uses its command
   * envelope here, while {@link #eventId()} still identifies the business payload.
   *
   * @return canonical event or local command-envelope identity
   */
  public BlueId inputId() {
    return new BlueId(input().event());
  }

  /**
   * Returns the explicit logical timestamp supplied for this invocation.
   *
   * @return logical timestamp
   */
  public long timestamp() {
    return input().timestamp();
  }

  /**
   * Returns the registered channel type whose evidence is being checked.
   *
   * @return exact registered type identity
   */
  public BlueId channelTypeId() {
    return new BlueId(ContractModel.str(input().runtime().get("type_id")));
  }

  /**
   * Returns the exact native behavior promised by the registration.
   *
   * @return semantics identity
   */
  public BlueId semanticsId() {
    return new BlueId(ContractModel.str(input().runtime().get("semantics_id")));
  }

  /**
   * Returns all receivers of this binding in deterministic order, including declarations,
   * generations and previous checkpoints. Each receiver is materialized as a bounded immutable
   * public value. The collection describes independent receiving documents and does not share a
   * single-value projection budget.
   *
   * @return immutable receivers
   */
  public List<Receiver> receivers() {
    ContractNative.AdmissionContext current = input();
    if (receivers == null) {
      List<Receiver> result = new ArrayList<>();
      for (Object item : current.members()) {
        Map<String, Object> row = ContractModel.obj(values.freeze(item));
        result.add(receiver(row));
      }
      receivers = List.copyOf(result);
    }
    return receivers;
  }

  static Receiver receiver(Map<String, Object> row) {
    Object previous = row.get("checkpoint");
    Optional<NativeChannel.Checkpoint> checkpoint = Optional.empty();
    if (previous != null) {
      Map<String, Object> data = ContractModel.obj(previous);
      checkpoint =
          Optional.of(
              new NativeChannel.Checkpoint(
                  new BlueId(ContractModel.str(ContractModel.obj(data.get("event")).get("blueId"))),
                  ContractModel.integer(data.get("timestamp"))));
    }
    return new Receiver(
        ContractModel.str(row.get("member")),
        new BlueId(ContractModel.str(row.get("document"))),
        ContractModel.str(row.get("channel")),
        ContractModel.integer(row.get("generation")),
        new BlueId(ContractModel.str(row.get("declaration"))),
        ContractModel.obj(row.get("header")),
        checkpoint);
  }

  /**
   * Returns the untrusted evidence supplied for this type. Validate its authority and every
   * protocol-required binding before returning a verified projection.
   *
   * @return immutable evidence data
   */
  public Object evidence() {
    return values.freeze(input().evidence());
  }

  /**
   * Charges declared native work against the same root/shared allowance as channel selection.
   *
   * @param counter declared tariff name
   * @param units nonnegative occurrence count
   */
  public void charge(String counter, long units) {
    input().gas().charge(Objects.requireNonNull(counter), units);
  }

  /**
   * Returns the currently available shared/root-local gas.
   *
   * @return remaining gas
   */
  public long remainingGas() {
    return input().gas().remaining();
  }

  /**
   * Rejects the complete invocation before any external channel executes.
   *
   * @param reason diagnostic rejection reason
   */
  public void reject(String reason) {
    input();
    throw new ContractModel.Error("ADMISSION_REJECTED", "reason", Objects.requireNonNull(reason));
  }

  Map<String, Object> verified(Map<String, Object> projection) {
    input();
    return values.object(projection);
  }

  private ContractNative.AdmissionContext input() {
    if (input == null || thread != Thread.currentThread().threadId()) {
      throw new IllegalStateException("Native admission is outside its invocation");
    }
    return input;
  }

  void close() {
    input = null;
    receivers = null;
    values.clear();
  }
}
