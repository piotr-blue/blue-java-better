package blue;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Explicitly versioned native intrinsic. Report acceptance remains authored Blue policy. */
@SuppressWarnings({"unchecked", "serial", "try"})
final class CanonicalWireMeasurementV1 {
  private static final String NAME = "Canonical Wire Byte Length V1";
  private static final String RULES =
      "Measure the UTF-8 byte length of Language canonicalText for the opened exact root. Preserve"
          + " nested native references; do not serialize the transitive evidence graph. The input"
          + " is exactly one input operand. Reject unavailable root content and invalid Unicode."
          + " Charge 5 base units, then ordinary logical native value construction/validation"
          + " charges. Before canonicalization, reserve closure work in deterministic depth-first"
          + " field order: 1 per manifest attempt; 8 times field count times one plus"
          + " ceil(log2(field count)) for members; 8 per list item; 8 per started 64 units of six"
          + " times each UTF-16 text/key length (keys also multiplied by sort rounds plus one); 8"
          + " times squared magnitude-based 32-bit Integer limbs. For native Integer decimal String"
          + " payloads reserve ceil(4 times decimal digits / 32) limbs before conversion, excluding"
          + " a minus sign; inspect exact type ancestry with 1 per edge, reference text blocks and"
          + " manifest charges. Reference identities cost 8 per started 64 UTF-16 units; follow"
          + " each non-core reference once. Reservations cover canonical traversal, sorting, wire"
          + " encoding, round-trip parsing/identity and UTF-8 encoding. No post-work gas charge. No"
          + " application size limit or business decision belongs to this capability. The"
          + " non-additive runtime marker and explicit binding select V1; previous bindings do not"
          + " install it.";
  public static final String TYPE_ID = identity();

  private static String identity() {
    {
      Language language = Language.create();
      return language
          .blueId(
              Language.Input.exactJson(
                  ContractWire.write(Map.of("name", NAME, "description", RULES))))
          .value();
    }
  }

  private static final ContractNative.Implementation MARKER =
      new ContractNative.Implementation() {};
  private final ContractBridge blue;

  CanonicalWireMeasurementV1(ContractBridge blue) {
    this.blue = blue;
  }

  static void installMarker(ContractProcessor contracts, ContractBridge blue) {
    var type = blue.source(Map.of("name", NAME, "description", RULES), "definition");
    if (!TYPE_ID.equals(ContractLanguage.identity(type)))
      throw new IllegalStateException("Canonical wire identity mismatch");
    var semantics = blue.source(RULES);
    var marker =
        blue.source(
            Map.of(
                "name",
                "Canonical Wire Measurement V1 Runtime",
                "type",
                "Contracts/Marker",
                "description",
                RULES,
                "intrinsic",
                Map.of("blueId", TYPE_ID)),
            "definition");
    contracts.installRuntime(
        new ContractNative.Binding(
            ContractLanguage.identity(marker),
            ContractLanguage.identity(semantics),
            "marker",
            List.of(),
            List.of(),
            "canonical-wire-v1",
            Map.of(),
            false,
            List.of(),
            "live"),
        MARKER);
  }

  Object measure(Map<String, Object> payload, BexGas gas) {
    gas.amount("canonicalWireV1Base", 5);
    if (!payload.keySet().equals(Set.of("input")))
      throw new BexFailure(
          "BEX_RUNTIME_ERROR", "canonical wire measurement requires exactly input");
    if (!(gas instanceof ContractModel.Meter meter))
      throw new BexFailure(
          "BEX_RUNTIME_ERROR", "canonical wire measurement requires the Contracts shared meter");
    var value = blue.node(payload.get("input"), "value", true, new ContractWork(blue, meter));
    final String wire;
    try {
      wire =
          blue.context.canonicalRootText(
              value, (counter, units) -> gas.charge("canonicalWireV1" + counter, units, units));
    } catch (LanguageFailure failure) {
      throw blue.translate(failure);
    }
    int length = wire.getBytes(StandardCharsets.UTF_8).length;
    return BigInteger.valueOf(length);
  }
}
