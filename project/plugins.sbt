libraryDependencies ++= Seq(
  "com.spotify" % "missinglink-core" % "0.2.2"
)

unmanagedSourceDirectories in Compile +=
  baseDirectory.value.getParentFile / "src/main/scala"

addSbtPlugin("org.scalameta" % "sbt-scalafmt" % "2.4.2")
addSbtPlugin("ch.epfl.scala" % "sbt-scalafix" % "0.9.24")
