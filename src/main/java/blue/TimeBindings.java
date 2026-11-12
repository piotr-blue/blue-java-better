package blue;

import static blue.ContractModel.integer;
import static blue.ContractModel.obj;
import static blue.ContractModel.require;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Exact Time request steps and processor-owned timer admission. This adapter performs no scheduling
 * or I/O. The host must durably register committed requests, order pending timers with other
 * inputs, establish complete source prefixes, and atomically publish each firing with its terminal
 * status.
 */
public final class TimeBindings {
  /** The supported Time publication for the canonical deterministic Contracts edition. */
  public static final BlueId PUBLICATION =
      new BlueId("6QHd1RcXJnddfLyePbtHouNYEpis6CXe4mMu4pNBqZrU");

  /** The separate Time edition whose optional context accepts every Blue value shape. */
  public static final BlueId CONTEXT_PUBLICATION =
      new BlueId("ESxWaRuSRX1KA9AvLXQXVhHe8joBRUaKxQBmfyUTBcx6");

  /**
   * Checks whether this adapter implements an exact published Time edition.
   *
   * @param publication immutable package identity
   * @return whether the original or unrestricted-context edition is supported
   */
  public static boolean supports(BlueId publication) {
    return PUBLICATION.equals(publication) || CONTEXT_PUBLICATION.equals(publication);
  }

  /** Checks one claimed firing against an immutable, already acquired host history view. */
  @FunctionalInterface
  public interface Verifier {
    /**
     * Validates pending state, managed identity, channel lifetime, exact message and deadline,
     * authoritative time, prerequisite coverage and the original receiving snapshot. No clock or
     * I/O may be consulted here; those observations must already be frozen by the host.
     *
     * @param request original invocation and untrusted supplied evidence
     * @return the one authorized receiver and registration
     */
    Firing verify(Request request);
  }

  /**
   * Detached original invocation. Constructing this value grants no timer authority.
   *
   * @param snapshotIdentity exact original Contracts snapshot
   * @param inputId execution input identity
   * @param eventId exact Timer Fired payload identity
   * @param timestamp requested logical deadline
   * @param receivers all current receivers of the exact timer channel type
   * @param evidence untrusted caller-supplied proof
   */
  public record Request(
      String snapshotIdentity,
      BlueId inputId,
      BlueId eventId,
      long timestamp,
      List<NativeAdmission.Receiver> receivers,
      Object evidence) {
    /** Captures immutable inputs without retaining an invocation context. */
    public Request {
      Objects.requireNonNull(snapshotIdentity);
      Objects.requireNonNull(inputId);
      Objects.requireNonNull(eventId);
      receivers = List.copyOf(receivers);
      evidence = BexValues.freeze(Objects.requireNonNull(evidence));
    }

    /**
     * Rejects the entire invocation before any channel can advance.
     *
     * @param reason deterministic rejection explanation
     */
    public void reject(String reason) {
      throw new ContractModel.Error(
          "TIME_UNTRUSTED_ADMISSION", "reason", Objects.requireNonNull(reason));
    }
  }

  /**
   * A host-verified firing; the receiver must be unchanged from the original request.
   *
   * @param receiver exact authorized receiver and channel generation
   * @param registrationId exact committed registration identity
   */
  public record Firing(NativeAdmission.Receiver receiver, BlueId registrationId) {
    /** Requires the complete receiver and registration identity. */
    public Firing {
      Objects.requireNonNull(receiver);
      Objects.requireNonNull(registrationId);
    }
  }

  private final NativeChannel channel;
  private final List<NativeStep> steps;

  private TimeBindings(BluePackage publication, Verifier verifier) {
    Objects.requireNonNull(publication);
    Objects.requireNonNull(verifier);
    if (!supports(publication.blueId())) {
      throw new IllegalArgumentException("Unsupported Time publication");
    }
    LanguageDocument rules =
        Language.newBuilder()
            .content(publication.document())
            .build()
            .define(publication.protocol().orElseThrow().rules());
    LanguageDocument channelType = publication.definition("Timer Channel");
    channel =
        new NativeChannel(
            channelType,
            rules,
            binding(channelType, rules, "external-channel", List.of(), "timeTimerChannel"),
            new ContractNative.Implementation() {
              @Override
              public boolean verifiesAdmission() {
                return true;
              }

              @Override
              public Map<String, Object> verifyAdmission(ContractNative.AdmissionContext context) {
                // An ordinary Timeline payload, even a Timer Fired-shaped one, has no Time
                // admission.
                if (context.evidence() == null) {
                  return Map.of("allowed", false);
                }
                var receivers =
                    context.members().stream()
                        .map(ContractModel::obj)
                        .map(NativeAdmission::receiver)
                        .toList();
                var request =
                    new Request(
                        context.snapshot(),
                        new BlueId(context.event()),
                        new BlueId(context.payload()),
                        context.timestamp(),
                        receivers,
                        context.evidence());
                Firing firing =
                    Objects.requireNonNull(
                        verifier.verify(request), "A verified firing is required");
                require(receivers.contains(firing.receiver()), "TIME_CHANNEL_GENERATION_MISMATCH");
                var selected = firing.receiver();
                return Map.of(
                    "allowed",
                    true,
                    "snapshot",
                    context.snapshot(),
                    "event",
                    context.event(),
                    "timestamp",
                    context.timestamp(),
                    "registrationId",
                    firing.registrationId().value(),
                    "member",
                    selected.member(),
                    "channel",
                    selected.channel(),
                    "generation",
                    selected.generation());
              }

              @Override
              public boolean accept(ContractNative.ExternalContext context) {
                context.gas().charge("validate");
                var proof = obj(context.evidence());
                return Boolean.TRUE.equals(proof.get("allowed"))
                    && context.member().equals(proof.get("member"))
                    && context.channel().equals(proof.get("channel"))
                    && context.generation() == integer(proof.get("generation"));
              }
            });
    steps = List.of(step(publication, rules, false), step(publication, rules, true));
  }

  /**
   * Selects the exact supported Time bindings with a mandatory trusted host verifier.
   *
   * @param publication verified pinned Time package
   * @param verifier authenticates each claimed firing against frozen durable host state
   * @return immutable adapter, installed through {@link
   *     Contracts.Builder#timeBindings(TimeBindings)}
   */
  public static TimeBindings verifiedBy(BluePackage publication, Verifier verifier) {
    return new TimeBindings(publication, verifier);
  }

  private static ContractNative.Binding binding(
      LanguageDocument type,
      LanguageDocument rules,
      String role,
      List<String> fields,
      String namespace) {
    return new ContractNative.Binding(
        type.blueId().value(),
        rules.blueId().value(),
        role,
        List.of(),
        fields,
        namespace,
        Map.of("validate", 1L),
        false,
        List.of(),
        "live");
  }

  private static NativeStep step(BluePackage publication, LanguageDocument rules, boolean cancel) {
    String name = cancel ? "Cancel Timer" : "Schedule Timer";
    LanguageDocument type = publication.definition(name);
    String requestType =
        publication.export(cancel ? "Cancel Timer Request" : "Timer Request").value();
    return new NativeStep(
        type,
        rules,
        binding(
            type,
            rules,
            "step",
            List.of("request"),
            cancel ? "timeCancelTimer" : "timeScheduleTimer"),
        new ContractNative.Implementation() {
          @Override
          public Object execute(ContractNative.Context context) {
            require(context.body().containsKey("request"), "TIME_MISSING_REQUEST");
            Object evaluated;
            try {
              evaluated =
                  new NativeValues()
                      .freeze(context.evaluate().apply(context.body().get("request")));
            } catch (ContractModel.Error failure) {
              if (failure.code().equals("EFFECTFUL_EXPRESSION")) {
                throw new ContractModel.Error("TIME_EFFECTFUL_ARGUMENTS");
              }
              throw failure;
            }
            require(evaluated instanceof Map<?, ?>, "TIME_REQUEST_NOT_OBJECT");
            var request = new LinkedHashMap<>(obj(evaluated));
            context.gas().charge("validate", request.size());
            validate(request, cancel);
            request.put("type", Map.of("blueId", requestType));
            return Map.of("events", List.of(request));
          }
        });
  }

  private static void validate(Map<String, Object> request, boolean cancel) {
    Set<String> required = cancel ? Set.of("channel", "key") : Set.of("channel", "timestamp");
    var allowed = new HashSet<>(required);
    if (!cancel) {
      allowed.addAll(Set.of("key", "context"));
    }
    require(
        request.keySet().containsAll(required) && allowed.containsAll(request.keySet()),
        "TIME_INVALID_REQUEST_FIELDS");
    for (String name : List.of("channel", "key")) {
      if (request.containsKey(name)) {
        require(request.get(name) instanceof String text && !text.isEmpty(), "TIME_INVALID_NAME");
      }
    }
    if (!cancel) {
      try {
        integer(request.get("timestamp"));
      } catch (ContractModel.Error failure) {
        throw new ContractModel.Error("TIME_INVALID_TIMESTAMP");
      }
    }
  }

  NativeChannel channel() {
    return channel;
  }

  List<NativeStep> steps() {
    return steps;
  }

  void checkCompatible(
      Map<BlueId, NativeStep> registeredSteps, Map<BlueId, NativeChannel> channels) {
    if (registeredSteps.containsKey(channel.typeId())
        || channels.containsKey(channel.typeId()) && channels.get(channel.typeId()) != channel) {
      throw new IllegalArgumentException("Conflicting Time channel registration");
    }
    for (NativeStep step : steps) {
      if (channels.containsKey(step.typeId())
          || registeredSteps.containsKey(step.typeId())
              && registeredSteps.get(step.typeId()) != step) {
        throw new IllegalArgumentException("Conflicting Time step registration");
      }
    }
  }
}
