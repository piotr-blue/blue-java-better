# Public declaration baseline

`public-api.txt` was recorded from the compiled `4fb26ef1d22f61b9a0497716fe6f69b22afb111a`
library before this revision changed any production source. It contains 1,850 declaration
and descriptor lines. `./gradlew checkApi` is part of `check` and requires JDK 25.

The JDK-only checker excludes classes inside non-public enclosing classes. It records
public/protected declarations, generic types, JVM descriptors and constant values. Missing
or changed existing lines fail; new lines are allowed. It does not certify behavioral
compatibility, overload resolution, annotations or added abstract interface obligations.

To record a deliberately approved new baseline, run the tool with `--record` after reviewing
the API diff and release policy. The normal build never records a baseline automatically.
