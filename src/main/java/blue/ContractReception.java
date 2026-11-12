package blue;

import static blue.ContractModel.*;

import java.util.*;

/** Shared semantic inspection for native admission and host source preparation. */
final class ContractReception {
  private ContractReception() {}

  static List<Contracts.Operation> operations(ContractProcessor processor, Snapshot snapshot) {
    return operations(processor, snapshot, snapshot.root);
  }

  static List<Contracts.Operation> operations(
      ContractProcessor processor, Snapshot snapshot, String member) {
    var document = snapshot.documents.get(member);
    require(document != null, "UNKNOWN_MEMBER", "member", member);
    var registry = processor.registry;
    if (registry.marker(document, "terminated") != null) return List.of();
    var contracts = registry.contracts(document);
    List<Contracts.Operation> result = new ArrayList<>();
    for (String name : ContractCanonical.keys(contracts.keySet())) {
      var declaration = contracts.get(name);
      if (!"operation".equals(declaration.get("_role"))) continue;
      result.add(
          new Contracts.Operation(
              name,
              str(declaration.get("channel")),
              Optional.ofNullable(declaration.get("request")),
              Optional.ofNullable((String) declaration.get("description"))));
    }
    return List.copyOf(result);
  }

  static Map<String, Object> receiver(
      ContractRegistry registry,
      String member,
      Document document,
      String channel,
      Map<String, Object> declaration) {
    var extension = (ContractNative.Installed) declaration.get("_extension");
    Life life = document.channelLives.get(channel);
    require(life != null, "UNINITIALIZED_CHANNEL", "channel", channel);
    return m(
        "member", member,
        "document", document.blueId,
        "channel", channel,
        "declaration", ContractLanguage.identity(registry.blue.exact(declaration.get("_recipe"))),
        "generation", life.generation(),
        "header", extension.header(declaration),
        "checkpoint", ContractChannels.checkpoint(registry, document, channel));
  }

  static Contracts.Reception inspect(ContractProcessor processor, Snapshot snapshot) {
    return inspect(processor, snapshot, snapshot.root);
  }

  static Contracts.Reception inspect(
      ContractProcessor processor, Snapshot snapshot, String member) {
    return inspect(processor, snapshot, member, snapshot.identity());
  }

  static Contracts.Reception inspect(
      ContractProcessor processor, Snapshot snapshot, String member, String identity) {
    var document = snapshot.documents.get(member);
    require(document != null, "UNKNOWN_MEMBER", "member", member);
    var registry = processor.registry;
    if (registry.marker(document, "terminated") != null)
      return new Contracts.Reception(identity, List.of());
    var declarations = registry.contracts(document);
    Map<String, List<String>> leaves = new HashMap<>();
    Map<String, Set<String>> selectors = new HashMap<>();
    Set<String> observed = new HashSet<>();
    for (var item : declarations.entrySet()) {
      if (!ContractRegistry.EXTERNAL.contains(item.getValue().get("_role"))) continue;
      List<String> selected = ContractChannels.leaves(declarations, item.getKey());
      leaves.put(item.getKey(), selected);
      for (String leaf : selected)
        selectors
            .computeIfAbsent(leaf, ignored -> new TreeSet<>(ContractCanonical.CODE_POINTS))
            .add(item.getKey());
    }
    for (var declaration : declarations.values())
      if (Set.of("workflow", "extension_handler").contains(declaration.get("_role")))
        observed.addAll(leaves.getOrDefault(declaration.get("channel"), List.of()));
    List<Contracts.ReceivingChannel> channels = new ArrayList<>();
    for (String name : ContractCanonical.keys(declarations.keySet())) {
      var declaration = declarations.get(name);
      if (!"extension_external".equals(declaration.get("_role"))) continue;
      var extension = (ContractNative.Installed) declaration.get("_extension");
      if (!extension.implementation.verifiesAdmission()) continue;
      Map<String, BlueId> fields = new LinkedHashMap<>();
      for (String field : extension.binding.headerFields()) {
        var value =
            supplied(
                registry,
                registry.blue.exact(declaration.get("_recipe")),
                extension.binding.typeId(),
                field);
        if (value != null) fields.put(field, new BlueId(ContractLanguage.identity(value)));
      }
      channels.add(
          new Contracts.ReceivingChannel(
              new BlueId(extension.binding.typeId()),
              NativeAdmission.receiver(receiver(registry, member, document, name, declaration)),
              fields,
              selectors.get(name),
              observed.contains(name)));
    }
    return new Contracts.Reception(identity, channels);
  }

  private static ExactNode supplied(
      ContractRegistry registry, ExactNode declaration, String base, String field) {
    List<ExactNode> parts = new ArrayList<>();
    Set<String> visited = new HashSet<>();
    ExactNode cursor = declaration;
    // A published field's description/type is contextual schema, not part of the supplied
    // provider, actor or timeline identity. Preserve domain inheritance above that boundary.
    while (cursor != null && !ContractLanguage.identity(cursor).equals(base)) {
      require(visited.add(ContractLanguage.identity(cursor)), "CYCLIC_TYPE_INHERITANCE");
      ExactNode.Value body = registry.blue.open(cursor);
      if (body.get(field) instanceof ExactNode value) parts.add(value);
      cursor = body.get("type") instanceof ExactNode.Ref parent ? parent : null;
    }
    if (parts.isEmpty()) return null;
    Collections.reverse(parts);
    return registry.blue.exact(LanguageSemantics.recipe(parts.toArray()));
  }

  static Optional<Contracts.OperationRequest> operation(
      ContractProcessor processor, LanguageContent message) {
    var scope = processor.blue.context.withContent(message);
    try {
      return operation(processor.registry, message.semantic);
    } finally {
      scope.close();
    }
  }

  static Optional<Contracts.OperationRequest> operation(
      ContractProcessor processor, LanguageContent message, LanguageDocument[] values) {
    var completed = processor.blue.context.withCompletedValues(values);
    try {
      return operation(processor, message);
    } finally {
      completed.close();
    }
  }

  static Optional<Contracts.OperationRequest> operation(
      ContractProcessor processor, TimelineVerifier.Entry entry, LanguageContent message) {
    Objects.requireNonNull(entry);
    Objects.requireNonNull(message);
    if (!entry.messageId().equals(message.blueId()))
      throw new IllegalArgumentException("Message differs from the verified Timeline occurrence");
    if (!entry.mayInvokeOperation()) return Optional.empty();
    ContractLanguage previous = processor.blue.context;
    processor.blue.context = previous.verificationSnapshot();
    try {
      var evidence = processor.blue.context.withTimelineEntry(entry);
      try {
        return operation(processor, message);
      } finally {
        evidence.close();
      }
    } finally {
      processor.blue.context = previous;
    }
  }

  private static Optional<Contracts.OperationRequest> operation(
      ContractRegistry registry, ExactNode message) {
    Map<String, Object> request = ContractChannels.operationRequest(registry, message);
    if (request == null) return Optional.empty();
    BlueId target =
        request.containsKey("document")
            ? new BlueId(str(obj(request.get("document")).get("blueId")))
            : null;
    return Optional.of(
        new Contracts.OperationRequest(
            str(request.get("operation")),
            str(request.get("channel")),
            Optional.ofNullable(target),
            Boolean.TRUE.equals(request.get("requireExactDocumentVersion"))));
  }
}
