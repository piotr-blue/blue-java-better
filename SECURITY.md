# Security boundaries

This library processes untrusted documents; it does not authenticate users, run a network
service, or grant permission to perform a business operation. Hosts own those decisions.
BlueIds identify content. Knowing an identity is not authorization to retrieve its bytes.

## Inputs and limits

Use the public `Language` entry points with explicit `Language.Limits` for hosted workloads.
They bound source bytes, depth, values, numeric text, provider work and execution steps.
JSON token parsing checks the operation budget before descending into or constructing a
value. YAML receives an event-level budget pass and rejects anchors, aliases, tags and
directives before composition. Internal JSON helpers also handle already validated exact
values; their Jackson settings are not a separate public admission policy. Replacing those
settings with arbitrary fixed limits would change valid operations without changing the
configured Language limits.

Pointer escape validation is shared by the public pointer, Language, Contracts and BEX parsers.
Every literal tilde must use `~0` or `~1`, including next to line terminators, as required by
[RFC 6901 section 3](https://www.rfc-editor.org/rfc/rfc6901#section-3). Valid field names may
contain line terminators. Contracts' normalization policy and BEX's root alias remain separate.

`PackageLoader` checks archive bytes, entry count, individual and aggregate expanded bytes,
safe paths, duplicate/case-folded names, UTF-8, ZIP metadata and package identities. Directory
loading is an operator facility: do not load directories another operating-system principal
can concurrently replace. Receive untrusted packages as bounded immutable archives and pin
the expected package BlueId. Package verification does not certify business terms as safe.

BEX admits a constrained program and uses logical gas. It is not JavaScript or a general
Java sandbox. Native implementations and content providers are trusted host code. Their
I/O, deadlines and side effects require host controls; language gas cannot interrupt an
arbitrarily blocked Java callback. Limit network bodies before constructing a String or
passing provider records into the library.

Timeline verification checks signed evidence and ordering. A signature does not prove that
a provider showed every reader the same history. A missing predecessor or revoked source
must not be bypassed using a local timeout. Such a policy needs a recorded protocol change.

## Ownership and physical strategies

Immutable inputs may be shared. Mutable execution objects and host callbacks require the
ownership rules in the public API and architecture documentation. Closing an execution
releases retained resources. Cache hits must preserve documents, receipts, events and gas.
`DiagnosticSwitches` documents every physical-strategy property; these are unsupported
diagnostics, normally off. Hosts must control JVM flags and environment configuration.

## Checks and reporting

`./gradlew build errorProne adversarialTest jacocoTestCoverageVerification` runs the ordinary
suite, API declaration guard, compiler checks, deterministic identity/archive mutations and
coverage gates. These complement the published conformance vectors and concurrency tests.
Deterministic mutation tests are reproducible regression tests, not coverage-guided fuzzing
or an independent security audit. The Contracts gas tariff still needs a coordinated
specification/conformance release; do not claim full cross-implementation conformance.

Report suspected vulnerabilities privately to the repository owner. Include the commit,
smallest reproducing input, limits, expected rejection and observed behavior. Do not include
credentials or private customer records in a public issue. No independent security review,
response-time commitment or production security certification is claimed by this file.
