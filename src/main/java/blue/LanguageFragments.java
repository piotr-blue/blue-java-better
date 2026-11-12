package blue;

import java.util.Map;

/** Exact fragment artifacts before conversion to the public immutable result. */
record LanguageFragments(
    String blueId, String rootText, Map<String, String> fragments, Map<String, String> proofs) {}
