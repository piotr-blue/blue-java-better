package blue;

/** Unicode scalar ordering with one admission callback per examined 64-code-point block. */
final class MeteredText {
  private MeteredText() {}

  static int compare(String left, String right, Runnable block) {
    int a = 0, b = 0;
    while (a < left.length() && b < right.length()) {
      block.run();
      int aEnd = blockEnd(left, a), bEnd = blockEnd(right, b);
      if (aEnd - a == bEnd - b && left.regionMatches(a, right, b, aEnd - a)) {
        a = aEnd;
        b = bEnd;
        continue;
      }
      while (a < aEnd && b < bEnd) {
        int x = left.codePointAt(a), y = right.codePointAt(b);
        if (x != y) return Integer.compare(x, y);
        a += Character.charCount(x);
        b += Character.charCount(y);
      }
      if (a < aEnd || b < bEnd) return Integer.compare(aEnd - a, bEnd - b);
    }
    return Integer.compare(left.length() - a, right.length() - b);
  }

  private static int blockEnd(String text, int start) {
    int end = start + Math.min(64, text.length() - start);
    if (end < text.length()
        && Character.isHighSurrogate(text.charAt(end - 1))
        && Character.isLowSurrogate(text.charAt(end))) end--;
    int remaining = 64 - text.codePointCount(start, end);
    while (remaining-- > 0 && end < text.length()) {
      end += Character.charCount(text.codePointAt(end));
    }
    return end;
  }
}
