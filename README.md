# sbt-missinglink

An sbt plugin that checks the transitive dependencies of your project for ABI (binary)
incompatibilities, using the `abi-check-core` library of Palantir's
[gradle-transitive-abi-checker](https://github.com/palantir/gradle-transitive-abi-checker).

Up to version 0.3.x, this plugin was based on Spotify's
[missinglink](https://github.com/spotify/missinglink), which is no longer maintained.
See [Migrating from 0.3.x](#migrating-from-03x).

## Usage

The plugin requires Java 17 or newer.
Add the following line in `project/plugins.sbt`:

```scala
addSbtPlugin("ch.epfl.scala" % "sbt-missinglink" % "<sbt-missinglink-version>")
```

| `sbt-missinglink` | `abi-check-core` |
| :---: | :---: |
| [![sbt-missinglink badge](https://maven-badges.sml.io/sonatype-central/ch.epfl.scala/sbt-missinglink/badge.svg)](https://central.sonatype.com/artifact/ch.epfl.scala/sbt-missinglink) | [![abi-check-core badge](https://maven-badges.sml.io/sonatype-central/com.palantir.gradle.abi-checker/abi-check-core/badge.svg)](https://central.sonatype.com/artifact/com.palantir.gradle.abi-checker/abi-check-core) |

Then, run the following task for the project you want to test:

```
> theProject/missinglinkCheck
```

The check works in two stages:

1. It finds the classes that are reachable from the classes of your project (in `src/main/`).
2. For each reachable class, it verifies that every class, method and field it refers to
   exists on the classpath of the configuration.

If your project has no classes, the check is skipped (see `missinglinkCheckCompletely`).

### Testing another configuration

You can test another configuration, such as `Test` or `Runtime`, with:

```
> theProject/Runtime/missinglinkCheck
```

### Configuration

The settings are the same as the
[configuration of the Gradle plugin](https://github.com/palantir/gradle-transitive-abi-checker#configuration).
Artifacts are named `organization:name:revision`, for example `com.google.guava:guava:18.0`.

```scala
// Report only conflicts in artifacts with one of these prefixes. Empty (the default) means all artifacts.
// If an artifact matches this setting and `missinglinkIgnoredArtifactPrefixes`, it is ignored.
missinglinkErrorArtifactPrefixes += "com.example"

// Do not report conflicts in artifacts with one of these prefixes.
missinglinkIgnoredArtifactPrefixes += "org.apache.hadoop:"

// Ignore classes with one of these prefixes, both on the calling and on the called side.
missinglinkIgnoredClassPrefixes += "org.apache.hc.client5."

// Ignore classes whose fully qualified name contains one of these keywords (case-insensitive).
missinglinkIgnoredClassnameKeywords += "esoterics"

// Check every class of every artifact, not only the classes reachable from your project.
// Useful for projects with no classes of their own, such as aggregates of dependencies.
missinglinkCheckCompletely := true

// Do not fail the build if conflicts are found (only log them).
missinglinkFailOnConflicts := false
```

### Limiting the concurrency

sbt runs the analysis on the modules you have concurrently.
Analysis of each module can take up a considerable amount of memory,
so you might want to limit the degree of concurrency.
To run the analysis on at most 4 projects at a time, add this setting to your project `root`.

```scala
concurrentRestrictions += Tags.limit(missinglinkConflictsTag, 4)
```

## Migrating from 0.3.x

| 0.3.x | Now |
| :--- | :--- |
| `missinglink-core` and `asm-tree` in `libraryDependencies` of `project/plugins.sbt` | Not necessary, remove them |
| Java 8 or newer | Java 17 or newer |
| All classes of the project are checked | Only the classes reachable from the project are checked |
| `missinglinkScanDependencies := true` | `missinglinkCheckCompletely := true` |
| `missinglinkExcludedDependencies += moduleFilter(organization = "org", name = "lib")` | `missinglinkIgnoredArtifactPrefixes += "org:lib:"` |
| `missinglinkIgnoreSourcePackages += IgnoredPackage("com.example")` | `missinglinkIgnoredClassPrefixes += "com.example."` |
| `missinglinkIgnoreDestinationPackages += IgnoredPackage("com.example")` | `missinglinkIgnoredClassPrefixes += "com.example."` |
| `missinglinkTargetSourcePackages`, `missinglinkTargetDestinationPackages` | No equivalent; use `missinglinkErrorArtifactPrefixes` to select artifacts |
| `missinglinkVerbose` | No equivalent; the report always shows the full breakdown |

## More information

You can find more information about the problem statement, caveats and limitations
in the README of [gradle-transitive-abi-checker](https://github.com/palantir/gradle-transitive-abi-checker).

## Acknowledgments

<a title="Scala Center" href="https://scala.epfl.ch/"><img alt="Scala Center" src="https://scala.epfl.ch/resources/img/scala-center-logo-black.png" height="60" /></a>

This project is funded by the <a title="Scala Center" href="https://scala.epfl.ch/">Scala Center</a>.
