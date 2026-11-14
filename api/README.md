# Public declaration baseline

`public-api.txt` was recorded from the compiled `4fb26ef1d22f61b9a0497716fe6f69b22afb111a`
library before this revision changed any production source. It contains 1,850 declaration
and descriptor lines. `./gradlew checkApi` is part of `check` and requires JDK 25.

The JDK-only checker excludes classes inside non-public enclosing classes. It records
public/protected declarations, generic types, JVM descriptors and constant values. Missing
or changed existing lines fail. New concrete methods and new types are allowed; new abstract
methods on existing types fail because they add obligations for client implementations. Nesting
comes from class-file metadata, so a top-level name containing `$` is not mistaken for an inner
class. It does not certify behavioral compatibility, overload resolution, annotations, external
dependency changes or every inherited-member/source compatibility case.

`./gradlew testApiChecker`, also included in `check`, compiles 18 independent fixture cases.
They cover valid additions, private changes, removed public/protected/nested members, generic
types, constants, static/instance and return-type changes, new abstract obligations, dollar
identifiers and CLI validation. Expected-rejection diagnostics remain under
`build/api-checker-test/`. Both tool classes are formatted and compiled with warnings as errors;
neither is included in the library JAR. The same tool and fixtures live in the host repository.

To record a deliberately approved new baseline, run the tool with `--record` after reviewing
the API diff and release policy. The normal build never records a baseline automatically.
