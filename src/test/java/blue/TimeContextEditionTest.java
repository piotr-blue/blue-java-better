package blue;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class TimeContextEditionTest {
  private static final BluePackage TIME =
      PackageLoader.create()
          .resource(
              TimeContextEditionTest.class.getClassLoader(),
              "time/context.bluepkg",
              TimeBindings.CONTEXT_PUBLICATION);
  private static final Language LANGUAGE =
      Language.newBuilder().mount("Contracts", Contracts.publication()).mount("Time", TIME).build();

  // Produced by the pinned original blue-java implementation, not by this adapter.
  static Stream<Arguments> contexts() {
    return Stream.of(
        Arguments.of("7", "C7DA77YP9FRAqieuatMbX3peJvky6nEtY5GFNd74ZrAJ"),
        Arguments.of("\"hello\"", "AcH5AYxRbjoXuSkxx5Msra5wjW8hupJimYR8CEqf8bzk"),
        Arguments.of("true", "AuLyeBwwJttmomGAnTZXmKh36nFVWeW7LrLwvZcgrw5w"),
        Arguments.of("[1,2]", "AU86opZJxNnEqPuuDZZgFrKknxvHR9DZe98LtSeAdLnE"),
        Arguments.of("{\"value\":\"hello\"}", "AcH5AYxRbjoXuSkxx5Msra5wjW8hupJimYR8CEqf8bzk"),
        Arguments.of("{\"note\":7}", "FJFWVdqpAe1ywRBSUtx5UWGGdy7LnDVzkuaMxA3qXDC"));
  }

  @ParameterizedTest
  @MethodSource("contexts")
  void requestIdentityMatchesOriginalLanguageAndNativeScheduling(String context, String expected) {
    String request =
        "{\"type\":{\"blueId\":\""
            + TIME.export("Timer Request")
            + "\"},\"channel\":\"timer\",\"timestamp\":100,\"context\":"
            + context
            + "}";
    assertEquals(expected, LANGUAGE.readJson(request).blueId().value());
    var runtime =
        Contracts.newBuilder()
            .language(LANGUAGE)
            .timeBindings(
                TimeBindings.verifiedBy(
                    TIME,
                    ignored -> {
                      throw new AssertionError("Scheduling must not grant firing authority");
                    }))
            .build();
    String source =
        """
        contracts:
          timer: {type: Time/Timer Channel}
          init: {type: Contracts/Lifecycle Event Channel}
          start:
            type: Contracts/Sequential Workflow
            channel: init
            steps:
              - type: Time/Schedule Timer
                request: {channel: timer, timestamp: 100, context: %s}
        """
            .formatted(context);
    try (var session = runtime.openYaml(source)) {
      var result = session.initialize().requireApplied();
      assertEquals(1, result.events().size());
      var event = LANGUAGE.readJson(LanguageCodec.write(result.events().getFirst()));
      assertEquals(expected, event.blueId().value());
      try (var restored = runtime.restore(session.snapshot(), session.identity())) {
        assertEquals(session.identity(), restored.identity());
      }
    }
  }
}
