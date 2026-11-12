package blue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Authored metadata belongs to a value; type labels and invalid refinements must not leak. */
@Tag("contracts")
class ContractViewTest {
  @Test
  void bexDataCompactsNestedValuesWithoutDiscardingListMetadata() {
    Language language = Language.create();
    LanguageDocument value =
        language.readYaml(
            """
            records:
              - count: 1
                labels: [a, b]
            tagged:
              name: Labeled
              items: [1, 2]
            large:
              type: Integer
              value: "9007199254740993"
            """);
    ContractBridge bridge = new ContractBridge(language);
    bridge.context.install(value);
    assertEquals(
        Map.of(
            "records", List.of(Map.of("count", BigInteger.ONE, "labels", List.of("a", "b"))),
            "tagged", Map.of("name", "Labeled", "items", List.of(BigInteger.ONE, BigInteger.TWO)),
            "large", new BigInteger("9007199254740993")),
        bridge.bexData(value.exact));
  }

  @Test
  void bexDataKeepsDerivedScalarTypesAndRepeatedReferences() {
    Language language = Language.create();
    LanguageDocument type = language.defineYaml("name: Label\ntype: Text");
    LanguageDocument value = type.specializeYaml("value: hello");
    ContractBridge bridge = new ContractBridge(language);
    bridge.context.install(value);
    ExactNode reference = new ExactNode.Ref(value.blueId().value());
    ExactNode siblings = ExactNode.list(List.of(reference, reference));
    Map<String, Object> expected =
        Map.of("type", Map.of("blueId", type.blueId().value()), "value", "hello");
    assertEquals(List.of(expected, expected), bridge.bexData(siblings));
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(ints = {1, 16, 64})
  void bexDataPreservesNestedObjectAndListStructure(int depth) {
    Object expected = BigInteger.TEN;
    for (int i = 0; i < depth; i++) {
      expected = Map.of("next", List.of(expected));
    }
    Language language = Language.create();
    LanguageDocument value = language.readJson(ContractCanonical.text(expected));
    ContractBridge bridge = new ContractBridge(language);
    bridge.context.install(value);
    assertEquals(expected, bridge.bexData(value.exact));
  }

  @Test
  void typeLabelsAreNotInheritedByTheProjectedValue() {
    Language language = Language.create();
    LanguageDocument type =
        language.defineYaml("name: Template\ndescription: Type label\ncount: {type: Integer}");
    LanguageDocument value = type.specializeYaml("count: 3");
    ContractBridge bridge = new ContractBridge(language);
    bridge.context.install(value);
    BexValues.HostValue view = (BexValues.HostValue) bridge.view(value.exact, null);
    assertFalse(view.keys().contains("name"));
    assertFalse(view.keys().contains("description"));
    assertSame(BexValues.UNDEFINED, view.get("name"));
    assertSame(BexValues.UNDEFINED, view.get("description"));
  }

  @Test
  void composedValueRetainsItsOwnMetadataAndMatchesTheFullProjection() {
    Language language = Language.create();
    LanguageDocument definition =
        language.defineYaml("description: Value description\ncount: {type: Integer}");
    LanguageDocument overlay = language.readYaml("name: Instance\ncount: 3");
    ContractBridge bridge = new ContractBridge(language);
    bridge.context.install(definition);
    bridge.context.install(overlay);
    var recipe = new LanguageModel.Recipe(List.of(definition.exact, overlay.exact), List.of());
    BexValues.HostValue view = (BexValues.HostValue) bridge.view(recipe, null);
    assertEquals("Instance", view.get("name"));
    assertEquals("Value description", view.get("description"));
    var projected = new java.util.TreeMap<String, Object>();
    view.keys().forEach(key -> projected.put(key, view.get(key)));
    assertEquals(bridge.project(recipe), projected);
  }

  @Test
  void readingMetadataDoesNotHideAnInvalidNestedRefinement() {
    Language language = Language.create();
    LanguageDocument first = language.readYaml("name: Value\nnested: {a: 1, b: {fixed: 2}}");
    LanguageDocument second = language.readYaml("nested: {b: {fixed: 3}}");
    ContractBridge bridge = new ContractBridge(language);
    bridge.context.install(first);
    bridge.context.install(second);
    var recipe = new LanguageModel.Recipe(List.of(first.exact, second.exact), List.of());
    assertThrows(
        ContractModel.Error.class,
        () -> {
          BexValues.HostValue view = (BexValues.HostValue) bridge.view(recipe, null);
          view.get("name");
        });
  }
}
