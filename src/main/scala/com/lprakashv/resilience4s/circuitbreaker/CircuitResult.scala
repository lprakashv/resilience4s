package com.lprakashv.resilience4s.circuitbreaker

import com.lprakashv.resilience4s.circuitbreaker.CircuitResult.{
  CircuitFailure,
  CircuitSuccess
}

trait CircuitResult[T] extends IterableOnce[T] with Product with Serializable {
  def isFailed: Boolean = this.toEither.isLeft

  def isSuccess: Boolean = this.toEither.isRight

  def toOption: Option[T] = this.toEither.toOption

  def toEither: Either[Throwable, T] = this match {
    case CircuitSuccess(value)     => Right(value)
    case CircuitFailure(exception) => Left(exception)
  }
}

object CircuitResult {
  case class CircuitSuccess[T](value: T) extends CircuitResult[T] {
    override def iterator: Iterator[T] = Iterator.apply(value)
  }
  case class CircuitFailure[T](exception: Throwable) extends CircuitResult[T] {
    override def iterator: Iterator[T] = Iterator.empty[T]
  }
}
