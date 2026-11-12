package blue.language;

import static blue.Language.Goal.*;
import static org.junit.jupiter.api.Assertions.*;

import blue.Language;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("language")
@Tag("concurrency")
class LanguageConcurrencyTest {
  private final Language language = Language.create();

  @Test
  void immutableContextsAndDocumentsCanBeUsedConcurrently() throws Exception {
    var type = language.readYaml("x: {type: Integer}", DEFINITION);
    var app = Language.newBuilder().type("Example/Point", type).build();
    try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
      var tasks = new java.util.ArrayList<java.util.concurrent.Future<Long>>();
      for (int i = 0; i < 32; i++) {
        final int value = i;
        tasks.add(
            pool.submit(
                () ->
                    app.readYaml("type: Example/Point\nx: " + value).resolve().at("/x").integer()));
      }
      for (int i = 0; i < 32; i++) {
        assertEquals((long) i, tasks.get(i).get());
      }
    }
  }
}
