# resilience4s
Scala toolset for microservice resilience patterns, inspired from resilience4j Java library.

#### Examples
* Circuit => A circuit specification for circuit-breaker design pattern.
  
  Usage:
  
  
```scala
import scala.concurrent.Future
import com.lprakashv.resilience4s.circuitbreaker._
import scala.concurrent.duration._
import java.util.logging.Logger
import scala.util.Random

val myCircuit = new Circuit[Int](
  "sample-circuit", 
   5, 
   5.seconds,
   1, 
   -1,
   Logger.getGlobal
)

def doThingAndReturnInt: Int = ??? //method to wrap

def doThingAndReturnIntF: Future[Int] = ??? //async method to wrap

myCircuit.execute(doThingAndReturnInt)

myCircuit.executeAsync(doThingAndReturnIntF)

myCircuit.execute {
  val x = 23
  //.. do something here
  ???
  val y = Random.between(1, 1000)
  x / y
}

//others methods like
import com.lprakashv.resilience4s.circuitbreaker.CircuitImplicits._

implicit val circuit: Circuit[String] = ???

def f: String = ???
def ff: Future[String] = ???

f.execute

ff.executeAsync
```