inThisBuild(Def.settings(
  scalaVersion := "2.12.9",
  organization := "ch.epfl.scala",
  version := "0.1.0-SNAPSHOT",
))

lazy val `sbt-missinglink` = project
  .in(file("."))
  .settings(
    sbtPlugin := true,

    libraryDependencies ++= Seq(
      "com.spotify" % "missinglink-core" % "0.1.1"
    )
  )

lazy val `has-problematic-dependency` = project
  .in(file("examples/has-problematic-dependency"))
  .enablePlugins(MissingLinkPlugin)
  .settings(
    libraryDependencies ++= Seq(
      "com.google.guava" % "guava" % "14.0",
      "com.google.guava" % "guava" % "18.0" % Runtime,
    ),

    // Speed up compilation a bit. Our .java files do not need to see the .scala files.
    compileOrder := CompileOrder.JavaThenScala,
  )
