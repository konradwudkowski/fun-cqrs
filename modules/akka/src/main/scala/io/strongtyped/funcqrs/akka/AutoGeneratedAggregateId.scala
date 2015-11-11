package io.strongtyped.funcqrs.akka

import io.strongtyped.funcqrs.{AggregateTypes, AggregateDef}

trait AutoGeneratedAggregateId[Aggregate <: AggregateDef] extends AggregateTypes[Aggregate] {
  this: AggregateManager[Aggregate] =>

  def generateId(): Id

  override def processCreation: Receive = {
    case cmd: Command => processAggregateCommand(generateId(), cmd)
  }
}
