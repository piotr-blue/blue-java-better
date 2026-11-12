package blue.examples;

import blue.BlueId;
import blue.Language;
import blue.LanguageContent;
import blue.LanguageDocument;
import blue.LanguageException;
import blue.LanguageRecord;
import blue.Node;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/** Canonical Language mappings from the tutorial. Execution scenarios use Contracts directly. */
public final class CompleteTutorialExample {
  private static final Language language = Language.create();

  private CompleteTutorialExample() {}

  public static void main(String[] args) {
    for (Lesson lesson : lessons()) {
      lesson.run();
    }
  }

  static List<Lesson> lessons() {
    return List.of(
        new Lesson("01 Identity", CompleteTutorialExample::lesson01),
        new Lesson("02 References and evidence", CompleteTutorialExample::lesson02),
        new Lesson("03 Inheritance", CompleteTutorialExample::lesson03),
        new Lesson("04 Lists", CompleteTutorialExample::lesson04),
        new Lesson("07 Reference transparency", CompleteTutorialExample::lesson07),
        new Lesson("09 Generalization", CompleteTutorialExample::lesson09),
        new Lesson("12 Cyclic content", CompleteTutorialExample::lesson12),
        new Lesson("16 Types and selective providers", CompleteTutorialExample::lesson16),
        new Lesson("17 Schema evolution", CompleteTutorialExample::lesson17));
  }

  record Lesson(String title, Runnable action) {
    void run() {
      action.run();
    }

    @Override
    public String toString() {
      return title;
    }
  }

  // region lesson01
  private static void lesson01() {
    LanguageDocument xyz =
        language.readYaml(
            """
            name: Xyz
            description: Some doc
            """);
    LanguageDocument changed = xyz.edit(e -> e.set("/description", "Some changed doc"));
    check(!xyz.blueId().equals(changed.blueId()), "Description changes identity");
    LanguageDocument compact = language.readYaml("x: 1");
    LanguageDocument explicit = language.readYaml("x: {value: 1}");
    check(compact.blueId().equals(explicit.blueId()), "Scalar spelling does not change identity");
    check(compact.canonicalJson().equals(explicit.canonicalJson()), "Same canonical form");
    Map<BlueId, LanguageRecord> contentStore = new HashMap<>();
    contentStore.putAll(xyz.records());
    Language reader =
        Language.newBuilder().provider(id -> Optional.ofNullable(contentStore.get(id))).build();
    LanguageDocument reopened = reader.read(xyz.blueId());
    check(reopened.blueId().equals(xyz.blueId()), "Reopen stored content");
    System.out.println(compact.canonicalJson());
  }

  // region lesson02
  private static void lesson02() {
    LanguageDocument parent =
        language.readYaml(
            """
            y: 1
            x: {name: Xyz, description: Some doc}
            """);
    LanguageDocument collapsed = parent.collapse("/x");
    LanguageDocument expanded = collapsed.expand("/x");
    check(parent.blueId().equals(collapsed.blueId()), "Collapsed identity");
    check(parent.blueId().equals(expanded.blueId()), "Expanded identity");
    check(collapsed.at("/x").name().orElseThrow().equals("Xyz"), "Metadata through reference");

    LanguageDocument child = language.readYaml("message: available later");
    LanguageContent missing =
        language.prepare(
            Language.Input.yaml(
                "name: Parent with missing child\nchild: {blueId: " + child.blueId() + "}"));
    BlueId structuralId = missing.blueId();
    expectIncomplete(() -> language.admit(missing, Language.Goal.VALUE));
    Language supplied = Language.newBuilder().content(child).build();
    LanguageDocument valid = supplied.admit(missing, Language.Goal.VALUE);
    check(valid.blueId().equals(structuralId), "Supplying evidence preserves identity");
    check(valid.at("/child/message").text().equals("available later"), "Verified child");

    Language wrong =
        Language.newBuilder().provider(id -> Optional.of(LanguageRecord.json("42"))).build();
    expectLanguage("ProviderBlueIdMismatch", () -> wrong.read(child.blueId()));
  }

  // region lesson03
  private static void lesson03() {
    LanguageDocument point =
        language.defineYaml(
            """
            name: Point
            description: A two-dimensional integer point.
            x: {type: Integer}
            y: {type: Integer}
            """);
    LanguageDocument coloredPoint =
        point.extendYaml(
            """
            name: Colored Point
            description: A Point with a text color.
            color: {type: Text}
            """);
    LanguageDocument plain = point.specializeYaml("x: 2\ny: 3");
    LanguageDocument colored =
        coloredPoint.specializeJson(
            """
            {"x":2,"y":3,"color":"blue"}
            """);
    check(language.isSubtype(coloredPoint, point), "Subtype");
    check(colored.at("/x").integer() == 2, "Inherited Integer requirement");
    check(colored.at("/color").text().equals("blue"), "Own color");
    Language app =
        Language.newBuilder()
            .type("Geometry/Point", point)
            .type("Drawing/Coordinate", point)
            .type("Drawing/Colored Point", coloredPoint)
            .build();
    LanguageDocument fromGeometry = app.readYaml("type: Geometry/Point\nx: 2\ny: 3");
    LanguageDocument fromDrawing = app.readYaml("type: Drawing/Coordinate\nx: 2\ny: 3");
    check(
        fromGeometry.blueId().equals(fromDrawing.blueId()),
        "Different export names, same instance");
    check(fromGeometry.canonicalJson().equals(fromDrawing.canonicalJson()), "Same exact content");
    check(
        fromGeometry.at("").type().orElseThrow().equals(point.blueId()),
        "Type resolves to content identity");
    check(
        point.at("").name().orElseThrow().equals("Point"), "Exporting does not rewrite the label");
    LanguageContent invalid =
        app.prepare(
            Language.Input.yaml(
                """
                type: Geometry/Point
                x: not-an-integer
                y: 3
                """));
    check(invalid.blueId() != null, "Invalid semantic value still has structural identity");
    expectInvalid(() -> app.admit(invalid, Language.Goal.VALUE));
    LanguageDocument revised = point.edit(e -> e.set("/description", "A revised definition"));
    check(!point.blueId().equals(revised.blueId()), "New definition identity");
    check(
        plain.at("").type().orElseThrow().equals(point.blueId()),
        "Existing instance retains old type");
  }

  // region lesson04
  private static void lesson04() {
    LanguageDocument compact = language.readYaml("values: [1, 2, 3]");
    LanguageDocument explicit = language.readYaml("values: {items: [1, 2, 3]}");
    LanguageDocument reordered = language.readYaml("values: [3, 2, 1]");
    LanguageDocument empty = language.readYaml("values: {items: []}");
    LanguageDocument typeOnly = language.defineYaml("values: {type: List}");
    check(compact.blueId().equals(explicit.blueId()), "List spelling");
    check(!compact.blueId().equals(reordered.blueId()), "Order matters");
    check(!empty.blueId().equals(typeOnly.blueId()), "Empty payload differs from declaration");
    LanguageDocument removed = compact.edit(e -> e.remove("/values/items/1"));
    LanguageDocument replaced =
        compact.edit(
            e -> e.set("/values/items", Node.list(List.of(Node.integer(1), Node.integer(3)))));
    LanguageDocument authored = language.readYaml("values: [1, 3]");
    check(removed.blueId().equals(replaced.blueId()), "Remove versus replace");
    check(removed.blueId().equals(authored.blueId()), "Remove versus author");
    check(removed.canonicalJson().equals(replaced.canonicalJson()), "Canonical remove/replace");
    check(removed.canonicalJson().equals(authored.canonicalJson()), "Canonical remove/author");
    check(compact.at("/values").size() == 3, "Original is unchanged");
    System.out.println(removed.canonicalJson());
  }

  // region lesson07
  private static void lesson07() {
    LanguageDocument shared =
        language
            .readYaml("counterValue: {value: 9}\nunrelatedColdValue: {value: 9}")
            .collapse("/counterValue", "/unrelatedColdValue");
    LanguageDocument changed = shared.edit(e -> e.set("/counterValue/value", 10));
    check(changed.at("/counterValue").integer() == 10, "Changed use");
    check(changed.at("/unrelatedColdValue").integer() == 9, "Other use");
    check(shared.at("/counterValue").integer() == 9, "Shared original");
  }

  // region lesson09
  private static void lesson09() {
    LanguageDocument base = language.defineYaml("name: Base\nphase: {type: Text}");
    LanguageDocument subtype =
        base.extendYaml(
            """
            name: Subtype
            requiredDatum: {type: Text, schema: {required: true}}
            """);
    LanguageDocument value = subtype.specializeYaml("phase: initial\nrequiredDatum: present");
    expectInvalid(() -> value.edit(e -> e.remove("/requiredDatum")));
    LanguageDocument explicitlyReauthored = base.specializeYaml("phase: initial");
    check(
        explicitlyReauthored.at("/phase").text().equals("initial"), "Explicit ancestor authoring");
  }

  // region lesson12
  private static void lesson12() {
    Language.CyclicSet cycle =
        language.finalizeSet(
            List.of(
                Language.Input.exactJson(
                    """
                    {"name":"A","peer":{"blueId":"this#1"}}
                    """),
                Language.Input.exactJson(
                    """
                    {"name":"B","peer":{"blueId":"this#0"}}
                    """)),
            Language.Goal.VALUE);
    LanguageDocument a = cycle.members().get(0);
    check(a.at("/peer").name().orElseThrow().equals("B"), "Cyclic content traversal");
    check(a.at("/peer/peer").name().orElseThrow().equals("A"), "Cycle closes");
    Language.Builder verifierBuilder = Language.newBuilder();
    for (LanguageDocument member : cycle.members()) {
      verifierBuilder.record(member.blueId(), LanguageRecord.cyclic(cycle.proofJson()));
    }
    Language verifier = verifierBuilder.build();
    check(verifier.read(a.blueId()).blueId().equals(a.blueId()), "Owning proof verification");
  }

  // region lesson16
  private static void lesson16() {
    LanguageDocument money =
        language.defineYaml(
            """
            name: Money
            amountMinor: {type: Integer}
            currency: {type: Text}
            """);
    Map<BlueId, LanguageRecord> records = new HashMap<>();
    AtomicInteger fetches = new AtomicInteger();
    Language reader =
        Language.newBuilder()
            .provider(
                id -> {
                  fetches.incrementAndGet();
                  return Optional.ofNullable(records.get(id));
                })
            .build();
    expectIncomplete(() -> reader.define(money.blueId()));
    records.putAll(money.records());
    LanguageDocument loadedType = reader.define(money.blueId());
    LanguageDocument amount = loadedType.specializeYaml("amountMinor: 1250\ncurrency: EUR");
    check(amount.at("/amountMinor").integer() == 1250, "Local Money example");
    check(fetches.get() > 0, "Selective provider used");
    check(
        language.readYaml("temperatureC: 9.5").at("/temperatureC").number() == 9.5,
        "Canonical Double");
  }

  // region lesson17
  private static void lesson17() {
    LanguageDocument device =
        language.defineYaml(
            """
            name: Device
            serial: {type: Text, schema: {required: true}}
            firmware: {type: Integer, schema: {minimum: 1}}
            """);
    LanguageDocument first = device.specializeYaml("serial: sensor-17\nfirmware: 1");
    LanguageDocument upgraded = first.edit(e -> e.set("/firmware/value", 2));
    check(first.at("/firmware").integer() == 1, "Old device content");
    check(upgraded.at("/firmware").integer() == 2, "New device content");
    expectInvalid(() -> first.edit(e -> e.set("/firmware/value", 0)));
  }

  private static void check(boolean condition, String message) {
    if (!condition) {
      throw new AssertionError(message);
    }
  }

  private static void expectInvalid(Runnable action) {
    try {
      action.run();
    } catch (LanguageException failure) {
      check(!failure.incomplete(), "Expected a conclusive rejection");
      return;
    }
    throw new AssertionError("Expected invalid Language content");
  }

  private static void expectIncomplete(Runnable action) {
    try {
      action.run();
    } catch (LanguageException failure) {
      check(failure.incomplete(), "Expected incomplete evidence");
      return;
    }
    throw new AssertionError("Expected incomplete Language evidence");
  }

  private static void expectLanguage(String category, Runnable action) {
    try {
      action.run();
    } catch (LanguageException failure) {
      check(
          failure.category().equals(category), "Unexpected Language error: " + failure.category());
      return;
    }
    throw new AssertionError("Expected Language error: " + category);
  }
}
