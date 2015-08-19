package colossus
package protocols

import akka.util.{ByteString, ByteStringBuilder}
import colossus.core.WorkerRef
import colossus.parsing.DataSize
import colossus.protocols.redis.Commands._
import colossus.service.Codec._
import colossus.service._

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.FiniteDuration
import scala.language.higherKinds

package object redis {

  trait Redis extends CodecDSL {
    type Input = Command
    type Output = Reply
  }

  implicit object RedisCodecProvider extends CodecProvider[Redis] {
    def provideCodec() = new RedisServerCodec

    def errorResponse(request: Command, reason: Throwable) = ErrorReply(s"Error (${reason.getClass.getName}): ${reason.getMessage}")
  }

  implicit object RedisClientCodecProvider extends ClientCodecProvider[Redis] {
    def clientCodec() = new RedisClientCodec
    val name = "redis"
  }
  
  trait RedisClient[M[_]] {
    this: ResponseAdapter[Redis, M] =>

    def del(key : ByteString) : M[Long] = {
      executeAndMap(Del(key)){
        case IntegerReply(x) => success(x)
        case x => failure(UnexpectedRedisReplyException(s"unexpected response $x when deleting $key"))
      }
    }
    def exists(key : ByteString) : M[Boolean] = {
      executeAndMap(Exists(key)) {
        case IntegerReply(x) => success(x == 1)
        case x => failure(UnexpectedRedisReplyException(s"unexpected response $x when checking for existence of $key"))
      }
    }
    def get(key : ByteString) : M[ByteString] = {
      executeAndMap(Get(key)){
        case BulkReply(x) => success(x)
        case x => failure(UnexpectedRedisReplyException(s"unexpected response $x when getting $key"))
      }
    }

    def set(key : ByteString, value : ByteString) : M[Boolean] = {
      executeAndMap(Set(key, value)){
        case StatusReply(x) => success(true)
        case x => failure(UnexpectedRedisReplyException(s"unexpected response $x when setting $key and $value"))
      }
    }
    def setnx(key : ByteString, value : ByteString) : M[Boolean] = {
      executeAndMap(Setnx(key, value)){
        case IntegerReply(x) => success(x == 1)
        case x => failure(UnexpectedRedisReplyException(s"unexpected response $x when setnx $key and $value"))
      }
    }
    def setex(key : ByteString, value : ByteString, ttl : FiniteDuration) : M[Boolean] = {
      executeAndMap(Setex(key, value, ttl)){
        case StatusReply(x) => success(true)
        case x => failure(UnexpectedRedisReplyException(s"unexpected response $x when settex $key and  $value with $ttl"))
      }
    }
    def strlen(key : ByteString) : M[Option[Long]] = {
      executeAndMap(Strlen(key)){
        case IntegerReply(x) if x > 0 => success(Some(x))
        case IntegerReply(x) => success(None)
        case x => failure(UnexpectedRedisReplyException(s"unexpected response $x when strlen $key"))
      }
    }
    def ttl(key : ByteString) : M[Option[Long]] = {
      executeAndMap(Ttl(key)){
        case IntegerReply(x) => success(Some(x))
        case StatusReply(x) => success(None)
        case x => failure(UnexpectedRedisReplyException(s"unexpected response $x when ttl $key"))
      }
    }
  }


  class RedisCallbackClient(val client : ServiceClient[Command, Reply])
    extends RedisClient[Callback] with CallbackResponseAdapter[Redis]


  class RedisFutureClient(val client : AsyncServiceClient[Command, Reply])
                          (implicit val executionContext : ExecutionContext)
    extends RedisClient[Future] with FutureResponseAdapter[Redis]

  case class UnexpectedRedisReplyException(message : String) extends Exception


  object RedisClient {

    def callbackClient(config: ClientConfig, worker: WorkerRef, maxSize : DataSize = RedisReplyParser.DefaultMaxSize) : RedisClient[Callback] = {
      val serviceClient = new ServiceClient[Command, Reply](new RedisClientCodec(maxSize), config, worker)
      new RedisCallbackClient(serviceClient)
    }
    def asyncClient(config : ClientConfig, maxSize : DataSize = RedisReplyParser.DefaultMaxSize)(implicit io : IOSystem) : RedisClient[Future] = {
      implicit val ec = io.actorSystem.dispatcher
      val client = AsyncServiceClient(config, new RedisClientCodec(maxSize))
      new RedisFutureClient(client)
    }
  }

  implicit object RedisServerCodecFactory extends ServerCodecFactory[Command, Reply] {
    def apply() = new RedisServerCodec
  }
  implicit object RedisClientCodecFactory extends ClientCodecFactory[Command, Reply] {
    def apply() = new RedisClientCodec
  }

  object UnifiedBuilder {

    import UnifiedProtocol._

    def buildArg(data: ByteString, builder: ByteStringBuilder) {
      builder append ARG_LEN
      builder append ByteString(data.size.toString)
      builder append RN
      builder append data
      builder append RN
    }
  }
}

