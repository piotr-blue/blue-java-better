package blue.examples;

import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("scenario")
class TutorialExportTest {
  @Test
  void archivedLanguageExamplesAndCanonicalCounter(@TempDir Path directory) throws Exception {
    TutorialExample.run(directory);
  }
}
