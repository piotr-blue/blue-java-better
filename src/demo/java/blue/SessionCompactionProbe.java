package blue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Bounded same-state comparison; no AWS, persistence, signed Timeline or statistical claim. */
public final class SessionCompactionProbe {
  private SessionCompactionProbe() {}

  public static void main(String[] args) {
    var bean =
        (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();
    var contracts = Contracts.create();
    var results = new ArrayList<Object>();
    for (int children : List.of(16, 256, 1024)) {
      var members = new TreeMap<String, Object>();
      for (int i = 0; i < children; i++) {
        members.put("child-" + i, Map.of("count", i));
      }
      var source =
          Map.of(
              "count",
              0,
              "children",
              members,
              "contracts",
              Map.of(
                  "embedded",
                      Map.of(
                          "type",
                          "Contracts/Process Embedded",
                          "collectionPaths",
                          List.of("/children")),
                  "input", Map.of("type", "Contracts/External Channel"),
                  "count",
                      Map.of(
                          "type",
                          "Contracts/Sequential Workflow",
                          "channel",
                          "input",
                          "steps",
                          List.of(
                              Map.of(
                                  "type",
                                  "Contracts/Update Document",
                                  "changeset",
                                  List.of(
                                      Map.of(
                                          "op",
                                          "replace",
                                          "path",
                                          "/count",
                                          "val",
                                          Map.of(
                                              "$add",
                                              List.of(Map.of("$document", "/count"), 1)))))))));
      long setup = System.nanoTime();
      Map<String, Object> snapshot;
      String identity;
      try (var initial = contracts.openJson(LanguageCodec.write(source))) {
        initial.initialize().requireApplied();
        for (int i = 0; i < 4; i++) {
          initial.process(Map.of("sequence", i), i).requireApplied();
        }
        snapshot = initial.snapshot();
        identity = initial.identity();
      }
      double setupMillis = (System.nanoTime() - setup) / 1e6;
      var samples = new ArrayList<Object>();
      for (int pair = 0; pair < 5; pair++) {
        try (var portable = contracts.restore(snapshot, identity);
            var direct = contracts.restore(snapshot, identity)) {
          var measured = new LinkedHashMap<String, Object>();
          for (String mode :
              pair % 2 == 0 ? List.of("portable", "direct") : List.of("direct", "portable")) {
            long allocated = bean.getThreadAllocatedBytes(Thread.currentThread().threadId());
            long started = System.nanoTime();
            if (mode.equals("direct")) {
              direct.compact();
            } else {
              try (var restored = contracts.restore(portable.snapshot(), identity)) {
                if (!restored.identity().equals(identity)) {
                  throw new AssertionError("Identity mismatch");
                }
              }
            }
            double millis = (System.nanoTime() - started) / 1e6;
            allocated = bean.getThreadAllocatedBytes(Thread.currentThread().threadId()) - allocated;
            measured.put(mode, Map.of("millis", millis, "allocatedBytes", allocated));
          }
          if (!snapshot.equals(direct.snapshot())) {
            throw new AssertionError("Snapshot mismatch");
          }
          var event = Map.of("sequence", 10);
          var a = portable.process(event, 10).requireApplied();
          var b = direct.process(event, 10).requireApplied();
          if (!a.receipt().equals(b.receipt())
              || a.gas() != b.gas()
              || !portable.identity().equals(direct.identity())) {
            throw new AssertionError("Execution mismatch");
          }
          measured.put("pair", pair);
          measured.put("warmup", pair < 2);
          samples.add(measured);
        }
      }
      results.add(Map.of("members", children + 1, "setupMillis", setupMillis, "samples", samples));
    }
    System.out.println(
        LanguageCodec.write(
            Map.of(
                "scenario",
                "portable versus direct current-state maintenance; static members; no AWS or"
                    + " Timeline",
                "results",
                results)));
  }
}
