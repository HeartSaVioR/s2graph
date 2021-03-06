package com.daumkakao.s2graph.core.types2.v2

import com.daumkakao.s2graph.core.types2._
import org.apache.hadoop.hbase.util.Bytes

/**
 * Created by shon on 6/10/15.
 */
object VertexQualifier extends HBaseDeserializable {
  import HBaseType._
  def fromBytes(bytes: Array[Byte],
                offset: Int,
                len: Int,
                version: String = VERSION2): (VertexQualifier, Int) = {
    (VertexQualifier(Bytes.toInt(bytes, offset, 4)), 4)
  }
}
case class VertexQualifier(propKey: Int) extends VertexQualifierLike {
  def bytes: Array[Byte] = Bytes.toBytes(propKey)
}
