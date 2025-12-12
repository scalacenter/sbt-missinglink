inThisBuild(Def.settings(
  version := "0.1.0",
  scalaVersion := "2.12.21",
))

lazy val `ignore-destination-package` = project
  .in(file("."))
  .settings(
    libraryDependencies ++= Seq(
      "com.google.guava" % "guava" % "14.0",
      "com.google.guava" % "guava" % "18.0" % Runtime,
    ),

    // Speed up compilation a bit. Our .java files do not need to see the .scala files.
    compileOrder := CompileOrder.JavaThenScala,

    missinglinkIgnoreDestinationPackages += IgnoredPackage("com.google.common"),
    missinglinkIgnoreDestinationPackages += IgnoredPackage("whatever"),
  )
