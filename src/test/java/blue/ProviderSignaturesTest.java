package blue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.EdECPrivateKeySpec;
import java.security.spec.NamedParameterSpec;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Cryptographic domain authentication is independent of protocol eligibility and read access. */
@Tag("coordination")
class ProviderSignaturesTest {
  // Public RFC 8032 test material, never a deployment key.
  private static final String KEY =
      "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a";
  private static final String SEED =
      "9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60";
  private static final String EMPTY_SIGNATURE =
      "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e06522490155"
          + "5fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b";

  private static final class Fixtures {
    static final BluePackage PACKAGE =
        PackageLoader.create()
            .resource(
                ProviderSignaturesTest.class.getClassLoader(),
                "coordination/coordination.bluepkg",
                ProviderSignatures.COORDINATION_PACKAGE);
    static final Language LANGUAGE = Language.newBuilder().mount("Coordination", PACKAGE).build();
  }

  @ParameterizedTest
  @CsvSource(
      value = {
        "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a||e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e06522490155"
            + "5fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b",
        "3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c|72|92a009a9f0d4cab8720e820b5f642540a2b27b5416503f8fb3762223ebdb69da"
            + "085ac1e43e15996e458f3613d0f11d8c387b2eaeb4302aeeb00d291612bb0c00",
        "fc51cd8e6218a1a38da47ed00230f0580816ed13ba3303ac5deb911548908025|af82|6291d657deec24024827e69c3abe01a30ce548a284743a445e3680d7db5ac3ac"
            + "18ff9b538d16f290ae67f760984dc6594a7c15e9716ed28dc027beceea1ec40a"
      },
      delimiter = '|')
  void matchesRfc8032PureEd25519Vectors(String key, String message, String signature) {
    ProviderSignatures.verifySignature(
        ProviderSignatures.key(key),
        HexFormat.of().parseHex(message == null ? "" : message),
        signature);
  }

  @Test
  void exactProviderObjectGuaranteeAndReaderDomainsCannotBeSubstituted() throws Exception {
    LanguageDocument provider = provider("https://provider.example/api");
    ProviderSignatures verifier = ProviderSignatures.adopt(Fixtures.PACKAGE, provider);
    BlueId object = Language.create().readYaml("message: hello").blueId();
    String objectSignature = sign("blue.coordination.object\n" + provider.blueId() + "\n" + object);
    verifier.verifyObject(object, objectSignature);
    assertEquals(provider.blueId(), verifier.providerId());
    assertRejected(() -> verifier.verifyGuarantee(object, objectSignature));
    assertRejected(() -> ProviderSignatures.verifyReaderRequest(object, KEY, objectSignature));
    BlueId changed = Language.create().readYaml("message: changed").blueId();
    assertRejected(() -> verifier.verifyObject(changed, objectSignature));
    ProviderSignatures other =
        ProviderSignatures.adopt(Fixtures.PACKAGE, provider("https://other.example/api"));
    assertRejected(() -> other.verifyObject(object, objectSignature));

    String guarantee = sign("blue.coordination.guarantee\n" + object);
    verifier.verifyGuarantee(object, guarantee);
    assertRejected(() -> verifier.verifyObject(object, guarantee));
    String reader = sign("blue.coordination.reader-request\n" + object);
    ProviderSignatures.verifyReaderRequest(object, KEY, reader);
    ProviderSignatures.verifyReaderRequest(object, KEY, reader);
    assertRejected(() -> ProviderSignatures.verifyReaderRequest(changed, KEY, reader));
    assertRejected(() -> verifier.verifyGuarantee(object, reader));
  }

  @Test
  void structuralCompatibilityDoesNotAdoptAnUnrelatedProviderType() {
    LanguageDocument impostor =
        Fixtures.PACKAGE
            .definition("Timeline Provider")
            .edit(e -> e.set("/name", "Unrelated Provider"))
            .specializeYaml("endpoint: https://provider.example\npublicKey: " + KEY);
    assertTrue(
        Language.create().isSubtype(impostor, Fixtures.PACKAGE.definition("Timeline Provider")));
    assertEquals(
        "INVALID_PROVIDER_TYPE",
        assertThrows(
                BlueException.class, () -> ProviderSignatures.adopt(Fixtures.PACKAGE, impostor))
            .code());
  }

  @Test
  void providerKeyAndEndpointMayBeInheritedWithoutRewritingItsIdentity() throws Exception {
    LanguageDocument type =
        Fixtures.PACKAGE
            .definition("Timeline Provider")
            .extendYaml("endpoint: https://inherited.example/api\npublicKey: " + KEY);
    LanguageDocument provider = type.specializeYaml("region: eu");
    ProviderSignatures verifier = ProviderSignatures.adopt(Fixtures.PACKAGE, provider);
    BlueId object = Language.create().readYaml("1").blueId();
    verifier.verifyObject(
        object, sign("blue.coordination.object\n" + provider.blueId() + "\n" + object));
    assertEquals(provider.blueId(), verifier.providerId());
  }

  @Test
  void publishedTimelineEntryGuaranteeAndReadRequestUseExactLanguageIdentities() throws Exception {
    LanguageDocument provider = provider("https://provider.example/api");
    Language language =
        Language.newBuilder().mount("Coordination", Fixtures.PACKAGE).content(provider).build();
    LanguageDocument timeline =
        language.readYaml(
            """
            type: Coordination/Signed Timeline
            provider: {blueId: '%s'}
            url: https://provider.example/history/one
            publicRead: true
            """
                .formatted(provider.blueId()));
    language =
        Language.newBuilder().mount("Coordination", Fixtures.PACKAGE).content(timeline).build();
    LanguageDocument entry =
        language.readYaml(
            """
            type: Coordination/Timeline Entry
            timeline: {blueId: '%s'}
            actor: {type: Coordination/Principal Actor, givenName: Alice}
            timestamp: 42
            message: {amount: 7}
            """
                .formatted(timeline.blueId()));
    ProviderSignatures verifier = ProviderSignatures.adopt(Fixtures.PACKAGE, provider);
    String signedEntry =
        sign("blue.coordination.object\n" + provider.blueId() + "\n" + entry.blueId());
    verifier.verifyObject(entry.blueId(), signedEntry);
    assertEquals(entry.blueId(), entry.expand("/timeline").blueId());
    verifier.verifyObject(entry.expand("/timeline").blueId(), signedEntry);

    language = Language.newBuilder().mount("Coordination", Fixtures.PACKAGE).content(entry).build();
    LanguageDocument guarantee =
        language.readYaml(
            """
            type: Coordination/Timeline Completeness Guarantee
            timeline: {blueId: '%s'}
            completeBefore: 43
            lastEntry: {blueId: '%s'}
            """
                .formatted(timeline.blueId(), entry.blueId()));
    verifier.verifyGuarantee(
        guarantee.blueId(), sign("blue.coordination.guarantee\n" + guarantee.blueId()));
    LanguageDocument request =
        language.readYaml(
            """
            type: Coordination/Read Timeline Request
            timeline: {blueId: '%s'}
            limit: 1
            minimumCompleteBefore: 43
            """
                .formatted(timeline.blueId()));
    ProviderSignatures.verifyReaderRequest(
        request.blueId(), KEY, sign("blue.coordination.reader-request\n" + request.blueId()));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "relative",
        "http://provider.example",
        "https://user:pass@provider.example",
        "https://provider.example?a=1",
        "https://provider.example#fragment",
        "https://provider.example/",
        "https://provider.example/a/../b",
        "https://provider.example/a/%2e%2e/b",
        "https://provider.example/a b"
      })
  void noncanonicalProviderEndpointsAreRejected(String endpoint) {
    assertEquals(
        "INVALID_PROVIDER_ENDPOINT",
        assertThrows(
                BlueException.class,
                () -> ProviderSignatures.adopt(Fixtures.PACKAGE, provider(endpoint)))
            .code());
  }

  @ParameterizedTest
  @NullSource
  @ValueSource(
      strings = {
        "",
        "00",
        "D75A980182B10AB7D54BFED3C964073A0EE172F3DAA62325AF021A68F707511A",
        "edffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f",
        "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff",
        "0200000000000000000000000000000000000000000000000000000000000000"
      })
  void keyDecodingRejectsWrongSizesCaseNoncanonicalCoordinatesAndInvalidPoints(String key) {
    assertEquals(
        "INVALID_PROVIDER_KEY",
        assertThrows(BlueException.class, () -> ProviderSignatures.key(key)).code());
  }

  @Test
  void signatureEncodingAndScalarMalleabilityAreRejected() {
    for (String signature :
        new String[] {
          null,
          "",
          "00",
          EMPTY_SIGNATURE.toUpperCase(),
          EMPTY_SIGNATURE.substring(0, 64) + "ff".repeat(32),
          "ff".repeat(32) + EMPTY_SIGNATURE.substring(64)
        }) {
      assertRejected(
          () ->
              ProviderSignatures.verifySignature(
                  ProviderSignatures.key(KEY), new byte[0], signature));
    }
    assertRejected(
        () ->
            ProviderSignatures.verifySignature(
                ProviderSignatures.key(KEY), new byte[] {1}, EMPTY_SIGNATURE));
  }

  @Test
  void unsupportedPackagesDefinitionsAndUnrelatedTypesCannotSelectAProvider() {
    BluePackage wrong =
        Fixtures.PACKAGE.imported(Fixtures.PACKAGE.imports().keySet().iterator().next());
    assertEquals(
        "UNSUPPORTED_COORDINATION_PACKAGE",
        assertThrows(
                BlueException.class,
                () -> ProviderSignatures.adopt(wrong, provider("https://provider.example")))
            .code());
    assertEquals(
        "PROVIDER_VALUE_REQUIRED",
        assertThrows(
                BlueException.class,
                () ->
                    ProviderSignatures.adopt(
                        Fixtures.PACKAGE, Fixtures.PACKAGE.definition("Timeline Provider")))
            .code());
    assertEquals(
        "INVALID_PROVIDER_TYPE",
        assertThrows(
                BlueException.class,
                () ->
                    ProviderSignatures.adopt(
                        Fixtures.PACKAGE, Language.create().readYaml("value: 1")))
            .code());
  }

  @Test
  @Tag("concurrency")
  void oneAdoptedProviderCanVerifyIndependentRequestsConcurrently() throws Exception {
    LanguageDocument provider = provider("https://provider.example");
    ProviderSignatures verifier = ProviderSignatures.adopt(Fixtures.PACKAGE, provider);
    BlueId object = Language.create().readYaml("42").blueId();
    String signature = sign("blue.coordination.object\n" + provider.blueId() + "\n" + object);
    List<Callable<BlueId>> work = new ArrayList<>();
    for (int i = 0; i < 32; i++) {
      work.add(
          () -> {
            verifier.verifyObject(object, signature);
            assertRejected(() -> verifier.verifyGuarantee(object, signature));
            return verifier.providerId();
          });
    }
    try (var executor = Executors.newFixedThreadPool(4)) {
      for (var result : executor.invokeAll(work)) {
        assertEquals(provider.blueId(), result.get());
      }
    }
  }

  private static LanguageDocument provider(String endpoint) {
    return Fixtures.LANGUAGE.readYaml(
        "type: Coordination/Timeline Provider\nendpoint: '" + endpoint + "'\npublicKey: " + KEY);
  }

  private static String sign(String message) throws Exception {
    PrivateKey key =
        KeyFactory.getInstance("Ed25519")
            .generatePrivate(
                new EdECPrivateKeySpec(NamedParameterSpec.ED25519, HexFormat.of().parseHex(SEED)));
    Signature signer = Signature.getInstance("Ed25519");
    signer.initSign(key);
    signer.update(message.getBytes(StandardCharsets.UTF_8));
    return HexFormat.of().formatHex(signer.sign());
  }

  private static void assertRejected(org.junit.jupiter.api.function.Executable check) {
    assertEquals("INVALID_PROVIDER_SIGNATURE", assertThrows(BlueException.class, check).code());
  }
}
