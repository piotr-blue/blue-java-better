package blue;

import java.util.List;

/** Validated content; wire serialization and evidence export remain explicit boundaries. */
record LanguageAdmission(ExactNode exact, String originalBlueId, List<String> removedFields) {
  LanguageAdmission {
    removedFields = List.copyOf(removedFields);
  }
}
