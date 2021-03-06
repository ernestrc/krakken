package krakken.dal

import akka.event.LoggingAdapter
import com.mongodb.casbah.Imports
import com.mongodb.casbah.Imports._
import com.novus.salat._
import krakken.model._
import krakken.utils.Implicits._

import scala.reflect.ClassTag
import scala.util.Try

sealed trait Source[T] {

  def findAllByEntityId(id: SID): List[_ <: T]

  def listAll: List[_ <: T]

  def save[E <: T](event: E): Try[SID]

  def findOneByObjectId[E <: T](id: ObjectId)(implicit tag: ClassTag[E]): Option[E]

  def findAllEventsOfType[E <: T](implicit tag: ClassTag[E]): List[E]

}


class MongoSource[T <: Event : ClassTag : FromHintGrater](val db: MongoDB)
                                                         (implicit log: LoggingAdapter) {

  val serializers = implicitly[FromHintGrater[T]]

  private def fromHintTo[E](mongoObject: Imports.DBObject) = {
    serializers(mongoObject.as[String]("_typeHint").toHint)
      .asObject(mongoObject)
      .asInstanceOf[E]
  }

  val runtimeClazz = implicitly[ClassTag[T]].runtimeClass.getSimpleName
  val collectionT: MongoCollection = db(runtimeClazz)

  def findAllByEntityId(id: SID): List[_ <: T] = {
    collectionT.find(MongoDBObject("entityId" → id)).toList.map { mongoObject ⇒
      serializers(mongoObject.as[String]("_typeHint").toHint).asObject(mongoObject)
    }
  }

  def listAll: List[_ <: T] = {
    val l = collectionT.find().toList
    log.debug("ListAll method in MongoSource retrieved {}", l.toString)
    l.map { mongoObject ⇒
      serializers(mongoObject.as[String]("_typeHint").toHint).asObject(mongoObject)
    }
  }

  def save[E <: T](event: E): Try[SID] = Try {
    val obj = serializers(InjectedTypeHint(event.getClass.getCanonicalName)).asInstanceOf[Grater[E]].asDBObject(event)
    collectionT.insert(obj)
    obj._id.get.toSid
  }

  def findOneByObjectId[E <: T](id: ObjectId)(implicit tag: ClassTag[E]): Option[E] = {
    val clazz = tag.runtimeClass.getCanonicalName
    val query = MongoDBObject("_typeHint" → clazz, "_id" → id)
    collectionT.findOne(query).orElse(collectionT.findOne(query)).map(fromHintTo[E])
  }

  def findAllEventsOfType[E <: T](implicit tag: ClassTag[E]): List[E] = {
    val clazz = tag.runtimeClass.getCanonicalName
    log.debug(s"Fetching all events of type $clazz")
    val query = "_typeHint" $eq clazz

    collectionT.find(query).toList.map(fromHintTo[E]) Ω { list ⇒
      s"Found events: $list"
    }
  }

}