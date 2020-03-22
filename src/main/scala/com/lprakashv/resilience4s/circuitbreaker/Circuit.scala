package com.lprakashv.resilience4s.circuitbreaker

import java.util.concurrent.atomic.{AtomicInteger, AtomicLong, AtomicReference}
import java.util.logging.Logger
import java.util.{Date, Timer, TimerTask}

import com.lprakashv.resilience4s.circuitbreaker.Circuit.InvalidEvalCircuitException
import com.lprakashv.resilience4s.circuitbreaker.CircuitResult.{
  CircuitFailure,
  CircuitSuccess
}
import com.lprakashv.resilience4s.circuitbreaker.CircuitState.{
  Closed,
  HalfOpen,
  Open
}

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

class Circuit[R](name: String,
                 private final val threshold: Int,
                 private final val timeout: Duration,
                 private final val maxAllowedHalfOpen: Int,
                 defaultAction: => R,
                 private val logger: Logger,
                 failedWhen: R => Boolean = { _: R =>
                   false
                 },
                 ignoreException: Throwable => Boolean = { _: Throwable =>
                   false
                 })(implicit ec: ExecutionContext) {
  private def circuitLogger(msg: String): Unit =
    logger.info(s"[$name] [${new Date()}] $msg")

  private val closedConsecutiveFailureCount = new AtomicInteger(0)
  private val lastOpenTime = new AtomicLong(Long.MaxValue)
  private val state = new AtomicReference[CircuitState](CircuitState.Closed)
  private val halfOpenConsecutiveFailuresCount = new AtomicInteger(0)

  new Timer(s"$name-circuit-opener").scheduleAtFixedRate(
    new TimerTask {
      override def run(): Unit = state.get() match {
        case Open
            if (System
              .currentTimeMillis() - lastOpenTime.get() > timeout.toMillis) =>
          circuitLogger("Max open timeout reached.")
          halfOpenCircuit()
        case _ => ()
      }
    },
    0L,
    10L
  )

  def getState: CircuitState = state.get()

  def execute(block: => R): CircuitResult[R] = {
    state.get() match {
      case Closed   => handleClosed(block)
      case HalfOpen => handleHalfOpen(block)
      case Open     => handleOpen
    }
  }

  def executeAsync(block: => Future[R]): Future[CircuitResult[R]] = {
    state.get() match {
      case Closed   => handleClosedAsync(block)
      case HalfOpen => handleHalfOpenAsync(block)
      case Open     => Future.successful(handleOpen)
    }
  }

  private def openCircuit(): Unit = {
    circuitLogger("Opening circuit...")
    state.set(Open)
    lastOpenTime.set(System.currentTimeMillis())
    circuitLogger("Circuit is open.")
  }

  private def closeCircuit(): Unit = {
    circuitLogger("Closing circuit...")
    state.set(Closed)
    lastOpenTime.set(Long.MaxValue)
    closedConsecutiveFailureCount.set(0)
    circuitLogger("Circuit is closed.")
  }

  private def halfOpenCircuit(): Unit = {
    circuitLogger("Half-opening circuit...")
    state.set(HalfOpen)
    halfOpenConsecutiveFailuresCount.set(0)
    circuitLogger("Circuit is half-open.")
  }

  private def handleClosed(block: => R): CircuitResult[R] = {
    Try(block) match {
      case Failure(exception) =>
        handleFailure(
          block,
          Left(exception),
          closedConsecutiveFailureCount,
          threshold
        )
      case Success(value) if failedWhen(value) =>
        handleFailure(
          block,
          Right(value),
          closedConsecutiveFailureCount,
          threshold
        )
      case Success(value) =>
        closedConsecutiveFailureCount.set(0)
        CircuitSuccess(value)
    }
  }

  private def handleHalfOpen(block: => R): CircuitResult[R] = {
    Try(block) match {
      case Failure(exception) =>
        handleFailure(
          block,
          Left(exception),
          halfOpenConsecutiveFailuresCount,
          maxAllowedHalfOpen
        )
      case Success(value) if failedWhen(value) =>
        handleFailure(
          block,
          Right(value),
          halfOpenConsecutiveFailuresCount,
          maxAllowedHalfOpen
        )
      case Success(value) =>
        closeCircuit()
        CircuitSuccess(value)
    }
  }

  private def handleClosedAsync(
    block: => Future[R]
  ): Future[CircuitResult[R]] = {
    block
      .flatMap { value =>
        if (failedWhen(value)) {
          handleFailureAsync(
            Future.successful(value),
            None,
            closedConsecutiveFailureCount,
            threshold
          )
        } else {
          closedConsecutiveFailureCount.set(0)
          Future.successful(CircuitSuccess(value))
        }
      }(ec)
      .recoverWith {
        case exception =>
          handleFailureAsync(
            block,
            Some(exception),
            closedConsecutiveFailureCount,
            threshold
          )
      }
  }

  private def handleHalfOpenAsync(
    block: => Future[R]
  ): Future[CircuitResult[R]] = {
    block
      .flatMap { value =>
        if (failedWhen(value)) {
          handleFailureAsync(
            Future.successful(value),
            None,
            halfOpenConsecutiveFailuresCount,
            maxAllowedHalfOpen
          )
        } else {
          closeCircuit()
          Future.successful(CircuitSuccess(value))
        }
      }(ec)
      .recoverWith {
        case exception =>
          handleFailureAsync(
            block,
            Some(exception),
            halfOpenConsecutiveFailuresCount,
            maxAllowedHalfOpen
          )
      }
  }

  private def handleOpen: CircuitResult[R] = {
    CircuitSuccess(defaultAction)
  }

  private def handleFailure(block: => R,
                            throwableOrValue: Either[Throwable, R],
                            atomicCounter: AtomicInteger,
                            maxFailures: Int): CircuitResult[R] = {
    val currentFailureCount = atomicCounter.incrementAndGet()
    circuitLogger(
      s"[${state.get()}-error-count = $atomicCounter] $throwableOrValue"
    )
    if (currentFailureCount > maxFailures) {
      openCircuit()
      execute(block)
    } else
      throwableOrValue match {
        case Left(exception) if ignoreException(exception) =>
          openCircuit()
          execute(block)
        case Left(exception) => CircuitFailure(exception)
        case Right(value) =>
          CircuitFailure(InvalidEvalCircuitException(name, value))
      }
  }

  private def handleFailureAsync(block: => Future[R],
                                 maybeException: Option[Throwable],
                                 atomicCounter: AtomicInteger,
                                 maxFailures: Int): Future[CircuitResult[R]] = {
    val currentFailureCount = atomicCounter.incrementAndGet()
    circuitLogger(
      s"[${state.get()}-error-count = $atomicCounter] $maybeException"
    )
    if (currentFailureCount > maxFailures) {
      openCircuit()
      executeAsync(block)
    } else
      maybeException match {
        case Some(exception) if ignoreException(exception) =>
          Future.successful(CircuitSuccess(defaultAction))
        case Some(exception) => Future.successful(CircuitFailure(exception))
        case None =>
          Future.successful(
            CircuitFailure(InvalidEvalCircuitException(name, block))
          )
      }
  }
}

object Circuit {
  case class InvalidEvalCircuitException[R](name: String, evaluated: R)
      extends Exception(s"[$name-invalid-evaluation] value = $evaluated")
}
