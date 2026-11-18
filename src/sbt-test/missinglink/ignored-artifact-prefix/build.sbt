inThisBuild(
  Def.settings(
    organization := "test-org",
    version := "0.1.0",
    scalaVersion := "2.12.21",
  )
)

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
    ),
    // The broken call is in this artifact, so its conflicts are not reported
    missinglinkIgnoredArtifactPrefixes += "test-org:has-problematic-dependency",
  )
