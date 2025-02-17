/*
 * sbt
 * Copyright 2023, Scala center
 * Copyright 2011 - 2022, Lightbend, Inc.
 * Copyright 2008 - 2010, Mark Harrah
 * Licensed under Apache License 2.0 (see LICENSE)
 */

package sbt

import scala.annotation.tailrec
import java.io.File
import sbt.io.syntax._
import sbt.io.IO
import sbt.internal.inc.{ RawCompiler, ScalaInstance }
import sbt.util.CacheImplicits._
import sbt.util.Tracked.inputChanged
import sbt.util.{ CacheStoreFactory, FilesInfo, HashFileInfo, ModifiedFileInfo, PlainFileInfo }
import sbt.util.FileInfo.{ exists, hash, lastModified }
import sbt.internal.util.ManagedLogger
import xsbti.compile.ClasspathOptions

object RawCompileLike {
  type Gen = (Seq[File], Seq[File], File, Seq[String], Int, ManagedLogger) => Unit

  private def optionFiles(options: Seq[String], fileInputOpts: Seq[String]): List[File] = {
    @tailrec
    def loop(opt: List[String], result: List[File]): List[File] = {
      opt.dropWhile(!fileInputOpts.contains(_)) match {
        case List(_, fileOpt, tail @ _*) => {
          val file = new File(fileOpt)
          if (file.isFile) loop(tail.toList, file :: result)
          else loop(tail.toList, result)
        }
        case Nil | List(_) => result
      }
    }
    loop(options.toList, Nil)
  }

  def cached(cacheStoreFactory: CacheStoreFactory, doCompile: Gen): Gen =
    cached(cacheStoreFactory, Seq(), doCompile)

  def cached(
      cacheStoreFactory: CacheStoreFactory,
      fileInputOpts: Seq[String],
      doCompile: Gen
  ): Gen =
    (sources, classpath, outputDirectory, options, maxErrors, log) => {
      type Inputs = (
          FilesInfo[HashFileInfo],
          FilesInfo[ModifiedFileInfo],
          Seq[File],
          File,
          Seq[String],
          Int,
      )
      val inputs: Inputs = (
        hash(sources.toSet ++ optionFiles(options, fileInputOpts)),
        FilesInfo[ModifiedFileInfo](classpath.toSet.map(lastModified.fileOrDirectoryMax)),
        classpath,
        outputDirectory,
        options,
        maxErrors
      )
      val cachedComp = inputChanged(cacheStoreFactory make "inputs") { (inChanged, in: Inputs) =>
        inputChanged(cacheStoreFactory make "output") {
          (outChanged, outputs: FilesInfo[PlainFileInfo]) =>
            if (inChanged || outChanged)
              doCompile(sources, classpath, outputDirectory, options, maxErrors, log)
            else
              log.debug("Uptodate: " + outputDirectory.getAbsolutePath)
        }
      }
      cachedComp(inputs)(exists(outputDirectory.allPaths.get().toSet))
    }

  def prepare(description: String, doCompile: Gen): Gen =
    (sources, classpath, outputDirectory, options, maxErrors, log) => {
      if (sources.isEmpty) log.info("No sources available, skipping " + description + "...")
      else {
        log.info(description.capitalize + " to " + outputDirectory.absolutePath + "...")
        IO.delete(outputDirectory)
        IO.createDirectory(outputDirectory)
        doCompile(sources, classpath, outputDirectory, options, maxErrors, log)
        log.info(description.capitalize + " successful.")
      }
    }

  def filterSources(f: File => Boolean, doCompile: Gen): Gen =
    (sources, classpath, outputDirectory, options, maxErrors, log) =>
      doCompile(sources filter f, classpath, outputDirectory, options, maxErrors, log)

  def rawCompile(instance: ScalaInstance, cpOptions: ClasspathOptions): Gen =
    (sources, classpath, outputDirectory, options, _, log) => {
      val compiler = new RawCompiler(instance, cpOptions, log)
      compiler(sources.map(_.toPath), classpath.map(_.toPath), outputDirectory.toPath, options)
    }

  def compile(
      label: String,
      cacheStoreFactory: CacheStoreFactory,
      instance: ScalaInstance,
      cpOptions: ClasspathOptions
  ): Gen =
    cached(cacheStoreFactory, prepare(label + " sources", rawCompile(instance, cpOptions)))

  val nop: Gen = (_, _, _, _, _, _) => ()
}
