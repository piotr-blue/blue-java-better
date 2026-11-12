# Published clause fixtures

Copied without changing the authored definitions from `blue-packages` on 2026-11-05.
Activation uses the current 0.8.0-preview.3 package; Hello is tutorial case 00.
Tests install explicit local test channels for the owner, following the original standalone runner.
These are not authenticated MyOS identities or live payment integrations.

The Activation archive is self-contained and verified by PackageLoader. Tests never read sibling
repositories or Python source. Connections is shared with the existing canonical demo fixture.

SHA-256:

```json
{
  "hello.case.yaml": "004727e847ab923202b4185b0ef5ffe21c9ce2b1ab7e9dcd546622b0fea7a0ea",
  "hello.blue": "a89f347e7e481195132464a9fb4b2a0948acc75432f160f38df0117f3cb3bf11",
  "activation.bluepkg": "3edf433b7b5feabbccab56394258306b0edae5d5ab4bb97832ec74b32c0ae26b"
}
```
