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
confliction (in `src/main/`) are all called.

You can ignore certain conflicts with regex match:

```scala
Compile / missinglinkIgnoreConflicts += "com.google.common.base.Enums.*".r
```                                                                             

If you're certain a library has conflicts which can be safely ignored and want to speedup checking a bit you can exclude the whole of it from analysis:

```scala
Compile / missinglinkExcludeDependencies ++= List("ch.qos.logback" % "logback-core" % "1.2.3", "ch.qos.logback" % "logback-classic" % "1.2.3")
``` 

### Testing another configuration

You can test another configuration, such as `Test` or `Runtime`, with:


```
> theProject/Runtime/missinglinkCheck
```

## More information

You can find more information about the problem statement, caveats and
limitations, etc. in the upstream project
[missinglink](https://github.com/spotify/missinglink).
