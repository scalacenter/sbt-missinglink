package test

object ProblematicDependency {

  def missingGroovy(): AnyRef = {
    org.slf4j.impl.StaticLoggerBinder.getSingleton
  }
}
