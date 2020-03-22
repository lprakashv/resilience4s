package com.lprakashv.resilience4s.circuitbreaker

import scala.concurrent.{ExecutionContext, Future}

object CircuitImplicits {

  implicit class BlockExtensions[R](block: => R)(implicit c: Circuit[R]) {
    def execute: CircuitResult[R] = c.execute(block)

    def executeAsync(implicit ex: ExecutionContext): Future[CircuitResult[R]] =
      Future {
        c.execute(block)
      }
  }

  implicit class AsyncBlockExtensions[R](block: => Future[R])(
    implicit c: Circuit[R]
  ) {

    def executeAsync(implicit ex: ExecutionContext): Future[CircuitResult[R]] =
      c.executeAsync(block)
  }

}
