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

### Unsupported features

At the moment, compared to the upstream `missinglink` project, this sbt plugin
does not support the following features:

* Excluding some dependencies from the analysis
* Ignoring conflicts in certain packages

## More information

You can find more information about the problem statement, caveats and
limitations, etc. in the upstream project
[missinglink](https://github.com/spotify/missinglink).
