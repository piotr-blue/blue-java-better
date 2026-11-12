package blue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** A verifier result is shared only after detachment, and never across invocations. */
@Tag("contracts")
class NativeAdmissionOwnershipTest {
  @ParameterizedTest
  @ValueSource(ints = {2, 128})
  void receiversShareOneImmutableProjectionWhileTheNextInputReverifies(int count) {
    Language language = Language.newBuilder().mount("Contracts", Contracts.publication()).build();
    LanguageDocument type = language.defineYaml("type: Contracts/Channel");
    LanguageDocument semantics = language.readYaml("description: Immutable admission ownership.");
    var binding =
        new ContractNative.Binding(
            type.blueId().value(),
            semantics.blueId().value(),
            "external-channel",
            List.of(),
            List.of(),
            "ownership",
            Map.of("accept", 1L),
            false,
            List.of(),
            "live");
    List<Object> received = new ArrayList<>();
    List<Object> supplied = new ArrayList<>();
    int[] verified = {0};
    NativeChannel channel =
        new NativeChannel(
            type,
            semantics,
            binding,
            new ContractNative.Implementation() {
              @Override
              public boolean verifiesAdmission() {
                return true;
              }

              @Override
              public Map<String, Object> verifyAdmission(ContractNative.AdmissionContext context) {
                verified[0]++;
                supplied.clear();
                supplied.add("verified");
                return Map.of("nested", Map.of("rows", supplied));
              }

              @Override
              @SuppressWarnings("unchecked")
              public boolean accept(ContractNative.ExternalContext context) {
                context.gas().charge("accept");
                received.add(context.evidence());
                Map<String, Object> projection = (Map<String, Object>) context.evidence();
                Map<String, Object> nested = (Map<String, Object>) projection.get("nested");
                List<Object> rows = (List<Object>) nested.get("rows");
                supplied.add("later mutation");
                assertEquals(List.of("verified"), rows);
                assertThrows(UnsupportedOperationException.class, () -> rows.add("changed"));
                assertThrows(
                    UnsupportedOperationException.class, () -> nested.put("changed", true));
                return true;
              }
            });
    ContractProcessor processor = new ContractProcessor();
    channel.install(processor);
    Map<String, Object> declarations = new LinkedHashMap<>();
    for (int n = 0; n < count; n++) {
      declarations.put("input" + n, Map.of("type", Map.of("blueId", type.blueId().value())));
    }
    var document = processor.create(Map.of("contracts", declarations));
    var first = processor.process(document, Map.of("sequence", 1), 1);
    assertEquals("APPLIED", first.status());
    var second = processor.process(first.document(), Map.of("sequence", 2), 2);
    assertEquals("APPLIED", second.status());
    assertEquals(2, verified[0]);
    assertEquals(2 * count, received.size());
    for (int n = 1; n < count; n++) {
      assertSame(received.get(0), received.get(n));
      assertSame(received.get(count), received.get(count + n));
    }
    assertNotSame(received.get(0), received.get(count));
  }
}
