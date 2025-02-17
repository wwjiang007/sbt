commandProgress += new ExecuteProgress2:
  override def beforeCommand(cmd: String, state: State): Unit = {
    EventLog.logs += (s"BEFORE: $cmd")
    // assert that `state` is the current state indeed
    assert(state.currentCommand.isDefined)
    assert(state.currentCommand.get.commandLine == cmd)
  }
  override def afterCommand(cmd: String, result: Either[Throwable, State]): Unit = {
    EventLog.logs += (s"AFTER: $cmd")
    result.left.foreach(EventLog.errors += _)
  }
  override def initial(): Unit = {}
  override def afterRegistered(
      task: TaskId[?],
      allDeps: Iterable[TaskId[?]],
      pendingDeps: Iterable[TaskId[?]]
  ): Unit = ()
  override def afterReady(task: TaskId[_]): Unit = ()
  override def beforeWork(task: TaskId[_]): Unit = ()
  override def afterWork[A](task: TaskId[A], result: Either[TaskId[A], Result[A]]): Unit = ()
  override def afterCompleted[A](task: TaskId[A], result: Result[A]): Unit = ()
  override def afterAllCompleted(results: RMap[TaskId, Result]): Unit = ()
  override def stop(): Unit = ()

val check = taskKey[Unit]("Check basic command events")
val checkParseError = taskKey[Unit]("Check that parse error is recorded")

check := {
  def hasEvent(cmd: String): Boolean =
    EventLog.logs.contains(s"BEFORE: $cmd") && EventLog.logs.contains(s"AFTER: $cmd")

  assert(hasEvent("compile"), "Missing command event `compile`")
  assert(hasEvent("+compile"), "Missing command event `+compile`")
}

checkParseError := {
  assert(EventLog.errors.exists(_.getMessage.toLowerCase.contains("not a valid command")))
}
