# sbt-missinglink

An sbt plugin for [missinglink](https://github.com/spotify/missinglink).

## Usage

Add the following line in `project/plugins.sbt`:

```scala
addSbtPlugin("ch.epfl.scala" % "sbt-missinglink" % "0.2.0")
```

the simply run the following task for the project you want to test:

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

## More information

You can find more information about the problem statement, caveats and
limitations, etc. in the upstream project
[missinglink](https://github.com/spotify/missinglink).
