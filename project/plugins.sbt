libraryDependencies += "com.spotify" % "missinglink-core" % "0.1.1"

unmanagedSourceDirectories in Compile +=
  baseDirectory.value.getParentFile / "src/main/scala"
