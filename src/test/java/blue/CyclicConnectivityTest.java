package blue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

class CyclicConnectivityTest {
  @Test
  void everyThreeMemberDirectedGraphMatchesIndependentAllPairsReachability() {
    int members = 3;
    for (int mask = 0; mask < 1 << (members * members); mask++) {
      boolean[][] paths = new boolean[members][members];
      var raw = new ArrayList<>();
      for (int source = 0; source < members; source++) {
        var body = new TreeMap<String, Object>();
        body.put("name", "member-" + source);
        for (int target = 0; target < members; target++) {
          if ((mask & (1 << (source * members + target))) != 0) {
            paths[source][target] = true;
            body.put("edge-" + target, Map.of("blueId", "this#" + target));
          }
        }
        raw.add(body);
      }
      for (int middle = 0; middle < members; middle++) {
        for (int src = 0; src < members; src++) {
          for (int dst = 0; dst < members; dst++) {
            paths[src][dst] |= paths[src][middle] && paths[middle][dst];
          }
        }
      }
      boolean connected = true;
      for (boolean[] row : paths) {
        for (boolean value : row) {
          connected &= value;
        }
      }
      if (connected) {
        assertEquals(
            members,
            LanguageCore.finalizeSet(raw, new LanguageContext()).ids().size(),
            "mask " + mask);
      } else {
        var failure =
            assertThrows(
                LanguageFailure.class,
                () -> LanguageCore.finalizeSet(raw, new LanguageContext()),
                "mask " + mask);
        assertEquals("CircularSetError", failure.category());
      }
    }
  }

  @Test
  void reindexingDistinctMembersPreservesTheMasterAndEveryMappedMember() {
    var base =
        List.of(
            Map.of("name", "Anna", "peer", Map.of("blueId", "this#1")),
            Map.of("name", "Mira", "peer", Map.of("blueId", "this#2")),
            Map.of("name", "Oli", "peer", Map.of("blueId", "this#0")));
    var permuted =
        List.of(
            Map.of("name", "Oli", "peer", Map.of("blueId", "this#1")),
            Map.of("name", "Anna", "peer", Map.of("blueId", "this#2")),
            Map.of("name", "Mira", "peer", Map.of("blueId", "this#0")));
    var first = LanguageCore.finalizeSet(base, new LanguageContext());
    var second = LanguageCore.finalizeSet(permuted, new LanguageContext());
    assertEquals(first.master(), second.master());
    assertEquals(List.of(first.ids().get(2), first.ids().get(0), first.ids().get(1)), second.ids());
  }
}
