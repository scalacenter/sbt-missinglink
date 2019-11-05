inThisBuild(
  Def.settings(
    version := "0.1.0",
    scalaVersion := "2.12.8",
  )
)

lazy val `has-problematic-dependency` = project
  .in(file("."))
  .settings(
    libraryDependencies ++= Seq(
      "ch.qos.logback" % "logback-classic" % "1.2.3",
    ),
    //for some reason can't do this directly from script
    addCommandAlias("addExclude", """set Compile / missinglinkExcludeDependencies ++= List("ch.qos.logback" % "logback-core" % "1.2.3", "ch.qos.logback" % "logback-classic" % "1.2.3") """)
  )
