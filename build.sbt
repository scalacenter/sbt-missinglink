inThisBuild(
  Def.settings(
    scalaVersion := "2.12.12",
    organization := "ch.epfl.scala",
    version := "0.3.3-SNAPSHOT",
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
      "com.spotify" % "missinglink-core" % "0.2.2"
    ),
    // configuration fro scripted
    scriptedLaunchOpts := {
      scriptedLaunchOpts.value ++
        Seq("-Xmx1024M", "-Dplugin.version=" + version.value)
    },
    scriptedBufferLog := false,
    scalacOptions += "-Ywarn-unused",
    semanticdbEnabled := true,
    semanticdbOptions += "-P:semanticdb:synthetics:on",
    semanticdbVersion := scalafixSemanticdb.revision,
    ThisBuild / scalafixScalaBinaryVersion := CrossVersion.binaryScalaVersion(scalaVersion.value),
    ThisBuild / scalafixDependencies += "com.github.liancheng" %% "organize-imports" % "0.5.0",
  )

addCommandAlias("fix", "; all compile:scalafix test:scalafix; all scalafmtSbt scalafmtAll")
addCommandAlias(
  "lint",
  "; scalafmtSbtCheck; scalafmtCheckAll; compile:scalafix --check; test:scalafix --check"
)
