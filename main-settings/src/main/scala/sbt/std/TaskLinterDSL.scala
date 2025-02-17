/*
 * sbt
 * Copyright 2023, Scala center
 * Copyright 2011 - 2022, Lightbend, Inc.
 * Copyright 2008 - 2010, Mark Harrah
 * Licensed under Apache License 2.0 (see LICENSE)
 */

/*
package sbt.std

import sbt.SettingKey
import sbt.dsl.LinterLevel
import sbt.dsl.LinterLevel.{ Abort, Warn }
import sbt.internal.util.Terminal
// import sbt.internal.util.appmacro.{ Convert, LinterDSL }

import scala.io.AnsiColor
import scala.reflect.macros.blackbox

abstract class BaseTaskLinterDSL extends LinterDSL {
  def isDynamicTask: Boolean
  def convert: Convert

  private def impl(
      ctx: blackbox.Context
  )(tree: ctx.Tree, lint: (ctx.Position, String) => Unit): Unit = {
    import ctx.universe._
    val isTask = convert.asPredicate(ctx)
    val unchecked = symbolOf[sbt.sbtUnchecked].asClass
    val initializeType = typeOf[sbt.Def.Initialize[_]]

    /*
 * Lints a task tree.
 *
 * @param insideIf indicates whether or not the current tree is enclosed in an if statement.
 *                It is generally illegal to call `.value` on a task within such a tree unless
 *                the tree has been annotated with `@sbtUnchecked`.
 * @param insideAnon indicates whether or not the current tree is enclosed in an anonymous
 *                   function. It is generally illegal to call `.value` on a task within such
 *                   a tree unless the tree has been annotated with `@sbtUnchecked`.
 * @param uncheckedWrapper an optional tree that is provided to lint a tree in the form:
 *                         `tree.value: @sbtUnchecked` for some tree. This can be used to
 *                         prevent the linter from rejecting task evaluation within a
 *                         conditional or an anonymous function.
 */
    class traverser(insideIf: Boolean, insideAnon: Boolean, uncheckedWrapper: Option[Tree])
        extends Traverser {

      def extractUncheckedWrapper(exprAtUseSite: Tree, tt: TypeTree): Option[Tree] = {
        tt.original match {
          case Annotated(annot, _) =>
            Option(annot.tpe) match {
              case Some(AnnotatedType(annotations, _)) =>
                val tpeAnnotations = annotations.flatMap(ann => Option(ann.tree.tpe).toList)
                val symAnnotations = tpeAnnotations.map(_.typeSymbol)
                val isUnchecked = symAnnotations.contains(unchecked)
                if (isUnchecked) {
                  // Use expression at use site, arg contains the old expr
                  // Referential equality between the two doesn't hold
                  Some(exprAtUseSite match {
                    case Typed(t, _) => t
                    case _           => exprAtUseSite
                  })
                } else None
              case _ => None
            }
          case _ => None
        }
      }

      @inline def isKey(tpe: Type): Boolean = isInitialize(tpe)
      @inline def isInitialize(tpe: Type): Boolean = tpe <:< initializeType

      def detectAndErrorOnKeyMissingValue(i: Ident): Unit = {
        if (isKey(i.tpe)) {
          val keyName = i.name.decodedName.toString
          lint(i.pos, TaskLinterDSLFeedback.missingValueForKey(keyName))
        } else ()
      }

      def detectAndErrorOnKeyMissingValue(s: Select): Unit = {
        if (isKey(s.tpe)) {
          val keyName = s.name.decodedName.toString
          lint(s.pos, TaskLinterDSLFeedback.missingValueForKey(keyName))
        } else ()
      }

      def detectAndErrorOnKeyMissingValue(a: Apply): Unit = {
        if (isInitialize(a.tpe)) {
          val expr = "X / y"
          lint(a.pos, TaskLinterDSLFeedback.missingValueForInitialize(expr))
        } else ()
      }

      override def traverse(tree: ctx.universe.Tree): Unit = {
        tree match {
          case ap @ Apply(TypeApply(Select(_, name), tpe :: Nil), qual :: Nil) =>
            val shouldIgnore = uncheckedWrapper.contains(ap)
            val wrapperName = name.decodedName.toString
            val (qualName, isSettingKey) =
              Option(qual.symbol)
                .map(sym => (sym.name.decodedName.toString, qual.tpe <:< typeOf[SettingKey[_]]))
                .getOrElse((ap.pos.source.lineToString(ap.pos.line - 1), false))

            if (!isSettingKey && !shouldIgnore && isTask(wrapperName, tpe.tpe, qual)) {
              if (insideIf && !isDynamicTask) {
                // Error on the use of value inside the if of a regular task (dyn task is ok)
                lint(ap.pos, TaskLinterDSLFeedback.useOfValueInsideIfExpression(qualName))
              }
              if (insideAnon) {
                // Error on the use of anonymous functions in any task or dynamic task
                lint(ap.pos, TaskLinterDSLFeedback.useOfValueInsideAnon(qualName))
              }
            }
            traverse(qual)
          case If(condition, thenp, elsep) =>
            traverse(condition)
            val newTraverser = new traverser(insideIf = true, insideAnon, uncheckedWrapper)
            newTraverser.traverse(thenp)
            newTraverser.traverse(elsep)
          case Typed(expr, tpt: TypeTree) if tpt.original != null =>
            new traverser(insideIf, insideAnon, extractUncheckedWrapper(expr, tpt)).traverse(expr)
            traverse(tpt)
          case Function(vparams, body) =>
            traverseTrees(vparams)
            if (!vparams.exists(_.mods.hasFlag(Flag.SYNTHETIC))) {
              new traverser(insideIf, insideAnon = true, uncheckedWrapper).traverse(body)
            } else traverse(body)
          case Block(stmts, expr) =>
            if (!isDynamicTask) {
              /* The missing .value analysis is dumb on purpose because it's expensive.
 * Detecting valid use cases of idents whose type is an sbt key is difficult
 * and dangerous because we may miss some corner cases. Instead, we report
 * on the easiest cases in which we are certain that the user does not want
 * to have a stale key reference. Those are idents in the rhs of a val definition
 * whose name is `_` and those idents that are in statement position inside blocks.
 */
              stmts.foreach {
                // TODO: Consider using unused names analysis to be able to report on more cases
                case ValDef(_, valName, _, rhs) if valName == termNames.WILDCARD =>
                  rhs match {
                    case i: Ident  => detectAndErrorOnKeyMissingValue(i)
                    case s: Select => detectAndErrorOnKeyMissingValue(s)
                    case a: Apply  => detectAndErrorOnKeyMissingValue(a)
                    case _         => ()
                  }
                case i: Ident  => detectAndErrorOnKeyMissingValue(i)
                case s: Select => detectAndErrorOnKeyMissingValue(s)
                case a: Apply  => detectAndErrorOnKeyMissingValue(a)
                case _         => ()
              }
            }
            traverseTrees(stmts)
            traverse(expr)
          case _ => super.traverse(tree)
        }
      }
    }
    new traverser(insideIf = false, insideAnon = false, uncheckedWrapper = None).traverse(tree)
  }

  override def runLinter(ctx: blackbox.Context)(tree: ctx.Tree): Unit = {
    import ctx.universe._
    ctx.inferImplicitValue(weakTypeOf[LinterLevel]) match {
      case t if t.tpe =:= weakTypeOf[Abort.type] =>
        impl(ctx)(tree, (pos, msg) => ctx.error(pos, msg))
      case t if t.tpe =:= weakTypeOf[Warn.type] =>
        impl(ctx)(tree, (pos, msg) => ctx.warning(pos, msg))
      case _ => ()
    }
  }
}

object TaskLinterDSL extends BaseTaskLinterDSL {
  override val isDynamicTask: Boolean = false
  override def convert: Convert = FullConvert
}

object OnlyTaskLinterDSL extends BaseTaskLinterDSL {
  override val isDynamicTask: Boolean = false
  override def convert: Convert = TaskConvert
}

object TaskDynLinterDSL extends BaseTaskLinterDSL {
  override val isDynamicTask: Boolean = true
  override def convert: Convert = FullConvert
}

object OnlyTaskDynLinterDSL extends BaseTaskLinterDSL {
  override val isDynamicTask: Boolean = true
  override def convert: Convert = TaskConvert
}

object TaskLinterDSLFeedback {
  private final val startBold = if (Terminal.isColorEnabled) AnsiColor.BOLD else ""
  private final val startRed = if (Terminal.isColorEnabled) AnsiColor.RED else ""
  private final val startGreen = if (Terminal.isColorEnabled) AnsiColor.GREEN else ""
  private final val reset = if (Terminal.isColorEnabled) AnsiColor.RESET else ""

  private final val ProblemHeader = s"${startRed}problem$reset"
  private final val SolutionHeader = s"${startGreen}solution$reset"

  def useOfValueInsideAnon(task: String): String =
    s"""${startBold}The evaluation of `$task` inside an anonymous function is prohibited.$reset
       |
       |$ProblemHeader: Task invocations inside anonymous functions are evaluated independently of whether the anonymous function is invoked or not.
       |$SolutionHeader:
       |  1. Make `$task` evaluation explicit outside of the function body if you don't care about its evaluation.
       |  2. Use a dynamic task to evaluate `$task` and pass that value as a parameter to an anonymous function.
       |  3. Annotate the `$task` evaluation with `@sbtUnchecked`, e.g. `($task.value: @sbtUnchecked)`.
       |  4. Add `import sbt.dsl.LinterLevel.Ignore` to your build file to disable all task linting.
    """.stripMargin

  def useOfValueInsideIfExpression(task: String): String =
    s"""${startBold}value lookup of `$task` inside an `if` expression$reset
       |
       |$ProblemHeader: `$task.value` is inside an `if` expression of a regular task.
       |  Regular tasks always evaluate task dependencies (`.value`) regardless of `if` expressions.
       |$SolutionHeader:
       |  1. Use a conditional task `Def.taskIf(...)` to evaluate it when the `if` predicate is true or false.
       |  2. Or turn the task body into a single `if` expression; the task is then auto-converted to a conditional task.
       |  3. Or make the static evaluation explicit by declaring `$task.value` outside the `if` expression.
       |  4. If you still want to force the static lookup, you may annotate the task lookup with `@sbtUnchecked`, e.g. `($task.value: @sbtUnchecked)`.
       |  5. Add `import sbt.dsl.LinterLevel.Ignore` to your build file to disable all task linting.
    """.stripMargin

  def missingValueForKey(key: String): String =
    s"""${startBold}The key `$key` is not being invoked inside the task definition.$reset
       |
       |$ProblemHeader:  Keys missing `.value` are not initialized and their dependency is not registered.
       |$SolutionHeader:
       |  1. Replace `$key` by `$key.value` or remove it if unused
       |  2. Add `import sbt.dsl.LinterLevel.Ignore` to your build file to disable all task linting.
    """.stripMargin

  def missingValueForInitialize(expr: String): String =
    s"""${startBold}The setting/task `$expr` is not being invoked inside the task definition.$reset
       |
       |$ProblemHeader:  Settings/tasks missing `.value` are not initialized and their dependency is not registered.
       |$SolutionHeader:
       |  1. Replace `$expr` by `($expr).value` or remove it if unused.
       |  2. Add `import sbt.dsl.LinterLevel.Ignore` to your build file to disable all task linting.
    """.stripMargin
}
 */
