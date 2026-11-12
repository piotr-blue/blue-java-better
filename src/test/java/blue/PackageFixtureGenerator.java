package blue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Rebuilds the small authored demonstration publications; not a public publishing API. */
public final class PackageFixtureGenerator {
  private PackageFixtureGenerator() {}

  public static void main(String[] args) throws Exception {
    Path output = Path.of(args[0]);
    Language language = Language.create();
    PackageFixtures.Publication bootstrap = PackageFixtures.bootstrap();
    LanguageDocument point = language.defineYaml("x: {type: Integer}\ny: {type: Integer}");
    LanguageDocument colored = point.extendYaml("color: {type: Text}");
    PackageFixtures.Publication geometry =
        PackageFixtures.library(
            "geometry",
            "1.0.0",
            bootstrap,
            Map.of(),
            Map.of("Point", point, "Origin", point.specializeYaml("x: 0\ny: 0")));
    PackageFixtures.Publication drawing =
        PackageFixtures.library(
            "drawing",
            "1.0.0",
            bootstrap,
            Map.of("geometry", geometry),
            Map.of("Coordinate", point, "Colored Point", colored));
    LanguageDocument spatial = point.extendYaml("z: {type: Integer}");
    PackageFixtures.Publication geometry2 =
        PackageFixtures.library("geometry", "2.0.0", bootstrap, Map.of(), Map.of("Point", spatial));
    Language.CyclicSet loop =
        language.finalizeSet(
            List.of(Language.Input.exactJson("{\"self\":{\"blueId\":\"this#0\"}}")),
            Language.Goal.DEFINITION);
    PackageFixtures.Publication content =
        PackageFixtures.library(
            "content",
            "1.0.0",
            bootstrap,
            Map.of(),
            Map.of(
                "Loop",
                loop.members().getFirst(),
                "Palette",
                language.readYaml("colors: [red, blue, green]")));
    Map<String, PackageFixtures.Publication> packages =
        Map.of(
            "language",
            bootstrap,
            "geometry",
            geometry,
            "drawing",
            drawing,
            "geometry-v2",
            geometry2,
            "content",
            content);
    for (var entry : packages.entrySet()) {
      BluePackage pkg = entry.getValue().load(output.resolve(entry.getKey() + ".bluepkg"));
      System.out.println(entry.getKey() + " " + pkg.blueId());
    }
  }
}
