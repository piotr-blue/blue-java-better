package blue;

import static blue.ContractModel.*;

import java.util.*;

/**
 * The two exact published Coordination channel bindings, with explicit host history verification.
 * Installing this adapter does not implement acquisition, permissions, lineage or a feeder. Its
 * verifier is trusted host code and must establish those facts for the original receiving view.
 * Untrusted range evidence or a caller's acceptance flag alone is never sufficient.
 *
 * <p>Registrations are immutable and reusable. The verifier must be deterministic and thread-safe,
 * use frozen established history, and perform no document mutations or external effects. The
 * adapter builds the closed admission commitment and charges the published accept tariff.
 */
public final class TimelineChannels {
  /** Complete decisions after authenticating history, authority and required-source closure. */
  @FunctionalInterface
  public interface HistoryVerifier {
    /**
     * Assesses every supplied receiver, including negative decisions. Missing history must reject
     * the attempt, not turn into a guessed negative decision or a fabricated completion.
     *
     * @param request immutable original invocation and untrusted supplied evidence
     * @return exactly one assessment for every receiver, in any order
     */
    List<Assessment> verify(Request request);
  }

  /** The dispositions that can enter a completed native admission. */
  public enum Disposition {
    /** Authenticated, authorized direct participation at this original receiving position. */
    ELIGIBLE,
    /** Established nonparticipation, not missing history or unavailable permissions. */
    INAPPLICABLE
  }

  /**
   * Detached inputs for a history verifier. Constructing this record grants no authority.
   *
   * @param snapshotIdentity exact original Contracts snapshot
   * @param inputId execution input identity; local commands use their host envelope here
   * @param entryId unchanged original timeline entry identity
   * @param timestamp original entry timestamp, to be verified against established history
   * @param channelTypeId exact binding whose receivers are being assessed
   * @param receivers current channel declarations, generations and prior checkpoints
   * @param evidence untrusted supplied proof data; never copied into the admission commitment
   */
  public record Request(
      String snapshotIdentity,
      BlueId inputId,
      BlueId entryId,
      long timestamp,
      BlueId channelTypeId,
      List<NativeAdmission.Receiver> receivers,
      Object evidence) {
    /** Captures independent immutable input containers. */
    public Request {
      Objects.requireNonNull(snapshotIdentity);
      Objects.requireNonNull(inputId);
      Objects.requireNonNull(entryId);
      Objects.requireNonNull(channelTypeId);
      receivers = List.copyOf(receivers);
      evidence = BexValues.freeze(evidence);
    }

    /**
     * Rejects admission before any business workflow or channel checkpoint can advance.
     *
     * @param reason diagnostic reason; this does not manufacture an input disposition
     */
    public void reject(String reason) {
      throw new ContractModel.Error("ADMISSION_REJECTED", "reason", Objects.requireNonNull(reason));
    }
  }

  /**
   * A host's verified assessment of one exact receiving channel lifetime.
   *
   * @param receiver unchanged receiver from the request
   * @param disposition eligible or established inapplicable
   * @param addressed whether the original operation addresses this member; all its rows must agree
   * @param reason deterministic human-readable explanation retained in the commitment
   */
  public record Assessment(
      NativeAdmission.Receiver receiver,
      Disposition disposition,
      boolean addressed,
      String reason) {
    /** Requires a complete immutable assessment; it remains untrusted outside its verifier call. */
    public Assessment {
      Objects.requireNonNull(receiver);
      Objects.requireNonNull(disposition);
      Objects.requireNonNull(reason);
    }
  }

  private final List<NativeChannel> channels;
  private final Map<String, String> interpretedPrograms;

  private TimelineChannels(BluePackage publication, HistoryVerifier verifier) {
    Objects.requireNonNull(publication);
    Objects.requireNonNull(verifier, "A host history verifier is required");
    if (!ProviderSignatures.COORDINATION_PACKAGE.equals(publication.blueId()))
      throw new IllegalArgumentException("Unsupported Coordination publication");
    LanguageDocument rules =
        Language.newBuilder()
            .content(publication.document())
            .build()
            .define(publication.protocol().orElseThrow().rules());
    interpretedPrograms =
        Map.of(
            publication.export("Update Timeline Access").value(),
            publication.protocol().orElseThrow().rules().value());
    channels =
        List.of(
            channel(
                publication.definition("Timeline Channel"),
                rules,
                List.of("timeline", "actor"),
                "coordinationTimeline",
                verifier),
            channel(
                publication.definition("Public Timeline Channel"),
                rules,
                List.of("provider", "url"),
                "coordinationPublicTimeline",
                verifier));
  }

  /**
   * Selects the supported published bindings and a mandatory trusted host history verifier.
   *
   * @param publication verified, exactly pinned Coordination publication
   * @param verifier authenticates source participation, closure, access, lineage and lifetimes
   * @return immutable profile adapter; install through the Contracts builder
   */
  public static TimelineChannels verifiedBy(BluePackage publication, HistoryVerifier verifier) {
    return new TimelineChannels(publication, verifier);
  }

  Map<String, String> interpretedPrograms() {
    return interpretedPrograms;
  }

  List<NativeChannel> registrations() {
    return channels;
  }

  void checkCompatible(Map<BlueId, NativeStep> steps, Map<BlueId, NativeChannel> registered) {
    for (NativeChannel channel : channels) {
      if (steps.containsKey(channel.typeId())
          || registered.containsKey(channel.typeId())
              && registered.get(channel.typeId()) != channel)
        throw new IllegalArgumentException(
            "Conflicting timeline registration: " + channel.typeId());
    }
  }

  private static NativeChannel channel(
      LanguageDocument type,
      LanguageDocument rules,
      List<String> fields,
      String namespace,
      HistoryVerifier verifier) {
    ContractNative.Binding binding =
        new ContractNative.Binding(
            type.blueId().value(),
            rules.blueId().value(),
            "external-channel",
            fields,
            List.of(),
            namespace,
            Map.of("accept", 1L),
            false,
            List.of(),
            "live");
    return new NativeChannel(
        type,
        rules,
        binding,
        new ContractNative.Implementation() {
          @Override
          public boolean additiveAdmission() {
            return true;
          }

          @Override
          public boolean verifiesAdmission() {
            return true;
          }

          @Override
          public Map<String, Object> verifyAdmission(ContractNative.AdmissionContext input) {
            require(input.runtime().equals(binding.wire()), "ADMISSION_RUNTIME_MISMATCH");
            NativeAdmission context = new NativeAdmission(input);
            try {
              Request request =
                  new Request(
                      context.snapshotIdentity(),
                      context.inputId(),
                      context.eventId(),
                      context.timestamp(),
                      context.channelTypeId(),
                      context.receivers(),
                      context.evidence());
              List<Assessment> assessments = Objects.requireNonNull(verifier.verify(request));
              require(
                  assessments.size() == request.receivers().size(),
                  "INCOMPLETE_ADMISSION_ASSESSMENTS");
              return projection(request, binding, assessments);
            } finally {
              context.close();
            }
          }

          @Override
          public boolean accept(ContractNative.ExternalContext context) {
            context.gas().charge("accept");
            require(context.evidence() instanceof Map<?, ?>, "MISSING_VERIFIED_ADMISSION");
            Map<String, Object> evidence = obj(context.evidence());
            require(
                integer(evidence.get("timestamp")) == context.timestamp(),
                "ADMISSION_TIMESTAMP_MISMATCH");
            Map<String, Object> decisions = obj(evidence.get("decisions"));
            String key =
                key(context.member(), context.document(), context.channel(), context.generation());
            require(decisions.containsKey(key), "UNASSESSED_CHANNEL");
            return Boolean.TRUE.equals(decisions.get(key));
          }
        });
  }

  private static Map<String, Object> projection(
      Request request, ContractNative.Binding binding, List<Assessment> supplied) {
    Map<String, NativeAdmission.Receiver> expected = new HashMap<>();
    for (NativeAdmission.Receiver receiver : request.receivers())
      require(expected.put(key(receiver), receiver) == null, "DUPLICATE_ADMISSION_RECEIVER");
    Map<String, Object> decisions = new TreeMap<>(ContractCanonical.CODE_POINTS);
    Map<String, Object> audience = new TreeMap<>(ContractCanonical.CODE_POINTS);
    List<Assessment> ordered = new ArrayList<>(supplied);
    ordered.sort(
        Comparator.comparing((Assessment a) -> a.receiver().member(), ContractCanonical.CODE_POINTS)
            .thenComparing(a -> a.receiver().channel(), ContractCanonical.CODE_POINTS));
    List<Object> rows = new ArrayList<>();
    NativeValues values = new NativeValues();
    for (Assessment assessment : ordered) {
      NativeAdmission.Receiver receiver = assessment.receiver();
      String key = key(receiver);
      require(receiver.equals(expected.remove(key)), "UNEXPECTED_ADMISSION_ASSESSMENT");
      decisions.put(key, assessment.disposition() == Disposition.ELIGIBLE);
      Object previous = audience.putIfAbsent(receiver.member(), assessment.addressed());
      require(
          previous == null || previous.equals(assessment.addressed()),
          "INCONSISTENT_OPERATION_AUDIENCE");
      rows.add(
          values.object(
              m(
                  "member",
                  receiver.member(),
                  "document",
                  receiver.documentId().value(),
                  "channel",
                  receiver.channel(),
                  "generation",
                  receiver.generation(),
                  "disposition",
                  assessment.disposition().name(),
                  "reason",
                  assessment.reason())));
    }
    require(expected.isEmpty(), "MISSING_ADMISSION_ASSESSMENT");
    // Each callback-supplied assessment is bounded above. This engine-built collection
    // spans independent documents; applying a single-value budget would cap graph size.
    return ContractModel.obj(
        BexValues.freeze(
            m(
                "snapshot",
                request.snapshotIdentity(),
                "entry",
                request.entryId().value(),
                "timestamp",
                request.timestamp(),
                "runtime",
                binding.wire(),
                "decisions",
                decisions,
                "assessments",
                rows,
                "operationAudience",
                audience)));
  }

  private static String key(NativeAdmission.Receiver receiver) {
    return key(
        receiver.member(),
        receiver.documentId().value(),
        receiver.channel(),
        receiver.generation());
  }

  private static String key(Object member, Object document, Object channel, Object generation) {
    return ContractWire.write(List.of(member, document, channel, generation));
  }
}
