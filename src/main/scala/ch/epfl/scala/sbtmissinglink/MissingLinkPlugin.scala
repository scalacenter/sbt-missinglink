package ch.epfl.scala.sbtmissinglink

import sbt._
import sbt.Keys._
import sbt.plugins.JvmPlugin

import java.io.FileInputStream

import scala.collection.JavaConverters._

import com.spotify.missinglink.{ArtifactLoader, Conflict, ConflictChecker, Java9ModuleLoader}
import com.spotify.missinglink.Conflict.ConflictCategory
import com.spotify.missinglink.datamodel.{
  Artifact,
  ArtifactBuilder,
  ArtifactName,
  ClassTypeDescriptor,
  DeclaredClass,
  Dependency
}

object MissingLinkPlugin extends AutoPlugin {

  object autoImport {

    final case class IgnoredPackage(name: String, ignoreSubpackages: Boolean = true)

    val missinglinkCheck: TaskKey[Unit] =
      taskKey[Unit]("Run the missinglink checks")

    val missinglinkFailOnConflicts: SettingKey[Boolean] =
      settingKey[Boolean]("Fail the build if any conflicts are found")

    val missinglinkIgnoreSourcePackages: SettingKey[Seq[IgnoredPackage]] =
      settingKey[Seq[IgnoredPackage]](
        "Optional list of packages to ignore conflicts where the source of the conflict " +
          "is in one of the specified packages."
      )

    val missinglinkIgnoreDestinationPackages: SettingKey[Seq[IgnoredPackage]] =
      settingKey[Seq[IgnoredPackage]](
        "Optional list of packages to ignore conflicts where the destination/called-side " +
          "of the conflict is in one of the specified packages."
      )
  }

  import autoImport._

  override def requires: Plugins = JvmPlugin
  override def trigger: PluginTrigger = allRequirements

  val configSettings: Seq[Setting[_]] = Def.settings(
    missinglinkCheck := {
      val log = streams.value.log

      val cp = Attributed.data(fullClasspath.value)
      val classDir = (classDirectory in Compile).value
      val failOnConflicts = missinglinkFailOnConflicts.value
      val ignoreSourcePackages = missinglinkIgnoreSourcePackages.value
      val ignoreDestinationPackages = missinglinkIgnoreDestinationPackages.value
      val conflicts = loadArtifactsAndCheckConflicts(cp, classDir, log)
      val filteredConflicts =
        filterConflicts(conflicts, ignoreSourcePackages, ignoreDestinationPackages, log)

      if (filteredConflicts.nonEmpty) {
        val initialTotal = conflicts.length
        val filteredTotal = filteredConflicts.length

        val diffMessage = if (initialTotal != filteredTotal) {
          s"($initialTotal conflicts were found before applying filters)"
        } else {
          ""
        }

        log.info(s"$filteredTotal conflicts found! $diffMessage")

        outputConflicts(filteredConflicts, log)

        if (failOnConflicts)
          throw new MessageOnlyException(s"There were $filteredTotal conflicts")
      } else {
        log.info("No conflicts found")
      }
    }
  )

  override def globalSettings: Seq[Def.Setting[_]] = Seq(
    missinglinkFailOnConflicts := true,
    missinglinkIgnoreSourcePackages := Nil,
    missinglinkIgnoreDestinationPackages := Nil
  )

  override def projectSettings: Seq[Setting[_]] = {
    inConfig(Compile)(configSettings) ++
      inConfig(Runtime)(configSettings) ++
      inConfig(Test)(configSettings)
  }

  private def loadArtifactsAndCheckConflicts(
    cp: Seq[File],
    classDirectory: File,
    log: Logger
  ): Seq[Conflict] = {

    val runtimeProjectArtifacts = constructArtifacts(cp, log)

    // also need to load JDK classes from the bootstrap classpath
    val bootstrapArtifacts = loadBootstrapArtifacts(bootClasspathToUse(log), log)

    val allArtifacts = runtimeProjectArtifacts ++ bootstrapArtifacts

    val projectArtifact = toArtifact(classDirectory)

    if (projectArtifact.classes().isEmpty()) {
      log.warn(
        "No classes found in project build directory" +
          " - did you run 'mvn compile' first?"
      )
    }

    log.debug("Checking for conflicts starting from " + projectArtifact.name().name())
    log.debug("Artifacts included in the project: ")
    for (artifact <- runtimeProjectArtifacts) {
      log.debug("    " + artifact.name().name())
    }

    val conflictChecker = new ConflictChecker

    val conflicts =
      conflictChecker.check(projectArtifact, runtimeProjectArtifacts.asJava, allArtifacts.asJava)

    conflicts.asScala.toSeq
  }

  private def toArtifact(outputDirectory: File): Artifact = {
    val classes =
      (outputDirectory ** "*.class")
        .get()
        .map(loadClass)
        .map(c => c.className() -> c)
        .toMap
        .asJava

    new ArtifactBuilder()
      .name(new ArtifactName("project"))
      .classes(classes)
      .build()
  }

  private def loadClass(f: File): DeclaredClass =
    com.spotify.missinglink.ClassLoader.load(new FileInputStream(f))

  private def loadBootstrapArtifacts(bootstrapClasspath: String, log: Logger): List[Artifact] = {
    if (bootstrapClasspath == null) {
      Java9ModuleLoader.getJava9ModuleArtifacts((s, ex) => log.warn(s)).asScala.toList
    } else {
      constructArtifacts(
        bootstrapClasspath.split(System.getProperty("path.separator")).map(file(_)),
        log
      )
    }
  }

  private def bootClasspathToUse(log: Logger): String = {
    /*if (this.bootClasspath != null) {
      log.debug("using configured boot classpath: " + this.bootClasspath);
      this.bootClasspath;
    } else {*/
    val bootClasspath = System.getProperty("sun.boot.class.path")
    log.debug("derived bootclasspath: " + bootClasspath)
    bootClasspath
    /*}*/
  }

  private def constructArtifacts(cp: Seq[File], log: Logger): List[Artifact] = {
    val artifactLoader = new ArtifactLoader

    def isValid(entry: File): Boolean =
      (entry.isFile() && entry.getPath().endsWith(".jar")) || entry.isDirectory()

    def fileToArtifact(f: File): Artifact = {
      log.debug("loading artifact for path: " + f)
      artifactLoader.load(f)
    }

    cp.filter(isValid(_)).map(fileToArtifact(_)).toList
  }

  private def filterConflicts(
    conflicts: Seq[Conflict],
    ignoreSourcePackages: Seq[IgnoredPackage],
    ignoreDestinationPackages: Seq[IgnoredPackage],
    log: Logger
  ): Seq[Conflict] = {

    def filter(
      ignoredPackages: Seq[IgnoredPackage],
      name: String,
      setting: String,
      field: Dependency => ClassTypeDescriptor
    ): Seq[Conflict] => Seq[Conflict] = { input =>

      if (ignoredPackages.nonEmpty) {
        log.debug(s"Ignoring $name packages: ${ignoredPackages.mkString(", ")}")

        def isIgnored(conflict: Conflict): Boolean = {
          val descriptor = field(conflict.dependency())
          val className = descriptor.getClassName.replace('/', '.')
          val conflictPackageName = className.substring(0, className.lastIndexOf('.'))

          ignoredPackages.exists { p =>
            conflictPackageName == p.name ||
            (p.ignoreSubpackages && conflictPackageName.startsWith(p.name + "."))
          }
        }

        val filtered = input.filterNot(isIgnored)
        val diff = input.length - filtered.length

        if (diff != 0) {
          log.info(
            s"""
            |$diff conflicts found in ignored $name packages.
            |Run plugin again without the '$setting' configuration to see all conflicts that were found.
             """.stripMargin
          )
        }

        filtered
      } else {
        input
      }
    }

    val filters = List(
      filter(ignoreSourcePackages, "source", "ignoreSourcePackages", _.fromClass),
      filter(ignoreDestinationPackages, "destination", "ignoreDestinationPackages", _.targetClass)
    )

    Function.chain(filters).apply(conflicts)
  }

  private def outputConflicts(conflicts: Seq[Conflict], log: Logger): Unit = {
    def logLine(msg: String): Unit =
      log.error(msg)

    val descriptions = Map(
      ConflictCategory.CLASS_NOT_FOUND -> "Class being called not found",
      ConflictCategory.METHOD_SIGNATURE_NOT_FOUND -> "Method being called not found",
    )

    // group conflict by category
    val byCategory = conflicts.groupBy(_.category())

    for ((category, conflictsInCategory) <- byCategory) {
      val desc = descriptions.getOrElse(category, category.name().replace('_', ' '))
      logLine("")
      logLine("Category: " + desc)

      // next group by artifact containing the conflict
      val byArtifact = conflictsInCategory.groupBy(_.usedBy())

      for ((artifactName, conflictsInArtifact) <- byArtifact) {
        logLine("  In artifact: " + artifactName.name())

        // next group by class containing the conflict
        val byClassName = conflictsInArtifact.groupBy(_.dependency().fromClass())

        for ((classDesc, conflictsInClass) <- byClassName) {
          logLine("    In class: " + classDesc.toString())

          for (conflict <- conflictsInClass) {
            def optionalLineNumber(lineNumber: Int): String =
              if (lineNumber != 0) ":" + lineNumber else ""

            val dep = conflict.dependency()
            logLine(
              "      In method:  " +
                dep.fromMethod().prettyWithoutReturnType() +
                optionalLineNumber(dep.fromLineNumber())
            )
            logLine("      " + dep.describe())
            logLine("      Problem: " + conflict.reason())
            if (conflict.existsIn() != ConflictChecker.UNKNOWN_ARTIFACT_NAME)
              logLine("      Found in: " + conflict.existsIn().name())
            // this could be smarter about separating each blob of warnings by method, but for
            // now just output a bunch of dashes always
            logLine("      --------")
          }
        }
      }
    }
  }
}
