package blue.examples;

import blue.BlueId;
import blue.Contracts;
import blue.Language;
import blue.LanguageContent;
import blue.LanguageDocument;
import blue.LanguageException;
import blue.LanguageRecord;
import blue.Node;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Executable tutorial examples. Canonical Language and Contracts use the published semantics. */
public final class TutorialExample {
  private TutorialExample() {}

  public static void main(String[] args) throws IOException {
    run(Path.of("build/reports/tutorial"));
  }

  public static void run(Path output) throws IOException {
    Files.createDirectories(output);
    Language language = Language.create();

    // Lesson 01: authored shorthand does not change identity.
    LanguageDocument compact =
        language.readYaml(original("01_blue_workbench_and_identity/03_scalar_compact.yaml"));
    LanguageDocument explicit =
        language.readYaml(original("01_blue_workbench_and_identity/04_scalar_explicit.yaml"));
    check(compact.blueId().equals(explicit.blueId()), "scalar identity");
    check(compact.canonicalJson().equals(explicit.canonicalJson()), "scalar canonical form");
    LanguageDocument xyz =
        language.readYaml(original("01_blue_workbench_and_identity/01_xyz.yaml"));
    LanguageDocument changed =
        language.readYaml(original("01_blue_workbench_and_identity/02_xyz_changed.yaml"));
    check(!xyz.blueId().equals(changed.blueId()), "changed content has a different identity");

    // Lesson 02: arbitrary content can be supplied by a local or remote provider.
    Map<BlueId, LanguageRecord> records = new ConcurrentHashMap<>(xyz.records());
    Language reader =
        Language.newBuilder().provider(id -> Optional.ofNullable(records.get(id))).build();
    LanguageDocument inline =
        reader.readYaml(original("02_content_graph_and_references/01_parent_inline.yaml"));
    LanguageDocument reference =
        reader.readYaml(
            original("02_content_graph_and_references/02_parent_reference.template.yaml")
                .replace("<XYZ_CONTENT_BLUE_ID>", xyz.blueId().value()));
    check(inline.blueId().equals(reference.blueId()), "inline/reference identity");
    check(reference.at("/x").name().orElseThrow().equals("Xyz"), "reference transparency");
    check(reference.at("/x").description().orElseThrow().equals("Some doc"), "description");
    Files.writeString(output.resolve("parent-collapsed.yaml"), reference.toYaml());
    Files.writeString(output.resolve("parent-expanded.yaml"), reference.expand("/x").toYaml());

    // Missing evidence is not an absent field. The caller can supply it and retry.
    LanguageContent unavailable = reader.reference(changed.blueId());
    boolean missing = false;
    try {
      unavailable.at("").exists();
    } catch (LanguageException failure) {
      check(failure.incomplete(), "missing content is incomplete");
      missing = true;
    }
    check(missing, "missing evidence fails");
    records.putAll(changed.records());
    check(reader.read(changed.blueId()).blueId().equals(changed.blueId()), "explicit retry");

    // Lesson 03: labels are content; package-qualified aliases are separate bindings.
    // Preserve archive files but adapt their two qualified labels for this demonstration.
    LanguageDocument point =
        language.defineYaml(
            original("03_types_and_liskov/01_point_type.yaml")
                .replace("name: Tutorial/Point", "name: Point"));
    LanguageDocument colored =
        point.extendYaml(
            original("03_types_and_liskov/02_colored_point_type.template.yaml")
                .replace("name: Tutorial/Colored Point", "name: Colored Point")
                .replace("<POINT_TYPE_BLUE_ID>", point.blueId().value()));
    LanguageDocument pointValue =
        point.specializeYaml(
            original("03_types_and_liskov/03_valid_point.template.yaml")
                .replace("<POINT_TYPE_BLUE_ID>", point.blueId().value()));
    LanguageDocument coloredValue =
        colored.specializeYaml(
            original("03_types_and_liskov/04_valid_colored_point.template.yaml")
                .replace("<COLORED_POINT_TYPE_BLUE_ID>", colored.blueId().value()));
    Language named =
        Language.newBuilder()
            .type("Geometry/Point", point)
            .type("Drawing/Coordinate", point)
            .build();
    check(
        named
            .readYaml("type: Geometry/Point\nx: 2\ny: 3")
            .blueId()
            .equals(named.readYaml("type: Drawing/Coordinate\nx: 2\ny: 3").blueId()),
        "multiple package export names identify the same definition");
    check(language.isSubtype(colored, point), "Colored Point is a subtype");
    check(pointValue.at("/x").integer() == 2 && pointValue.at("/y").integer() == 3, "Point values");
    check(coloredValue.at("/color").text().equals("blue"), "Colored Point value");
    boolean invalid = false;
    try {
      point.specializeYaml(
          original("03_types_and_liskov/05_invalid_point.template.yaml")
              .replace("<POINT_TYPE_BLUE_ID>", point.blueId().value()));
    } catch (LanguageException failure) {
      check(!failure.incomplete(), "invalid integer is an established rejection");
      invalid = true;
    }
    check(invalid, "invalid Point rejected");
    Files.writeString(output.resolve("point.yaml"), pointValue.toYaml());
    Files.writeString(output.resolve("colored-point.yaml"), coloredValue.toYaml());

    // Lesson 04: order matters; an empty value differs from an unfilled definition.
    LanguageDocument list =
        language.readYaml(original("04_lists_and_canonical_forms/01_list_compact.yaml"));
    LanguageDocument listExplicit =
        language.readYaml(original("04_lists_and_canonical_forms/02_list_explicit.yaml"));
    LanguageDocument reordered =
        language.readYaml(original("04_lists_and_canonical_forms/03_list_reordered.yaml"));
    LanguageDocument empty =
        language.readYaml(original("04_lists_and_canonical_forms/04_empty_list.yaml"));
    LanguageDocument typeOnly =
        language.defineYaml(original("04_lists_and_canonical_forms/05_type_only_list.yaml"));
    check(list.blueId().equals(listExplicit.blueId()), "list shorthand identity");
    check(!list.blueId().equals(reordered.blueId()), "list order matters");
    check(!empty.blueId().equals(typeOnly.blueId()), "empty list is not a type-only list");
    LanguageDocument removed = list.edit(editor -> editor.remove("/values/items/1"));
    LanguageDocument replaced =
        list.edit(
            editor ->
                editor.set("/values/items", Node.list(List.of(Node.integer(1), Node.integer(3)))));
    LanguageDocument authored = language.readYaml("values: [1, 3]");
    check(
        removed.blueId().equals(replaced.blueId()) && removed.blueId().equals(authored.blueId()),
        "three deletion routes");
    check(
        removed.canonicalJson().equals(replaced.canonicalJson())
            && removed.canonicalJson().equals(authored.canonicalJson()),
        "three identical canonical forms");
    Files.writeString(output.resolve("list-after-removal.canonical.json"), removed.canonicalJson());

    // Lesson 07: changing one use of shared content must leave the original intact.
    LanguageDocument nine =
        language.readYaml(
            original("07_reference_transparency_and_missing_content/02_counter_value.yaml"));
    Language contentReader = Language.newBuilder().content(nine).build();
    LanguageDocument shared =
        contentReader.readYaml(
            "counterValue: {blueId: "
                + nine.blueId()
                + "}\nunrelatedColdValue: {blueId: "
                + nine.blueId()
                + "}");
    LanguageDocument ten = shared.edit(editor -> editor.set("/counterValue/value", 10));
    check(ten.at("/counterValue").integer() == 10, "edited value");
    check(
        ten.at("/unrelatedColdValue").integer() == 9 && nine.at("").integer() == 9,
        "shared original retained");
    Files.writeString(output.resolve("copy-on-write.yaml"), ten.toYaml());

    runCounter(output);
    System.out.println(
        "Tutorial: identity, references, missing-content retry, Point subtyping, invalid Point"
            + " rejection,");
    System.out.println("list canonical forms and copy-on-write all passed.");
    System.out.println("List [1,3] BlueId: " + removed.blueId());
    System.out.println("Canonical JSON: " + removed.canonicalJson());
    System.out.println("Canonical counter: 0 -> 3 -> 2, with Counter Changed events.");
    System.out.println("Results: " + output.toAbsolutePath());
  }

  private static void runCounter(Path output) throws IOException {
    Contracts contracts = Contracts.create();
    String source = resource("/tutorial/counter.yaml");
    try (Contracts.Session session = contracts.openYaml(source)) {
      session.initialize().requireApplied();
      LanguageDocument initial = session.document();
      Contracts.Transition plusThree =
          session.call("owner", "increment", Map.of("amount", 3), 1).requireApplied();
      check(session.read("/counter").equals(java.math.BigInteger.valueOf(3)), "increment");
      Contracts.Transition minusOne =
          session.call("owner", "increment", Map.of("amount", -1), 2).requireApplied();
      check(initial.at("/counter").integer() == 0, "immutable initial state");
      check(session.read("/counter").equals(java.math.BigInteger.TWO), "decrement");
      check(plusThree.events().size() == 1 && minusOne.events().size() == 1, "counter events");
      check(
          ((Map<?, ?>) minusOne.events().getFirst()).get("count").equals(java.math.BigInteger.TWO),
          "event value");
      Files.writeString(output.resolve("counter-before.yaml"), source);
      Files.writeString(output.resolve("counter-after.yaml"), session.document().toYaml());
      Files.writeString(output.resolve("counter-event.txt"), minusOne.events().toString());
    }
  }

  private static String original(String path) throws IOException {
    return resource("/tutorial/original/" + path);
  }

  private static String resource(String path) throws IOException {
    try (InputStream stream = TutorialExample.class.getResourceAsStream(path)) {
      if (stream == null) {
        throw new IOException("Missing tutorial resource: " + path);
      }
      return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  private static void check(boolean condition, String message) {
    if (!condition) {
      throw new AssertionError(message);
    }
  }
}
