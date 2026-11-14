package blue;

import java.util.regex.Pattern;

/** Shared immutable grammar; matchers remain local to each validation. */
final class LanguageSyntax {
  static final Pattern INTEGER = Pattern.compile("0|-[1-9][0-9]*|[1-9][0-9]*");
  static final Pattern NUMBER =
      Pattern.compile("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?");
  static final Pattern LIST_INDEX = Pattern.compile("0|[1-9][0-9]*");
  static final Pattern LOCAL_REFERENCE = Pattern.compile("this#(?:0|[1-9][0-9]*)");

  private LanguageSyntax() {}
}
