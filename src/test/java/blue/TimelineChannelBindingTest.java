package blue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Exact published binding/commitment wire invariants; source authentication is tested separately.
 */
@Tag("coordination")
class TimelineChannelBindingTest {
  private static final BluePackage PACKAGE =
      PackageLoader.create()
          .resource(
              TimelineChannelBindingTest.class.getClassLoader(),
              "coordination/coordination.bluepkg",
              ProviderSignatures.COORDINATION_PACKAGE);
  private static final BlueId ID = Language.create().readYaml("sample: true").blueId();

  @Test
  void bothBindingsMatchThePublishedProfileWithoutGenericInvocationOrAdmissionCharges() {
    TimelineChannels profile = TimelineChannels.verifiedBy(PACKAGE, request -> List.of());
    ContractProcessor processor =
        new ContractProcessor(Language.create(), ContractBindings.dispatch());
    profile.registrations().forEach(channel -> channel.install(processor));
    for (String name : List.of("Timeline Channel", "Public Timeline Channel")) {
      boolean publicSource = name.startsWith("Public");
      ContractNative.Binding actual =
          processor.registry.extensions.get(PACKAGE.export(name).value()).binding;
      assertEquals(
          new ContractNative.Binding(
                  PACKAGE.export(name).value(),
                  PACKAGE.protocol().orElseThrow().rules().value(),
                  "external-channel",
                  publicSource ? List.of("provider", "url") : List.of("timeline", "actor"),
                  List.of(),
                  publicSource ? "coordinationPublicTimeline" : "coordinationTimeline",
                  Map.of("accept", 1L),
                  false,
                  List.of(),
                  "live")
              .wire(),
          actual.wire());
    }
  }

  @Test
  void admissionRefusesAnyDifferentRuntimeBeforeCallingTheHost() {
    int[] calls = {0};
    TimelineChannels profile =
        TimelineChannels.verifiedBy(
            PACKAGE,
            request -> {
              calls[0]++;
              return List.of();
            });
    ContractProcessor processor =
        new ContractProcessor(Language.create(), ContractBindings.dispatch());
    profile.registrations().forEach(channel -> channel.install(processor));
    ContractNative.Installed installed =
        processor.registry.extensions.get(PACKAGE.export("Timeline Channel").value());
    Map<String, Object> different = new LinkedHashMap<>(installed.binding.wire());
    different.put("namespace", "another-runtime");
    assertThrows(
        ContractModel.Error.class,
        () ->
            installed.implementation.verifyAdmission(
                new ContractNative.AdmissionContext(
                    "snapshot",
                    ID.value(),
                    ID.value(),
                    7,
                    different,
                    List.of(row("root", "first")),
                    Map.of(),
                    null)));
    assertEquals(0, calls[0]);
  }

  @Test
  void closedCommitmentBindsOriginalPayloadAndRetainsNegativeAssessmentsInCodePointOrder() {
    TimelineChannels profile =
        TimelineChannels.verifiedBy(
            PACKAGE,
            request -> {
              assertNotEquals(
                  request.inputId(), request.entryId(), "Local command envelopes remain distinct");
              assertEquals(Map.of("transportOnly", "not-a-commitment-field"), request.evidence());
              List<TimelineChannels.Assessment> result = new ArrayList<>();
              for (NativeAdmission.Receiver row : request.receivers()) {
                result.add(
                    new TimelineChannels.Assessment(
                        row,
                        row.channel().equals("first")
                            ? TimelineChannels.Disposition.ELIGIBLE
                            : TimelineChannels.Disposition.INAPPLICABLE,
                        false,
                        "Established decision"));
              }
              Collections.reverse(result);
              return result;
            });
    ContractProcessor processor =
        new ContractProcessor(Language.create(), ContractBindings.dispatch());
    profile.registrations().forEach(channel -> channel.install(processor));
    ContractNative.Installed installed =
        processor.registry.extensions.get(PACKAGE.export("Timeline Channel").value());
    String input = Language.create().readYaml("command: local").blueId().value();
    Map<String, Object> value =
        installed.implementation.verifyAdmission(
            new ContractNative.AdmissionContext(
                "original-snapshot",
                input,
                ID.value(),
                7,
                installed.binding.wire(),
                List.of(
                    row("\uD800\uDC00", "second"), row("\uE000", "second"), row("\uE000", "first")),
                Map.of("transportOnly", "not-a-commitment-field"),
                null));
    assertEquals(
        Set.of(
            "snapshot",
            "entry",
            "timestamp",
            "runtime",
            "decisions",
            "assessments",
            "operationAudience"),
        value.keySet());
    assertEquals("original-snapshot", value.get("snapshot"));
    assertEquals(ID.value(), value.get("entry"));
    assertEquals(
        ContractWire.write(installed.binding.wire()), ContractWire.write(value.get("runtime")));
    List<?> rows = (List<?>) value.get("assessments");
    assertEquals(
        List.of("\uE000:first", "\uE000:second", "\uD800\uDC00:second"),
        rows.stream()
            .map(
                raw -> {
                  Map<?, ?> r = (Map<?, ?>) raw;
                  return r.get("member") + ":" + r.get("channel");
                })
            .toList());
    for (Object raw : rows) {
      assertEquals(
          Set.of("member", "document", "channel", "generation", "disposition", "reason"),
          ((Map<?, ?>) raw).keySet());
    }
    Map<?, ?> decisions = (Map<?, ?>) value.get("decisions");
    assertEquals(true, decisions.get("[\"\uE000\",\"" + ID + "\",\"first\",1]"));
    assertEquals(false, decisions.get("[\"\uE000\",\"" + ID + "\",\"second\",1]"));
    assertEquals(Map.of("\uE000", false, "\uD800\uDC00", false), value.get("operationAudience"));
    assertFalse(value.toString().contains("transportOnly"));
    assertThrows(UnsupportedOperationException.class, value::clear);
  }

  @Test
  void independentReceiversDoNotShareOneProjectedValueBudget() {
    List<NativeAdmission.Receiver> retained = new ArrayList<>();
    TimelineChannels profile =
        TimelineChannels.verifiedBy(
            PACKAGE,
            request -> {
              retained.addAll(request.receivers());
              return request.receivers().stream()
                  .map(
                      receiver ->
                          new TimelineChannels.Assessment(
                              receiver,
                              TimelineChannels.Disposition.ELIGIBLE,
                              true,
                              "Verified independently"))
                  .toList();
            });
    ContractProcessor processor =
        new ContractProcessor(Language.create(), ContractBindings.dispatch());
    profile.registrations().forEach(channel -> channel.install(processor));
    ContractNative.Installed installed =
        processor.registry.extensions.get(PACKAGE.export("Timeline Channel").value());
    List<Object> rows = new ArrayList<>();
    // This collection exceeds the old aggregate value budget while each receiver is tiny.
    for (int index = 0; index < 20_000; index++) {
      rows.add(row("member-" + index, "input"));
    }
    var input =
        new ContractNative.AdmissionContext(
            "snapshot", ID.value(), ID.value(), 7, installed.binding.wire(), rows, Map.of(), null);
    assertThrows(ContractNative.AccessFailure.class, () -> new NativeValues().freeze(rows));
    Map<String, Object> result = installed.implementation.verifyAdmission(input);
    assertEquals(20_000, ((List<?>) result.get("assessments")).size());
    assertEquals(20_000, ((Map<?, ?>) result.get("decisions")).size());
    assertEquals(20_000, ((Map<?, ?>) result.get("operationAudience")).size());
    assertEquals(20_000, retained.size());
    assertThrows(
        UnsupportedOperationException.class,
        () -> retained.getFirst().header().put("changed", true));
    assertThrows(UnsupportedOperationException.class, () -> result.put("changed", true));
    @SuppressWarnings("unchecked")
    Map<String, Object> first = (Map<String, Object>) rows.getFirst();
    first.put("header", Map.of("changed", true));
    assertFalse(retained.getFirst().header().containsKey("changed"));
  }

  @Test
  void independentReceiverAndAssessmentBoundsStillApply() {
    String oversized = "x".repeat(Math.toIntExact(ProjectionLimits.defaults().characters()) + 1);
    for (boolean oversizedHeader : List.of(true, false)) {
      int[] invoked = {0};
      TimelineChannels profile =
          TimelineChannels.verifiedBy(
              PACKAGE,
              request -> {
                invoked[0]++;
                return List.of(
                    new TimelineChannels.Assessment(
                        request.receivers().getFirst(),
                        TimelineChannels.Disposition.ELIGIBLE,
                        true,
                        oversized));
              });
      ContractProcessor processor =
          new ContractProcessor(Language.create(), ContractBindings.dispatch());
      profile.registrations().forEach(channel -> channel.install(processor));
      ContractNative.Installed installed =
          processor.registry.extensions.get(PACKAGE.export("Timeline Channel").value());
      Map<String, Object> receiver = row("member", "input");
      if (oversizedHeader) {
        receiver.put("header", Map.of("tooLarge", oversized));
      }
      var input =
          new ContractNative.AdmissionContext(
              "snapshot",
              ID.value(),
              ID.value(),
              7,
              installed.binding.wire(),
              List.of(receiver),
              Map.of(),
              null);
      var failure =
          assertThrows(
              ContractNative.AccessFailure.class,
              () -> installed.implementation.verifyAdmission(input));
      assertInstanceOf(BlueException.class, failure.failure);
      assertTrue(failure.failure.getMessage().contains("text length"));
      assertEquals(oversizedHeader ? 0 : 1, invoked[0]);
    }
  }

  private static Map<String, Object> row(String member, String channel) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("member", member);
    row.put("document", ID.value());
    row.put("channel", channel);
    row.put("generation", 1L);
    row.put("declaration", ID.value());
    row.put(
        "header",
        Map.of("timeline", Map.of("blueId", ID.value()), "actor", Map.of("blueId", ID.value())));
    row.put("checkpoint", null);
    return row;
  }
}
