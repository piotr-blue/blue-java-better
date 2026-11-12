package blue;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/** Small arithmetic and embedding fixtures shared by accounting and locality tests. */
final class ContractsFixtures {
  static Map<String, Object> members(int count) {
    Map<String, Object> members = new LinkedHashMap<>();
    Map<String, Object> children = new TreeMap<>();
    children.put("active", ContractProcessor.link("child"));
    members.put(
        "child",
        yaml(
            """
            count: 0
            contracts:
              input: {type: Contracts/External Channel, event: {kind: tick}}
              run:
                type: Contracts/Sequential Workflow
                channel: input
                steps:
                  - type: Contracts/Compute
                    expr:
                      changeset: [{op: replace, path: /count, val: {$add: [{$document: /count}, 1]}}]
                  - type: Contracts/Trigger Event
                    event: {kind: changed}
            """));
    for (int i = 1; i < count; i++) {
      children.put("quiet" + i, ContractProcessor.link("quiet" + i));
      members.put("quiet" + i, Map.of("key", "quiet" + i, "contracts", Map.of()));
    }
    Map<String, Object> root =
        yaml(
            """
            count: 0
            contracts:
              embedded: {type: Contracts/Process Embedded, collectionPaths: [/children]}
              events: {type: Contracts/Embedded Node Channel, event: {kind: changed}}
              react:
                type: Contracts/Sequential Workflow
                channel: events
                steps:
                  - type: Contracts/Compute
                    expr:
                      changeset: [{op: replace, path: /count, val: {$add: [{$document: /count}, 1]}}]
                  - type: Contracts/Trigger Event
                    event: {kind: received}
            """);
    root.put("children", children);
    members.put("root", root);
    return members;
  }

  static Map<String, Object> yaml(String text) {
    return new org.yaml.snakeyaml.Yaml().load(text);
  }
}
