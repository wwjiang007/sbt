/*
 * sbt
 * Copyright 2023, Scala center
 * Copyright 2011 - 2022, Lightbend, Inc.
 * Copyright 2008 - 2010, Mark Harrah
 * Licensed under Apache License 2.0 (see LICENSE)
 */

package sbt
package internal
package graph

import java.io.File
import sjsonnew._
import scala.collection.mutable

private[sbt] case class GraphModuleId(
    organization: String,
    name: String,
    version: String,
) {
  def idString: String = organization + ":" + name + ":" + version
}

private[sbt] object GraphModuleId:
  import sjsonnew.BasicJsonProtocol.StringJsonFormat
  given graphModuleIdIso: IsoLList.Aux[GraphModuleId, String :*: String :*: String :*: LNil] =
    LList.iso[GraphModuleId, String :*: String :*: String :*: LNil](
      { (m: GraphModuleId) =>
        ("organization", m.organization) :*: ("name", m.name) :*: ("version", m.version) :*: LNil
      },
      { case (_, organization) :*: (_, name) :*: (_, version) :*: LNil =>
        GraphModuleId(organization, name, version)
      }
    )
end GraphModuleId

private[sbt] case class Module(
    id: GraphModuleId,
    license: Option[String] = None,
    extraInfo: String = "",
    evictedByVersion: Option[String] = None,
    jarFile: Option[File] = None,
    error: Option[String] = None
):
  def hadError: Boolean = error.isDefined
  def isUsed: Boolean = !isEvicted
  def isEvicted: Boolean = evictedByVersion.isDefined
end Module

private[sbt] object Module:
  import sjsonnew.BasicJsonProtocol.*
  given moduleIso: IsoLList.Aux[
    Module,
    GraphModuleId :*: Option[String] :*: String :*:
      Option[
        String
      ] :*: Option[File] :*: Option[String] :*: LNil
  ] = LList.iso[
    Module,
    GraphModuleId :*: Option[String] :*: String :*:
      Option[
        String
      ] :*: Option[File] :*: Option[String] :*: LNil
  ](
    { (m: Module) =>
      ("id", m.id) :*:
        ("license", m.license) :*:
        ("extraInfo", m.extraInfo) :*:
        ("evictedByVersion", m.evictedByVersion) :*:
        ("jarFile", m.jarFile) :*:
        ("error", m.error) :*: LNil
    },
    {
      case (_, id) :*:
          (_, license) :*:
          (_, extraInfo) :*:
          (_, evictedByVersion) :*:
          (_, jarFile) :*:
          (_, error) :*: LNil =>
        Module(id, license, extraInfo, evictedByVersion, jarFile, error)
    }
  )
end Module

private[sbt] case class ModuleGraph(nodes: Seq[Module], edges: Seq[Edge]) {
  lazy val modules: Map[GraphModuleId, Module] =
    nodes.map(n => (n.id, n)).toMap

  def module(id: GraphModuleId): Option[Module] = modules.get(id)

  lazy val dependencyMap: Map[GraphModuleId, Seq[Module]] =
    createMap(identity)

  lazy val reverseDependencyMap: Map[GraphModuleId, Seq[Module]] =
    createMap { case (a, b) => (b, a) }

  def createMap(
      bindingFor: ((GraphModuleId, GraphModuleId)) => (GraphModuleId, GraphModuleId)
  ): Map[GraphModuleId, Seq[Module]] = {
    val map = mutable.Map.empty[GraphModuleId, mutable.Set[Module]]
    edges.foreach { entry =>
      val (f, t) = bindingFor(entry)
      module(t).foreach { m => map.getOrElseUpdate(f, mutable.Set.empty) += m }
    }
    map.view.mapValues(_.toSeq.sortBy(_.id.idString)).toMap.withDefaultValue(Nil)
  }

  def roots: Seq[Module] =
    nodes.filter(n => !edges.exists(_._2 == n.id)).sortBy(_.id.idString)
}

private[sbt] object ModuleGraph:
  val empty = ModuleGraph(Seq.empty, Seq.empty)

  import BasicJsonProtocol._
  given moduleGraphIso: IsoLList.Aux[ModuleGraph, Vector[Module] :*: Vector[Edge] :*: LNil] =
    LList.iso[ModuleGraph, Vector[Module] :*: Vector[Edge] :*: LNil](
      { (g: ModuleGraph) =>
        ("nodes", g.nodes.toVector) :*: ("edges", g.edges.toVector) :*: LNil
      },
      { case (_, nodes: Vector[Module]) :*: (_, edges: Vector[Edge]) :*: LNil =>
        ModuleGraph(nodes, edges)
      }
    )
end ModuleGraph
