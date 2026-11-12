package blue;

import java.util.*;

/** Local structural retention diagnosis, outside ordinary tests. */
public final class NormalizationSharingProbe {
  public static void main(String[] args) {
    var steps = new ArrayList<Object>();
    for (int i = 0; i < 24; i++)
      steps.add(
          Map.of(
              "operation",
              "update-" + i,
              "parameters",
              Map.of(
                  "path",
                  "/status/" + i,
                  "expression",
                  Map.of("$add", List.of(Map.of("$document", "/amount"), i)))));
    var template = Map.of("steps", steps, "description", "Shared workflow definition");
    var bean =
        (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();
    var results = new ArrayList<Object>();
    for (int count : List.of(16, 256, 1024)) {
      var context = new LanguageContext();
      for (int i = 0; i < 8; i++)
        LanguageCore.normalize(
            Map.of("key", "warmup", "workflow", template), context, "exact", false);
      context.work.set(0);
      long allocated = bean.getThreadAllocatedBytes(Thread.currentThread().threadId());
      long started = System.nanoTime();
      var roots = new ArrayList<ExactNode>();
      for (int i = 0; i < count; i++)
        roots.add(
            LanguageCore.normalize(
                Map.of("key", "record-" + i, "workflow", template), context, "exact", false));
      double millis = (System.nanoTime() - started) / 1e6;
      allocated = bean.getThreadAllocatedBytes(Thread.currentThread().threadId()) - allocated;
      var seen = Collections.newSetFromMap(new IdentityHashMap<ExactNode, Boolean>());
      var pending = new ArrayDeque<ExactNode>(roots);
      pending.addAll(context.bodies.values());
      long values = 0, references = 0;
      while (!pending.isEmpty()) {
        var node = pending.removeLast();
        if (!seen.add(node)) continue;
        if (node instanceof ExactNode.Value value) {
          values++;
          for (Object field : value.fields().values()) {
            if (field instanceof ExactNode child) pending.add(child);
            else if (field instanceof List<?> list)
              for (Object child : list) pending.add((ExactNode) child);
          }
        } else if (node instanceof ExactNode.Ref) references++;
      }
      results.add(
          Map.of(
              "documents",
              count,
              "millis",
              millis,
              "allocatedBytes",
              allocated,
              "retainedValueInstances",
              values,
              "retainedReferenceInstances",
              references,
              "work",
              context.work.get(),
              "identities",
              LanguageCore.hash(roots.stream().map(LanguageCore::identity).toList())));
    }
    System.out.println(
        LanguageCodec.write(
            Map.of(
                "scenario", "repeated inline workflow, one context; not AWS", "results", results)));
  }
}
