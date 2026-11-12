package blue;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Physical preparation must preserve the existing logical-prefix byte sequence. */
@Tag("contracts")
@Tag("conformance")
class ContractInventoryTest {
  @Test
  void identitiesRetainUtf16OrderAndExactEscapingAcrossIncrementalAdditions() {
    var inventory = new ContractInventory();
    var expected = new TreeSet<String>();
    for (String value : List.of("z", "\uE000", "😀", "a\n\"b", "z", "é", "")) {
      assertEquals(expected.add(value), inventory.add(value));
      assertEquals(
          ContractCanonical.text(List.copyOf(expected)),
          ContractCanonical.text(inventory.encoding()));
      assertTrue(inventory.contains(value));
    }
    var fork = new ContractInventory();
    fork.addAll(inventory);
    fork.add("fork");
    assertFalse(inventory.contains("fork"));
    assertThrows(
        UnsupportedOperationException.class,
        () -> {
          var iterator = inventory.iterator();
          iterator.next();
          iterator.remove();
        });
  }

  @Test
  void ordinaryAndCyclicEvidenceShareTheExistingRecordOrder() {
    var rows = new TreeMap<String, ContractCanonical.Fragment>();
    var ordinary = new ContractInventory(rows, k -> List.of("ordinary", k), false);
    var cyclic = new ContractInventory(rows, ContractWire::json, false);
    var expected = new ArrayList<Object>();
    for (String identity : List.of("z", "😀", "a")) {
      ordinary.add(identity);
      expected.add(List.of("ordinary", identity));
      Object record = List.of("cyclic", Map.of("scope", identity), identity);
      assertTrue(cyclic.add(ContractWire.write(record)));
      assertFalse(cyclic.add(ContractWire.write(record)));
      expected.add(record);
      expected.sort(Comparator.comparing(ContractWire::write));
      assertEquals(ContractCanonical.text(expected), ContractCanonical.text(ordinary.encoding()));
      assertEquals(ContractCanonical.text(expected), ContractCanonical.text(cyclic.encoding()));
    }
  }
}
