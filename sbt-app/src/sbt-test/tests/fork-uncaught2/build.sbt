ThisBuild / scalaVersion := "2.12.19"

libraryDependencies += "org.scala-sbt" % "test-interface" % "1.0"

testFrameworks := new TestFramework("build.MyFramework") :: Nil

fork := true

Test / definedTests += new sbt.TestDefinition(
      "my",
      // marker fingerprint since there are no test classes
      // to be discovered by sbt:
      new sbt.testing.AnnotatedFingerprint {
        def isModule = true
        def annotationName = "my"
      }, true, Array()
    )
