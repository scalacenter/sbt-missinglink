inThisBuild(
  Def.settings(
    scalaVersion := "2.12.9",
    organization := "ch.epfl.scala",
    version := "0.3.1-SNAPSHOT",
    homepage := Some(url("https://github.com/scalacenter/sbt-missinglink")),
    licenses += ("BSD New",
    url("https://github.com/scalacenter/sbt-missinglink/blob/master/LICENSE")),
    scmInfo := Some(
      ScmInfo(
        url("https://github.com/scalacenter/sbt-missinglink"),
        "scm:git:git@github.com:scalacenter/sbt-missinglink.git",
        Some("scm:git:git@github.com:scalacenter/sbt-missinglink.git")
      )
    ),
    publishMavenStyle := true,
    publishTo := {
      val nexus = "https://oss.sonatype.org/"
      if (version.value.endsWith("-SNAPSHOT"))
        Some("snapshots" at nexus + "content/repositories/snapshots")
      else
        Some("releases" at nexus + "service/local/staging/deploy/maven2")
    },
    pomExtra := (
      // format: off
      <developers>
        <developer>
          <id>sjrd</id>
          <name>Sébastien Doeraene</name>
          <url>https://github.com/sjrd/</url>
        </developer>
      </developers>
      // format: on
    ),
    pomIncludeRepository := { _ =>
      false
    },
  )
)

lazy val `sbt-missinglink` = project
  .in(file("."))
  .enablePlugins(SbtPlugin)
  .settings(
    libraryDependencies ++= Seq(
      "com.spotify" % "missinglink-core" % "0.2.1"
    ),
    // configuration fro scripted
    scriptedLaunchOpts := {
      scriptedLaunchOpts.value ++
        Seq("-Xmx1024M", "-Dplugin.version=" + version.value)
    },
    scriptedBufferLog := false,
  )
