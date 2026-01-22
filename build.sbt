inThisBuild(
  Def.settings(
    scalaVersion := "2.12.21",
    organization := "ch.epfl.scala",
    homepage := Some(url("https://github.com/scalacenter/sbt-missinglink")),
    licenses += (
      "BSD New",
      url("https://github.com/scalacenter/sbt-missinglink/blob/main/LICENSE")
    ),
    developers := List(
      Developer(
        "sjrd",
        "Sébastien Doeraene",
        "sjrdoeraene@gmail.com",
        url("https://github.com/sjrd/")
      )
    ),
  )
)

lazy val `sbt-missinglink` = project
  .in(file("."))
  .enablePlugins(SbtPlugin)
  .settings(
    libraryDependencies ++= Seq(
      "com.spotify" % "missinglink-core" % "0.2.11",
      "org.ow2.asm" % "asm-tree" % "9.9.1"
    ),
    scriptedLaunchOpts := {
      scriptedLaunchOpts.value ++
        Seq("-Xmx1024M", "-Dplugin.version=" + version.value)
    },
    scriptedBufferLog := false,
  )
