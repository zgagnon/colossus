package colossus.service

import scala.concurrent.{ExecutionContext, Future}
import scala.language.higherKinds

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
