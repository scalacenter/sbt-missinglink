package ch.epfl.scala.sbtmissinglink

import sbt._
import sbt.Keys._
import sbt.plugins.JvmPlugin

import sbtcompat.PluginCompat

import scala.collection.JavaConverters._

import com.palantir.abi.checker.{
  AbiCheckerClassLoader,
  ArtifactLoader,
  ConflictChecker,
  ConflictCheckerConfiguration,
  JdkModuleLoader
}
import com.palantir.abi.checker.datamodel.{Artifact, ArtifactName}
import com.palantir.abi.checker.datamodel.conflict.Conflict
import xsbti.FileConverter

/** sbt front-end for Palantir's
  * [[https://github.com/palantir/gradle-transitive-abi-checker abi-check-core]].
  *
  * The settings mirror Palantir's `ConflictCheckerConfiguration` one to one, so that the
  * documentation of the Gradle plugin applies to this plugin as well.
  */
object MissingLinkPlugin extends AutoPlugin {

  object autoImport {

    @transient
    val missinglinkCheck: TaskKey[Unit] =
      taskKey[Unit]("Check the runtime classpath for ABI incompatibilities")

    val missinglinkFailOnConflicts: SettingKey[Boolean] =
      settingKey[Boolean]("Fail the build if any conflicts are found")

    val missinglinkErrorArtifactPrefixes: SettingKey[Seq[String]] =
      settingKey[Seq[String]](
        "Prefixes of artifacts (`organization:name:revision`) whose conflicts are reported. " +
          "Empty means all artifacts. missinglinkIgnoredArtifactPrefixes wins over this setting."
      )

    val missinglinkIgnoredArtifactPrefixes: SettingKey[Seq[String]] =
      settingKey[Seq[String]](
        "Prefixes of artifacts (`organization:name:revision`) whose conflicts are not reported."
      )

    val missinglinkIgnoredClassPrefixes: SettingKey[Seq[String]] =
      settingKey[Seq[String]](
        "Prefixes of fully qualified class names to ignore, both on the calling and on the called side."
      )

    val missinglinkIgnoredClassnameKeywords: SettingKey[Seq[String]] =
      settingKey[Seq[String]](
        "Case-insensitive keywords; classes whose fully qualified name contains one are ignored."
      )

    val missinglinkCheckCompletely: SettingKey[Boolean] =
      settingKey[Boolean](
        "Check every class of every (non-ignored) artifact, instead of only the classes " +
          "reachable from the classes of this project."
      )
  }

  import autoImport._

  override def requires: Plugins = JvmPlugin
  override def trigger: PluginTrigger = allRequirements

  // Make it easy to throttle the concurrency of running missing-link on multiple projects, it consumes a lot of memory
  val missinglinkConflictsTag = Tags.Tag("missinglinkConflicts")

  // The JDK does not change during the lifetime of the sbt server, so its index can be shared.
  private lazy val jdkModuleLoader = new JdkModuleLoader

  val configSettings: Seq[Setting[?]] = Def.settings(
    missinglinkCheck := Def
      .task {
        val log = streams.value.log

        implicit val converter: FileConverter = fileConverter.value
        val cp = fullClasspath.value
        val classDir = (Compile / classDirectory).value
        val failOnConflicts = missinglinkFailOnConflicts.value

        val userConfiguration = ConflictCheckerConfiguration
          .builder()
          .errorArtifactPrefixes(missinglinkErrorArtifactPrefixes.value.asJava)
          .ignoredArtifactPrefixes(missinglinkIgnoredArtifactPrefixes.value.asJava)
          .ignoredClassPrefixes(missinglinkIgnoredClassPrefixes.value.asJava)
          .ignoredClassnameKeywords(missinglinkIgnoredClassnameKeywords.value.asJava)
          .checkCompletely(missinglinkCheckCompletely.value)
          .build()

        checkConflicts(cp, classDir, userConfiguration, log) match {
          case None =>
            log.warn(
              "Skipping missinglink check, no classes found in project build directory " +
                "(use 'missinglinkCheckCompletely := true' to run anyway)."
            )
          case Some(Nil) =>
            log.info("No conflicts found")
          case Some(conflicts) =>
            val total = conflicts.size
            val conflictNoun = if (total == 1) "conflict" else "conflicts"
            log.info(s"$total $conflictNoun found!")

            ConflictPrinter.outputConflicts(conflicts).linesIterator.foreach(log.error(_))

            if (failOnConflicts) {
              val verb = if (total == 1) "was" else "were"
              throw new MessageOnlyException(s"There $verb $total $conflictNoun")
            }
        }
      }
      .tag(missinglinkConflictsTag)
      .value,
  )

  override def globalSettings: Seq[Def.Setting[?]] = Seq(
    missinglinkFailOnConflicts := true,
    missinglinkErrorArtifactPrefixes := Nil,
    missinglinkIgnoredArtifactPrefixes := Nil,
    missinglinkIgnoredClassPrefixes := Nil,
    missinglinkIgnoredClassnameKeywords := Nil,
    missinglinkCheckCompletely := false,
  )

  override def projectSettings: Seq[Setting[?]] = {
    inConfig(Compile)(configSettings) ++
      inConfig(Runtime)(configSettings) ++
      inConfig(Test)(configSettings)
  }

  /** @return
    *   `None` if the check was skipped because the project has no classes, otherwise the conflicts
    */
  private def checkConflicts(
      cp: Classpath,
      classDirectory: File,
      userConfiguration: ConflictCheckerConfiguration,
      log: Logger
  )(implicit converter: FileConverter): Option[List[Conflict]] = {
    val artifactLoader = new ArtifactLoader

    // Acts as the "entry point" for the reachability analysis
    val projectArtifact =
      artifactLoader.load(classDirectory.toPath, ArtifactName.of(classDirectory.toString))
    val projectClasses = projectArtifact.classes().values()

    if (projectClasses.isEmpty && !userConfiguration.getCheckCompletely) {
      None
    } else {
      val jdkArtifacts = jdkModuleLoader.getJavaModuleArtifacts().asScala.toList
      val dependencyArtifacts = cp.toList
        .map(entry => entry -> PluginCompat.toFile(entry.data))
        .filter { case (_, file) => file != classDirectory }
        .map { case (entry, file) => loadArtifact(artifactLoader, entry, file, log) }

      log.debug("Artifacts on the runtime classpath:")
      dependencyArtifacts.foreach(a => log.debug("    " + a.name().name()))

      // The JDK comes first on the classpath, like at runtime
      val runtimeClasspath = jdkArtifacts ++ (projectArtifact :: dependencyArtifacts)

      // The JDK is not analyzed. The classes of this project are, because unlike in Gradle,
      // sbt lets the Runtime classpath diverge from the Compile one.
      val configuration = ConflictCheckerConfiguration
        .builder()
        .from(userConfiguration)
        .addAllIgnoredArtifactPrefixes(jdkArtifacts.map(_.name().name()).asJava)
        .build()

      val conflicts = ConflictChecker.checkWithEntryPoints(
        configuration,
        new AbiCheckerClassLoader,
        runtimeClasspath.asJava,
        projectClasses
      )
      Some(conflicts.asScala.toList)
    }
  }

  private def loadArtifact(
      artifactLoader: ArtifactLoader,
      entry: Attributed[PluginCompat.FileRef],
      file: File,
      log: Logger
  ): Artifact = {
    log.debug("loading artifact for path: " + file)
    artifactLoader.load(file.toPath, ArtifactName.of(artifactName(entry, file)))
  }

  /** Same shape as Gradle's component display name, `organization:name:revision`. */
  private def artifactName(entry: Attributed[PluginCompat.FileRef], file: File): String =
    entry
      .get(PluginCompat.moduleIDStr)
      .map(PluginCompat.parseModuleIDStrAttribute)
      .fold(file.toString)(m => s"${m.organization}:${m.name}:${m.revision}")
}
