// Defines "macro" configuration.
// By default, this includes the dependencies of the normal sources.
// Drop the `extend(Compile)` to include no dependencies (not even scala-library) by default.
val Macro = config("macro").hide.extend(Compile)

lazy val root = (project in file("."))
  .settings(
    scalaVersion := "2.12.19",

    // Adds a "macro" configuration for macro dependencies.
    ivyConfigurations.value += Macro,

    // add the compiler as a dependency for src/macro/
    libraryDependencies += {
      "org.scala-lang" % "scala-compiler" % scalaVersion.value % Macro
    },

    // adds standard compile, console, package tasks for src/macro/
    inConfig(Macro)(Defaults.configSettings),

    // puts the compiled macro on the classpath for the main sources
    Compile / unmanagedClasspath ++=
      (Macro / fullClasspath).value,

    // includes sources in src/macro/ in the main source package
    Compile / packageSrc / mappings ++=
      (Macro / packageSrc / mappings).value,

    // Includes classes compiled from src/macro/ in the main binary
    // This can be omitted if the classes in src/macro/ aren't used at runtime
    Compile / packageBin / mappings ++=
      (Macro / packageBin / mappings).value
  )
