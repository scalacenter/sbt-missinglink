# sbt-missinglink

An sbt plugin for [missinglink](https://github.com/spotify/missinglink).

## Usage

Add the following lines in `project/plugins.sbt`:

```scala
addSbtPlugin("ch.epfl.scala" % "sbt-missinglink" % "<sbt-missinglink-version>")
libraryDependencies += "com.spotify" % "missinglink-core" % "<missinglink-core-version>"
```

You can find the latest `missinglink-core` version [in their release list](https://github.com/spotify/missinglink/releases).

| `sbt-missinglink` | `missinglink-core` |
| :---: | :---: |
| ![sbt-missinglink badge](https://maven-badges.herokuapp.com/maven-central/ch.epfl.scala/sbt-missinglink/badge.svg) | ![missinglink-core badge](https://maven-badges.herokuapp.com/maven-central/com.spotify/missinglink-core/badge.svg) |

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
