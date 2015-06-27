package com.daumkakao.s2graph.core.storages

import com.daumkakao.s2graph.core.models.{LabelMeta, LabelIndex}
import com.daumkakao.s2graph.core._
import com.daumkakao.s2graph.core.types2._
import org.apache.hadoop.hbase.util.Bytes
import org.hbase.async.KeyValue

/**
 * Created by shon on 6/28/15.
 */
object GraphStorable {

  import com.daumkakao.s2graph.core.GraphConstant._

  trait Serializable[T, R] {
    def encode(from: T): R

    def decode(v: R): T

    /** common helpers for hbase serializable/deserializable */


    val EMPTY_SEQ_BYTE = Byte.MaxValue

    def labelOrderSeqWithIsInverted(labelOrderSeq: Byte, isInverted: Boolean): Array[Byte] = {
      assert(labelOrderSeq < (1 << 6))
      val byte = labelOrderSeq << 1 | (if (isInverted) 1 else 0)
      Array.fill(1)(byte.toByte)
    }

    def bytesToLabelIndexSeqWithIsInverted(bytes: Array[Byte], offset: Int): (Byte, Boolean) = {
      val byte = bytes(offset)
      val isInverted = if ((byte & 1) != 0) true else false
      val labelOrderSeq = byte >> 1
      (labelOrderSeq.toByte, isInverted)
    }

    def propsToBytes(props: Seq[(Byte, InnerValLike)]): Array[Byte] = {
      val len = props.length
      assert(len < Byte.MaxValue)
      var bytes = Array.fill(1)(len.toByte)
      for ((k, v) <- props) bytes = Bytes.add(bytes, v.bytes)
      bytes
    }

    def propsToKeyValues(props: Seq[(Byte, InnerValLike)]): Array[Byte] = {
      val len = props.length
      assert(len < Byte.MaxValue)
      var bytes = Array.fill(1)(len.toByte)
      for ((k, v) <- props) bytes = Bytes.add(bytes, Array.fill(1)(k), v.bytes)
      bytes
    }

    def propsToKeyValuesWithTs(props: Seq[(Byte, InnerValLikeWithTs)]): Array[Byte] = {
      val len = props.length
      assert(len < Byte.MaxValue)
      var bytes = Array.fill(1)(len.toByte)
      for ((k, v) <- props) bytes = Bytes.add(bytes, Array.fill(1)(k), v.bytes)
      bytes
    }

    def bytesToKeyValues(bytes: Array[Byte],
                         offset: Int,
                         len: Int,
                         version: String): (Seq[(Byte, InnerValLike)], Int) = {
      var pos = offset
      val len = bytes(pos)
      pos += 1
      val kvs = for (i <- (0 until len)) yield {
        val k = bytes(pos)
        pos += 1
        val v = InnerVal.fromBytes(bytes, pos, 0, version)
        pos += v.bytes.length
        (k -> v)
      }
      val ret = (kvs.toList, pos)
      //    Logger.debug(s"bytesToProps: $ret")
      ret
    }

    def bytesToKeyValuesWithTs(bytes: Array[Byte],
                               offset: Int,
                               version: String): (Seq[(Byte, InnerValLikeWithTs)], Int) = {
      var pos = offset
      val len = bytes(pos)
      pos += 1
      val kvs = for (i <- (0 until len)) yield {
        val k = bytes(pos)
        pos += 1
        val v = InnerValLikeWithTs.fromBytes(bytes, pos, 0, version)
        pos += v.bytes.length
        (k -> v)
      }
      val ret = (kvs.toList, pos)
      //    Logger.debug(s"bytesToProps: $ret")
      ret
    }

    def bytesToProps(bytes: Array[Byte],
                     offset: Int,
                     version: String): (Seq[(Byte, InnerValLike)], Int) = {
      var pos = offset
      val len = bytes(pos)
      pos += 1
      val kvs = for (i <- (0 until len)) yield {
        val k = EMPTY_SEQ_BYTE
        val v = InnerVal.fromBytes(bytes, pos, 0, version)
        pos += v.bytes.length
        (k -> v)
      }
      //    Logger.error(s"bytesToProps: $kvs")
      val ret = (kvs.toList, pos)

      ret
    }
  }

  object IndexedEdgeLikeV1 extends Serializable[EdgeWithIndex, KeyValue] {


    val version = InnerVal.VERSION1

    def encode(edgeWithIndex: EdgeWithIndex): KeyValue = {
      val id = VertexId.toSourceVertexId(edgeWithIndex.srcVertex.id)
      /** rowKey */
      val rowKey = Bytes.add(id.bytes,
        edgeWithIndex.labelWithDir.bytes,
        labelOrderSeqWithIsInverted(edgeWithIndex.labelIndexSeq,
          edgeWithIndex.isInverted))

      /** qualifier */
      val tgtVertexIdBytes = VertexId.toTargetVertexId(edgeWithIndex.tgtVertex.id).bytes
      val idxPropsMap = edgeWithIndex.orders.toMap
      val idxPropsBytes = propsToBytes(edgeWithIndex.orders)

      val qualifier = Bytes.add(idxPropsBytes, tgtVertexIdBytes, Array[Byte](edgeWithIndex.op))
      /** value */
      val value = propsToKeyValues(edgeWithIndex.metas.toList)

      new KeyValue(rowKey, edgeCf, qualifier, edgeWithIndex.ts, value)
    }

    def decode(kv: KeyValue): EdgeWithIndex = {
      /** row Key */
      val keyBytes = kv.key()
      var pos = 0
      val srcVertexId = SourceVertexId.fromBytes(keyBytes, pos, keyBytes.length, version)
      pos += srcVertexId.bytes.length
      val labelWithDir = LabelWithDirection(Bytes.toInt(keyBytes, pos, 4))
      pos += 4
      val (labelOrderSeq, isInverted) = bytesToLabelIndexSeqWithIsInverted(keyBytes, pos)

      /** qualifier */
      val qualifierBytes = kv.qualifier()
      pos = 0
      val op = qualifierBytes.last
      val (idxProps, tgtVertexId) = {
        val (decodedProps, endAt) = bytesToProps(qualifierBytes, pos, version)
        val decodedVId = TargetVertexId.fromBytes(qualifierBytes, endAt, qualifierBytes.length, version)
        (decodedProps, decodedVId)
      }
      /** value */
      val valueBytes = kv.value()
      pos = 0
      val (props, endAt) = bytesToKeyValues(valueBytes, pos, 0, version)

      EdgeWithIndex(Vertex(srcVertexId), Vertex(tgtVertexId), labelWithDir,
        op, kv.timestamp, labelOrderSeq, (idxProps ++ props).toMap)
    }

  }

  object IndexedEdgeLikeV2 extends Serializable[EdgeWithIndex, KeyValue] {


    val version = InnerVal.VERSION2

    def encode(edgeWithIndex: EdgeWithIndex): KeyValue = {
      val id = VertexId.toSourceVertexId(edgeWithIndex.srcVertex.id)
      /** rowKey */
      val rowKey = Bytes.add(id.bytes,
        edgeWithIndex.labelWithDir.bytes,
        labelOrderSeqWithIsInverted(edgeWithIndex.labelIndexSeq,
          edgeWithIndex.isInverted))

      /** qualifier */
      val tgtVertexIdBytes = VertexId.toTargetVertexId(edgeWithIndex.tgtVertex.id).bytes
      val idxPropsMap = edgeWithIndex.orders.toMap
      val idxPropsBytes = propsToBytes(edgeWithIndex.orders)

      // not store op byte.
      val qualifier = idxPropsMap.get(LabelMeta.toSeq) match {
        case None => Bytes.add(idxPropsBytes, tgtVertexIdBytes)
        case Some(vId) => idxPropsBytes
      }
      /** value */
      val value = propsToKeyValues(edgeWithIndex.metas.toList)

      new KeyValue(rowKey, edgeCf, qualifier, edgeWithIndex.ts, value)
    }

    def decode(kv: KeyValue): EdgeWithIndex = {
      /** row Key */
      val keyBytes = kv.key()
      var pos = 0
      val srcVertexId = SourceVertexId.fromBytes(keyBytes, pos, keyBytes.length, version)
      pos += srcVertexId.bytes.length
      val labelWithDir = LabelWithDirection(Bytes.toInt(keyBytes, pos, 4))
      pos += 4
      val (labelOrderSeq, isInverted) = bytesToLabelIndexSeqWithIsInverted(keyBytes, pos)

      /** qualifier */
      val qualifierBytes = kv.qualifier()
      pos = 0
      val op = GraphUtil.defaultOpByte
      val (idxProps, tgtVertexId) = {
        val (decodedProps, endAt) = bytesToProps(qualifierBytes, pos, version)
        val decodedVId =
          if (endAt == qualifierBytes.length) {
            val innerValOpt = decodedProps.toMap.get(LabelMeta.toSeq)
            assert(innerValOpt.isDefined)
            TargetVertexId(VertexId.DEFAULT_COL_ID, innerValOpt.get)
          } else {
            TargetVertexId.fromBytes(qualifierBytes, endAt, qualifierBytes.length, version)
          }
        (decodedProps, decodedVId)
      }
      /** value */
      val valueBytes = kv.value()
      pos = 0
      val (props, endAt) = bytesToKeyValues(valueBytes, pos, 0, version)

      EdgeWithIndex(Vertex(srcVertexId), Vertex(tgtVertexId), labelWithDir,
        op, kv.timestamp, labelOrderSeq, (idxProps ++ props).toMap)
    }

  }

  object SnapshotEdgeLikeV1 extends Serializable[EdgeWithIndexInverted, KeyValue] {
    val version = InnerVal.VERSION1
    val isInverted = true

    def encode(edgeWithIndexInverted: EdgeWithIndexInverted): KeyValue = {
      /** rowKey */
      val id = VertexId.toSourceVertexId(edgeWithIndexInverted.srcVertex.id)
      val rowKey = Bytes.add(id.bytes,
        edgeWithIndexInverted.labelWithDir.bytes,
        labelOrderSeqWithIsInverted(LabelIndex.defaultSeq, isInverted = isInverted))

      /** qualifier */
      val qualifier = VertexId.toTargetVertexId(edgeWithIndexInverted.tgtVertex.id).bytes
      /** value */
      val value = Bytes.add(Array.fill(1)(edgeWithIndexInverted.op),
        propsToKeyValuesWithTs(edgeWithIndexInverted.props.toSeq))

      new KeyValue(rowKey, edgeCf, qualifier, edgeWithIndexInverted.version, value)
    }

    def decode(kv: KeyValue): EdgeWithIndexInverted = {
      /** row Key */
      val keyBytes = kv.key()
      var pos = 0
      val srcVertexId = SourceVertexId.fromBytes(keyBytes, pos, keyBytes.length, version)
      pos += srcVertexId.bytes.length
      val labelWithDir = LabelWithDirection(Bytes.toInt(keyBytes, pos, 4))
      pos += 4
      val (labelOrderSeq, isInverted) = bytesToLabelIndexSeqWithIsInverted(keyBytes, pos)

      /** qualifier */
      val qualifierBytes = kv.qualifier()
      pos = 0
      val tgtVertexId = TargetVertexId.fromBytes(qualifierBytes, 0, qualifierBytes.length, version)

      /** value */
      val valueBytes = kv.value()
      pos = 0
      val op = valueBytes(pos)
      pos += 1
      var (props, endAt) = bytesToKeyValuesWithTs(valueBytes, pos, version)
      EdgeWithIndexInverted(Vertex(srcVertexId), Vertex(tgtVertexId),
        labelWithDir, op, kv.timestamp(), props.toMap)
    }
  }
  object SnapshotEdgeLikeV2 extends Serializable[EdgeWithIndexInverted, KeyValue] {
    val version = InnerVal.VERSION2
    val isInverted = true

    def encode(edgeWithIndexInverted: EdgeWithIndexInverted): KeyValue = {
      /** rowKey */
      val id = VertexId.toSourceVertexId(edgeWithIndexInverted.srcVertex.id)
      val rowKey = Bytes.add(id.bytes,
        edgeWithIndexInverted.labelWithDir.bytes,
        labelOrderSeqWithIsInverted(LabelIndex.defaultSeq, isInverted = isInverted))

      /** qualifier */
      val qualifier = VertexId.toTargetVertexId(edgeWithIndexInverted.tgtVertex.id).bytes
      /** value */
      val value = Bytes.add(Array.fill(1)(edgeWithIndexInverted.op),
        propsToKeyValuesWithTs(edgeWithIndexInverted.props.toSeq))

      new KeyValue(rowKey, edgeCf, qualifier, edgeWithIndexInverted.version, value)
    }

    def decode(kv: KeyValue): EdgeWithIndexInverted = {
      /** row Key */
      val keyBytes = kv.key()
      var pos = 0
      val srcVertexId = SourceVertexId.fromBytes(keyBytes, pos, keyBytes.length, version)
      pos += srcVertexId.bytes.length
      val labelWithDir = LabelWithDirection(Bytes.toInt(keyBytes, pos, 4))
      pos += 4
      val (labelOrderSeq, isInverted) = bytesToLabelIndexSeqWithIsInverted(keyBytes, pos)

      /** qualifier */
      val qualifierBytes = kv.qualifier()
      pos = 0
      val tgtVertexId = TargetVertexId.fromBytes(qualifierBytes, 0, qualifierBytes.length, version)

      /** value */
      val valueBytes = kv.value()
      pos = 0
      val op = valueBytes(pos)
      pos += 1
      var (props, endAt) = bytesToKeyValuesWithTs(valueBytes, pos, version)
      EdgeWithIndexInverted(Vertex(srcVertexId), Vertex(tgtVertexId),
        labelWithDir, op, kv.timestamp(), props.toMap)
    }
  }

}