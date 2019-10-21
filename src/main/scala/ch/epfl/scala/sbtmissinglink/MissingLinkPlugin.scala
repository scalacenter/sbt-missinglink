package ch.epfl.scala.sbtmissinglink

import sbt._
import sbt.Keys._
import sbt.plugins.JvmPlugin

import java.io.FileInputStream

import scala.collection.JavaConverters._

import com.google.common.collect.ImmutableList
import com.google.common.io.Files

import com.spotify.missinglink.{ArtifactLoader, Conflict, ConflictChecker}
import com.spotify.missinglink.Conflict.ConflictCategory
import com.spotify.missinglink.datamodel.{
  Artifact, ArtifactBuilder, ArtifactName, ClassTypeDescriptor, DeclaredClass,
  Dependency
}

object MissingLinkPlugin extends AutoPlugin {
  object autoImport {
    val missinglinkCheck: TaskKey[Unit] =
      taskKey[Unit]("run the missinglink checks")
  }

  import autoImport._

  override def requires: Plugins = JvmPlugin
  override def trigger: PluginTrigger = allRequirements

  val configSettings: Seq[Setting[_]] = Def.settings(
    missinglinkCheck := {
      val log = streams.value.log

      val cp = Attributed.data(fullClasspath.value)
      val classDir = (classDirectory in Compile).value
      val conflicts = loadArtifactsAndCheckConflicts(cp, classDir, log)

      outputConflicts(conflicts, log)
      if (conflicts.nonEmpty)
        throw new MessageOnlyException("there were conflicts")
    }
  )

  override def projectSettings: Seq[Setting[_]] = {
    inConfig(Compile)(configSettings) ++
    inConfig(Runtime)(configSettings) ++
    inConfig(Test)(configSettings)
  }

  private def loadArtifactsAndCheckConflicts(cp: Seq[File],
      classDirectory: File, log: Logger): Seq[Conflict] = {

    val runtimeProjectArtifacts = constructArtifacts(cp, log)

    // also need to load JDK classes from the bootstrap classpath
    val bootstrapArtifacts = loadBootstrapArtifacts(bootClasspathToUse(log), log)

    val allArtifacts = ImmutableList.builder[Artifact]()
      .addAll(runtimeProjectArtifacts)
      .addAll(bootstrapArtifacts)
      .build()

    val projectArtifact = toArtifact(classDirectory)

    if (projectArtifact.classes().isEmpty()) {
      log.warn(
          "No classes found in project build directory" +
          " - did you run 'mvn compile' first?")
    }

    log.debug("Checking for conflicts starting from " + projectArtifact.name().name())
    log.debug("Artifacts included in the project: ")
    for (artifact <- runtimeProjectArtifacts.asScala) {
      log.debug("    " + artifact.name().name())
    }

    val conflictChecker = new ConflictChecker

    val conflicts = conflictChecker.check(
        projectArtifact, runtimeProjectArtifacts, allArtifacts)

    conflicts.asScala.toSeq
  }

  private def toArtifact(outputDirectory: File): Artifact = {
    new ArtifactBuilder()
      .name(new ArtifactName("project"))
      .classes(Files.fileTreeTraverser().breadthFirstTraversal(outputDirectory)
        .filter(f => f.getName().endsWith(".class"))
        .transform(loadClass(_))
        .uniqueIndex(_.className()))
      .build()
  }

  private def loadClass(f: File): DeclaredClass = {
    com.spotify.missinglink.ClassLoader.load(new FileInputStream(f))
  }

  private def loadBootstrapArtifacts(bootstrapClasspath: String, log: Logger): java.util.List[Artifact] = {
    if (bootstrapClasspath == null) {
      ???
      //Java9ModuleLoader.getJava9ModuleArtifacts((s, ex) => log.warn(s))
    } else {
      constructArtifacts(
          bootstrapClasspath.split(System.getProperty("path.separator")).map(file(_)),
          log)
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

  private def constructArtifacts(cp: Seq[File], log: Logger): ImmutableList[Artifact] = {
    val artifactLoader = new ArtifactLoader

    def isValid(entry: File): Boolean =
      (entry.isFile() && entry.getPath().endsWith(".jar")) || entry.isDirectory()

    def fileToArtifact(f: File): Artifact = {
      log.debug("loading artifact for path: " + f)
      artifactLoader.load(f)
    }

    val list = cp.filter(isValid(_)).map(fileToArtifact(_))
    ImmutableList.copyOf(list.asJava: java.lang.Iterable[Artifact])
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
                optionalLineNumber(dep.fromLineNumber()))
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
