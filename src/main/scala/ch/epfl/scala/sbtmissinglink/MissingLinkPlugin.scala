package ch.epfl.scala.sbtmissinglink

import sbt._
import sbt.Keys._
import sbt.librarymanagement.ModuleFilter
import sbt.plugins.JvmPlugin

import sbtcompat.PluginCompat

import java.io.FileInputStream

import scala.collection.JavaConverters._
import scala.collection.immutable.Map

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
import xsbti.FileConverter

object MissingLinkPlugin extends AutoPlugin {

  object autoImport {

    final case class IgnoredPackage(name: String, ignoreSubpackages: Boolean = true)
        extends PackageFilter {
      override def apply(packageName: String): Boolean =
        packageName != name && (!ignoreSubpackages || !packageName.startsWith(s"$name."))
    }
    private[sbtmissinglink] implicit object IgnoredPackages extends PackageFilters[IgnoredPackage] {
      def apply(name: String)(filters: Seq[IgnoredPackage]): Boolean = filters.forall(_.apply(name))
    }

    final case class TargetedPackage(name: String, targetSubpackages: Boolean = true)
        extends PackageFilter {
      override def apply(packageName: String): Boolean =
        packageName == name || (targetSubpackages && packageName.startsWith(s"$name."))
    }

    private[sbtmissinglink] implicit object TargetedPackages
        extends PackageFilters[TargetedPackage] {
      def apply(name: String)(filters: Seq[TargetedPackage]): Boolean =
        filters.exists(_.apply(name))
    }

    @transient
    val missinglinkCheck: TaskKey[Unit] =
      taskKey[Unit]("Run the missinglink checks")

    val missinglinkFailOnConflicts: SettingKey[Boolean] =
      settingKey[Boolean]("Fail the build if any conflicts are found")

    val missinglinkScanDependencies: SettingKey[Boolean] =
      settingKey[Boolean]("Also scan all dependencies")

    val missinglinkIgnoreSourcePackages: SettingKey[Seq[IgnoredPackage]] =
      settingKey[Seq[IgnoredPackage]](
        "Optional list of packages to ignore conflicts where the source of the conflict " +
          "is in one of the specified packages."
      )

    val missinglinkTargetSourcePackages: SettingKey[Seq[TargetedPackage]] =
      settingKey[Seq[TargetedPackage]](
        "Optional list of source packages to specifically target conflicts in. " +
          "Cannot be used with missinglinkIgnoreSourcePackages."
      )

    val missinglinkIgnoreDestinationPackages: SettingKey[Seq[IgnoredPackage]] =
      settingKey[Seq[IgnoredPackage]](
        "Optional list of packages to ignore conflicts where the destination/called-side " +
          "of the conflict is in one of the specified packages."
      )

    val missinglinkTargetDestinationPackages: SettingKey[Seq[TargetedPackage]] =
      settingKey[Seq[TargetedPackage]](
        "Optional list of source packages to specifically target conflicts in. " +
          "Cannot be used with missinglinkIgnoreDestinationPackages."
      )

    val missinglinkExcludedDependencies =
      settingKey[Seq[ModuleFilter]]("Dependencies that are excluded from analysis")

    val missinglinkVerbose: SettingKey[Boolean] =
      settingKey[Boolean](
        "Print the full per-class, per-call-site breakdown of every conflict. " +
          "When false (the default), only the concise summary is printed."
      )
  }

  import autoImport._

  override def requires: Plugins = JvmPlugin
  override def trigger: PluginTrigger = allRequirements

  // Make it easy to throttle the concurrency of running missing-link on multiple projects, it consumes a lot of memory
  val missinglinkConflictsTag = Tags.Tag("missinglinkConflicts")

  val configSettings: Seq[Setting[?]] = Def.settings(
    missinglinkCheck := Def
      .task {
        val log = streams.value.log

        implicit val converter: FileConverter = fileConverter.value
        val cp = fullClasspath.value
        val classDir = (Compile / classDirectory).value
        val failOnConflicts = missinglinkFailOnConflicts.value
        val scanDependencies = missinglinkScanDependencies.value
        val verbose = missinglinkVerbose.value
        assert(
          missinglinkIgnoreSourcePackages.value.isEmpty || missinglinkTargetSourcePackages.value.isEmpty,
          "ignoreSourcePackages and targetSourcePackages cannot be defined in the same project."
        )

        assert(
          missinglinkIgnoreDestinationPackages.value.isEmpty || missinglinkTargetDestinationPackages.value.isEmpty,
          "ignoreDestinationPackages and targetDestinationPackages cannot be defined in the same project."
        )

        val filter =
          missinglinkExcludedDependencies.value.foldLeft[ModuleFilter](_ => true)((k, v) => k - v)

        val (conflicts, sourceModules) =
          loadArtifactsAndCheckConflicts(cp, classDir, scanDependencies, filter, log)

        val conflictFilters = filterConflicts(
          missinglinkIgnoreSourcePackages.value,
          missinglinkIgnoreSourcePackages,
          log,
          "source",
          _.fromClass,
        ) andThen filterConflicts(
          missinglinkTargetSourcePackages.value,
          missinglinkTargetSourcePackages,
          log,
          "source",
          _.fromClass,
        ) andThen filterConflicts(
          missinglinkIgnoreDestinationPackages.value,
          missinglinkIgnoreDestinationPackages,
          log,
          "destination",
          _.targetClass,
        ) andThen filterConflicts(
          missinglinkTargetDestinationPackages.value,
          missinglinkTargetDestinationPackages,
          log,
          "destination",
          _.targetClass,
        )

        val filteredConflicts = conflictFilters(conflicts)

        if (filteredConflicts.nonEmpty) {
          val initialTotal = conflicts.length
          val filteredTotal = filteredConflicts.length

          val diffMessage = if (initialTotal != filteredTotal) {
            s"($initialTotal conflicts were found before applying filters)"
          } else {
            ""
          }

          log.info(s"$filteredTotal conflicts found! $diffMessage")

          outputConflicts(filteredConflicts, sourceModules, verbose, log)

          if (failOnConflicts)
            throw new MessageOnlyException(s"There were $filteredTotal conflicts")
        } else {
          log.info("No conflicts found")
        }
      }
      .tag(missinglinkConflictsTag)
      .value,
  )

  override def globalSettings: Seq[Def.Setting[?]] = Seq(
    missinglinkFailOnConflicts := true,
    missinglinkScanDependencies := false,
    missinglinkIgnoreSourcePackages := Nil,
    missinglinkTargetSourcePackages := Nil,
    missinglinkIgnoreDestinationPackages := Nil,
    missinglinkTargetDestinationPackages := Nil,
    missinglinkExcludedDependencies := Nil,
    missinglinkVerbose := false,
  )

  override def projectSettings: Seq[Setting[?]] = {
    inConfig(Compile)(configSettings) ++
      inConfig(Runtime)(configSettings) ++
      inConfig(Test)(configSettings)
  }

  private def loadArtifactsAndCheckConflicts(
      cp: Classpath,
      classDirectory: File,
      scanDependencies: Boolean,
      excluded: ModuleFilter,
      log: Logger
  )(implicit
      converter: FileConverter
  ): (Seq[Conflict], Map[ClassTypeDescriptor, ModuleID]) = {

    val runtimeProjectArtifacts = constructArtifacts(cp, log)

    // Map each class to the module that provides it (first wins), to attribute conflicts to a JAR.
    val sourceModules: Map[ClassTypeDescriptor, ModuleID] =
      runtimeProjectArtifacts.foldLeft(Map.empty[ClassTypeDescriptor, ModuleID]) { (acc, ma) =>
        ma.module match {
          case Some(module) =>
            ma.artifact.classes().keySet().asScala.foldLeft(acc) { (current, className) =>
              if (current.contains(className)) current else current + (className -> module)
            }
          case None => acc
        }
      }

    // also need to load JDK classes from the bootstrap classpath
    val bootstrapArtifacts = loadBootstrapArtifacts(bootClasspathToUse(log), log)

    val allArtifacts = runtimeProjectArtifacts.map(_.artifact) ++ bootstrapArtifacts

    val runtimeArtifactsAfterExclusions = runtimeProjectArtifacts
      .filter(f => f.module.fold(true)(excluded))
      .map(_.artifact)

    val projectArtifact =
      if (scanDependencies)
        classesToArtifact(runtimeArtifactsAfterExclusions.flatMap(_.classes.asScala).toMap)
      else
        toArtifact(classDirectory)

    if (projectArtifact.classes().isEmpty()) {
      log.warn(
        "No classes found in project build directory" +
          " - did you run 'sbt compile' first?"
      )
    }

    log.debug("Checking for conflicts starting from " + projectArtifact.name().name())
    log.debug("Artifacts included in the project: ")
    for (artifact <- runtimeArtifactsAfterExclusions) {
      log.debug("    " + artifact.name().name())
    }

    val conflictChecker = new ConflictChecker

    val conflicts =
      conflictChecker.check(
        projectArtifact,
        runtimeArtifactsAfterExclusions.asJava,
        allArtifacts.asJava
      )

    (conflicts.asScala.toSeq, sourceModules)
  }

  private def toArtifact(outputDirectory: File): Artifact = {
    val classes =
      (outputDirectory ** "*.class")
        .get()
        .map(loadClass)
        .map(c => c.className() -> c)
        .toMap

    classesToArtifact(classes)
  }

  private def classesToArtifact(classes: Map[ClassTypeDescriptor, DeclaredClass]): Artifact = {
    new ArtifactBuilder()
      .name(new ArtifactName("project"))
      .classes(classes.asJava)
      .build()
  }

  private def loadClass(f: File): DeclaredClass = {
    val is = new FileInputStream(f)
    try com.spotify.missinglink.ClassLoader.load(is)
    finally is.close()
  }

  private def loadBootstrapArtifacts(bootstrapClasspath: String, log: Logger)(implicit
      converter: FileConverter
  ): List[Artifact] = {
    if (bootstrapClasspath == null) {
      Java9ModuleLoader.getJava9ModuleArtifacts((s, ex) => log.warn(s)).asScala.toList
    } else {
      val cp = bootstrapClasspath
        .split(System.getProperty("path.separator"))
        .map(f => Attributed.blank(PluginCompat.toFileRef(file(f))))
        .toList

      constructArtifacts(cp, log).map(_.artifact)
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

  private def constructArtifacts(cp: Classpath, log: Logger)(implicit
      converter: FileConverter
  ): List[ModuleArtifact] = {
    val artifactLoader = new ArtifactLoader

    def isValid(entry: File): Boolean =
      (entry.isFile() && entry.getPath().endsWith(".jar")) || entry.isDirectory()

    def fileToArtifact(f: Attributed[PluginCompat.FileRef]): ModuleArtifact = {
      log.debug("loading artifact for path: " + f)
      ModuleArtifact(
        artifactLoader.load(PluginCompat.toFile(f.data)),
        f.get(PluginCompat.moduleIDStr).map(PluginCompat.parseModuleIDStrAttribute)
      )
    }

    cp.filter(c => isValid(PluginCompat.toFile(c.data))).map(fileToArtifact).toList
  }

  private def filterConflicts[T <: PackageFilter](
      packageFilters: Seq[T],
      setting: SettingKey[?],
      log: Logger,
      name: String,
      field: Dependency => ClassTypeDescriptor,
  )(implicit pfs: PackageFilters[T]): Seq[Conflict] => Seq[Conflict] = { input =>
    if (packageFilters.nonEmpty) {
      log.debug(s"Applying filters on $name packages: ${packageFilters.mkString(", ")}")

      def isFiltered(conflict: Conflict): Boolean = {
        val descriptor = field(conflict.dependency())
        val className = descriptor.getClassName.replace('/', '.')
        val conflictPackageName = className.substring(0, className.lastIndexOf('.'))

        pfs.apply(conflictPackageName)(packageFilters)
      }

      val filtered = input.filter(isFiltered)
      val diff = input.length - filtered.length

      if (diff != 0) {
        log.info(
          s"""
            |$diff conflicts found in ignored ${name} packages.
            |Run plugin again without the '${setting.key.label}' setting to see all conflicts that were found.
             """.stripMargin
        )
      }

      filtered
    } else {
      input
    }
  }

  private def outputConflicts(
      conflicts: Seq[Conflict],
      sourceModules: Map[ClassTypeDescriptor, ModuleID],
      verbose: Boolean,
      log: Logger
  ): Unit = {
    def logLine(msg: String): Unit =
      log.error(msg)

    // Agreement for "N conflict(s) reference(s)": the noun takes "s" in the plural, the verb in the singular.
    def conflictNoun(count: Int): String = if (count == 1) "conflict" else "conflicts"
    def referenceVerb(count: Int): String = if (count == 1) "references" else "reference"

    val descriptions = Map(
      ConflictCategory.CLASS_NOT_FOUND -> "Class being called not found",
      ConflictCategory.METHOD_SIGNATURE_NOT_FOUND -> "Method being called not found",
    )

    def categoryDesc(category: ConflictCategory): String =
      descriptions.getOrElse(category, category.name().replace('_', ' '))

    def optionalLineNumber(lineNumber: Int): String =
      if (lineNumber != 0) ":" + lineNumber else ""

    // group conflict by category
    val byCategory = conflicts.groupBy(_.category())

    if (verbose) {
      for ((category, conflictsInCategory) <- byCategory) {
        logLine("")
        logLine("Category: " + categoryDesc(category))

        // next group by artifact containing the conflict
        val byArtifact = conflictsInCategory.groupBy(_.usedBy())

        for ((artifactName, conflictsInArtifact) <- byArtifact) {
          logLine("  In artifact: " + artifactName.name())

          // next group by class containing the conflict
          val byClassName = conflictsInArtifact.groupBy(_.dependency().fromClass())

          for ((classDesc, conflictsInClass) <- byClassName) {
            logLine("    In class: " + classDesc.toString())

            // collapse all call sites that share the same missing target + reason
            val byProblem = conflictsInClass.groupBy { conflict =>
              (conflict.dependency().describe(), conflict.reason(), conflict.existsIn())
            }

            for (((describe, reason, existsIn), groupedConflicts) <- byProblem) {
              logLine("      " + describe)
              logLine("      Problem: " + reason)
              if (existsIn != ConflictChecker.UNKNOWN_ARTIFACT_NAME)
                logLine("      Found in: " + existsIn.name())

              val callSites = groupedConflicts
                .map { conflict =>
                  val dep = conflict.dependency()
                  dep.fromMethod().prettyWithoutReturnType() +
                    optionalLineNumber(dep.fromLineNumber())
                }
                .distinct
                .sorted

              logLine("      Referenced from:")
              for (callSite <- callSites)
                logLine("        " + callSite)
              logLine("      --------")
            }
          }
        }
      }
    } else {
      // Per-category counts: exclude calling JARs (missing classes) or realign/ignore (methods/fields).
      val total = conflicts.size

      logLine("")
      logLine(s"Missinglink summary: $total ${conflictNoun(total)} found.")

      // Render `<proj>/<setting> ++= List(...)`, one entry per line with its conflict count, highest first.
      def logSettingList(setting: String, entries: Seq[(String, Int)]): Unit = {
        val sorted = entries.sortBy { case (entry, count) => (-count, entry) }
        logLine(s"  <proj>/$setting ++= List(")
        sorted.zipWithIndex.foreach { case ((entry, count), index) =>
          val comma = if (index == sorted.size - 1) "" else ","
          logLine(s"    $entry$comma  // $count ${conflictNoun(count)}")
        }
        logLine("  )")
      }

      // Group conflicts by calling module; project-local callers have no module to exclude.
      def excludeDependencySnippet(cs: Seq[Conflict]): Unit = {
        val byModule = cs
          .flatMap(c => sourceModules.get(c.dependency().fromClass()))
          .groupBy(m => (m.organization, m.name))
          .map { case ((org, name), ms) =>
            s"""moduleFilter(organization = "$org", name = "$name")""" -> ms.size
          }
          .toSeq
        if (byModule.isEmpty)
          logLine("  (these calls originate in your own project; nothing to exclude)")
        else
          logSettingList("missinglinkExcludedDependencies", byModule)
      }

      def packageOf(c: ClassTypeDescriptor): String = {
        val className = c.getClassName.replace('/', '.')
        val idx = className.lastIndexOf('.')
        if (idx < 0) "" else className.substring(0, idx)
      }

      // Collapse missing packages into the topmost present ancestor (IgnoredPackage covers subpackages).
      def collapsedPackages(cs: Seq[Conflict]): Seq[(String, Int)] = {
        val counts =
          cs.map(c => packageOf(c.dependency().targetClass()))
            .filter(_.nonEmpty)
            .groupBy(identity)
            .map { case (pkg, occurrences) => pkg -> occurrences.size }
        val present = counts.keySet
        def topmostAncestor(p: String): String =
          present.filter(q => p == q || p.startsWith(q + ".")).minBy(_.length)
        counts.toSeq
          .groupBy { case (pkg, _) => topmostAncestor(pkg) }
          .map { case (root, entries) => root -> entries.map(_._2).sum }
          .toSeq
      }

      // Destination-package ignores filter the conflict list directly, so they reliably suppress
      // evicted/duplicated classes that dependency exclusion cannot.
      def ignoreDestinationSnippet(packages: Seq[(String, Int)]): Unit =
        if (packages.nonEmpty)
          logSettingList(
            "missinglinkIgnoreDestinationPackages",
            packages.map { case (pkg, count) => s"""IgnoredPackage("$pkg")""" -> count }
          )

      val (classNotFound, signatureNotFound) =
        conflicts.partition(_.category() == ConflictCategory.CLASS_NOT_FOUND)

      if (classNotFound.nonEmpty) {
        logLine("")
        logLine(
          s"${classNotFound.size} ${conflictNoun(classNotFound.size)} " +
            s"${referenceVerb(classNotFound.size)} classes missing " +
            "from the classpath - usually optional dependencies you do not use. " +
            "Exclude the dependencies that reference them:"
        )
        excludeDependencySnippet(classNotFound)
      }

      if (signatureNotFound.nonEmpty) {
        logLine("")
        logLine(
          s"${signatureNotFound.size} ${conflictNoun(signatureNotFound.size)} " +
            s"${referenceVerb(signatureNotFound.size)} methods " +
            "or fields missing from libraries already on your classpath - usually two dependency " +
            "versions disagree and a binary-incompatible one was evicted."
        )
        // The target class is present, so name the module/version missing the member to realign.
        val culprits =
          signatureNotFound
            .flatMap(c => sourceModules.get(c.dependency().targetClass()))
            .groupBy(m => (m.organization, m.name, m.revision))
            .map { case ((org, name, revision), ms) => s"$org:$name:$revision" -> ms.size }
            .toSeq
            .sortBy { case (label, count) => (-count, label) }
        if (culprits.nonEmpty) {
          logLine("Check `show <proj>/evicted` and align these versions:")
          for ((label, count) <- culprits)
            logLine(s"  - $label  ($count ${conflictNoun(count)})")
        }
        logLine("To ignore them instead:")
        ignoreDestinationSnippet(collapsedPackages(signatureNotFound))
      }

      logLine("")
      logLine(
        "Re-run with 'missinglinkVerbose := true' to see the full per-class breakdown " +
          "(which class references each missing symbol, and from which methods)."
      )
    }
  }

  private final case class ModuleArtifact(artifact: Artifact, module: Option[ModuleID])

  private[sbtmissinglink] sealed trait PackageFilter {
    def apply(name: String): Boolean
  }
  private[sbtmissinglink] trait PackageFilters[T <: PackageFilter] {
    def apply(name: String)(filters: Seq[T]): Boolean
  }
}
