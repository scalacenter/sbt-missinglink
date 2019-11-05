package ch.epfl.scala.sbtmissinglink

import sbt._
import sbt.Keys._
import sbt.plugins.JvmPlugin

import java.io.FileInputStream

import scala.collection.JavaConverters._
import scala.util.matching.Regex

import com.spotify.missinglink.{ArtifactLoader, Conflict, ConflictChecker, Java9ModuleLoader}
import com.spotify.missinglink.Conflict.ConflictCategory
import com.spotify.missinglink.datamodel.{Artifact, ArtifactBuilder, ArtifactName, DeclaredClass}

object MissingLinkPlugin extends AutoPlugin {
  object autoImport {
    val missinglinkCheck: TaskKey[Unit] =
      taskKey[Unit]("run the missinglink checks")
    val missinglinkConflicts: TaskKey[Seq[Conflict]] =
      taskKey("return binary compatibility conflicts")
    val missinglinkIgnoreConflicts: SettingKey[Seq[Regex]] =
      settingKey("filter out conflicts matching the regex")
    val missinglinkExcludeDependencies: SettingKey[Seq[ModuleID]] =
      settingKey("don't analyze these dependencies")
  }

  import autoImport._

  override def requires: Plugins = JvmPlugin
  override def trigger: PluginTrigger = allRequirements

  val configSettings: Seq[Setting[_]] = Def.settings(
    missinglinkConflicts := {
      val log = streams.value.log
      val cp = fullClasspath.value
      val classDir = (classDirectory in Compile).value
      loadArtifactsAndCheckConflicts(cp, missinglinkExcludeDependencies.value, classDir, log)
    },
    missinglinkCheck := {
      val log = streams.value.log
      val allConflicts = missinglinkConflicts.value
      val ignores = missinglinkIgnoreConflicts.value
      val conflicts =
        if (ignores.isEmpty) allConflicts
        else
          allConflicts.filterNot { c =>
            val descriptions = ConflictDescription(c).allDescriptions
            ignores.exists(r => descriptions.exists(d => r.findFirstIn(d).nonEmpty))
          }
      outputConflicts(conflicts, log)
      if (conflicts.nonEmpty)
        throw new MessageOnlyException(s"Found ${conflicts.size} conflict(s).")
    }
  )

  override def projectSettings: Seq[Setting[_]] = {
    inConfig(Compile)(configSettings) ++
      inConfig(Runtime)(configSettings) ++
      inConfig(Test)(configSettings)
  }

  override def globalSettings: Seq[Setting[_]] = Seq(
    missinglinkExcludeDependencies := Nil,
    missinglinkIgnoreConflicts := Nil,
  )

  private def loadArtifactsAndCheckConflicts(
    cp: Classpath,
    excludes: Seq[ModuleID],
    classDirectory: File,
    log: Logger
  ): Seq[Conflict] = {

    val allProjectArtifacts = constructArtifacts(cp, excludes, log)
    val runtimeProjectArtifacts = allProjectArtifacts.map(_._2)
    val artifactsToCheck = allProjectArtifacts.collect {
      case (true, a) => a
    }

    // also need to load JDK classes from the bootstrap classpath
    val bootstrapArtifacts = loadBootstrapArtifacts(bootClasspathToUse(log), log)

    val allArtifacts = runtimeProjectArtifacts ++ bootstrapArtifacts

    val projectArtifact = toArtifact(classDirectory)

    log.debug("Checking for conflicts starting from " + projectArtifact.name().name())
    log.debug("Artifacts included in the project: ")
    for ((included, artifact) <- allProjectArtifacts) {
      log.debug(
        "    " + artifact.name().name() + (if (included) "" else " (excluded from analysis)")
      )
    }

    val conflictChecker = new ConflictChecker

    val conflicts =
      conflictChecker.check(projectArtifact, artifactsToCheck.asJava, allArtifacts.asJava)

    conflicts.asScala
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

  private def loadBootstrapArtifacts(bootstrapClasspath: String, log: Logger): Seq[Artifact] = {
    if (bootstrapClasspath == null) {
      Java9ModuleLoader.getJava9ModuleArtifacts((s, ex) => log.warn(s)).asScala.toList
    } else {
      val bcp = bootstrapClasspath
        .split(System.getProperty("path.separator"))
        .map(f => Attributed.blank(file(f)))
      constructArtifacts(bcp, Nil, log).map(_._2)
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

  private def constructArtifacts(
    cp: Classpath,
    excludes: Seq[ModuleID],
    log: Logger
  ): Seq[(Boolean, Artifact)] = {
    val artifactLoader = new ArtifactLoader

    def isValid(entry: Attributed[File]): Boolean =
      (entry.data.isFile() && entry.data.getPath().endsWith(".jar")) || entry.data.isDirectory()

    def fileToArtifact(f: Attributed[File]) = {
      val module = f.get(moduleID.key)
      log.debug(s"loading artifact${module.fold(" ")(_.toString())} for path: " + f.data)
      val excluded = excludes.exists(e => module.contains(e))
      !excluded -> artifactLoader.load(f.data)
    }

    cp.filter(isValid).map(fileToArtifact)
  }

  private def optionalLineNumber(lineNumber: Int): String =
    if (lineNumber != 0) ":" + lineNumber else ""

  private case class ConflictDescription(c: Conflict) {
    val inClass: String = "In class: " + c.dependency().fromClass().toString
    val inMethod: String = "In method:  " + c.dependency().fromMethod().prettyWithoutReturnType() +
      optionalLineNumber(c.dependency().fromLineNumber())
    val dependency: String = c.dependency().describe()
    val reason: String = "Problem: " + c.reason()

    def allDescriptions = List(dependency, inClass, inMethod, reason)
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
        val byClassName = conflictsInArtifact.map(ConflictDescription.apply).groupBy(_.inClass)

        for ((inClass, conflictsInClass) <- byClassName) {
          logLine("    " + inClass)

          for (conflict <- conflictsInClass) {
            logLine("      " + conflict.inMethod)
            logLine("      " + conflict.dependency)
            logLine("      " + conflict.reason)
            if (conflict.c.existsIn() != ConflictChecker.UNKNOWN_ARTIFACT_NAME)
              logLine("      Found in: " + conflict.c.existsIn().name())
            // this could be smarter about separating each blob of warnings by method, but for
            // now just output a bunch of dashes always
            logLine("      --------")
          }
        }
      }
    }
  }
}
