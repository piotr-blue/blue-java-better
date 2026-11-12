package blue.bench;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** Same source, events, timestamp, gas and receipt boundary in both Java implementations. */
public final class CanonicalComparison {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static Class<?> processorType;
  private static Path sources;
  private static final int SAMPLES = Integer.getInteger("blue.bench.samples", 9);

  private CanonicalComparison() {}

  private static Object call(Object owner, String name, Object... args) throws Exception {
    for (Method method : owner.getClass().getMethods()) {
      if (!method.getName().equals(name) || method.getParameterCount() != args.length) {
        continue;
      }
      boolean matches = true;
      for (int i = 0; i < args.length; i++) {
        Class<?> type = method.getParameterTypes()[i];
        if (args[i] != null
            && !(type.isInstance(args[i]) || type == long.class && args[i] instanceof Long)) {
          matches = false;
        }
      }
      if (!matches) {
        continue;
      }
      method.setAccessible(true);
      try {
        return method.invoke(owner, args);
      } catch (InvocationTargetException error) {
        throw new IllegalStateException(name, error.getCause());
      }
    }
    throw new IllegalArgumentException(name);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> source(String name, String key) throws Exception {
    String text = Files.readString(sources.resolve(name + ".json"));
    text =
        text.replace("ORDER_KEY", key)
            .replace("ORDER_CHANNEL", "order-" + key)
            .replace("PAYMENT_CHANNEL", "payment-" + key);
    return JSON.readValue(text, Map.class);
  }

  private static Object event(Object processor, String channel, String operation, Object request)
      throws Exception {
    return call(
        processor,
        "typed",
        "Operation Request",
        Map.of("channel", channel, "operation", operation, "request", request));
  }

  private static Object process(Object processor, Object document, Object event, long timestamp)
      throws Exception {
    Object result = call(processor, "process", document, event, timestamp);
    if (!call(result, "status").equals("APPLIED")) {
      throw new IllegalStateException(call(result, "observations").toString());
    }
    call(result, "receipt"); // Include the canonical receipt, not only the business writes.
    return result;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> scenario(int count) throws Exception {
    long start = System.nanoTime();
    Constructor<?> constructor = processorType.getDeclaredConstructor();
    constructor.setAccessible(true);
    Object processor = constructor.newInstance();
    double constructionMs = (System.nanoTime() - start) / 1e6;
    Map<String, Object> root = source("agreement", "");
    Map<String, Object> orders = (Map<String, Object>) root.get("orders");

    start = System.nanoTime();
    Object initial = call(processor, "initialize", root);
    if (!call(initial, "status").equals("APPLIED")) {
      throw new IllegalStateException(call(initial, "observations").toString());
    }
    String initReceipt = (String) call(initial, "receipt");
    double initializationMs = (System.nanoTime() - start) / 1e6;
    Object base = call(initial, "document");
    long setupStart = System.nanoTime();
    for (int i = 0; i < count; i++) {
      String existing = "" + i;
      Object request =
          event(
              processor,
              "input",
              "addOrder",
              Map.of("key", existing, "order", source("order", existing)));
      base = call(process(processor, base, request, i + 1L), "document");
    }
    double setupMs = (System.nanoTime() - setupStart) / 1e6;
    String key = "new";
    Object addEvent =
        event(processor, "input", "addOrder", Map.of("key", key, "order", source("order", key)));
    Object submitEvent =
        event(processor, "order-new", "submit", Map.of("payment", source("payment", key)));
    Object payEvent = event(processor, "payment-new", "settle", Map.of());
    Object added = process(processor, base, addEvent, 1000L);
    Object afterAdd = call(added, "document");
    Object submitted = process(processor, afterAdd, submitEvent, 1001L);
    Object afterSubmit = call(submitted, "document");
    Object paid = process(processor, afterSubmit, payEvent, 1002L);
    Object afterPay = call(paid, "document");
    if (!call(processor, "read", afterPay, "/paidTotal").toString().equals("42")) {
      throw new AssertionError("payment not propagated");
    }
    if (!call(processor, "read", afterPay, "/activityCount").toString().equals("" + (count + 3))) {
      throw new AssertionError("activity mismatch");
    }
    jdk.jfr.Recording recording = null;
    if (System.getProperty("blue.bench.jfr") != null) {
      recording = new jdk.jfr.Recording(jdk.jfr.Configuration.getConfiguration("profile"));
      recording.start();
    }
    List<Object> timings = new ArrayList<>();
    Object[] documents = {base, afterAdd, afterSubmit};
    Object[] events = {addEvent, submitEvent, payEvent};
    String[] names = {"add", "submit", "settle"};
    for (int step = 0; step < 3; step++) {
      for (int warm = 0; warm < 4; warm++) {
        process(processor, documents[step], events[step], 1000L + step);
      }
      double[] ms = new double[SAMPLES];
      Object result = null;
      for (int sample = 0; sample < SAMPLES; sample++) {
        start = System.nanoTime();
        result = process(processor, documents[step], events[step], 1000L + step);
        ms[sample] = (System.nanoTime() - start) / 1e6;
      }
      Arrays.sort(ms);
      timings.add(
          Map.of(
              "step",
              names[step],
              "medianMs",
              ms[SAMPLES / 2],
              "maxMs",
              ms[SAMPLES - 1],
              "samplesMs",
              ms,
              "gas",
              call(result, "gas"),
              "receipt",
              call(result, "receipt")));
    }
    if (recording != null) {
      recording.stop();
      recording.dump(Path.of(System.getProperty("blue.bench.jfr") + "-" + count + ".jfr"));
      recording.close();
    }
    return Map.of(
        "orders",
        count,
        "constructionMs",
        constructionMs,
        "initializationMs",
        initializationMs,
        "initializationReceipt",
        initReceipt,
        "setupMs",
        setupMs,
        "steps",
        timings);
  }

  public static void main(String[] args) throws Exception {
    processorType =
        Class.forName(
            args[0].equals("old") ? "blue.contracts.Contracts" : "blue.ContractProcessor");
    sources = Path.of(args[1]);
    scenario(1); // Warm common code before recorded scenarios.
    List<Object> rows = new ArrayList<>();
    for (String size : args[3].split(",")) {
      rows.add(scenario(Integer.parseInt(size)));
      Files.writeString(
          Path.of(args[2]),
          JSON.writerWithDefaultPrettyPrinter()
              .writeValueAsString(
                  Map.of(
                      "runtime",
                      args[0],
                      "java",
                      System.getProperty("java.runtime.version"),
                      "samples",
                      SAMPLES,
                      "scenarios",
                      rows)));
      System.out.println("Completed " + args[0] + " orders=" + size);
    }
    String json =
        JSON.writerWithDefaultPrettyPrinter()
            .writeValueAsString(
                Map.of(
                    "runtime",
                    args[0],
                    "java",
                    System.getProperty("java.runtime.version"),
                    "samples",
                    SAMPLES,
                    "scenarios",
                    rows));
    Files.writeString(Path.of(args[2]), json + "\n");
    System.out.println(json);
  }
}
