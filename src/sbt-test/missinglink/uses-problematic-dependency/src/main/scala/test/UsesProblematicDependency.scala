package test

/**
 * Invokes a method from [[test.ProblematicDependency]] which will blow up if
 * Guava >= 18 is used.
 */
object UsesProblematicDependency {
  def callsClassThatReliesOnDeletedGuavaMethod(): AnyRef = {
    ProblematicDependency.reliesOnRemovedMethod()
  }
}
