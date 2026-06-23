# sbt-missinglink

An sbt plugin for [missinglink](https://github.com/spotify/missinglink).

## Usage

Add the following lines in `project/plugins.sbt`:

```scala
addSbtPlugin("ch.epfl.scala" % "sbt-missinglink" % "<sbt-missinglink-version>")
libraryDependencies ++= List(
  "com.spotify" % "missinglink-core" % "<missinglink-core-version>",
  "org.ow2.asm" % "asm-tree" % "<asm-tree-version>",
)
```

You can find the latest `missinglink-core` version [in their release list](https://github.com/spotify/missinglink/releases).

| `sbt-missinglink` | `missinglink-core` | `asm-tree` |
| :---: | :---: | :---: |
| [![sbt-missinglink badge](https://maven-badges.sml.io/sonatype-central/ch.epfl.scala/sbt-missinglink/badge.svg)](https://central.sonatype.com/artifact/ch.epfl.scala/sbt-missinglink) | [![missinglink-core badge](https://maven-badges.sml.io/sonatype-central/com.spotify/missinglink-core/badge.svg)](https://central.sonatype.com/artifact/com.spotify/missinglink-core) | [![asm-tree badge](https://maven-badges.sml.io/sonatype-central/org.ow2.asm/asm-tree/badge.svg)](https://central.sonatype.com/artifact/org.ow2.asm/asm-tree) |

Then, run the following task for the project you want to test:

```
> theProject/missinglinkCheck
```

This will check that the transitive dependencies of your project do not exhibit
any binary compatibility conflict, assuming that the methods of your `Compile`
configuration (in `src/main/`) are all called.

### Testing another configuration

You can test another configuration, such as `Test` or `Runtime`, with:


```
> theProject/Runtime/missinglinkCheck
```

### Do not fail on conflicts

By default, the plugin fails the build if any conflicts are found.
It can be disabled by the `missinglinkFailOnConflicts` setting:

```
missinglinkFailOnConflicts := false
```

### Ignore conflicts in certain packages

Conflicts can be ignored based on the package name of the class that has the conflict.
There are separate configuration options for ignoring conflicts on the "source" side of the conflict and the "destination" side of the conflict.
Packages on the source side can be ignored with `missinglinkIgnoreSourcePackages` and packages on the destination side can be ignored with `missinglinkIgnoreDestinationPackages`:

```
missinglinkIgnoreDestinationPackages += IgnoredPackage("com.google.common")
missinglinkIgnoreSourcePackages += IgnoredPackage("com.example")
```

By default, all subpackages of the specified package are also ignored, but this can be disabled by the `ignoreSubpackages` field: `IgnoredPackage("test", ignoreSubpackages = false)`.

### Understanding the conflict report

By default `missinglinkCheck` prints a concise summary: conflict counts split by category, each
with a ready-to-paste snippet to silence it:

```
Missinglink summary: 206 conflicts found.

87 conflicts reference classes missing from the classpath - usually optional dependencies you do not use. Exclude the dependencies that reference them:
  <proj>/missinglinkExcludedDependencies ++= List(
    moduleFilter(organization = "io.netty", name = "netty-common"),  // 84 conflicts
    moduleFilter(organization = "io.opentelemetry", name = "opentelemetry-sdk-trace")  // 3 conflicts
  )

119 conflicts reference methods or fields missing from libraries already on your classpath - usually two dependency versions disagree and a binary-incompatible one was evicted.
Check `show <proj>/evicted` and align these versions:
  - org.slf4j:jcl-over-slf4j:2.0.17  (110 conflicts)
  - javax.activation:javax.activation-api:1.2.0  (9 conflicts)
To ignore them instead:
  <proj>/missinglinkIgnoreDestinationPackages ++= List(
    IgnoredPackage("org.apache.commons.logging"),  // 110 conflicts
    IgnoredPackage("javax.activation")  // 9 conflicts
  )

Re-run with 'missinglinkVerbose := true' to see the full per-class breakdown (which class references each missing symbol, and from which methods).
```

The two categories get different advice:

- **Missing classes** (`CLASS_NOT_FOUND`): usually an optional dependency you don't ship — exclude
  the dependency that references the missing classes with `missinglinkExcludedDependencies`.
- **Missing methods / fields**: usually a real binary incompatibility — realign the named
  module/version. The class is already on the classpath (often in more than one jar), so excluding a
  dependency won't make it go away; ignore the destination package with
  `missinglinkIgnoreDestinationPackages` only if you can't realign.

Set `missinglinkVerbose := true` for the full per-class, per-call-site breakdown.

### Excluding some dependencies from the analysis

You can exclude certain dependencies using `moduleFilter`:

```
missinglinkExcludedDependencies += moduleFilter(organization = "com.google.guava")
missinglinkExcludedDependencies += moduleFilter(organization = "ch.qos.logback", name = "logback-core")
```

### Limiting the concurrency

sbt runs the missing-link analysis on the modules you have concurrently.
Analysis of each module can take up a considerable amount of memory,
so you might want to limit the degree of concurrency.
To run missing-link at most on 4 projects at a time, add this setting to your project `root`.

```scala
concurrentRestrictions += Tags.limit(missinglinkConflictsTag, 4)
```

## More information

You can find more information about the problem statement, caveats and
limitations, etc. in the upstream project
[missinglink](https://github.com/spotify/missinglink).

## Acknowledgments

<a title="Scala Center" href="https://scala.epfl.ch/"><img alt="Scala Center" src="https://scala.epfl.ch/resources/img/scala-center-logo-black.png" height="60" /></a>

This project is funded by the <a title="Scala Center" href="https://scala.epfl.ch/">Scala Center</a>.
