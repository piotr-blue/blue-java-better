package blue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("core")
class NodeTest {
  private final NodeCodec codec = new NodeCodec();

  @Test
  void yamlJsonAndPersistentEdits() {
    Node original = codec.readYaml("name: Order\nitems: [1, 2, 3]\ncustomer: {name: Alice}\n");
    Node changed = original.edit(edit -> edit.set("/items/1", Node.integer(8)));
    assertEquals(2, original.at("/items/1").integer());
    assertSame(original.get("customer"), changed.get("customer"));
    assertEquals(original, codec.readJson(codec.writeJson(original)));
    assertEquals(original, codec.readYaml(codec.writeYaml(original)));
    assertThrows(BlueException.class, () -> codec.readYaml("x: 1\nx: 2"));
    assertThrows(BlueException.class, () -> codec.readJson("{\"x\":1,\"x\":2}"));
    assertThrows(UnsupportedOperationException.class, () -> original.fields().clear());
  }

  @Test
  void pointerEscapingAndListPatchSemantics() {
    Node node = Node.object(Map.of("a/b", Node.object(Map.of("~", Node.integer(1)))));
    assertEquals(1, node.at("/a~1b/~0").integer());
    assertThrows(BlueException.class, () -> node.at("/a~2b"));
    Node array = Node.list(List.of(Node.integer(1), Node.integer(2)));
    assertEquals(
        List.of(Node.integer(1), Node.integer(9), Node.integer(2)),
        array.patch(Pointer.parse("/1"), Node.integer(9), "add").items());
    assertEquals(
        List.of(Node.integer(1), Node.integer(9)),
        array.patch(Pointer.parse("/1"), Node.integer(9), "replace").items());
  }

  @Test
  void randomPersistentEditsPreserveLogicalWeightsAndOldSnapshots() {

    Node value = Node.object();
    var random = new Random(20261103);
    var expected = new java.util.TreeMap<String, Node>();
    var snapshots = new java.util.ArrayList<Node>();
    var contents = new java.util.ArrayList<Map<String, Node>>();
    for (int i = 0; i < 3000; i++) {
      if (i % 100 == 0) {
        snapshots.add(value);
        contents.add(Map.copyOf(expected));
      }
      Node previous = value;
      String key = "key" + random.nextInt(200);
      if (random.nextBoolean()) {
        Node child = Node.list(List.of(Node.text("text" + i), Node.integer(i)));
        value = value.with(key, child);
        expected.put(key, child);
      } else {
        value = value.without(key);
        expected.remove(key);
      }
      assertEquals(expected, value.fields());
      assertEquals(codec.readJson(codec.writeJson(value)).logicalSize(), value.logicalSize());
      if (previous != value) {
        assertNotEquals(previous, value);
      }
    }
    for (int i = 0; i < snapshots.size(); i++) {
      assertEquals(contents.get(i), snapshots.get(i).fields(), "Retained snapshot " + i);
    }
  }
}
