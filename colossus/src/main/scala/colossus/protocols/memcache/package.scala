package colossus
package protocols

import akka.util.ByteString
import colossus.core.WorkerRef
import colossus.parsing.DataSize
import colossus.protocols.memcache.MemcacheCommand._
import colossus.protocols.memcache.MemcacheReply._
import colossus.protocols.memcache._
import service._

import scala.language.higherKinds

import scala.concurrent.{ExecutionContext, Future}

package object memcache {

  trait Memcache extends CodecDSL {
    type Input = MemcacheCommand
    type Output = MemcacheReply
  }

  implicit object MemcacheClientProvider extends ClientCodecProvider[Memcache] {
    def clientCodec = new MemcacheClientCodec
    def name = "memcache"
  }

}

trait ResponseAdapter[C <: CodecDSL, M[_]] {

  def executeAndMap[T](i : C#Input)(f : C#Output => M[T]) = flatMap(execute(i))(f)

  def execute(i : C#Input) : M[C#Output]

  def flatMap[T](t : M[C#Output])(f : C#Output => M[T]) : M[T]

  def success[T](t : T) : M[T]

  def failure[T](ex : Throwable) : M[T]
}

trait CallbackResponseAdapter[C <: CodecDSL] extends ResponseAdapter[C, Callback] {

  def client : ServiceClient[C#Input, C#Output]

  def execute(i : C#Input) : Callback[C#Output] = client.send(i)

  override def flatMap[T](t: Callback[C#Output])(f: (C#Output) => Callback[T]): Callback[T] = t.flatMap(f)

  override def success[T](t: T): Callback[T] = Callback.successful(t)

  override def failure[T](ex: Throwable): Callback[T] = Callback.failed(ex)
}

trait FutureResponseAdapter[C <: CodecDSL] extends ResponseAdapter[C, Future] {

  def client : AsyncServiceClient[C#Input, C#Output]

  def execute(i : C#Input) : Future[C#Output] = client.send(i)

  implicit def executionContext : ExecutionContext

  override def flatMap[T](t: Future[C#Output])(f: (C#Output) => Future[T]): Future[T] = t.flatMap(f)

  override def success[T](t: T): Future[T] = Future.successful(t)

  override def failure[T](ex: Throwable): Future[T] = Future.failed(ex)
}

trait MemcacheClient[M[_]] { this : ResponseAdapter[Memcache, M] =>

  def add(key : ByteString, value : ByteString, ttl : Int = 0, flags : Int = 0) : M[Boolean] = {
    executeAndMap(Add(MemcachedKey(key), value, ttl, flags)){
      case Stored => success(true)
      case NotStored => success(false)
      case x => failure(UnexpectedMemcacheReplyException(s"unexpected response $x when adding $key and $value"))
    }
  }
  def append(key : ByteString, value : ByteString) : M[Boolean] = {
    executeAndMap(Append(MemcachedKey(key), value)){
      case Stored => success(true)
      case NotStored => success(false)
      case x => failure(UnexpectedMemcacheReplyException(s"unexpected response $x when appending $key and $value"))
    }
  }

  def decr(key : ByteString, value : Long) : M[Option[Long]] = {
    executeAndMap(Decr(MemcachedKey(key), value)){
      case Counter(v) => success(Some(v))
      case NotFound => success(None)
      case x => failure(new Exception(s"unexpected response $x when decr $key with $value"))
    }
  }
  def delete(key : ByteString) : M[Boolean] = {
    executeAndMap(Delete(MemcachedKey(key))) {
      case Deleted => success(true)
      case NotFound => success(false)
      case x => failure(UnexpectedMemcacheReplyException(s"unexpected response $x when deleting $key"))
    }
  }

  def get(keys : ByteString*) : M[Map[String, Value]] = {
    executeAndMap(Get(keys.map(MemcachedKey(_)) : _*)){
      case a : Value => success(Map(a.key->a))
      case Values(x) => success(x.map(y => y.key->y).toMap)
      case x => failure(UnexpectedMemcacheReplyException(s"unexpected response $x when getting $keys"))
    }
  }

  def incr(key : ByteString, value : Long) : M[Option[Long]] = {
    executeAndMap(Incr(MemcachedKey(key), value)){
      case Counter(v) => success(Some(v))
      case NotFound => success(None)
      case x => failure(UnexpectedMemcacheReplyException(s"unexpected response $x when incr $key with $value"))
    }
  }

  def prepend(key : ByteString, value : ByteString) : M[Boolean] = {
    executeAndMap(Prepend(MemcachedKey(key), value)){
      case Stored => success(true)
      case NotStored => success(false)
      case x => failure(UnexpectedMemcacheReplyException(s"unexpected response $x when prepending $key and $value"))
    }
  }

  def replace(key : ByteString, value : ByteString, ttl : Int = 0, flags : Int = 0) : M[Boolean] = {
    executeAndMap(Replace(MemcachedKey(key), value, ttl, flags)){
      case Stored => success(true)
      case NotStored => success(false)
      case x => failure(UnexpectedMemcacheReplyException(s"unexpected response $x when replacing $key and $value"))
    }
  }

  def set(key : ByteString, value : ByteString, ttl : Int = 0, flags : Int = 0) : M[Boolean] = {
    executeAndMap(Set(MemcachedKey(key), value, ttl, flags)){
      case Stored => success(true)
      case NotStored => success(false)
      case x => failure(UnexpectedMemcacheReplyException(s"unexpected response $x when setting $key and $value"))
    }
  }

  def touch(key : ByteString, ttl : Int = 0) : M[Boolean] = {
    executeAndMap(Touch(MemcachedKey(key), ttl)){
      case Touched => success(true)
      case NotFound => success(false)
      case x => failure(UnexpectedMemcacheReplyException(s"unexpected response $x when touching $key with $ttl"))
    }
  }
}

class MemcacheCallbackClient(val client : ServiceClient[MemcacheCommand, MemcacheReply])
  extends MemcacheClient[Callback] with CallbackResponseAdapter[Memcache]


class MemcacheFutureClient(val client : AsyncServiceClient[MemcacheCommand, MemcacheReply])
                          (implicit val executionContext : ExecutionContext)
  extends MemcacheClient[Future] with FutureResponseAdapter[Memcache]


case class UnexpectedMemcacheReplyException(message : String) extends Exception

object MemcacheClient {

  def callbackClient(config: ClientConfig, worker: WorkerRef, maxSize : DataSize = MemcacheReplyParser.DefaultMaxSize) : MemcacheClient[Callback] = {
    val serviceClient = new ServiceClient[MemcacheCommand, MemcacheReply](new MemcacheClientCodec(maxSize), config, worker)
    new MemcacheCallbackClient(serviceClient)
  }
  def asyncClient(config : ClientConfig, maxSize : DataSize = MemcacheReplyParser.DefaultMaxSize)(implicit io : IOSystem) : MemcacheClient[Future] = {
    implicit val ec = io.actorSystem.dispatcher
    val client = AsyncServiceClient(config, new MemcacheClientCodec(maxSize))
    new MemcacheFutureClient(client)
  }
}

