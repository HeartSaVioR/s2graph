package com.daumkakao.s2graph.core

// import com.daumkakao.s2graph.core.mysqls._
import com.daumkakao.s2graph.core.models._

import com.daumkakao.s2graph.core.types2._
import org.apache.hadoop.hbase.client.Put
import org.apache.hadoop.hbase.client.Delete
import org.apache.hadoop.hbase.util.Bytes
import play.api.libs.json.Json
import scala.collection.mutable.{HashMap, ListBuffer}
import org.hbase.async.{DeleteRequest, HBaseRpc, PutRequest, GetRequest}

/**
  */
case class Vertex(id: VertexId,
                  ts: Long = System.currentTimeMillis(),
                  props: Map[Int, InnerValLike] = Map.empty[Int, InnerValLike],
                  op: Byte = 0) extends GraphElement {
  import GraphConstant._

  lazy val innerId = id.innerId
  lazy val schemaVer = serviceColumn.schemaVersion
  lazy val serviceColumn = ServiceColumn.findById(id.colId)
  lazy val service = Service.findById(serviceColumn.serviceId)
  lazy val (hbaseZkAddr, hbaseTableName) = (service.cluster, service.hTableName)

//  lazy val rowKey = VertexRowKey(id)(schemaVer)
  lazy val rowKey = id
  lazy val defaultProps = Map(ColumnMeta.lastModifiedAtColumnSeq.toInt -> InnerVal.withLong(ts, schemaVer))
  lazy val qualifiersWithValues = for {
    (k, v) <- props ++ defaultProps
  } yield {
      Bytes.toBytes(k) -> v.bytes
    }
//  lazy val qualifiersWithValues =
//    for ((k, v) <- props ++ defaultProps) yield (VertexQualifier(k)(schemaVer), v)

  /** TODO: make this as configurable */
  override lazy val serviceName = service.serviceName
  override lazy val isAsync = false
  override lazy val queueKey = Seq(ts.toString, serviceName).mkString("|")
  override lazy val queuePartitionKey = id.innerId.toString

  lazy val propsWithName = for {
    (seq, v) <- props
    meta <- ColumnMeta.findByIdAndSeq(id.colId, seq.toByte)
  } yield (meta.name -> v.toString)

  def buildPuts(): List[Put] = {
    //    play.api.Logger.error(s"put: $this => $rowKey")
    val put = new Put(rowKey.bytes)
    for ((q, v) <- qualifiersWithValues) {
      put.addColumn(vertexCf, q, ts, v)
    }
    List(put)
  }

  def buildPutsAsync(): List[PutRequest] = {
    val qualifiers = ListBuffer[Array[Byte]]()
    val values = ListBuffer[Array[Byte]]()
    for ((q, v) <- qualifiersWithValues) {
      qualifiers += q
      values += v
      //        new PutRequest(hbaseTableName.getBytes, rowKey.bytes, vertexCf, qualifier.bytes, v.bytes, ts)
    }
    val put = new PutRequest(hbaseTableName.getBytes, rowKey.bytes, vertexCf, qualifiers.toArray, values.toArray, ts)
    List(put)
  }

  //  def buildPutsAll(): List[Mutation] = {
  //    op match {
  //      case d: Byte if d == GraphUtil.operations("delete") => // delete
  //        buildDelete()
  //      case _ => // insert/update/increment
  //        buildPuts()
  //    }
  //  }
  def buildPutsAll(): List[HBaseRpc] = {
    op match {
      case d: Byte if d == GraphUtil.operations("delete") => buildDeleteAsync()
//      case dAll: Byte if dAll == GraphUtil.operations("deleteAll") => buildDeleteAllAsync()
      case _ => buildPutsAsync()
    }
  }

  def buildDelete(): List[Delete] = {
    List(new Delete(rowKey.bytes, ts))
  }

  def buildDeleteAsync(): List[DeleteRequest] = {
    List(new DeleteRequest(hbaseTableName.getBytes, rowKey.bytes, vertexCf, ts))
  }

  def belongLabelIds(): Iterable[Label] = {
    for {
      label <- (Label.findBySrcColumnId(id.colId) ++ Label.findByTgtColumnId(id.colId)).groupBy(_.id.get).map { _._2.head }
    } yield label
  }


  //  def buildGet() = {
  //    val get = new Get(rowKey.bytes)
  //    //    play.api.Logger.error(s"get: $this => $rowKey")
  //    get.addFamily(vertexCf)
  //    get
  //  }
  def buildGet() = {
    new GetRequest(hbaseTableName.getBytes, rowKey.bytes, vertexCf)
  }

  def toEdgeVertex() = Vertex(SourceVertexId(id.colId, innerId), ts, props, op)


  override def hashCode() = {
    id.hashCode()
  }

  override def equals(obj: Any) = {
    obj match {
      case otherVertex: Vertex =>
        id.equals(otherVertex.id)
      case _ => false
    }
  }

  def withProps(newProps: Map[Int, InnerValLike]) = Vertex(id, ts, newProps, op)

  def toLogString(): String = {
    val (serviceName, columnName) =
      if (!id.storeColId) ("", "")
      else {
        (serviceColumn.service.serviceName, serviceColumn.columnName)
      }
    val ls = ListBuffer(ts, GraphUtil.fromOp(op), "v", id.innerId, serviceName, columnName)
    if (!propsWithName.isEmpty) ls += Json.toJson(propsWithName)
    ls.mkString("\t")
  }
}

object Vertex {

  //  val emptyVertex = Vertex(new CompositeId(CompositeId.defaultColId, CompositeId.defaultInnerId, false, true),
  //    System.currentTimeMillis())
  def fromString(s: String): Option[Vertex] = Graph.toVertex(s)

  def apply(kvs: Seq[org.hbase.async.KeyValue], version: String): Option[Vertex] = {
    if (kvs.isEmpty) None
    else {

      val head = kvs.head
      val headBytes = head.key()
      val id = VertexId.fromBytes(headBytes, 0, headBytes.length, version)

      var maxTs = Long.MinValue
      /**
       *
       * TODO
       * Make sure this does not violate any MVCC Version.
       */
      val props =
        for {
          kv <- kvs
          kvQual = kv.qualifier()
          propKey = kvQual.head
          v = kv.value()
          propVal = InnerVal.fromBytes(v, 0, v.length, version)
          ts = kv.timestamp()
        } yield {
          if (ts > maxTs) maxTs = ts
          (propKey.toInt, propVal)
        }
      assert(maxTs != Long.MinValue)
      Option(Vertex(id = id, ts = maxTs, props = props.toMap, op = 0))
    }
  }
}
