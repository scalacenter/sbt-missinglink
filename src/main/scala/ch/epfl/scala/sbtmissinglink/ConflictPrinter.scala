package ch.epfl.scala.sbtmissinglink

import scala.collection.JavaConverters._
import scala.collection.immutable.SortedMap

import com.palantir.abi.checker.datamodel.conflict.Conflict
import com.palantir.abi.checker.datamodel.types.ClassTypeDescriptor

/** Scala port of the `ConflictPrinter` of Palantir's
  * [[https://github.com/palantir/gradle-transitive-abi-checker gradle-transitive-abi-checker]],
  * which is not part of `abi-check-core`. The output is kept identical.
  */
private[sbtmissinglink] object ConflictPrinter {

  def outputConflicts(conflicts: Seq[Conflict]): String = {
    val sb = new StringBuilder

    // First output the dependency library that broke (this might be unknown for classes not found)
    val byBrokenDependency = groupByKeySorted(conflicts)(_.usedBy().name())
    outputRecommendations(sb, byBrokenDependency)

    byBrokenDependency.foreach { case (artifactName, conflictsForTransitive) =>
      sb.append("Breaks found in: " + artifactName + "\n")
      outputConflictsForBrokenDependency(sb, conflictsForTransitive)
      sb.append("===========================\n\n")
    }

    sb.toString
  }

  private def outputRecommendations(
      sb: StringBuilder,
      byBrokenDependency: SortedMap[String, Seq[Conflict]]
  ): Unit = {
    sb.append("ABI Incompatibilities were detected between the following libraries. ")
    sb.append(
      "You should upgrade or downgrade one for each pair. Exact conflicts are detailed below.\n\n"
    )

    byBrokenDependency.foreach { case (dependency, conflicts) =>
      val missingClassesInUnknownDep = conflicts
        .filter(_.existsIn().name() == Conflict.UNKNOWN_ARTIFACT_NAME_STRING)
        .map(_.dependency().targetClass().toString)
        .distinct
        .sorted
      if (missingClassesInUnknownDep.nonEmpty) {
        sb.append(
          "\t" + dependency + " refers to unknown classes, " +
            "so we couldn't determine the transitive library that broke its ABI.\n"
        )
        missingClassesInUnknownDep.foreach(clazz => sb.append("\t\t" + clazz + "\n"))
      }

      val knownBrokenTransitives = conflicts
        .map(_.existsIn().name())
        .distinct
        .filter(_ != Conflict.UNKNOWN_ARTIFACT_NAME_STRING)
        .sorted

      if (knownBrokenTransitives.isEmpty) {
        sb.append("\n")
      } else {
        sb.append("\t" + dependency)
        knownBrokenTransitives match {
          case Seq(transitive) =>
            sb.append(" with " + transitive)
            if (transitive == dependency)
              sb.append("\n\t\t-> " + selfDependencyHint(dependency))
          case transitives =>
            sb.append(" with:")
            transitives.foreach { transitive =>
              sb.append("\n\t\t" + transitive)
              if (transitive == dependency)
                sb.append("\n\t\t\t-> " + selfDependencyHint(dependency))
            }
        }
        sb.append("\n\n")
      }
    }
  }

  private def selfDependencyHint(dependency: String): String =
    "This generally indicates a class in " + dependency +
      " inherited from a super class from another library, which had an ABI break, " +
      "but there is insufficient information to determine the root dependency."

  private def outputConflictsForBrokenDependency(
      sb: StringBuilder,
      conflictsInBrokenDependency: Seq[Conflict]
  ): Unit = {
    val byTransitive = groupByKeySorted(conflictsInBrokenDependency)(_.existsIn().name())
    forEachKnownLast(byTransitive) { (dependencyName, conflictsInDependency, isLastDep) =>
      sb.append("  " + indentCharItem(isLastDep) + "- Using transitive: " + dependencyName + "\n")
      val indentDep = "  " + indentCharSubItems(isLastDep)
      outputConflictsByClassForDependency(sb, indentDep, conflictsInDependency)
    }
  }

  private def outputConflictsByClassForDependency(
      sb: StringBuilder,
      indentPrefix: String,
      conflictsInDependency: Seq[Conflict]
  ): Unit = {
    val byClassName = groupByKeySorted(conflictsInDependency)(_.dependency().fromClass().toString)
    forEachKnownLast(byClassName) { (classDesc, conflictsInClass, isLastClass) =>
      sb.append(
        indentPrefix + "  " + indentCharItem(isLastClass) + "- In class: " + classDesc + "\n"
      )
      val indentClass = indentPrefix + "  " + indentCharSubItems(isLastClass)
      // All conflicts of a class share the same reachability path
      outputPathForClass(
        sb,
        indentClass,
        conflictsInClass.head.dependency().reachabilityPath().asScala.toList
      )
      outputConflictsForClass(sb, indentClass, conflictsInClass)
    }
  }

  private def outputPathForClass(
      sb: StringBuilder,
      indentPrefix: String,
      reachabilityPath: Seq[ClassTypeDescriptor]
  ): Unit = {
    val path = reachabilityPath.map(_.toString).mkString("\n" + indentPrefix + "  |    -> ")
    sb.append(indentPrefix + "  |  With path:\n")
    sb.append(indentPrefix + "  |    *  " + path + "\n")
  }

  private def outputConflictsForClass(
      sb: StringBuilder,
      indentPrefix: String,
      conflictsInClass: Seq[Conflict]
  ): Unit = {
    val byConflictReason = groupByKeySorted(conflictsInClass)(_.reason())
    forEachKnownLast(byConflictReason) { (reason, conflictsForReason, isLastReason) =>
      sb.append(indentPrefix + "  |\n")
      sb.append(indentPrefix + "  " + indentCharItem(isLastReason) + "- " + reason + "\n")
      val indentReason = indentPrefix + "  " + indentCharSubItems(isLastReason)

      // If the target and the source class are the same, the break likely comes from a super class
      val firstDependency = conflictsForReason.head.dependency()
      if (firstDependency.targetClass() == firstDependency.fromClass()) {
        sb.append(
          indentReason + "  |  * " +
            "It was likely coming from a super class/interface provided by another library.\n"
        )
      }
      outputCallsitesForConflictReasons(sb, indentReason, conflictsForReason)
    }
  }

  private def outputCallsitesForConflictReasons(
      sb: StringBuilder,
      indentPrefix: String,
      conflictsForReason: Seq[Conflict]
  ): Unit = {
    val byCallSite =
      groupByKeySorted(conflictsForReason)(_.dependency().fromMethod().method().pretty())
    forEachKnownLast(byCallSite) { (callSite, callSiteConflicts, isLastCallSite) =>
      val lineNumbers = callSiteConflicts.map(_.dependency().fromLineNumber()).distinct.sorted
      sb.append(
        indentPrefix + "  " + indentCharItem(isLastCallSite) + "- In " + callSite +
          " (line " + lineNumbers.mkString(", ") + ")\n"
      )
    }
  }

  private def groupByKeySorted[K: Ordering, V](values: Seq[V])(key: V => K): SortedMap[K, Seq[V]] =
    SortedMap.empty[K, Seq[V]] ++ values.groupBy(key)

  private def forEachKnownLast[K, V](map: SortedMap[K, V])(f: (K, V, Boolean) => Unit): Unit = {
    val last = map.size - 1
    map.zipWithIndex.foreach { case ((k, v), index) => f(k, v, index == last) }
  }

  private def indentCharItem(isLastItem: Boolean): String = if (isLastItem) "\\" else "|"

  private def indentCharSubItems(isLastItem: Boolean): String = if (isLastItem) " " else "|"
}
