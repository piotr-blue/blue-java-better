package blue.examples;

import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The guide and tests execute the same snippets; these are documented adaptations, not MyOS parity.
 */
@Tag("scenario")
class TutorialScenariosTest {
  static Stream<CompleteTutorialExample.Lesson> lessons() {
    return CompleteTutorialExample.lessons().stream();
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("lessons")
  void implementsDocumentedLesson(CompleteTutorialExample.Lesson lesson) {
    lesson.run();
  }
}
