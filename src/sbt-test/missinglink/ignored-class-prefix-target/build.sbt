inThisBuild(
  Def.settings(
    version := "0.1.0",
    scalaVersion := "2.12.21",
  )
)

lazy val `ignored-class-prefix-target` = project
  .in(file("."))
  .settings(
    libraryDependencies ++= Seq(
      "com.google.guava" % "guava" % "14.0",
      "com.google.guava" % "guava" % "18.0" % Runtime,
    ),

    // Speed up compilation a bit. Our .java files do not need to see the .scala files.
    compileOrder := CompileOrder.JavaThenScala,

    // The called class is ignored
    missinglinkIgnoredClassPrefixes += "com.google.common.",
    missinglinkIgnoredClassPrefixes += "whatever.",
  )
