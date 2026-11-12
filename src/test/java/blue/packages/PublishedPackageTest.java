package blue.packages;

import static blue.packages.PackageTestSupport.CONTRACTS;
import static blue.packages.PackageTestSupport.CURRENT_LANGUAGE;
import static blue.packages.PackageTestSupport.bytes;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import blue.BluePackage;
import blue.Language;
import blue.LanguageException;
import blue.PackageLoader;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("packages")
@Tag("conformance")
class PublishedPackageTest {

  @Test
  void currentContractsPublicationLoadsWithItsPinnedLanguageAndProtocols() throws Exception {
    String archiveHash =
        java.util.HexFormat.of()
            .formatHex(
                java.security.MessageDigest.getInstance("SHA-256").digest(bytes("contracts")));
    assertEquals("405bcc2749e45ae6cee435c42043a5e7dae11d7f9acb8a6acca19d7ac3170f8d", archiveHash);
    PackageLoader loader = PackageLoader.newBuilder().language(CURRENT_LANGUAGE).build();
    BluePackage contracts =
        loader.resource(getClass().getClassLoader(), "packages/contracts.bluepkg", CONTRACTS);
    assertEquals(CONTRACTS, contracts.blueId());
    assertEquals(CURRENT_LANGUAGE, contracts.languageId());
    assertEquals("blue-contracts", contracts.name());
    assertEquals(CURRENT_LANGUAGE, contracts.imported("language").blueId());
    assertEquals("blue-bex", contracts.imported("bex").name());
    assertTrue(contracts.protocol().isPresent());
    assertFalse(contracts.declaredRequirements().isEmpty());
    assertEquals(contracts.export("Compute"), contracts.definition("Compute").blueId());
    assertFalse(contracts.documentation("README.md").isEmpty());
    Language language = Language.newBuilder().mount("Contracts", contracts).build();
    assertEquals(
        contracts.export("Compute"),
        language.defineYaml("type: Contracts/Compute").at("").type().orElseThrow());
    assertThrows(LanguageException.class, () -> language.defineYaml("type: bex/Function"));
  }
}
