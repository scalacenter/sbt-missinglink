libraryDependencies ++= Seq(
  "com.spotify" % "missinglink-core" % "0.2.11",
  "org.ow2.asm" % "asm-tree" % "9.9"
)

Compile / unmanagedSourceDirectories +=
  baseDirectory.value.getParentFile / "src/main/scala"

addSbtPlugin("org.scalameta" % "sbt-scalafmt" % "2.0.6")
addSbtPlugin("com.github.sbt" % "sbt-ci-release" % "1.5.10")
