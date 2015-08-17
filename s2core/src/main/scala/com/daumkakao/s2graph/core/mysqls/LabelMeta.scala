package com.daumkakao.s2graph.core.mysqls

/**
 * Created by shon on 6/3/15.
 */

//import com.daumkakao.s2graph.core.HBaseElement.InnerVal
import com.daumkakao.s2graph.core.JSONParser

//import com.daumkakao.s2graph.core.types2.InnerVal
import play.api.libs.json.Json
import scalikejdbc._

object LabelMeta extends Model[LabelMeta] with JSONParser {

  /** dummy sequences */

  val fromSeq = -4.toByte
  val toSeq = -5.toByte
  val lastOpSeq = -3.toByte
  val lastDeletedAt = -2.toByte
  val timeStampSeq = 0.toByte
  val countSeq = (Byte.MaxValue - 2).toByte
  val degreeSeq = (Byte.MaxValue - 1).toByte
  val maxValue = Byte.MaxValue
  val emptyValue = Byte.MaxValue

  /** reserved sequences */
  val from = LabelMeta(id = Some(fromSeq), labelId = fromSeq, name = "_from",
    seq = fromSeq, defaultValue = fromSeq.toString, dataType = "long")
  val to = LabelMeta(id = Some(toSeq), labelId = toSeq, name = "_to",
    seq = toSeq, defaultValue = toSeq.toString, dataType = "long")
  val timestamp = LabelMeta(id = Some(-1), labelId = -1, name = "_timestamp",
    seq = timeStampSeq, defaultValue = "0", dataType = "long")
  val degree = LabelMeta(id = Some(-1), labelId = -1, name = "_degree",
    seq = degreeSeq, defaultValue = "0", dataType = "long")
  val count = LabelMeta(id = Some(-1), labelId = -1, name = "_count",
  seq = countSeq, defaultValue = "-1", dataType = "long")

  val reservedMetas = List(from, to, degree, timestamp, count)
  val notExistSeqInDB = List(lastOpSeq, lastDeletedAt, countSeq, degree, timeStampSeq, from.seq, to.seq)

  def apply(rs: WrappedResultSet): LabelMeta = {
    LabelMeta(Some(rs.int("id")), rs.int("label_id"), rs.string("name"), rs.byte("seq"),
      rs.string("default_value"), rs.string("data_type").toLowerCase())
  }

  def findById(id: Int): LabelMeta = {
//    val cacheKey = s"id=$id"
    val cacheKey = "id=" + id
    withCache(cacheKey) {
      sql"""select * from label_metas where id = ${id}""".map { rs => LabelMeta(rs) }.single.apply
    }.get
  }

  def findAllByLabelId(labelId: Int, useCache: Boolean = true): List[LabelMeta] = {
//    val cacheKey = s"labelId=$labelId"
    val cacheKey = "labelId=" + labelId
    if (useCache) {
      withCaches(cacheKey)(sql"""select *
    		  						from label_metas
    		  						where label_id = ${labelId} order by seq ASC"""
        .map { rs => LabelMeta(rs) }.list.apply())
    } else {
      sql"""select *
      		from label_metas
      		where label_id = ${labelId} order by seq ASC"""
        .map { rs => LabelMeta(rs) }.list.apply()
    }

  }

  def findByName(labelIdWithName: (Int, String)): Option[LabelMeta] = {
    val (labelId, name) = labelIdWithName
    findByName(labelId, name)
  }
  def findByName(labelId: Int, name: String, useCache: Boolean = true): Option[LabelMeta] = {
    name match {
      case timestamp.name => Some(timestamp)
      case from.name => Some(from)
      case to.name => Some(to)
      case _ =>
//        val cacheKey = s"labelId=$labelId:name=$name"
        val cacheKey = "labelId=" + labelId + ":name=" + name
        if (useCache) {
          withCache(cacheKey)(sql"""
            select *
            from label_metas where label_id = ${labelId} and name = ${name}"""
            .map { rs => LabelMeta(rs) }.single.apply())
        } else {
          sql"""
            select *
            from label_metas where label_id = ${labelId} and name = ${name}"""
            .map { rs => LabelMeta(rs) }.single.apply()
        }

    }

  }
  def insert(labelId: Int, name: String, defaultValue: String, dataType: String) = {
    val ls = findAllByLabelId(labelId, false)
    //    val seq = LabelIndexProp.maxValue + ls.size + 1
    val seq = ls.size + 1
    if (seq < maxValue) {
      sql"""insert into label_metas(label_id, name, seq, default_value, data_type)
    select ${labelId}, ${name}, ${seq}, ${defaultValue}, ${dataType}"""
        .updateAndReturnGeneratedKey.apply()
    }
  }

  def findOrInsert(labelId: Int, name: String,
                   defaultValue: String, dataType: String)(implicit dBSession: DBSession): LabelMeta = {
    //    play.api.Logger.debug(s"findOrInsert: $labelId, $name")
    findByName(labelId, name) match {
      case Some(c) => c
      case None =>
        insert(labelId, name, defaultValue, dataType)
//        val cacheKey = s"labelId=$labelId:name=$name"
//        val cacheKeys = s"labelId=$labelId"
        val cacheKey = "labelId=" + labelId + ":name=" + name
        val cacheKeys = "labelId=" + labelId
        expireCache(cacheKey)
        expireCaches(cacheKeys)
        findByName(labelId, name, false).get
    }
  }

  def delete(id: Int) = {
    val labelMeta = findById(id)
    val (labelId, name) = (labelMeta.labelId, labelMeta.name)
    sql"""delete from label_metas where id = ${id}""".execute.apply()
    val cacheKeys = List(s"id=$id", s"labelId=$labelId", s"labelId=$labelId:name=$name")
    cacheKeys.foreach(expireCache(_))
  }


  def findAll() = {
    val ls = sql"""select * from label_metas""".map { rs => LabelMeta(rs) }.list.apply
    putsToCache(ls.map { x =>
      var cacheKey = s"id=${x.id.get}"
      (cacheKey -> x)
    })
    putsToCache(ls.map { x =>
      var cacheKey = s"labelId=${x.labelId}:name=${x.name}"
      (cacheKey -> x)
    })
    putsToCache(ls.map { x =>
      var cacheKey = s"labelId=${x.labelId}:seq=${x.seq}"
      (cacheKey -> x)
    })

    putsToCaches( ls.groupBy(x => x.labelId).map { case (labelId, ls) =>
        val cacheKey = s"labelId=${labelId}"
        (cacheKey -> ls)
      }.toList
    )

//    for {
//      x <- ls
//    } {
//      Logger.info(s"LabelMeta: $x")
//      findById(x.id.get)
//      findByName(x.labelId, x.name, useCache = true)
//      findAllByLabelId(x.labelId, useCache = true)
//    }
  }
}

case class LabelMeta(id: Option[Int], labelId: Int, name: String, seq: Byte, defaultValue: String, dataType: String) extends JSONParser {
  lazy val toJson = Json.obj("name" -> name, "defaultValue" -> defaultValue, "dataType" -> dataType)

}
