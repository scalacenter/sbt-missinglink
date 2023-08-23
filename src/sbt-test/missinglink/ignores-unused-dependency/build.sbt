inThisBuild(Def.settings(
  version := "0.1.0",
  scalaVersion := "2.12.8",
))

lazy val `has-problematic-dependency` = project
  .in(file("has-problematic-dependency"))
  .settings(
    libraryDependencies ++= Seq(
      "com.google.guava" % "guava" % "14.0",
    ),
    compileOrder := CompileOrder.JavaThenScala,
  )

lazy val `uses-problematic-dependency` = project
  .in(file("."))
  .dependsOn(`has-problematic-dependency`)
  .settings(
    libraryDependencies ++= Seq(
      "com.google.guava" % "guava" % "18.0",
    )
  )
