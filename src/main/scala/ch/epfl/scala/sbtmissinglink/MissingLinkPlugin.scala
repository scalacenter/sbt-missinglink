package ch.epfl.scala.sbtmissinglink

import sbt._
import sbt.Keys._
import sbt.librarymanagement.ModuleFilter
import sbt.plugins.JvmPlugin

import sbtcompat.PluginCompat

import scala.collection.JavaConverters._
import scala.collection.immutable.Map

import com.palantir.abi.checker.{
  AbiCheckerClassLoader,
  ArtifactLoader,
  ConflictChecker,
  ConflictCheckerConfiguration,
  JdkModuleLoader
}
import com.palantir.abi.checker.datamodel.{Artifact, ArtifactName}
import com.palantir.abi.checker.datamodel.classlocation.ClassLocation
import com.palantir.abi.checker.datamodel.conflict.{
  ClassDependency,
  Conflict,
  Dependency,
  FieldDependency,
  MethodDependency
}
import com.palantir.abi.checker.datamodel.conflict.Conflict.ConflictCategory
import com.palantir.abi.checker.datamodel.types.ClassTypeDescriptor
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

          val diffMessage =
            if (initialTotal != filteredTotal)
              s" ($initialTotal conflicts were found before applying filters)"
            else
              ""

          val conflictNoun = if (filteredTotal == 1) "conflict" else "conflicts"
          log.info(s"$filteredTotal $conflictNoun found!$diffMessage")

          outputConflicts(filteredConflicts, sourceModules, verbose, log)

          if (failOnConflicts) {
            val verb = if (filteredTotal == 1) "was" else "were"
            throw new MessageOnlyException(s"There $verb $filteredTotal $conflictNoun")
          }
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
    val artifactLoader = new ArtifactLoader

    val runtimeProjectArtifacts = constructArtifacts(artifactLoader, cp, classDirectory, log)

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

    // also need to load JDK classes
    val jdkArtifacts = jdkModuleLoader.getJavaModuleArtifacts().asScala.toList

    val (runtimeArtifactsAfterExclusions, excludedArtifacts) =
      runtimeProjectArtifacts.partition(_.module.fold(true)(excluded))

    val projectArtifact =
      artifactLoader.load(classDirectory.toPath, ArtifactName.of("project"))

    // The classes the reachability analysis starts from
    val entryPoints: Seq[ClassLocation] =
      if (scanDependencies)
        (projectArtifact :: runtimeArtifactsAfterExclusions.map(_.artifact))
          .flatMap(_.classes().values().asScala)
      else
        projectArtifact.classes().values().asScala.toSeq

    if (entryPoints.isEmpty) {
      log.warn(
        "No classes found in project build directory" +
          " - did you run 'sbt compile' first?"
      )
    }

    log.debug("Checking for conflicts starting from " + projectArtifact.name().name())
    log.debug("Artifacts included in the project: ")
    for (artifact <- runtimeArtifactsAfterExclusions) {
      log.debug("    " + artifact.artifact.name().name())
    }

    // Excluded dependencies stay on the classpath, but their classes are not analyzed.
    // Neither are the classes of the JDK.
    val configuration = ConflictCheckerConfiguration
      .builder()
      .addAllIgnoredArtifactPrefixes(
        (jdkArtifacts ++ excludedArtifacts.map(_.artifact)).map(_.name().name()).asJava
      )
      .build()

    val runtimeClasspath =
      jdkArtifacts ++ (projectArtifact :: runtimeProjectArtifacts.map(_.artifact))

    val conflicts = ConflictChecker.checkWithEntryPoints(
      configuration,
      new AbiCheckerClassLoader,
      runtimeClasspath.asJava,
      entryPoints.asJava
    )

    (conflicts.asScala.toSeq, sourceModules)
  }

  private def constructArtifacts(
      artifactLoader: ArtifactLoader,
      cp: Classpath,
      classDirectory: File,
      log: Logger
  )(implicit converter: FileConverter): List[ModuleArtifact] = {
    def isValid(entry: File): Boolean =
      entry != classDirectory &&
        ((entry.isFile() && entry.getPath().endsWith(".jar")) || entry.isDirectory())

    // JARs are named by their file name. Directories are named by their full path,
    // since most of them are called "classes".
    def artifactName(f: File): String =
      if (f.isDirectory()) f.getPath() else f.getName()

    def fileToArtifact(f: Attributed[PluginCompat.FileRef]): ModuleArtifact = {
      log.debug("loading artifact for path: " + f)
      val file = PluginCompat.toFile(f.data)
      ModuleArtifact(
        artifactLoader.load(file.toPath, ArtifactName.of(artifactName(file))),
        f.get(PluginCompat.moduleIDStr).map(PluginCompat.parseModuleIDStrAttribute)
      )
    }

    cp.filter(c => isValid(PluginCompat.toFile(c.data))).map(fileToArtifact).toList
  }

  /** Same text as `Dependency.describe()` of Spotify's missinglink. */
  private def describe(dependency: Dependency): String = dependency match {
    case d: MethodDependency =>
      "Call to: " + d.targetClass() + "." + d.targetMethod().method().prettyWithoutReturnType()
    case d: FieldDependency =>
      "Access to: " + d.targetClass() + "." + d.field().name()
    case d: ClassDependency =>
      "Load class: " + d.targetClass()
    case d =>
      "Reference to: " + d.targetClass()
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
        val className = descriptor.className()
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

    def conflictNoun(count: Int): String = if (count == 1) "conflict" else "conflicts"
    def referenceVerb(count: Int): String = if (count == 1) "references" else "reference"

    val descriptions = Map(
      ConflictCategory.CLASS_NOT_FOUND -> "Class being called not found",
      ConflictCategory.METHOD_SIGNATURE_NOT_FOUND -> "Method being called not found",
      ConflictCategory.FIELD_NOT_FOUND -> "Field being accessed not found",
    )

    def categoryDesc(category: ConflictCategory): String =
      descriptions.getOrElse(category, category.name().replace('_', ' '))

    def optionalLineNumber(lineNumber: Int): String =
      if (lineNumber != 0) ":" + lineNumber else ""

    // Attribute every conflict to the JAR that references the missing symbol
    val byModule: Seq[((String, String), (Int, Int))] =
      conflicts
        .flatMap(c => sourceModules.get(c.dependency().fromClass()).map(_ -> c.category()))
        .groupBy { case (module, _) => (module.organization, module.name) }
        .map { case (key, grouped) =>
          val missingClasses = grouped.count(_._2 == ConflictCategory.CLASS_NOT_FOUND)
          key -> (missingClasses, grouped.size - missingClasses)
        }
        .toSeq
        .sortBy { case ((org, name), (classes, members)) => (-classes, -members, org, name) }

    logLine(
      if (byModule.isEmpty) "Missinglink summary: conflicts found."
      else
        s"Missinglink summary: conflicts found in ${byModule.size} " +
          s"${if (byModule.size == 1) "dependency" else "dependencies"}."
    )

    if (verbose) {
      for ((category, conflictsInCategory) <- conflicts.groupBy(_.category())) {
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
              (describe(conflict.dependency()), conflict.reason(), conflict.existsIn())
            }

            for (((describe, reason, existsIn), groupedConflicts) <- byProblem) {
              logLine("      " + describe)
              logLine("      Problem: " + reason)
              if (existsIn != Conflict.UNKNOWN_ARTIFACT_NAME)
                logLine("      Found in: " + existsIn.name())

              val callSites = groupedConflicts
                .map { conflict =>
                  val dep = conflict.dependency()
                  dep.fromMethod().method().prettyWithoutReturnType() +
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
      val (classNotFound, signatureNotFound) =
        conflicts.partition(_.category() == ConflictCategory.CLASS_NOT_FOUND)

      def classCount(n: Int): String = s"$n missing ${if (n == 1) "class" else "classes"}"
      def memberCount(n: Int): String = s"$n missing ${if (n == 1) "member" else "members"}"

      if (classNotFound.nonEmpty)
        logLine(
          s" * ${classNotFound.size} ${conflictNoun(classNotFound.size)} " +
            s"${referenceVerb(classNotFound.size)} classes missing from the classpath"
        )
      if (signatureNotFound.nonEmpty)
        logLine(
          s" * ${signatureNotFound.size} ${conflictNoun(signatureNotFound.size)} " +
            s"${referenceVerb(signatureNotFound.size)} members missing from classes already on your classpath"
        )

      if (byModule.nonEmpty) {
        logLine("Exclude the dependencies that reference them:")
        logLine("    <proj>/missinglinkExcludedDependencies ++= List(")
        byModule.zipWithIndex.foreach { case (((org, name), (classes, members)), index) =>
          val comma = if (index == byModule.size - 1) "" else ","
          logLine(
            s"""      moduleFilter(organization = "$org", name = "$name")$comma""" +
              s"  // ${classCount(classes)}, ${memberCount(members)}"
          )
        }
        logLine("    )")
      }

      val projectLocal =
        conflicts.count(c => !sourceModules.contains(c.dependency().fromClass()))
      if (projectLocal != 0) {
        val verb = if (projectLocal == 1) "originates" else "originate"
        logLine(
          s"$projectLocal ${conflictNoun(projectLocal)} $verb in your own project and cannot " +
            "be excluded this way."
        )
      }

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
