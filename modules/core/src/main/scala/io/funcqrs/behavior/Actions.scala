package io.funcqrs.behavior

import io.funcqrs.interpreters._
import io.funcqrs._

import scala.collection.immutable
import scala.concurrent.Future
import scala.language.higherKinds
import scala.reflect.ClassTag
import scala.util.{ Failure, Try }

case class Actions[A <: AggregateLike](
    cmdHandlerInvokers: CommandToInvoker[A#Command, A#Event] = PartialFunction.empty,
    rejectCmdInvokers: CommandToInvoker[A#Command, A#Event]  = PartialFunction.empty,
    eventHandlers: EventHandler[A#Event, A]                  = PartialFunction.empty
) extends AggregateAliases {

  type Aggregate = A

  /**
    * All command handlers together.
    * First reject handlers, then normal command handlers
    */
  private val allHandlers = rejectCmdInvokers orElse cmdHandlerInvokers

  /**
    * Returns a [[CommandHandlerInvoker]] for the passed [[Command]]. Invokers are delayed execution
    * of `Command Handlers` and abstract over the Functor that will be returned when handling the command.
    *
    * Internally, this method calls the declared `Command Handlers`.
    *
    */
  def onCommand(cmd: Command): CommandHandlerInvoker[Command, Event] = {
    if (allHandlers.isDefinedAt(cmd))
      allHandlers(cmd)
    else {
      val cmdHandler: PartialFunction[Command, Try[Events]] = {
        case _ =>
          val msg = s"No command handlers defined for command: $cmd"
          Failure(new MissingCommandHandlerException(msg))
      }
      // return a fallback invoker if not define
      TryCommandHandlerInvoker(cmdHandler)
    }
  }

  /**
    * Applies the passed [[Event]] producing a new instance of [[Aggregate]].
    * Internally, this method calls the declared `Event Handlers`.
    *
    * @throws MissingEventHandlerException if no Event handler is defined for the passed event.
    */
  def onEvent(evt: Event): Aggregate = {
    if (eventHandlers.isDefinedAt(evt))
      eventHandlers(evt)
    else
      throw new MissingEventHandlerException(s"No event handlers defined for events: $evt")
  }

  /**
    * Concatenate `this` Actions with `that` Actions
    */
  def ++(that: Actions[A]) = {
    this.copy(
      cmdHandlerInvokers = this.cmdHandlerInvokers orElse that.cmdHandlerInvokers,
      rejectCmdInvokers  = this.rejectCmdInvokers orElse that.rejectCmdInvokers,
      eventHandlers      = this.eventHandlers orElse that.eventHandlers
    )
  }

  /**
    * Declares a guard clause that reject commands that fulfill a given condition.
    *
    * A guard clause is a `Command Handler` as it handles a incoming command,
    * but instead of producing [[Event]], it returns a [[Throwable]] to signalize an error condition.
    *
    * Guard clauses command handlers have precedence over handlers producing [[Event]]s.
    *
    * @param cmdHandler - a PartialFunction from [[Command]] to [[Throwable]].
    * @return - return a [[Actions]].
    */
  def reject(cmdHandler: PartialFunction[A#Command, Throwable]): Actions[Aggregate] = {

    val invokerPF: CommandToInvoker[A#Command, A#Event] = {
      case cmd if cmdHandler.isDefinedAt(cmd) =>
        TryCommandHandlerInvoker(cmd => Failure(cmdHandler(cmd)))
    }

    this.copy(
      rejectCmdInvokers = rejectCmdInvokers orElse invokerPF
    )
  }

  /** Alias for reject */
  def rejectCommand(cmdHandler: PartialFunction[Command, Throwable]): Actions[Aggregate] = reject(cmdHandler)

  /** Declares a `Command Handler` that produces one single [[Event]] */
  def handleCommand[C <: Command, E <: Event](cmdHandler: PartialFunction[C, Identity[E]]): Actions[Aggregate] = {
    // wrap single event in immutable.Seq
//    val handlerWithSeq: C => Identity[immutable.Seq[E]] = (cmd: C) => immutable.Seq(cmdHandler(cmd))
    val handlerWithSeq: PartialFunction[C, Identity[immutable.Seq[E]]] = {
      case cmd if cmdHandler.isDefinedAt(cmd) => immutable.Seq(cmdHandler(cmd))
  }

    handleCommand[C, E, immutable.Seq](handlerWithSeq)
  }

//  def handleCommand[C <: Command: ClassTag, E <: Event, F[_]](cmdHandler: C => F[E])(
//      implicit ivk: InvokerDirective[F]): Actions[Aggregate] =
//    addInvoker(ivk.newInvoker(cmdHandler))


  // what is command invoker?

  def handleCommand[C <: Command, E <: Event, F[_]](cmdHandler: PartialFunction[C, F[E]])
                                                   (implicit ivk: InvokerDirective[F]): Actions[Aggregate] ={
    val pf: CommandToInvoker[C,E] = {
      case cmd if cmdHandler.isDefinedAt(cmd) => ivk.newInvoker(cmdHandler)
    }
    addInvokerPf { pf }

  }

//  def handleCommand[C <: Command, E <: Event, F[_]](cmdHandler: C => F[immutable.Seq[E]])(
//      implicit ivk: InvokerSeqDirective[F]): Actions[Aggregate] =
//    addInvoker(ivk.newInvoker(cmdHandler))
//  ???

//  def handleCommand[C <: Command, E <: Event, F[_]](cmdHandler: C => F[List[E]])(
//      implicit ivk: InvokerListDirective[F]): Actions[Aggregate] = ???
//    addInvoker(ivk.newInvoker(cmdHandler))
//CommandToInvoker[A#Command, A#Event]
  private def addInvokerPf[C <: Command, E <: Event](pf: CommandToInvoker[C,E]): Actions[Aggregate] = {
    this.copy(cmdHandlerInvokers = cmdHandlerInvokers orElse pf.asInstanceOf[CommandToInvoker[A#Command, A#Event]])
  }


  /**
    * Declares an event handler
    *
    * @param eventHandler - the event handler function
    * @return an Actions for A
    */
  def handleEvent[E <: Event: ClassTag](eventHandler: E => A): Actions[Aggregate] = {

    object EvtExtractor extends ClassTagExtractor[E]

    val eventHandlerPF: EventHandler[A#Event, A] = {
      case EvtExtractor(evt) => eventHandler(evt)
    }
    this.copy(eventHandlers = eventHandlers orElse eventHandlerPF)
  }

}

object Actions {

  def apply[A <: AggregateLike]: Actions[A] = Actions[A]()
  def empty[A <: AggregateLike]: Actions[A] = apply

}
