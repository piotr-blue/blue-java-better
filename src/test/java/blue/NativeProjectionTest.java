package blue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class NativeProjectionTest {
  @Test
  void listsAndMapsReachTheSameInclusiveNativeDepthLimit() {
    int maximum = ProjectionLimits.defaults().depth();
    for (boolean list : List.of(true, false)) {
      Object nested = "leaf";
      for (int level = 0; level < maximum; level++) {
        nested = list ? List.of(nested) : Map.of("child", nested);
      }
      var values = new NativeValues();
      assertEquals(nested, values.freeze(nested));
      Object overLimit = list ? List.of(nested) : Map.of("child", nested);
      var error = assertThrows(ContractNative.AccessFailure.class, () -> values.freeze(overLimit));
      assertEquals("PROJECTION_LIMIT_EXCEEDED", ((BlueException) error.getCause()).code());
    }
  }

  @Test
  void clearingTheNativeViewDropsOnlyItsInvocationSnapshot() {
    var values = new NativeValues();
    var rows = new ArrayList<>(List.of("before"));
    var first = values.freeze(rows);
    rows.add("after");
    assertSame(first, values.freeze(rows));
    assertEquals(List.of("before"), first);
    values.clear();
    assertEquals(List.of("before", "after"), values.freeze(rows));
    assertEquals(List.of("before"), first);
  }
}
