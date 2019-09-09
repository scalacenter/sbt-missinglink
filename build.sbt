inThisBuild(Def.settings(
  scalaVersion := "2.12.9",
  organization := "ch.epfl.scala",
  version := "0.1.0-SNAPSHOT",
))

lazy val `sbt-missinglink` = project
  .in(file("."))
  .enablePlugins(SbtPlugin)
  .settings(
    libraryDependencies ++= Seq(
      "com.spotify" % "missinglink-core" % "0.1.1"
    ),

    // configuration fro scripted
    scriptedLaunchOpts := { scriptedLaunchOpts.value ++
      Seq("-Xmx1024M", "-Dplugin.version=" + version.value)
    },
    scriptedBufferLog := false,
  )
