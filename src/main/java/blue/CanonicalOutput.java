package blue;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/** Invocation-local UTF-8 buffer. Canonical fragments are copied without Writer locks. */
final class CanonicalOutput implements Appendable {
  private static final int MAX_BUFFER = 16 * 1024;
  private final OutputStream output;
  private byte[] buffer = new byte[512];
  private final Map<String, byte[]> quotes = new HashMap<>();
  private int used;

  CanonicalOutput(OutputStream output) {
    this.output = output;
  }

  void quote(String value) throws IOException {
    byte[] encoded = quotes.get(value);
    if (encoded == null) {
      encoded = LanguageCodec.quote(value).getBytes(StandardCharsets.UTF_8);
      // Bound retention independently of the size of the serialized document.
      if (value.length() <= 256 && quotes.size() < 512) {
        quotes.put(value, encoded);
      }
    }
    appendUtf8(encoded);
  }

  void appendUtf8(byte[] value) throws IOException {
    int offset = 0;
    while (offset < value.length) {
      if (used == buffer.length) {
        makeRoom();
      }
      int count = Math.min(value.length - offset, buffer.length - used);
      System.arraycopy(value, offset, buffer, used, count);
      used += count;
      offset += count;
    }
  }

  void number(long value) throws IOException {
    if (buffer.length - used < 21) {
      makeRoom();
    }
    if (value < 0) {
      buffer[used++] = '-';
    }
    // Work in the negative range, which also represents Long.MIN_VALUE.
    long remaining = value > 0 ? -value : value;
    int start = used;
    do {
      buffer[used++] = (byte) ('0' - remaining % 10);
      remaining /= 10;
    } while (remaining != 0);
    for (int left = start, right = used - 1; left < right; left++, right--) {
      byte swap = buffer[left];
      buffer[left] = buffer[right];
      buffer[right] = swap;
    }
  }

  @Override
  public CanonicalOutput append(char value) throws IOException {
    if (buffer.length - used < 3) {
      makeRoom();
    }
    if (value < 128) {
      buffer[used++] = (byte) value;
    } else if (value < 2048) {
      buffer[used++] = (byte) (0xc0 | value >> 6);
      buffer[used++] = (byte) (0x80 | value & 63);
    } else if (Character.isSurrogate(value)) {
      buffer[used++] = '?';
    } else {
      buffer[used++] = (byte) (0xe0 | value >> 12);
      buffer[used++] = (byte) (0x80 | value >> 6 & 63);
      buffer[used++] = (byte) (0x80 | value & 63);
    }
    return this;
  }

  @Override
  public CanonicalOutput append(CharSequence value) throws IOException {
    return append(value, 0, value.length());
  }

  @Override
  public CanonicalOutput append(CharSequence value, int start, int end) throws IOException {
    for (int i = start; i < end; i++) {
      char c = value.charAt(i);
      if (Character.isHighSurrogate(c)
          && i + 1 < end
          && Character.isLowSurrogate(value.charAt(i + 1))) {
        int code = Character.toCodePoint(c, value.charAt(++i));
        if (buffer.length - used < 4) {
          makeRoom();
        }
        buffer[used++] = (byte) (0xf0 | code >> 18);
        buffer[used++] = (byte) (0x80 | code >> 12 & 63);
        buffer[used++] = (byte) (0x80 | code >> 6 & 63);
        buffer[used++] = (byte) (0x80 | code & 63);
      } else {
        append(c);
      }
    }
    return this;
  }

  /** Most commitments are small; larger exports amortize writes with a bounded growing buffer. */
  private void makeRoom() throws IOException {
    drain();
    if (buffer.length < MAX_BUFFER) buffer = new byte[buffer.length * 2];
  }

  private void drain() throws IOException {
    output.write(buffer, 0, used);
    used = 0;
  }

  void flush() throws IOException {
    drain();
    output.flush();
  }
}
