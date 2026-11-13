# Provider signature authentication

`ProviderSignatures` implements the three signature domains from Coordination
1.0.0-deterministic.2, pinned to package
`GMzA1zqLaxpDhxefhcS3gWtPFQobAUB5rgWSj3jtvaJa`. It is a signature component, not a
complete provider, evidence verifier or feeder. It installs no native capability.

Adoption selects a trusted provider document. The verifier resolves its inherited
endpoint and key, checks the canonical HTTPS endpoint and retains its exact identity:

```java
BluePackage coordination = BluePackage.open(
    Path.of("coordination.bluepkg"), ProviderSignatures.COORDINATION_PACKAGE);
Language language = Language.newBuilder().mount("Coordination", coordination).build();
LanguageDocument provider = language.readYaml(Path.of("trusted-provider.blue"));
ProviderSignatures signatures = ProviderSignatures.adopt(coordination, provider);

signatures.verifyObject(objectBlueId, objectSignature);
signatures.verifyGuarantee(guaranteeBlueId, guaranteeSignature);
ProviderSignatures.verifyReaderRequest(requestBlueId, readerPublicKey, readerSignature);
```

Each call authenticates only the selected signature. A caller must first compute
or verify the signed content identity with Language; hashing arbitrary JSON is
not equivalent. The input IDs in this example identify separately acquired content.
The provider document must come from the caller's adoption policy, not an untrusted
response that offers a replacement key or identity.

| Signature | Still required before using its claim |
| --- | --- |
| Provider object | Exact envelope provider, allowed object type, request correlation and every governed timeline's adopted provider |
| Completeness guarantee | Exact attestation/guarantee timeline, bounds, complete predecessor adjacency, strict timestamps, retained-history agreement and final head equality |
| Reader request | Read-only operation, target provider and current Timeline Access on every request, including continuation |

A valid signature is neither a read grant nor proof that a document may process
an event. A previously valid reader request is deliberately reusable while access
remains granted. Completeness is historical and exclusive; no wall-clock freshness
is inferred. These remaining checks are not implemented by this component.

Wire keys and signatures use lowercase hexadecimal with exactly 32 and 64 bytes.
The implementation checks canonical public-key coordinates and delegates point
validation and pure Ed25519 verification to the maintained JDK cryptographic
implementation. Signature instances are invocation-local; immutable provider
verifiers may be shared across threads. No signer, private-key storage, generic
signing endpoint or network client is supplied.

The cryptographic API follows [RFC 8032](https://www.rfc-editor.org/rfc/rfc8032.html)
and uses the JDK's [Ed25519 support](https://docs.oracle.com/en/java/javase/25/security/oracle-providers.html).
The local Coordination specification remains authoritative for domain strings and
protocol obligations; its full published text is retained in the pinned fixture.

## Qualification and next integration

`ProviderSignaturesTest` owns RFC test vectors, inherited provider fields, identity
and domain replay, endpoint/key/signature rejection, wrong package/type selection
and concurrent use. Run `./gradlew coordinationTest` or the full build. Tests use
the bundled 675 KB canonical package, never a sibling repository or Python data.

[TimelineVerifier](TIMELINE-VERIFICATION.md) now establishes newly acquired source suffixes
against opaque, previously verified prefixes. It validates published range and successor evidence,
exact sources, predecessor chains, bounds and original messages without reopening old history.
Provider adoption now requires actual type ancestry: a structurally compatible unrelated record
cannot select the Timeline Provider role. Importing either component still installs no native service.

[TimelineReader](TIMELINE-READING.md) adds signed discovery, response/request correlation and contiguous
paginated reads. Network and exact-content acquisition, current reader authorization, historical access
execution, required-source closure, feeder ordering, durable prefix storage and native channel
integration remain work.

## Locally formed cyclic references

When a caller supplies a complete symbolic owning set, `Language.finalizeSet` derives its ordered proof and the body of each named member together. It installs those immutable results under canonical member indices. It admits members in the caller's original order and applies the caller's validation goal to each. The proof as a whole remains subject to the wire-size and structure limits. A separate provider is necessary for an external reference whose body is missing. A receiver that imports a proof authenticates it against the requested master and member index before accepting it. [Public regression tests](../src/test/java/blue/language/ContentExportTest.java)
