import java.io.File
import org.gradle.external.javadoc.StandardJavadocDocletOptions

plugins {
    `java-library`
    `maven-publish`
}

group = "blue.language"
version = "0.1.0-SNAPSHOT"
description = "Canonical Blue Language, BEX and Contracts with durable host integration."

repositories { mavenCentral() }

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(25)) }
    withSourcesJar()
    withJavadocJar()
}

val benchmark = sourceSets.create("benchmark") {
    java.setSrcDirs(listOf("src/jmh/java"))
    resources.setSrcDirs(listOf("src/jmh/resources"))
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += sourceSets.main.get().output
}
configurations[benchmark.implementationConfigurationName].extendsFrom(configurations.implementation.get())
configurations[benchmark.runtimeOnlyConfigurationName].extendsFrom(configurations.runtimeOnly.get())

val demo = sourceSets.create("demo") {
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += sourceSets.main.get().output
}
configurations[demo.implementationConfigurationName].extendsFrom(configurations.implementation.get())
sourceSets.test.get().compileClasspath += demo.output
sourceSets.test.get().runtimeClasspath += demo.output
benchmark.compileClasspath += demo.output
benchmark.runtimeClasspath += demo.output

val javaFormatter = configurations.create("javaFormatter")

dependencies {
    add(javaFormatter.name, "com.google.googlejavaformat:google-java-format:1.30.0:all-deps") {
        isTransitive = false
    }
    implementation("org.pcollections:pcollections:4.0.2")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.21.2")
    implementation("org.yaml:snakeyaml:2.4")
    testImplementation(platform("org.junit:junit-bom:6.0.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    add(benchmark.implementationConfigurationName, "org.openjdk.jmh:jmh-core:1.37")
    add(benchmark.annotationProcessorConfigurationName, "org.openjdk.jmh:jmh-generator-annprocess:1.37")
}

dependencyLocking { lockAllConfigurations() }

tasks.withType<JavaCompile>().configureEach {
    mustRunAfter("format")
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
}
tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    testLogging { events("failed", "skipped") }
}
// Domain selectors reuse the same source set. The standard test/build tasks include every tag.
for ((taskName, tag) in mapOf(
    "languageTest" to "language",
    "packageTest" to "packages",
    "bexTest" to "bex",
    "canonicalBexTest" to "canonical-bex",
    "contractsTest" to "contracts",
    "coordinationTest" to "coordination",
    "conformanceTest" to "conformance",
    "scenarioTest" to "scenario",
    "concurrencyTest" to "concurrency"
)) {
    tasks.register<Test>(taskName) {
        group = "verification"
        description = "Run tests tagged $tag."
        testClassesDirs = sourceSets.test.get().output.classesDirs
        classpath = sourceSets.test.get().runtimeClasspath
        useJUnitPlatform { includeTags(tag) }
        shouldRunAfter(tasks.test)
    }
}

tasks.withType<Javadoc>().configureEach {
    mustRunAfter("format")
    options.encoding = "UTF-8"
    (options as StandardJavadocDocletOptions).apply {
        addBooleanOption("Xdoclint:all", true)
        addStringOption("Xmaxwarns", "300")
        addBooleanOption("Werror", true)
    }
}
tasks.withType<AbstractArchiveTask>().configureEach {
    mustRunAfter("format")
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

val java25 = javaToolchains.launcherFor {
    languageVersion.set(JavaLanguageVersion.of(25))
}
val javaSources = fileTree("src") { include("**/*.java") }
for (taskName in listOf("format", "checkFormat")) {
    tasks.register<JavaExec>(taskName) {
        group = if (taskName == "format") "formatting" else "verification"
        description = if (taskName == "format") "Format Java sources." else "Verify Java source formatting."
        classpath = javaFormatter
        javaLauncher.set(java25)
        mainClass.set("com.google.googlejavaformat.java.Main")
        jvmArgs(listOf("api", "file", "parser", "tree", "util", "code").map {
            "--add-exports=jdk.compiler/com.sun.tools.javac.$it=ALL-UNNAMED"
        })
        args(if (taskName == "format") listOf("--replace") else listOf("--dry-run", "--set-exit-if-changed"))
        args(javaSources.files.sorted().map { it.absolutePath })
    }
}
tasks.named("checkFormat") { mustRunAfter("format") }
tasks.named("check") { dependsOn("checkFormat") }

tasks.register<JavaExec>("benchmarkHelp") {
    group = "benchmark"
    description = "Verify the JMH launcher and show its options."
    classpath = benchmark.runtimeClasspath
    javaLauncher.set(java25)
    mainClass.set("org.openjdk.jmh.Main")
    args("-h")
}
tasks.register<JavaExec>("benchmark") {
    group = "benchmark"
    description = "Run authored JMH benchmarks; use -PbenchmarkFilter=... to select them."
    dependsOn(benchmark.classesTaskName)
    classpath = benchmark.runtimeClasspath
    javaLauncher.set(java25)
    mainClass.set("org.openjdk.jmh.Main")
    val result = layout.buildDirectory.file("reports/jmh/results.json")
    args(providers.gradleProperty("benchmarkFilter").getOrElse(".*"))
    val options = providers.gradleProperty("benchmarkArgs").getOrElse(
        "-f 1 -wi 3 -i 5 -w 1s -r 1s -prof gc").split(" ")
    args(options)
    if ("-foe" !in options) args("-foe", "true")
    args("-rf", "json", "-rff", result.get().asFile.absolutePath)
    doFirst {
        result.get().asFile.parentFile.mkdirs()
    }
}
tasks.named("check") { dependsOn(benchmark.classesTaskName) }

// Runnable examples. They live in the demo source set, outside the published JAR, and always run
// on the Java 25 toolchain. Some programs have two task names because the guides refer to both.
for ((taskName, program) in mapOf(
    "example" to ("blue.examples.CanonicalAgreement" to
        "Run the canonical clause-rich agreement and export its states and events."),
    "canonicalDemo" to ("blue.examples.CanonicalAgreement" to
        "Run the canonical agreement, embedded order and payment workflows."),
    "canonicalClausesDemo" to ("blue.examples.CanonicalClauses" to
        "Initialize and run the published Agreements and Connections clauses."),
    "languageExample" to ("blue.examples.CompleteTutorialExample" to
        "Run canonical Language examples: inheritance, list versions, providers and cyclic proofs."),
    "completeTutorialExample" to ("blue.examples.CompleteTutorialExample" to
        "Run the canonical Language mappings from the archived tutorial."),
    "tutorialExample" to ("blue.examples.TutorialExample" to
        "Run canonical Language examples and a canonical Contracts counter."),
    "packageExample" to ("blue.examples.PackageExample" to
        "Run 18 canonical package examples with pinned, self-contained publications.")
)) {
    tasks.register<JavaExec>(taskName) {
        group = "application"
        description = program.second
        classpath = demo.runtimeClasspath
        javaLauncher.set(java25)
        mainClass.set(program.first)
    }
}

tasks.register<JavaExec>("normalizationSharingProbe") {
    group = "benchmark"
    description = "Measure bounded repeated-normalization retention without AWS or execution."
    classpath = demo.runtimeClasspath
    javaLauncher.set(java25)
    mainClass.set("blue.NormalizationSharingProbe")
    maxHeapSize = "512m"
}

tasks.register<JavaExec>("contractCounterProbe") {
    group = "benchmark"
    description = "Print physical counters for a verified Agreement, Order and Payment execution."
    classpath = demo.runtimeClasspath
    javaLauncher.set(java25)
    mainClass.set("blue.ContractCounterProbe")
    systemProperty("blue.physical.counters", "true")
}

// Maintainer-only fixture generation. Normal builds consume the checked-in, pinned artifacts.
tasks.register<JavaExec>("preparePackageExamples") {
    group = "verification"
    description = "Rebuild the small canonical package fixtures from their authored Java definitions."
    classpath = sourceSets.test.get().runtimeClasspath
    javaLauncher.set(java25)
    mainClass.set("blue.PackageFixtureGenerator")
    args(layout.buildDirectory.dir("generated-package-fixtures").get().asFile.absolutePath)
}

tasks.register("canonicalClasspath") {
    group = "build setup"
    description = "Write the demo runtime classpath to build/canonical-classpath.txt for external tools."
    dependsOn(tasks.classes, tasks.named("demoClasses"))
    val output = layout.buildDirectory.file("canonical-classpath.txt")
    val cp = demo.runtimeClasspath
    doLast { output.get().asFile.writeText(cp.asPath) }
}

tasks.register("resolveDependencies") {
    notCompatibleWithConfigurationCache("Explicit dependency resolution for lockfile maintenance")
    group = "build setup"
    description = "Resolve and optionally lock the test and benchmark toolchain dependencies."
    doLast { configurations.filter { it.isCanBeResolved }.forEach { it.resolve() } }
}

publishing {
    publications {
        create<MavenPublication>("library") { from(components["java"]) }
    }
    // Publication is local until release coordinates and a license are decided.
    repositories { maven { name = "localBuild"; url = uri(layout.buildDirectory.dir("repository")) } }
}
