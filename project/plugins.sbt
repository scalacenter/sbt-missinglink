libraryDependencies ++= Seq(
  "com.spotify" % "missinglink-core" % "0.2.5"
)

Compile / unmanagedSourceDirectories +=
  baseDirectory.value.getParentFile / "src/main/scala"

addSbtPlugin("org.scalameta" % "sbt-scalafmt" % "2.0.6")
addSbtPlugin("com.github.sbt" % "sbt-ci-release" % "1.5.10")
