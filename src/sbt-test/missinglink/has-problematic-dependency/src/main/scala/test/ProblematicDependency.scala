package test

import com.google.common.base.Enums

/**
 * Calls a method in the Guava Enums class which was removed in guava 18. If a project calls this
 * method while overriding Guava to >= 18, it will cause a NoSuchMethodError at runtime.
 */
object ProblematicDependency {

  def reliesOnRemovedMethod(): AnyRef = {
    Enums.valueOfFunction(classOf[Foo])
  }
}
