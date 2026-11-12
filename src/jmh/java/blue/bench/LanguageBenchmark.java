package blue.bench;

import blue.Language;
import blue.LanguageContent;
import blue.LanguageDocument;
import blue.LanguageView;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

/** Checks registry-size sensitivity of selected reads and small standalone admissions. */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
public class LanguageBenchmark {
  @Param({"1", "128", "4096"})
  public int unrelatedDefinitions;

  private Language language;
  private LanguageView age;
  private Language.Input input;

  @Setup
  public void setup() {
    Language source = Language.create();
    var type =
        source.readYaml(
            "name: Person\nage: {type: Integer}\ncountry: PL", Language.Goal.DEFINITION);
    var builder = Language.newBuilder().type("Example/Person", type);
    for (int i = 0; i < unrelatedDefinitions; i++) {
      LanguageContent content =
          source.prepare(
              Language.Input.json("{\"name\":\"Unrelated" + i + "\",\"value\":" + i + "}"));
      builder.content(content).alias("Example/Unrelated" + i, content.blueId());
    }
    language = builder.build();
    input = Language.Input.yaml("type: Example/Person\nage: 42");
    age = language.read(input, Language.Goal.VALUE).resolve().at("/age");
  }

  @Benchmark
  public long selectedRead() {
    return age.integer();
  }

  @Benchmark
  public LanguageDocument admitSmallDocument() {
    return language.read(input, Language.Goal.VALUE);
  }
}
