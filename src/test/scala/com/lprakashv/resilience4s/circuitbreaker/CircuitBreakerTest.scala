package com.lprakashv.resilience4s.circuitbreaker

import java.util.logging.Logger

import org.scalatest.FunSuite

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}

class CircuitBreakerTest extends FunSuite {
  //TODO: - make this FuncSpec based test
  //TODO: - improve coverage
  //TODO: - small unit tests first, try to make as few composites as possible.

  import com.lprakashv.resilience4s.circuitbreaker.CircuitImplicits._

  test("test successes with CircuitImplicits") {
    implicit val sampleCircuit: Circuit[Int] =
      new Circuit[Int]("sample-circuit", 5, 5.seconds, 1, -1, Logger.getGlobal)

    // to test concurrent executions
    val resultsF: Future[IndexedSeq[CircuitResult[Int]]] =
      Future.sequence((1 to 500).map(_ => (2 + 2).executeAsync))

    val results: Seq[CircuitResult[Int]] =
      Await.result(resultsF, 10.minutes).toList

    assert((1 * 34 / 3).execute.isSuccess)

    assert(results.flatten.count(_ == 4) == 500)
  }

  test(
    "[on continuous invalid execution] " +
      "failures 5 times"
  ) {
    implicit val sampleCircuit: Circuit[Int] =
      new Circuit[Int]("sample-circuit", 5, 5.seconds, 1, -1, Logger.getGlobal)

    val resultsF: Future[IndexedSeq[CircuitResult[Int]]] = Future.sequence(for {
      _ <- 1 to 5
    } yield (1 / 0).executeAsync)

    val results: Seq[CircuitResult[Int]] =
      Await.result(resultsF, 1.minutes).toList

    assert(results.forall(_.isFailed), s"got $results")
  }

  test(
    "[on continuous invalid execution different circuits] " +
      "failures 5 times"
  ) {
    implicit val sampleIntCircuit: Circuit[Int] =
      new Circuit[Int](
        "sample-int-circuit",
        5,
        5.seconds,
        1,
        -1,
        Logger.getGlobal
      )

    implicit val sampleStringCircuit: Circuit[String] =
      new Circuit[String](
        "sample-str-circuit",
        5,
        5.seconds,
        1,
        "default",
        Logger.getGlobal
      )

    val resultsF: Future[IndexedSeq[CircuitResult[Int]]] = Future.sequence(for {
      _ <- 1 to 5
    } yield (1 / 0).executeAsync)

    val results: Seq[CircuitResult[Int]] =
      Await.result(resultsF, 1.minutes).toList

    assert(results.forall(_.isFailed), s"got $results")
  }

  test(
    "[on continuous invalid execution] " +
      "success with default answer after failure 5 times"
  ) {
    implicit val sampleCircuit: Circuit[Int] =
      new Circuit[Int]("sample-circuit", 5, 5.seconds, 1, -1, Logger.getGlobal)

    val resultsF: Future[IndexedSeq[CircuitResult[Int]]] =
      Future.sequence((1 to 12).map(_ => (1 / 0).executeAsync))

    val results: Seq[CircuitResult[Int]] =
      Await.result(resultsF, 1.minutes).toList

    assert(
      results.flatten.count(_ == -1) == 7,
      "failed to verify 7 successes (default case in open circuit) after 5 failures on 12 invalid executions"
    )
  }

  test(
    "[on continuous invalid execution] " +
      "failure 5 times and then successes then failure after timeout (5 sec) and then successes again"
  ) {
    implicit val sampleCircuit: Circuit[Int] =
      new Circuit[Int]("sample-circuit", 5, 5.seconds, 1, -1, Logger.getGlobal)

    // 5 failures and then 5 successes
    val resultsF: Future[IndexedSeq[CircuitResult[Int]]] =
      Future.sequence((1 to 10).map(_ => (1 / 0).executeAsync))
    val results: Seq[CircuitResult[Int]] = Await.result(resultsF, 10.minutes)

    assert(
      results.flatten.count(_ == -1) == 5,
      "failed to verify - 5 failures and 5 successes for 10 invalid executions"
    )

    Thread.sleep(5100)

    assert(
      (1 / 0).execute.isFailed,
      "failed to verify failure after timeout (showing half-open try)"
    )

    assert(
      (1 / 0).execute.toOption.contains(-1),
      "failed to verify success showing open (default case) after half-open failure"
    )

    Thread.sleep(5200)

    val resultsF2: Future[IndexedSeq[CircuitResult[Int]]] =
      Future.sequence((1 to 5).map(_ => (7 * 7).executeAsync))
    val results2: Seq[CircuitResult[Int]] = Await.result(resultsF2, 10.minutes)

    assert(
      results2.flatten.forall(_ == 49),
      "failed to verify successes with valid results after timeout showing closed after half-open success"
    )
  }

  test(
    "failure 5 times and then default successes even after having valid execution"
  ) {
    implicit val sampleCircuit: Circuit[Int] =
      new Circuit[Int]("sample-circuit", 5, 5.seconds, 1, -1, Logger.getGlobal)

    Await.result(
      Future.sequence((1 to 5).map(_ => (1 / 0).executeAsync)),
      10.minutes
    )

    assert(
      (1 / 0).execute.toOption.contains(-1),
      "failed to validate circuit failure after reaching failure threshold"
    )

    val resultsF: Future[IndexedSeq[CircuitResult[Int]]] =
      Future.sequence((1 to 5).map(_ => Future.successful(7 * 7).executeAsync))
    val results: Seq[CircuitResult[Int]] = Await.result(resultsF, 10.minutes)

    assert(results.flatten.forall(_ == -1))
  }
}
