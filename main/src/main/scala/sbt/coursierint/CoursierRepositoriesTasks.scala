/*
 * sbt
 * Copyright 2023, Scala center
 * Copyright 2011 - 2022, Lightbend, Inc.
 * Copyright 2008 - 2010, Mark Harrah
 * Licensed under Apache License 2.0 (see LICENSE)
 */

package sbt
package coursierint

import sbt.librarymanagement._
import sbt.Keys._
import sbt.ProjectExtra.transitiveInterDependencies
import sbt.ScopeFilter.Make._
import sbt.SlashSyntax0._

object CoursierRepositoriesTasks {
  private object CResolvers {
    private val slowReposBase = Seq(
      "https://repo.typesafe.com/",
      "https://repo.scala-sbt.org/",
      "http://repo.typesafe.com/",
      "http://repo.scala-sbt.org/"
    )

    private val fastReposBase = Seq(
      "http://repo1.maven.org/",
      "https://repo1.maven.org/"
    )

    private def url(res: Resolver): Option[String] =
      res match {
        case m: sbt.librarymanagement.MavenRepository =>
          Some(m.root)
        case u: URLRepository =>
          u.patterns.artifactPatterns.headOption
            .orElse(u.patterns.ivyPatterns.headOption)
        case _ =>
          None
      }

    private def fastRepo(res: Resolver): Boolean =
      url(res).exists(u => fastReposBase.exists(u.startsWith))

    private def slowRepo(res: Resolver): Boolean =
      url(res).exists(u => slowReposBase.exists(u.startsWith))

    def reorderResolvers(resolvers: Seq[Resolver]): Seq[Resolver] =
      if (resolvers.exists(fastRepo) && resolvers.exists(slowRepo)) {
        val (slow, other) = resolvers.partition(slowRepo)
        other ++ slow
      } else resolvers
  }

  // local-preloaded-ivy contains dangling ivy.xml without JAR files
  // https://github.com/sbt/sbt/issues/4661
  private final val keepPreloaded = false // coursierKeepPreloaded.value

  def coursierResolversTask(
      resolversKey: TaskKey[Seq[Resolver]]
  ): Def.Initialize[sbt.Task[Seq[Resolver]]] = Def.task {
    val result0 = resolversKey.value.filterNot(_ == projectResolver.value)
    val reorderResolvers = true // coursierReorderResolvers.value

    val paths = ivyPaths.value
    val result1 =
      if (reorderResolvers) CResolvers.reorderResolvers(result0)
      else result0
    val result2 =
      paths.ivyHome match {
        case Some(ivyHome) =>
          val ivyHomeUri = ivyHome.toString
          result1 map {
            case r: FileRepository =>
              val ivyPatterns = r.patterns.ivyPatterns map {
                _.replace("$" + "{ivy.home}", ivyHomeUri)
              }
              val artifactPatterns = r.patterns.artifactPatterns map {
                _.replace("$" + "{ivy.home}", ivyHomeUri)
              }
              val p =
                r.patterns.withIvyPatterns(ivyPatterns).withArtifactPatterns(artifactPatterns)
              r.withPatterns(p)
            case r => r
          }
        case _ => result1
      }

    if (keepPreloaded)
      result2
    else
      result2.filter { r =>
        !r.name.startsWith("local-preloaded")
      }
  }

  private val pluginIvySnapshotsBase =
    Resolver.SbtRepositoryRoot.stripSuffix("/") + "/ivy-snapshots"

  def coursierSbtResolversTask: Def.Initialize[sbt.Task[Seq[Resolver]]] = Def.task {
    val resolvers =
      sbt.Classpaths
        .bootRepositories(appConfiguration.value)
        .toSeq
        .flatten ++ // required because of the hack above it seems
        (updateSbtClassifiers / externalResolvers).value

    val pluginIvySnapshotsFound = resolvers.exists {
      case repo: URLRepository =>
        repo.patterns.artifactPatterns.headOption
          .exists(_.startsWith(pluginIvySnapshotsBase))
      case _ => false
    }

    val resolvers0 =
      if (pluginIvySnapshotsFound && !resolvers.contains(Classpaths.sbtPluginReleases))
        resolvers :+ Classpaths.sbtPluginReleases
      else
        resolvers
    if (keepPreloaded)
      resolvers0
    else
      resolvers0.filter { r =>
        !r.name.startsWith("local-preloaded")
      }
  }

  def coursierRecursiveResolversTask: Def.Initialize[sbt.Task[Seq[Resolver]]] =
    (Def
      .task {
        val s = state.value
        val projectRef = thisProjectRef.value
        val dependencyRefs = Project.transitiveInterDependencies(s, projectRef)
        (ScopeFilter(inProjects(projectRef)), ScopeFilter(inProjects(dependencyRefs: _*)))
      })
      .flatMapTask { case (filter1, filter2) =>
        Def.task {
          val resolvers = csrResolvers.all(filter1).value ++ csrResolvers.all(filter2).value
          resolvers.flatten
        }
      }
}
