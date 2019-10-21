libraryDependencies ++= Seq(
  "com.spotify" % "missinglink-core" % "0.2.0",
  "com.google.guava" % "guava" % "18.0"
)

unmanagedSourceDirectories in Compile +=
  baseDirectory.value.getParentFile / "src/main/scala"
