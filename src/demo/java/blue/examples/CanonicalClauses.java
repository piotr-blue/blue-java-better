package blue.examples;

import blue.BluePackage;
import blue.Contracts;
import blue.Language;
import blue.PackageLoader;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

/** Executes the published Agreements and Connections packages without a package publisher. */
public final class CanonicalClauses {
  private CanonicalClauses() {}

  private static final class Packages {
    static final BluePackage CONNECTIONS = load();

    static BluePackage load() {
      try (InputStream input =
          CanonicalClauses.class.getResourceAsStream("/canonical/connections.bluepkg")) {
        if (input == null) throw new IllegalStateException("Missing bundled Connections package");
        return PackageLoader.create().read(input);
      } catch (IOException error) {
        throw new IllegalStateException(error);
      }
    }
  }

  public static Contracts contracts() {
    BluePackage connections = Packages.CONNECTIONS;
    BluePackage agreements = connections.imported("Agreements");
    Language language =
        Language.newBuilder()
            .mount("Connections", connections)
            .mount("Agreements", agreements)
            .build();
    return Contracts.newBuilder()
        .language(language)
        .require(connections)
        .require(agreements)
        .build();
  }

  public static String source() {
    return """
    type: Agreements/Agreement with Clauses
    greeting: before
    activity: before
    clauses:
      welcome:
        type: Connections/Hello Clause
        bindings:
          text: /greeting
          say:
            start:
              on: initialized
              params: {text: Hello canonical clauses}
      activity:
        type: Connections/Hello Clause
        bindings:
          text: /activity
          say:
            ping:
              on: event
              event: {type: Connections/Ping}
              params: {text: Received a ping}
    contracts:
      input: {type: Contracts/External Channel, event: {channel: input}}
      ping:
        type: Contracts/Sequential Workflow Operation
        channel: input
        steps:
        - type: Contracts/Trigger Event
          event: {type: Connections/Ping}
    """;
  }

  public static void main(String[] args) {
    Contracts contracts = contracts();
    try (Contracts.Session session = contracts.openYaml(source())) {
      Contracts.Transition initialization = session.initialize().requireApplied();
      System.out.println("greeting=" + session.read("/greeting") + " gas=" + initialization.gas());
      Contracts.Transition ping = session.call("input", "ping", Map.of(), 1).requireApplied();
      System.out.println("activity=" + session.read("/activity") + " gas=" + ping.gas());
      System.out.println(ping.events());
    }
  }
}
