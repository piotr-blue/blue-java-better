package blue;

import java.util.Arrays;
import java.util.Map;

/** Bounded diagnostic: fresh document state on every attempt, one immutable configuration. */
public final class ConfigurationReuseProbe {
  private ConfigurationReuseProbe() {}

  public static void main(String[] args) {
    for (String profile : new String[] {"base", "dispatch", "eight-native-steps"}) {
      Contracts.Builder builder = Contracts.newBuilder();
      if (!profile.equals("base")) {
        builder.enableDocumentDispatch();
      }
      if (profile.equals("eight-native-steps")) {
        Language language =
            Language.newBuilder().mount("Contracts", Contracts.publication()).build();
        LanguageDocument semantics =
            language.readYaml("description: Configuration reuse probe semantics");
        for (int n = 0; n < 8; n++) {
          LanguageDocument type =
              language.defineYaml("type: Contracts/Contract\ndescription: Probe " + n);
          builder.nativeStep(
              NativeStep.newBuilder(type, semantics, "probe" + n).execute(context -> {}).build());
        }
      }
      Contracts configuration = builder.build();
      long[] timings = new long[200];
      long gas = -1;
      String identity = null;
      for (int i = -100; i < timings.length; i++) {
        long start = System.nanoTime();
        try (Contracts.Session session = configuration.openYaml("count: 0")) {
          Contracts.Transition result = session.initialize().requireApplied();
          session.process(Map.of("event", "no-op"), 1).requireApplied();
          if (gas != -1 && (gas != result.gas() || !identity.equals(session.identity()))) {
            throw new AssertionError("Configuration reuse changed semantics");
          }
          gas = result.gas();
          identity = session.identity();
        }
        if (i >= 0) {
          timings[i] = System.nanoTime() - start;
        }
      }
      Arrays.sort(timings);
      System.out.printf(
          "profile=%s medianMs=%.3f p95Ms=%.3f initGas=%d identity=%s%n",
          profile, timings[100] / 1e6, timings[189] / 1e6, gas, identity);
    }
  }
}
