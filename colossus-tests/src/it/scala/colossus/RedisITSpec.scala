package colossus

import java.net.InetSocketAddress

import akka.util.ByteString
import colossus.protocols.redis._
import colossus.service.{AsyncServiceClient, ClientConfig}
import colossus.testkit.ColossusSpec
import org.scalatest.concurrent.ScalaFutures

import scala.collection.mutable
import scala.concurrent.Future
import scala.concurrent.duration._
/*
Please be aware when running this test, that if you run it on a machine with a redis server
running on 6379, it will begin to interact with it.  Be mindful if you are running this on a server
which has data you care about.
This test runs by hitting the REST endpoints exposed by teh TestRedisServer, which just proxies directly to
a Redis client, which communicates with redis
 */
class RedisITSpec extends ColossusSpec with ScalaFutures{

  import scala.concurrent.ExecutionContext.Implicits.global

  implicit val sys = IOSystem("test-system", 2)

  type AsyncRedisClient = AsyncServiceClient[Command, Reply]

  val client = RedisClient.asyncClient(ClientConfig(new InetSocketAddress("localhost", 6379), 1.second, "redis"))

  val usedKeys = scala.collection.mutable.HashSet[ByteString]()

  override def afterAll() {
    val f: Future[mutable.HashSet[Long]] = Future.sequence(usedKeys.map(client.del))
    f.futureValue must be (mutable.HashSet(1, 0)) //some keys are deleted by the tests.
    super.afterAll()
  }

  "Redis Client" should {

    "del" in {
      val delKey = getKey("colITDel")
      client.set(delKey, ByteString("value")).futureValue must be (true)
      client.del(delKey).futureValue must be (1)
    }

    "existence" in {
      val exKey = getKey("colITEx")
      client.exists(exKey).futureValue must be (false)
      client.set(exKey, ByteString("value")).futureValue must be (true)
      client.exists(exKey).futureValue must be (true)
    }

    "set && get" in {
      val setKey = getKey("colITSet")
      client.set(setKey, ByteString("value")).futureValue must be (true)
      client.get(setKey).futureValue must be (ByteString("value"))
    }

    "setnx" in {
      val setnxKey = getKey("colITSetnx")
      client.setnx(setnxKey, ByteString("value")).futureValue must be (true)
      client.get(setnxKey).futureValue must be (ByteString("value"))
      client.setnx(setnxKey, ByteString("value")).futureValue must be (false)
    }

    "setex && ttl" in {
      val setexKey = getKey("colITSetex")
      client.setex(setexKey, ByteString("value"), 10.seconds).futureValue must be (true)
      client.get(setexKey).futureValue must be (ByteString("value"))
      client.ttl(setexKey).futureValue must be (Some(10))
    }

    "strlen" in {
      val strlenKey = getKey("colITStrlen")
      client.set(strlenKey, ByteString("value"))
      client.strlen(strlenKey).futureValue must be (Some(5))
    }
  }

  def getKey(key: String): ByteString = {
    val bKey = ByteString(key)
    usedKeys += bKey
    bKey
  }
}
