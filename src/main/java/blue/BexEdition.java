package blue;

/** Exact current Language/BEX publication. No alternate semantic editions. */
final class BexEdition {
  public static final String LANGUAGE = "8XGYNufx24r7Dc5bGoeobcGNCn9roLzSfZCQyHBTaTx4";
  public static final String PROTOCOL = "9X3Ws96pyH1SdP4yuXpkNfWPfW3vJCzDzhdza3u9NpoB";
  public static final String RULES = "8ncKHLULWkzyNKEHfzpswF6iKZfc3H1DzDpgH8UC1hV";
  private static final BexEdition CURRENT = new BexEdition();

  private BexEdition() {}

  public static BexEdition current() {
    return CURRENT;
  }

  public boolean finalGas() {
    return true;
  }

  public static BexEdition select(String language, String protocol) {
    if (!LANGUAGE.equals(language) || !PROTOCOL.equals(protocol))
      throw new BexFailure("UNSUPPORTED_PROFILE", "Unsupported exact Language/BEX binding");
    return CURRENT;
  }

  public String languageBlueId() {
    return LANGUAGE;
  }

  public String protocolBlueId() {
    return PROTOCOL;
  }

  public String rulesBlueId() {
    return RULES;
  }
}
