package blue;

import java.math.BigInteger;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.EdECPoint;
import java.security.spec.EdECPublicKeySpec;
import java.security.spec.NamedParameterSpec;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Signature authentication for an explicitly adopted Coordination provider. This verifies the three
 * published Ed25519 domains; it does not grant read access, establish a complete timeline prefix or
 * authorize Contracts execution. Importing this class installs no native runtime.
 *
 * <p>The provider identity is selected by the caller's trust policy. Its key is read from that
 * exact admitted document, including inherited fields; a response cannot substitute another
 * identity or key. Instances are immutable and safe to share between threads. No private key is
 * retained.
 */
public final class ProviderSignatures {
  /** The Coordination publication whose signature domains and provider schema are supported. */
  public static final BlueId COORDINATION_PACKAGE =
      new BlueId("GMzA1zqLaxpDhxefhcS3gWtPFQobAUB5rgWSj3jtvaJa");

  private static final BigInteger FIELD =
      BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19));
  private final BlueId provider;
  private final PublicKey key;

  private ProviderSignatures(BlueId provider, PublicKey key) {
    this.provider = provider;
    this.key = key;
  }

  /**
   * Adopts a provider selected by trusted configuration; this performs no discovery or network I/O.
   *
   * @param coordination verified supported Coordination package
   * @param provider exact completed provider selected by the caller, never an untrusted replacement
   * @return immutable verifier bound to the provider's exact identity and resolved key
   * @throws BlueException if the package, provider type, endpoint or key is unsupported or invalid
   */
  public static ProviderSignatures adopt(BluePackage coordination, LanguageDocument provider) {
    Objects.requireNonNull(coordination);
    Objects.requireNonNull(provider);
    require(COORDINATION_PACKAGE.equals(coordination.blueId()), "UNSUPPORTED_COORDINATION_PACKAGE");
    require(provider.goal() == Language.Goal.VALUE, "PROVIDER_VALUE_REQUIRED");
    require(
        Language.run(
            () ->
                LanguageSemantics.ancestry(
                        provider.blueId().value(),
                        provider.owner.context(provider, coordination.document()))
                    .contains(coordination.exports().get("Timeline Provider").value())),
        "INVALID_PROVIDER_TYPE");
    endpoint(provider.at("/endpoint").text());
    return new ProviderSignatures(provider.blueId(), key(provider.at("/publicKey").text()));
  }

  /**
   * Returns the pinned provider identity, independent of endpoint routing.
   *
   * @return exact adopted provider identity
   */
  public BlueId providerId() {
    return provider;
  }

  /**
   * Verifies a provider-object signature over the exact claimed object identity.
   *
   * <p>The caller must separately check the attestation's provider reference, permitted object
   * type, request correlation and every governed timeline. This method cannot turn an arbitrary
   * signed object into an accepted protocol response or execution input.
   *
   * @param object exact object identity, computed or verified by Language
   * @param signature canonical lowercase hexadecimal signature
   * @throws BlueException if the signature encoding or authentication is invalid
   */
  public void verifyObject(BlueId object, String signature) {
    verifySignature(
        key,
        message(
            "blue.coordination.object\n"
                + provider.value()
                + "\n"
                + Objects.requireNonNull(object).value()),
        signature);
  }

  /**
   * Verifies the dedicated completeness-guarantee signature domain.
   *
   * <p>The caller must establish that the guarantee and attestation name the same timeline owned by
   * this provider. A signature alone does not supply the predecessor chain or prove completeness.
   *
   * @param guarantee exact guarantee identity, computed or verified by Language
   * @param signature canonical lowercase hexadecimal signature
   * @throws BlueException if the signature encoding or authentication is invalid
   */
  public void verifyGuarantee(BlueId guarantee, String signature) {
    verifySignature(
        key,
        message("blue.coordination.guarantee\n" + Objects.requireNonNull(guarantee).value()),
        signature);
  }

  /**
   * Verifies possession of a reader key for an exact read request. This never grants permission.
   * Current Timeline Access, the operation's read-only role and its target provider must be checked
   * on every use, including cursor continuation. The signed query is intentionally reusable.
   *
   * @param request exact read-request identity, computed or verified by Language
   * @param publicKey canonical lowercase hexadecimal Ed25519 public key
   * @param signature canonical lowercase hexadecimal signature
   * @throws BlueException if the key, signature encoding or authentication is invalid
   */
  public static void verifyReaderRequest(BlueId request, String publicKey, String signature) {
    verifySignature(
        key(publicKey),
        message("blue.coordination.reader-request\n" + Objects.requireNonNull(request).value()),
        signature);
  }

  private static byte[] message(String text) {
    return text.getBytes(StandardCharsets.UTF_8);
  }

  static PublicKey key(String text) {
    byte[] encoded = hex(text, 32, "INVALID_PROVIDER_KEY");
    boolean odd = (encoded[31] & 128) != 0;
    encoded[31] &= 127;
    for (int i = 0; i < encoded.length / 2; i++) {
      byte swap = encoded[i];
      encoded[i] = encoded[encoded.length - i - 1];
      encoded[encoded.length - i - 1] = swap;
    }
    BigInteger y = new BigInteger(1, encoded);
    require(y.compareTo(FIELD) < 0, "INVALID_PROVIDER_KEY");
    try {
      PublicKey key =
          KeyFactory.getInstance("Ed25519")
              .generatePublic(
                  new EdECPublicKeySpec(NamedParameterSpec.ED25519, new EdECPoint(odd, y)));
      Signature.getInstance("Ed25519").initVerify(key);
      return key;
    } catch (GeneralSecurityException invalid) {
      throw failure("INVALID_PROVIDER_KEY", invalid);
    }
  }

  static void verifySignature(PublicKey key, byte[] message, String text) {
    byte[] encoded = hex(text, 64, "INVALID_PROVIDER_SIGNATURE");
    try {
      Signature verifier = Signature.getInstance("Ed25519");
      verifier.initVerify(key);
      verifier.update(message);
      require(verifier.verify(encoded), "INVALID_PROVIDER_SIGNATURE");
    } catch (GeneralSecurityException invalid) {
      throw failure("INVALID_PROVIDER_SIGNATURE", invalid);
    }
  }

  private static byte[] hex(String text, int bytes, String code) {
    require(text != null && text.length() == 2 * bytes, code);
    for (int i = 0; i < text.length(); i++) {
      char digit = text.charAt(i);
      require(digit >= '0' && digit <= '9' || digit >= 'a' && digit <= 'f', code);
    }
    return HexFormat.of().parseHex(text);
  }

  private static void endpoint(String value) {
    try {
      URI uri = URI.create(value);
      require(
          "https".equals(uri.getScheme())
              && uri.getHost() != null
              && uri.getUserInfo() == null
              && uri.getQuery() == null
              && uri.getFragment() == null
              && !value.endsWith("/")
              && uri.normalize().equals(uri),
          "INVALID_PROVIDER_ENDPOINT");
      for (String segment : uri.getPath().split("/", -1))
        require(!segment.equals(".") && !segment.equals(".."), "INVALID_PROVIDER_ENDPOINT");
    } catch (IllegalArgumentException invalid) {
      throw failure("INVALID_PROVIDER_ENDPOINT", invalid);
    }
  }

  private static void require(boolean accepted, String code) {
    if (!accepted) throw new BlueException(code, code);
  }

  private static BlueException failure(String code, Exception cause) {
    BlueException failure = new BlueException(code, cause.getMessage());
    failure.initCause(cause);
    return failure;
  }
}
