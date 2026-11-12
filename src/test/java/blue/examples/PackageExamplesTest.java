package blue.examples;

import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

@Tag("scenario")
@Tag("packages")
class PackageExamplesTest {
  @TempDir Path directory;

  static Stream<PackageExample.Lesson> lessons() {
    return PackageExample.lessons().stream();
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("lessons")
  void runsDocumentedExample(PackageExample.Lesson lesson) throws Exception {
    lesson.run(directory);
  }
}
