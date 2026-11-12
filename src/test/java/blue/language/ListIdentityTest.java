package blue.language;

import static org.junit.jupiter.api.Assertions.*;

import blue.Language;
import blue.LanguageDocument;
import blue.Node;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Tutorial 04's identity law, including duplicate values, empty results and collapsed content. */
@Tag("language")
class ListIdentityTest {
  static Stream<Arguments> removals() {
    return Stream.of(List.of(1L), List.of(1L, 2L, 3L), List.of(5L, 5L, -9L, 5L))
        .flatMap(
            values ->
                IntStream.range(0, values.size())
                    .boxed()
                    .flatMap(
                        index ->
                            Stream.of(false, true)
                                .map(collapsed -> Arguments.of(values, index, collapsed))));
  }

  @ParameterizedTest(name = "values={0}, remove={1}, collapsed={2}")
  @MethodSource("removals")
  void deletionReplacementAndAuthoredResultHaveIdenticalContent(
      List<Long> values, int index, boolean collapsed) {
    Language language = Language.create();
    LanguageDocument original = language.readJson("{\"values\":" + values + "}");
    LanguageDocument input = collapsed ? original.collapse("/values") : original;
    List<Long> expected = new ArrayList<>(values);
    expected.remove(index);
    LanguageDocument authored = language.readJson("{\"values\":" + expected + "}");
    LanguageDocument deleted = input.edit(editor -> editor.remove("/values/items/" + index));
    LanguageDocument replaced =
        input.edit(
            editor ->
                editor.set(
                    "/values/items",
                    Node.list(
                        expected.stream().map(value -> Node.integer(value.longValue())).toList())));

    assertAll(
        () -> assertEquals(authored.blueId(), deleted.blueId()),
        () -> assertEquals(authored.blueId(), replaced.blueId()),
        () -> assertEquals(authored.canonicalJson(), deleted.canonicalJson()),
        () -> assertEquals(authored.canonicalJson(), replaced.canonicalJson()),
        () -> assertEquals(values.size(), original.at("/values").size()),
        () -> assertEquals(original.blueId(), input.blueId()));
  }
}
