libraryDependencies ++= Seq(
  "com.spotify" % "missinglink-core" % "0.2.11",
  "org.ow2.asm" % "asm-tree" % "9.9.1"
)

addSbtPlugin("com.github.sbt" % "sbt-ci-release" % "1.11.2")
