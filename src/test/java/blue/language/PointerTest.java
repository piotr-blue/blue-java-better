package blue.language;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import blue.BlueException;
import blue.Pointer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The public RFC 6901 pointer value: parsing, escaping, navigation and immutability. */
@Tag("language")
class PointerTest {
  @Test
  void theEmptyPointerAddressesTheRoot() {
    Pointer root = Pointer.parse("");
    assertEquals(List.of(), root.segments());
    assertEquals("", root.toString());
  }

  @Test
  void aSingleSlashAddressesTheEmptyKeyNotTheRoot() {
    assertEquals(List.of(""), Pointer.parse("/").segments());
    assertEquals(List.of("a", "", "b"), Pointer.parse("/a//b").segments());
    assertNotEquals(Pointer.parse(""), Pointer.parse("/"));
  }

  @Test
  void tildeEscapesDecodeToLiteralSegments() {
    assertEquals(List.of("a/b", "c~d"), Pointer.parse("/a~1b/c~0d").segments());
  }

  @Test
  void anEscapedTildeFollowedByOneIsNotASlash() {
    // "~01" is the escape of "~" followed by a literal "1"; decoding order must not produce "/".
    assertEquals(List.of("~1"), Pointer.parse("/~01").segments());
    assertEquals("/~01", Pointer.parse("/~01").toString());
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "/", "/a", "/a/b", "/a~1b/c~0d", "/~01", "/a//b", "/0/-/ż"})
  void printingReproducesTheParsedText(String text) {
    assertEquals(text, Pointer.parse(text).toString());
  }

  @ParameterizedTest
  @ValueSource(strings = {"a", "a/b", " /a", "/a~", "/a~2", "/~x/b", "/ok/~"})
  void malformedPointersAreRejectedWithAStableCode(String text) {
    BlueException failure = assertThrows(BlueException.class, () -> Pointer.parse(text));
    assertEquals("INVALID_POINTER", failure.code());
    assertEquals(text, failure.getMessage());
  }

  @Test
  void childAppendsALiteralSegmentAndLeavesTheParentUnchanged() {
    Pointer parent = Pointer.parse("/orders");
    Pointer child = parent.child("a/b~c");
    assertEquals(List.of("orders"), parent.segments());
    assertEquals(List.of("orders", "a/b~c"), child.segments());
    assertEquals("/orders/a~1b~0c", child.toString());
    assertEquals(child, Pointer.parse(child.toString()));
  }

  @Test
  void containsIsAReflexiveSegmentPrefixTest() {
    Pointer orders = Pointer.parse("/orders");
    assertTrue(orders.contains(orders));
    assertTrue(orders.contains(Pointer.parse("/orders/7/total")));
    assertTrue(Pointer.parse("").contains(orders));
    assertFalse(orders.contains(Pointer.parse("")));
    assertFalse(Pointer.parse("/orders/7").contains(orders));
    // A prefix is whole segments, never characters.
    assertFalse(orders.contains(Pointer.parse("/ordersArchive")));
    assertFalse(orders.contains(Pointer.parse("/order")));
  }

  @Test
  void escapeEncodesTildeBeforeSlash() {
    assertEquals("plain", Pointer.escape("plain"));
    assertEquals("a~1b", Pointer.escape("a/b"));
    assertEquals("a~0b", Pointer.escape("a~b"));
    assertEquals("~01", Pointer.escape("~1"));
  }

  @Test
  void segmentsAreCopiedAndUnmodifiable() {
    List<String> source = new ArrayList<>(List.of("a", "b"));
    Pointer pointer = new Pointer(source);
    source.set(0, "changed");
    assertEquals(List.of("a", "b"), pointer.segments());
    assertThrows(UnsupportedOperationException.class, () -> pointer.segments().add("c"));
  }

  @Test
  void nullSegmentsAreRejected() {
    assertThrows(NullPointerException.class, () -> new Pointer(null));
    assertThrows(NullPointerException.class, () -> new Pointer(Arrays.asList("a", null)));
    assertThrows(NullPointerException.class, () -> Pointer.parse("/a").child(null));
  }

  @Test
  void equalityFollowsDecodedSegments() {
    assertEquals(Pointer.parse("/a~1b"), new Pointer(List.of("a/b")));
    assertEquals(Pointer.parse("/a~1b").hashCode(), new Pointer(List.of("a/b")).hashCode());
    assertNotEquals(Pointer.parse("/a/b"), new Pointer(List.of("a/b")));
  }
}
