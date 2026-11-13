package blue;

import java.math.BigInteger;
import java.util.Map;

/** Exercises both sides of the public native-step projection boundary in a fixed-size JVM. */
public final class NativeReadBudgetProbe {
  private NativeReadBudgetProbe() {}

  public static void main(String[] args) {
    int depth = 19;
    var base = Language.newBuilder().mount("Contracts", Contracts.publication()).build();
    var type = base.defineYaml("type: Contracts/Contract");
    var semantics = base.readYaml("name: Read the selected branch");
    var tree = base.readYaml("leaf: 7");
    for (int level = 0; level < depth; level++) {
      String ref = "{blueId: '" + tree.blueId() + "'}";
      tree = base.toBuilder().content(tree).build().readYaml("left: " + ref + "\nright: " + ref);
    }
    String leaf = "/shared" + "/left".repeat(depth) + "/leaf";
    var step =
        NativeStep.newBuilder(type, semantics, "projectionprobe")
            .execute(
                context -> {
                  boolean whole = Boolean.TRUE.equals(context.event("/request/whole"));
                  Object value = context.read(whole ? "/shared" : leaf);
                  if (whole || !BigInteger.valueOf(7).equals(value)) {
                    throw new AssertionError("Unexpected native projection");
                  }
                })
            .build();
    var runtime =
        Contracts.newBuilder()
            .language(base.toBuilder().content(tree).build())
            .nativeStep(step)
            .build();
    String source =
        """
        shared: {blueId: '%s'}
        contracts:
          input: {type: Contracts/External Channel}
          read:
            type: Contracts/Sequential Workflow Operation
            channel: input
            steps:
              - type: {blueId: '%s'}
        """
            .formatted(tree.blueId(), type.blueId());
    try (var session = runtime.openYaml(source)) {
      session.initialize().requireApplied();
      String before = session.identity();
      try {
        session.call("input", "read", Map.of("whole", true), 1);
        throw new AssertionError("The shared result exceeded the default projection allowance");
      } catch (BlueException failure) {
        if (!"PROJECTION_LIMIT_EXCEEDED".equals(failure.code())
            || !before.equals(session.identity())) {
          throw new AssertionError(
              "The failed read must preserve the accepted checkpoint", failure);
        }
      }
      session.call("input", "read", Map.of("whole", false), 2).requireApplied();
      System.out.println(
          "Oversized read stopped at its projection budget; one shared leaf remained readable.");
    }
  }
}
