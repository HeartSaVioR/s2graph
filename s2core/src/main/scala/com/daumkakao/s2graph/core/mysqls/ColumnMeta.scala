package com.daumkakao.s2graph.core.mysqls

import play.api.Logger
import play.api.libs.json.Json
import scalikejdbc._

object ColumnMeta extends Model[ColumnMeta] {

  val timeStampSeq = 0.toByte
  val countSeq = -1.toByte
  val lastModifiedAtColumnSeq = 0.toByte
  val lastModifiedAtColumn = ColumnMeta(Some(0), 0, "lastModifiedAt", lastModifiedAtColumnSeq, "long")
  val maxValue = Byte.MaxValue

  def apply(rs: WrappedResultSet): ColumnMeta = {
    ColumnMeta(Some(rs.int("id")), rs.int("column_id"), rs.string("name"), rs.byte("seq"), rs.string("data_type").toLowerCase())
  }

  def findById(id: Int) = {
//    val cacheKey = s"id=$id"
    val cacheKey = "id=" + id
    withCache(cacheKey) {
      sql"""select * from column_metas where id = ${id}""".map { rs => ColumnMeta(rs) }.single.apply
    }.get
  }

  def findAllByColumn(columnId: Int, useCache: Boolean = true) = {
//    val cacheKey = s"columnId=$columnId"
    val cacheKey = "columnId=" + columnId
    if (useCache) {
      withCaches(cacheKey)( sql"""select *from column_metas where column_id = ${columnId} order by seq ASC"""
        .map { rs => ColumnMeta(rs) }.list.apply())
    } else {
      sql"""select * from column_metas where column_id = ${columnId} order by seq ASC"""
        .map { rs => ColumnMeta(rs) }.list.apply()
    }
  }

  def findByName(columnId: Int, name: String) = {
//    val cacheKey = s"columnId=$columnId:name=$name"
    val cacheKey = "columnId=" + columnId + ":name=" + name
    withCache(cacheKey)( sql"""select * from column_metas where column_id = ${columnId} and name = ${name}"""
      .map { rs => ColumnMeta(rs) }.single.apply())
  }

  def insert(columnId: Int, name: String, dataType: String) = {
    val ls = findAllByColumn(columnId, false)
    val seq = ls.size + 1
    if (seq <= maxValue) {
      sql"""insert into column_metas(column_id, name, seq, data_type)
    select ${columnId}, ${name}, ${seq}, ${dataType}"""
        .updateAndReturnGeneratedKey.apply()
    }
  }

  def findOrInsert(columnId: Int, name: String, dataType: String): ColumnMeta = {
    //    play.api.Logger.debug(s"findOrInsert: $columnId, $name")
    findByName(columnId, name) match {
      case Some(c) => c
      case None =>
        insert(columnId, name, dataType)
        expireCache(s"columnId=$columnId:name=$name")
        findByName(columnId, name).get
    }
  }

  def findByIdAndSeq(columnId: Int, seq: Byte, useCache: Boolean = true) = {
//    val cacheKey = s"columnId=$columnId:seq=$seq"
    val cacheKey = "columnId=" + columnId + ":seq=" + seq
    if (useCache) {
      withCache(cacheKey)( sql"""
        select * from column_metas where column_id = ${columnId} and seq = ${seq}
    """.map { rs => ColumnMeta(rs) }.single.apply())
    } else {
      sql"""
        select * from column_metas where column_id = ${columnId} and seq = ${seq}
    """.map { rs => ColumnMeta(rs) }.single.apply()
    }
  }

  def delete(id: Int) = {
    val columnMeta = findById(id)
    val (columnId, name) = (columnMeta.columnId, columnMeta.name)
    sql"""delete from column_metas where id = ${id}""".execute.apply()
    val cacheKeys = List(s"id=$id", s"columnId=$columnId:name=$name", s"colunmId=$columnId")
    cacheKeys.foreach(expireCache(_))
  }

  def findAll() = {
    val ls = sql"""select * from column_metas""".map { rs => ColumnMeta(rs) }.list().apply()
    putsToCache(ls.map { x =>
      var cacheKey = s"id=${x.id.get}"
      (cacheKey -> x)
    })
    putsToCache(ls.map { x =>
      var cacheKey = s"columnId=${x.columnId}:name=${x.name}"
      (cacheKey -> x)
    })
    putsToCache(ls.map { x =>
      var cacheKey = s"columnId=${x.columnId}:seq=${x.seq}"
      (cacheKey -> x)
    })
    putsToCaches( ls.groupBy(x => x.columnId).map { case (columnId, ls) =>
      val cacheKey = s"columnId=${columnId}"
      (cacheKey -> ls)
    }.toList
    )
//    for {
//      x <- ls
//    } {
//      Logger.info(s"ColumnMeta: $x")
//      findById(x.id.get)
//      findByName(x.columnId, x.name)
//      findAllByColumn(x.columnId)
//      findByIdAndSeq(x.id.get, x.seq)
//    }
  }
}

case class ColumnMeta(id: Option[Int], columnId: Int, name: String, seq: Byte, dataType: String) {
  lazy val toJson = Json.obj("name" -> name, "dataType" -> dataType)
}
