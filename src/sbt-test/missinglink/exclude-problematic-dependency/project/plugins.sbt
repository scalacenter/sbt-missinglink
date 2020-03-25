System.getProperty("plugin.version") match {
  case null =>
    throw new MessageOnlyException(
        "The system property 'plugin.version' is not defined. " +
        "Specify this property using the scriptedLaunchOpts -D.")
  case pluginVersion =>
    addSbtPlugin("ch.epfl.scala" % "sbt-missinglink" % pluginVersion)
}
