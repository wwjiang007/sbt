ThisBuild / scalaVersion := "2.12.17"

val test = (project in file(".")).settings(
  libraryDependencies += "org.scalatest" %% "scalatest" % "3.0.7" % Test
)
