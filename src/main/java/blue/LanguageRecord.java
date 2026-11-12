package blue;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Immutable, unverified provider evidence. A valid identifier alone does not authenticate it. */
public final class LanguageRecord {
  private final Map<String, Object> response;

  LanguageRecord(Map<String, Object> response) {
    this.response = LanguageCore.map(LanguageValues.freeze(response));
  }

  /**
   * Supplies an exact JSON body.
   *
   * @param text original JSON, without authoring aliases or list construction
   * @return provider evidence
   */
  public static LanguageRecord json(String text) {
    return new LanguageRecord(
        Map.of("text", Objects.requireNonNull(text), "mode", "exact", "format", "json"));
  }

  /**
   * Supplies an exact YAML body.
   *
   * @param text original YAML, without authoring aliases or list construction
   * @return provider evidence
   */
  public static LanguageRecord yaml(String text) {
    return new LanguageRecord(
        Map.of("text", Objects.requireNonNull(text), "mode", "exact", "format", "yaml"));
  }

  /**
   * Supplies a complete owning-set proof for a cyclic member.
   *
   * @param proof original JSON containing the canonical symbolic members
   * @return provider evidence; the requested member selects its body
   */
  public static LanguageRecord cyclic(String proof) {
    return new LanguageRecord(Map.of("proof", Objects.requireNonNull(proof)));
  }

  /**
   * Supplies a cyclic proof and a separately claimed member body, both of which must agree.
   *
   * @param proof original owning-set JSON
   * @param body original exact body JSON
   * @return provider evidence
   */
  public static LanguageRecord cyclic(String proof, String body) {
    return new LanguageRecord(
        Map.of("proof", Objects.requireNonNull(proof), "body", Objects.requireNonNull(body)));
  }

  /**
   * Reads a portable provider-record envelope, not a Blue document. The resulting evidence remains
   * unverified until Language resolves it under the requested identity.
   *
   * @param json envelope previously written by {@link #toJson()}
   * @return immutable unverified exact-body or cyclic-proof evidence
   */
  public static LanguageRecord fromJson(String json) {
    Objects.requireNonNull(json);
    return Language.run(
        () -> {
          Map<String, Object> data =
              LanguageCore.map(LanguageOperations.parse(json, "json", Language.create().context()));
          if (data.containsKey("proof")) {
            LanguageFailure.require(
                Set.of("proof", "body").containsAll(data.keySet()), "InvalidProviderRecord");
          } else {
            LanguageFailure.require(
                data.get("text") instanceof String
                    && Set.of("text", "mode", "format").containsAll(data.keySet())
                    && "exact".equals(data.getOrDefault("mode", "exact"))
                    && Set.of("json", "yaml").contains(data.getOrDefault("format", "json")),
                "InvalidProviderRecord");
          }
          return new LanguageRecord(data);
        });
  }

  /**
   * Serializes the provider-record envelope, retaining a cyclic proof and claimed body together.
   * This does not admit the content or assert its claimed identity.
   *
   * @return portable JSON envelope; reload with {@link #fromJson(String)}
   */
  public String toJson() {
    return LanguageCodec.wire(response);
  }

  Map<String, Object> response() {
    return response;
  }
}
