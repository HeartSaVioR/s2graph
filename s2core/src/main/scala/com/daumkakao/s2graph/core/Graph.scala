package com.daumkakao.s2graph.core


//import com.daumkakao.s2graph.core.mysqls._

import java.util

import com.daumkakao.s2graph.core.models._
import com.google.common.cache.CacheBuilder
import scala.util.hashing.MurmurHash3
import scala.util.{Success, Failure}


import com.daumkakao.s2graph.core.types2._
import org.apache.hadoop.hbase.HBaseConfiguration
import org.apache.hadoop.hbase.client._
import java.util.concurrent.{TimeUnit, Executors}
import play.api.Logger
import scala.collection.JavaConversions._
import scala.collection.mutable.HashSet
import scala.concurrent._
import scala.concurrent.duration._
import scala.collection.mutable.ListBuffer
import scala.collection.mutable.HashMap
import com.typesafe.config.Config
import scala.reflect.ClassTag
import org.hbase.async._
import com.stumbleupon.async.Deferred
import com.stumbleupon.async.Callback
import java.util.ArrayList

object GraphConstant {

  val vertexCf = "v".getBytes()
  val edgeCf = "e".getBytes()
  val updateCf = "u".getBytes()
  val ttsForActivity = 60 * 60 * 24 * 30
  val delimiter = "|"
  val seperator = ":"

  val writeBufferSize = 1024 * 1024 * 2

  val maxValidEdgeListSize = 10000

  //  implicit val ex = play.api.libs.concurrent.Execution.Implicits.defaultContext
}

object GraphConnection {
  lazy val tablePool = Executors.newFixedThreadPool(1)
  lazy val connectionPool = Executors.newFixedThreadPool(1)
  val defaultConfigs = Map(
    "hbase.zookeeper.quorum" -> "localhost",
    "hbase.table.name" -> "s2graph",
    "phase" -> "dev",
    "async.hbase.client.flush.interval" -> 100.toShort)
  var config: Config = null

  def getOrElse[T: ClassTag](conf: com.typesafe.config.Config)(key: String, default: T): T = {
    if (conf.hasPath(key)) (default match {
      case _: String => conf.getString(key)
      case _: Int | _: Integer => conf.getInt(key)
      case _: Float | _: Double => conf.getDouble(key)
      case _: Boolean => conf.getBoolean(key)
      case _ => default
    }).asInstanceOf[T]
    else default
  }

  /**
   * requred: hbase.zookeeper.quorum
   * optional: all hbase. prefix configurations.
   */
  def toHBaseConfig(config: com.typesafe.config.Config) = {
    val configVals = for ((k, v) <- defaultConfigs) yield {
      val currentVal = getOrElse(config)(k, v)
      Logger.debug(s"$k -> $currentVal")
      k -> currentVal
    }
    val conf = HBaseConfiguration.create()
    conf.set("hbase.zookeeper.quorum", configVals("hbase.zookeeper.quorum").toString)
    for (entry <- config.entrySet() if entry.getKey().startsWith("hbase.")) {
      val value = entry.getValue().unwrapped().toString
      conf.set(entry.getKey(), value)
    }
    conf
  }

  def apply(config: Config) = {
    this.config = config
    val hbaseConfig = toHBaseConfig(config)
    (hbaseConfig -> ConnectionFactory.createConnection(hbaseConfig))
  }
}

object Graph {

  import GraphConstant._
  import GraphConnection._

  //  val Logger = Edge.Logger
  val conns = scala.collection.mutable.Map[String, Connection]()
  val clients = scala.collection.mutable.Map[String, HBaseClient]()
  val emptyKVs = new ArrayList[KeyValue]()
  val emptyKVlist = new ArrayList[ArrayList[KeyValue]]()


  var executionContext: ExecutionContext = null
  var config: com.typesafe.config.Config = null
  var hbaseConfig: org.apache.hadoop.conf.Configuration = null
  var storageExceptionCount = 0L
  var singleGetTimeout = 10000 millis
  var clientFlushInterval = 100.toShort
  val defaultScore = 1.0

  lazy val cache = CacheBuilder.newBuilder()
    .expireAfterWrite(100, TimeUnit.SECONDS)
    .maximumSize(10000)
    .build[java.lang.Integer, QueryResult]()

  //  implicit val ex = ExecutionContext.fromExecutor(Executors.newFixedThreadPool(16))
  def apply(config: com.typesafe.config.Config)(implicit ex: ExecutionContext) = {
    this.config = config
    val (hbaseConfig, conn) = GraphConnection.apply(config)
    this.hbaseConfig = hbaseConfig
    Model.apply(config)
    this.executionContext = ex
    this.singleGetTimeout = getOrElse(config)("hbase.client.operation.timeout", 1000 millis)
    this.clientFlushInterval = getOrElse(config)("async.hbase.client.flush.interval", 100.toShort)
    val zkQuorum = hbaseConfig.get("hbase.zookeeper.quorum")
    //    conns += (zkQuorum -> conn)
    //    clients += (zkQuorum -> new HBaseClient(zkQuorum, "/hbase", Executors.newCachedThreadPool(), 8))
    val client = new HBaseClient(zkQuorum)
    client.setFlushInterval(clientFlushInterval)
    clients += (zkQuorum -> client)
  }


  def getClient(zkQuorum: String, flushInterval: Short = clientFlushInterval) = {
    val client = clients.get(zkQuorum) match {
      case None =>
        val client = new HBaseClient(zkQuorum)
        client.setFlushInterval(clientFlushInterval)
        clients += (zkQuorum -> client)
        client
      //        throw new RuntimeException(s"connection to $zkQuorum is not established.")
      case Some(c) => c
    }
    client.setFlushInterval(flushInterval)
    client
  }

  def getConn(zkQuorum: String) = {
    conns.get(zkQuorum) match {
      case None =>
        Logger.debug(s"${this.hbaseConfig}")
        val conn = ConnectionFactory.createConnection(this.hbaseConfig)
        conns += (zkQuorum -> conn)
        conn
      //        throw new RuntimeException(s"connection to $zkQuorum is not established.")
      case Some(c) => c
    }
  }

  def withTimeout[T](zkQuorum: String, op: => Future[T], fallback: => T)(implicit timeout: Duration): Future[T] = {
    try {
      val client = getClient(zkQuorum)
      //      TimeoutFuture(op, fallback)(this.executionContext, timeout)
      op
    } catch {
      case e: Throwable =>
        Logger.error(s"withTimeout: $e", e)
        Future {
          fallback
        }(this.executionContext)
    }
  }

  def defferedToFuture[A](d: Deferred[A])(fallback: A): Future[A] = {
    val promise = Promise[A]

    d.addBoth(new Callback[Unit, A] {
      def call(arg: A) = arg match {
        case e: Throwable =>
          Logger.error(s"deferred return throwable: $e", e)
          promise.success(fallback)
        case _ => promise.success(arg)
      }
    })

    promise.future
  }

  def deferredToFutureWithoutFallback[T](d: Deferred[T]): Future[T] = {
    val promise = Promise[T]
    d.addBoth(new Callback[Unit, T] {
      def call(arg: T) = arg match {
        case e: Throwable =>
          Logger.error(s"deferred return throwable: $e", e)
          promise.failure(e)
        case _ => promise.success(arg)
      }
    })
    promise.future
  }

  def deferredCallbackWithFallback[T, R](d: Deferred[T])(f: T => R, fallback: => R) = {
    d.addCallback(new Callback[R, T] {
      def call(args: T): R = {
        f(args)
      }
    }).addErrback(new Callback[R, Exception] {
      def call(e: Exception): R = {
        Logger.error(s"Exception on deferred: $e", e)
        fallback
      }
    })
  }

  //  def deferredCallback[R, T](d: Deferred[T])(f: T => R, fallback: => R) = {
  //    d.addCallback(new Callback[R, T]{
  //      def call(args: T): R = {
  //        args match {
  //          case ex: Throwable =>
  //            Logger.error(s"$ex", ex)
  //
  //        }
  //      }
  //    })
  //  }
  def writeAsync(zkQuorum: String, elementRpcs: Seq[Seq[HBaseRpc]]): Future[Seq[Boolean]] = {
    implicit val ex = this.executionContext
    if (elementRpcs.isEmpty) {
      Future.successful(Seq.empty[Boolean])
    } else {
      val client = getClient(zkQuorum)
      val defers = elementRpcs.map { rpcs =>
        //TODO: register errorBacks on this operations to log error


        val defer = rpcs.map { rpc =>
          Logger.error(s"$rpc")
          val deferred = rpc match {
            case d: DeleteRequest => client.delete(d)
            case p: PutRequest => client.put(p)
            case i: AtomicIncrementRequest => client.bufferAtomicIncrement(i)
          }
          //          deferredCallbackWithFallback(deferred)({
          //            (anyRef: Any) => anyRef match {
          //              case e: Exception => false
          //              case _ => true
          //            }
          //          }, {
          //            false
          //          })
        }
        //        val ret = deferredToFutureWithoutFallback(Deferred.group(defer)).map { arr => arr.forall(identity) }
        //        ret
      }
      //      Future.sequence(defers)
      Future.successful(elementRpcs.map(x => true))
    }
  }

  /**
   * Edge
   */
  //  def mutateEdge(edge: Edge): Unit = {
  //    save(edge.label.hbaseZkAddr, edge.label.hbaseTableName, edge.buildPutsAll())
  //  }

  //only for testcase.
  def getEdgesSync(q: Query): Seq[QueryResult] = {
    Await.result(getEdgesAsync(q), 10 seconds)
  }

  //select

  /**
   *
   */
  def getEdgesAsync(q: Query): Future[Seq[QueryResult]] = {
    implicit val ex = this.executionContext
    // not sure this is right. make sure refactor this after.
    try {
      if (q.steps.isEmpty) {
        // TODO: this should be get vertex query.
        Future.successful(q.vertices.map(v => QueryResult(QueryParam.empty)))
      } else {
        var seedEdgesFuture: Future[Seq[QueryResult]] = Future.successful(QueryResult.fromVertices(q.vertices, q.steps.head.queryParams))
        for {
          (step, idx) <- q.steps.zipWithIndex
        } {
          seedEdgesFuture = getEdgesAsyncWithRank(seedEdgesFuture, q, idx)
        }
        seedEdgesFuture
      }
    } catch {
      case e: Throwable =>
        Logger.error(s"getEdgesAsync: $e", e)
        Future.successful(q.vertices.map(v => QueryResult(QueryParam.empty)))
    }
  }


  private def fetchEdgesLs(currentStepRequestLss: Seq[(Iterable[(Object, QueryParam)], Double)]): Seq[Deferred[QueryResult]] = {
    for {
      (prevStepTgtVertexResultLs, prevScore) <- currentStepRequestLss
      (fetchRequest, queryParam) <- prevStepTgtVertexResultLs if fetchRequest.isInstanceOf[GetRequest] || fetchRequest.isInstanceOf[Scanner]
    } yield {
      //      fetchEdges(getRequest, queryParam, prevScore)
      fetchEdgesWithCache(fetchRequest, queryParam, prevScore)
    }
  }


  private def fetchEdgesWithCache(fetchRequest: Object, queryParam: QueryParam, prevScore: Double): Deferred[QueryResult] = {
    val cacheKey = MurmurHash3.stringHash(fetchRequest.toString)
    def queryResultCallback(cacheKey: Int) = new Callback[QueryResult, QueryResult] {
      def call(arg: QueryResult): QueryResult = {
        Logger.debug(s"queryResultCachePut, $arg")
        cache.put(cacheKey, arg)
        arg
      }
    }
    if (queryParam.cacheTTLInMillis > 0) {
      val cacheTTL = queryParam.cacheTTLInMillis
      if (cache.asMap().containsKey(cacheKey)) {
        val cachedVal = cache.asMap().get(cacheKey)
        val elapsedTime = queryParam.timestamp - cachedVal.timestamp
        if (elapsedTime < cacheTTL) {
          Logger.debug(s"cacheHitAndValid: $cacheKey, $cacheTTL, $elapsedTime")
          Deferred.fromResult(cachedVal)
        }
        else {
          cache.asMap().remove(cacheKey)
          Logger.debug(s"cacheHitInvalid: $cacheKey, $cacheTTL")
          fetchEdges(fetchRequest, queryParam, prevScore).addBoth(queryResultCallback(cacheKey))
        }
      } else {
        Logger.debug(s"cacheMiss: $cacheKey")
        fetchEdges(fetchRequest, queryParam, prevScore).addBoth(queryResultCallback(cacheKey))
      }
    } else {
      Logger.debug(s"cacheMiss: $cacheKey")
      fetchEdges(fetchRequest, queryParam, prevScore)
    }
  }


  /** actual request to HBase */
  private def fetchEdges(fetchRequest: Object, queryParam: QueryParam,
                         prevScore: Double, isSnapshotEdge: Boolean = false): Deferred[QueryResult] = {
    try {
      val client = getClient(queryParam.label.hbaseZkAddr)
      /** now hbase result should be ArrayList[ArrayList[KeyValue]] */
      fetchRequest match {
        case get: GetRequest =>
          deferredCallbackWithFallback(client.get(get))({ kvs =>
            val edgeWithScores = for {
              kv <- kvs
              edge <- Edge.toEdge(Seq(kv), queryParam, isSnapshotEdge = isSnapshotEdge)
            } yield {
                (edge, edge.rank(queryParam.rank) * prevScore)
              }
            QueryResult(queryParam, new ArrayList(edgeWithScores))
          }, QueryResult(queryParam))

        case scanner: Scanner =>
          deferredCallbackWithFallback(scanner.nextRows())({ rows =>
            if (rows == null || rows.isEmpty) {
              QueryResult(queryParam)
            } else {
              val edgeWithScores = for {
                kvs <- rows
                edge <- Edge.toEdge(kvs, queryParam, isSnapshotEdge = isSnapshotEdge)
              } yield {
                  (edge, edge.rank(queryParam.rank) * prevScore)
                }
              val ret = QueryResult(queryParam, new ArrayList(edgeWithScores))
              Logger.debug(s"getEdge: $ret")
              ret
            }
          }, QueryResult(queryParam))
      }
    } catch {
      case e@(_: Throwable | _: Exception) =>
        Logger.error(s"Exception: $e", e)
        Deferred.fromResult(QueryResult(queryParam))
    }
  }

  private def alreadyVisitedVertices(queryResultLs: Seq[QueryResult]) = {
    val vertices = for {
      queryResult <- queryResultLs
      (edge, score) <- queryResult.edgeWithScoreLs
    } yield {
        (edge.labelWithDir, if (edge.labelWithDir.dir == GraphUtil.directions("out")) edge.tgtVertex else edge.srcVertex) -> true
      }
    vertices.toMap
  }

  private def getEdgesAsyncWithRank(queryResultsLs: Seq[QueryResult],
                                    q: Query,
                                    stepIdx: Int): Future[Seq[QueryResult]] = {
    implicit val ex = executionContext

    val step = q.steps(stepIdx)
    val alreadyVisited =
      if (stepIdx == 0) Map.empty[(LabelWithDirection, Vertex), Boolean]
      else alreadyVisitedVertices(queryResultsLs)

    val nextStepSrcVertices = for {
      queryResult <- queryResultsLs
      (edge, score) <- queryResult.edgeWithScoreLs
    } yield {
        (edge.tgtVertex -> score)
      }
    val currentStepRequestLss = buildGetRequests(nextStepSrcVertices, step.queryParams)

    val queryParams = currentStepRequestLss.flatMap { case (getsWithQueryParams, prevScore) =>
      getsWithQueryParams.map { case (get, queryParam) => queryParam }
    }
    val fallback = new util.ArrayList(queryParams.map(param => QueryResult(param)))
    val deffered = fetchEdgesLs(currentStepRequestLss)
    val grouped: Deferred[util.ArrayList[QueryResult]] = Deferred.group(deffered)

    filterEdges(defferedToFuture(grouped)(fallback), q, stepIdx, alreadyVisited)
  }

  def getEdgesAsyncWithRank(queryResultLsFuture: Future[Seq[QueryResult]], q: Query, stepIdx: Int): Future[Seq[QueryResult]] = {
    implicit val ex = executionContext
    for {
      queryResultLs <- queryResultLsFuture
      //      (queryParam, edgeWithScoreLs) <- srcEdges
      // prevStep: (QueryParam, Seq[(Edge, Double)]), q: Query, stepIdx: Int): Future[Seq[(QueryParam, Iterable[(Edge, Double)])]] = {
      ret <- getEdgesAsyncWithRank(queryResultLs, q, stepIdx)
    } yield {
      ret
    }
  }

  def getEdge(srcVertex: Vertex, tgtVertex: Vertex, queryParam: QueryParam, prevScore: Double = 1.0): Future[QueryResult] = {
    implicit val ex = this.executionContext


    val label = queryParam.label
    val dir = queryParam.labelWithDir.dir
    val invertedEdge = Edge(srcVertex, tgtVertex, queryParam.labelWithDir).toInvertedEdgeHashLike()

    Logger.debug(s"getEdge: ${srcVertex.innerId}, ${tgtVertex.innerId}, ${srcVertex.innerId.bytes.toList}, ${tgtVertex.innerId.bytes.toList}")
    val fetchRequest =
        queryParam.tgtVertexInnerIdOpt(Option(tgtVertex.innerId)).buildGetRequest(srcVertex)

//    val keyValue = invertedEdge.keyValues.head
//    val qualifier = keyValue.qualifier
//
//    val client = getClient(label.hbaseZkAddr)
//    val getRequest = new GetRequest(label.hbaseTableName.getBytes(), keyValue.key, edgeCf, qualifier)
//    Logger.debug(s"$getRequest")

    /** now hbase result should be ArrayList[ArrayList[KeyValue]] */
    val deferred = fetchEdges(fetchRequest, queryParam, 1.0, isSnapshotEdge = true)

    defferedToFuture(deferred)(QueryResult(queryParam)).map { queryResult =>
      queryResult
    }
  }

  def checkEdges(quads: Seq[(Vertex, Vertex, Label, Int)]): Future[Seq[QueryResult]] = {
    implicit val ex = this.executionContext
    val futures = for {
      (srcVertex, tgtVertex, label, dir) <- quads
      queryParam = QueryParam(LabelWithDirection(label.id.get, dir))
    } yield getEdge(srcVertex, tgtVertex, queryParam)

    Future.sequence(futures)
  }


  def buildGetRequests(startVertices: Seq[(Vertex, Double)], params: List[QueryParam]): Seq[(Iterable[(Object, QueryParam)], Double)] = {
    for {
      (vertex, score) <- startVertices
    } yield {
      val requests = for {
        param <- params
      } yield {
          param.label.schemaVersion match {
            case InnerVal.VERSION3 => (param.buildScanRequest(vertex), param)
            case _ => (param.buildGetRequest(vertex), param)
          }
        }
      (requests, score)
    }
  }


  def convertEdge(edge: Edge, labelOutputFields: Map[Int, Byte]): Option[Edge] = {
    labelOutputFields.get(edge.labelWithDir.labelId) match {
      case None => Some(edge)
      case Some(outputField) =>
        edge.propsWithTs.get(outputField) match {
          case None => None
          case Some(outputFieldVal) =>
            Some(edge.updateTgtVertex(outputFieldVal.innerVal))
        }
    }
  }

  def filterEdges(queryResultLsFuture: Future[ArrayList[QueryResult]],
                  q: Query,
                  stepIdx: Int,
                  alreadyVisited: Map[(LabelWithDirection, Vertex), Boolean] =
                  Map.empty[(LabelWithDirection, Vertex), Boolean]): Future[Seq[QueryResult]] = {
    implicit val ex = Graph.executionContext
    queryResultLsFuture.map { queryResultLs =>
      val step = q.steps(stepIdx)
      val labelOutputFields = step.queryParams.map { qParam => qParam.outputField.map(outputField => qParam.labelWithDir.labelId -> outputField) }.flatten.toMap
      val excludeLabelWithDirs = (for (queryParam <- step.queryParams if queryParam.exclude) yield queryParam.labelWithDir).toSet
      val includeLabelWithDirOpt = (for (queryParam <- step.queryParams if queryParam.include) yield queryParam.labelWithDir).toSet.headOption
      val seen = new HashMap[(Vertex, LabelWithDirection, Vertex), Double]
      val excludeFromTos = new HashSet[(String, String)]
      val includeFromTos = new HashSet[(String, String)]

      val hasIncludeLabel = includeLabelWithDirOpt.isDefined

      for {
        queryResult <- queryResultLs
        (edge, score) <- queryResult.edgeWithScoreLs
      } {
        //        Logger.debug(s"${edge.toStringRaw}")
        if (hasIncludeLabel && edge.labelWithDir == includeLabelWithDirOpt.get) {
          //          includeFromTos += (edge.srcVertex.id -> edge.tgtVertex.id)
          includeFromTos += (edge.srcVertex.id.toString -> edge.tgtVertex.id.toString)
        }
        if (excludeLabelWithDirs.contains(edge.labelWithDir)) {
          excludeFromTos += (edge.srcVertex.id.toString -> edge.tgtVertex.id.toString)
        }
      }

      val queryResultLsConverted = for {
        queryResult <- queryResultLs
      } yield {
          val edgeWithScoreLs = for {
            (edge, score) <- queryResult.edgeWithScoreLs
            fromTo = (edge.srcVertex.id.toString -> edge.tgtVertex.id.toString)
            if !excludeFromTos.contains(fromTo)
            if (!hasIncludeLabel || includeFromTos.contains(fromTo))
            convertedEdge <- convertEdge(edge, labelOutputFields)
            key = (convertedEdge.labelWithDir, convertedEdge.tgtVertex)
            //          if !seen.contains(key)
            if filterDuplicates(seen, queryResult.queryParam, edge, score)
            if !(q.removeCycle && alreadyVisited.contains(key))
          } yield {
              //          seen += key

              (convertedEdge, score)
            }
          QueryResult(queryResult.queryParam, edgeWithScoreLs)
        }

      for {
        queryResult <- queryResultLsConverted
      } yield {
        val edgeWithScoreLs = for {
          (edge, score) <- queryResult.edgeWithScoreLs
          key = (edge.srcVertex, edge.labelWithDir, edge.tgtVertex)
          aggregatedScore = seen.getOrElse(key, score)
        } yield {
            (edge, aggregatedScore)
          }
        QueryResult(queryResult.queryParam, edgeWithScoreLs)
      }
    }
  }

  private def filterDuplicates(seen: HashMap[(Vertex, LabelWithDirection, Vertex), Double], queryParam: QueryParam,
                               edge: Edge, score: Double) = {
    val key = (edge.srcVertex, edge.labelWithDir, edge.tgtVertex)
    val newScore = queryParam.duplicatePolicy match {
      case Query.DuplicatePolicy.CountSum => 1.0
      case _ => score
    }
    seen.get(key) match {
      case None =>
        seen += (key -> newScore)
        true
      case Some(oldScore) =>

        queryParam.duplicatePolicy match {
          case Query.DuplicatePolicy.First =>
            // use first occurrence`s score
            false
          case Query.DuplicatePolicy.Raw =>
            // TODO: assumes all duplicate vertices will have same score
            seen += (key -> newScore)
            true
          case _ =>
            // aggregate score
            seen += (key -> (oldScore + newScore))
            false
        }
    }
  }


  def getVerticesAsync(vertices: Seq[Vertex]): Future[Seq[Vertex]] = {
    // TODO: vertex needs meta for hbase table.
    //    play.api.Logger.error(s"$vertices")
    implicit val ex = executionContext
    val futures = vertices.map { vertex =>
      withTimeout[Option[Vertex]](vertex.hbaseZkAddr, {
        //          val get = vertex.buildGet
        val client = getClient(vertex.hbaseZkAddr)
        //        val get = vertex.buildGetRequest()
        val get = vertex.buildGet
        defferedToFuture(client.get(get))(emptyKVs).map { kvs =>
          Vertex(kvs, vertex.serviceColumn.schemaVersion)
        }
        //          Logger.error(s"$get")
      }, {
        None
      })(singleGetTimeout)

    }
    Future.sequence(futures).map { result => result.toList.flatten }
  }

  /**
   * Vertex
   */
  def mutateEdge(edge: Edge): Future[Boolean] = {
    implicit val ex = this.executionContext
    writeAsync(edge.label.hbaseZkAddr, Seq(edge).map(e => e.buildPutsAll())).map { rets =>
      rets.forall(identity)
    }
  }

  def mutateEdges(edges: Seq[Edge]): Future[Seq[Boolean]] = {
    implicit val ex = this.executionContext
    val futures = edges.map { edge => mutateEdge(edge) }
    Future.sequence(futures)
  }

  def mutateVertex(vertex: Vertex): Future[Boolean] = {
    implicit val ex = this.executionContext
    if (vertex.op == GraphUtil.operations("delete") || vertex.op == GraphUtil.operations("deleteAll")) {
      //      throw new RuntimeException("Not yet supported")
      deleteVertexAll(vertex).onComplete {
        case Success(s) => Logger.info(s"mutateVertex($vertex) for deleteAll successed.")
        case Failure(ex) => Logger.error(s"mutateVertex($vertex) for deleteAll failed. $ex", ex)
      }
      Future.successful(true)
    } else {
      writeAsync(vertex.hbaseZkAddr, Seq(vertex).map(v => v.buildPutsAll())).map { rets =>
        rets.forall(identity)
      }
    }
  }

  def mutateVertices(vertices: Seq[Vertex]): Future[Seq[Boolean]] = {
    implicit val ex = this.executionContext
    val futures = vertices.map { vertex => mutateVertex(vertex) }
    Future.sequence(futures)
  }

  private def deleteVertex(vertex: Vertex): Future[Boolean] = {
    implicit val ex = this.executionContext
    writeAsync(vertex.hbaseZkAddr, Seq(vertex).map(_.buildDeleteAsync())).map { rets =>
      rets.forall(identity)
    }
  }

  private def deleteVertices(vertices: Seq[Vertex]): Future[Seq[Boolean]] = {
    implicit val ex = this.executionContext
    val futures = vertices.map { vertex => deleteVertex(vertex) }
    Future.sequence(futures)
  }

  /**
   * O(E), maynot feasable
   */

  private def deleteVertexAll(vertex: Vertex): Future[Boolean] = {
    implicit val ex = this.executionContext

    val labels = (Label.findBySrcColumnId(vertex.id.colId) ++
      Label.findByTgtColumnId(vertex.id.colId)).groupBy(_.id.get).map {
      _._2.head
    }

    /** delete vertex only */
    for {
      relEdgesOutDeleted <- deleteVertexAllAsync(vertex, labels.toSeq, GraphUtil.directions("out"))
      relEdgesInDeleted <- deleteVertexAllAsync(vertex, labels.toSeq, GraphUtil.directions("in"))
      vertexDeleted <- deleteVertex(vertex)
    } yield {
      relEdgesOutDeleted && relEdgesInDeleted && vertexDeleted
    }
  }

  private def deleteVertexAllAsync(srcVertex: Vertex, labels: Seq[Label], dir: Int): Future[Boolean] = {
    implicit val ex = Graph.executionContext

    val queryParams = for {
      label <- labels
    } yield {
        val labelWithDir = LabelWithDirection(label.id.get, dir)
        QueryParam(labelWithDir).limit(0, maxValidEdgeListSize * 5)
      }

    val step = Step(queryParams.toList)
    val q = Query(List(srcVertex), List(step), true)
    for {
      queryResultLs <- getEdgesAsync(q)
      invertedFutures = for {
        queryResult <- queryResultLs
        (edge, score) <- queryResult.edgeWithScoreLs
      } yield {
          val convertedEdge = if (dir == GraphUtil.directions("out")) {
            Edge(edge.srcVertex, edge.tgtVertex, edge.labelWithDir, edge.op, edge.ts, srcVertex.ts, edge.propsWithTs)
          } else {
            Edge(edge.tgtVertex, edge.srcVertex, edge.labelWithDir, edge.op, edge.ts, srcVertex.ts, edge.propsWithTs)
          }
          Logger.debug(s"ConvertedEdge: $convertedEdge")
          for {
            rets <- writeAsync(edge.label.hbaseZkAddr, Seq(convertedEdge).map { e =>
              e.buildDeleteBulk()
            })
          } yield {
            rets.forall(identity)
          }
        }
      ret <- Future.sequence(invertedFutures).map { rets => rets.forall(identity) }
    } yield ret
  }


  def mutateElements(elemnents: Seq[GraphElement]): Future[Seq[Boolean]] = {
    implicit val ex = this.executionContext
    val futures = elemnents.map { element =>
      element match {
        case edge: Edge => mutateEdge(edge)
        case vertex: Vertex => mutateVertex(vertex)
        case _ => throw new RuntimeException(s"$element is not edge/vertex")
      }
    }
    Future.sequence(futures)
  }


  // select
  def getVertex(vertex: Vertex): Future[Option[Vertex]] = {
    implicit val ex = executionContext
    val client = getClient(vertex.hbaseZkAddr)
    defferedToFuture(client.get(vertex.buildGet))(emptyKVs).map { kvs =>
      Vertex(kvs, vertex.serviceColumn.schemaVersion)
    }
  }

  /**
   * Bulk
   */

  /**
   * when how from to what direction (meta info key:value)
   * ex) timestamp insert shon sol talk_friend directed/undirected propperties
   * ex) timestamp insert shon talk_user_id propperties
   *
   */
  def toGraphElement(s: String, labelMapping: Map[String, String] = Map.empty): Option[GraphElement] = {
    val parts = GraphUtil.split(s)
    try {
      val logType = parts(2)
      val element = if (logType == "edge" | logType == "e") {
        /** current only edge is considered to be bulk loaded */
        labelMapping.get(parts(5)) match {
          case None =>
          case Some(toReplace) =>
            parts(5) = toReplace
        }
        toEdge(parts)
      } else if (logType == "vertex" | logType == "v") {
        toVertex(parts)
      } else {
        throw new KGraphExceptions.JsonParseException("log type is not exist in log.")
      }
      element
    } catch {
      case e: Throwable =>
        Logger.error(s"$e", e)
        None
    }
  }

  def bulkMutates(elements: Iterable[GraphElement], mutateInPlace: Boolean = false) = {
    val vertices = new ListBuffer[Vertex]
    val edges = new ListBuffer[Edge]
    for (e <- elements) {
      e match {
        case edge: Edge => edges += edge
        case vertex: Vertex => vertices += vertex
        case _ => throw new Exception("GraphElement should be either vertex or edge.")
      }
    }
    mutateVertices(vertices)
    mutateEdges(edges)
  }

  def toVertex(s: String): Option[Vertex] = {
    toVertex(GraphUtil.split(s))
  }

  def toEdge(s: String): Option[Edge] = {
    toEdge(GraphUtil.split(s))
  }

  //"1418342849000\tu\te\t3286249\t71770\ttalk_friend\t{\"is_hidden\":false}"
  //{"from":1,"to":101,"label":"graph_test","props":{"time":-1, "weight":10},"timestamp":1417616431},
  def toEdge(parts: Array[String]): Option[Edge] = {
    try {
      val (ts, operation, logType, srcId, tgtId, label) = (parts(0), parts(1), parts(2), parts(3), parts(4), parts(5))
      val props = if (parts.length >= 7) parts(6) else "{}"
      val direction = if (parts.length >= 8) parts(7) else "out"
      val edge = Management.toEdge(ts.toLong, operation, srcId, tgtId, label, direction, props)
      //            Logger.debug(s"toEdge: $edge")
      Some(edge)
    } catch {
      case e: Throwable =>
        Logger.error(s"toEdge: $e", e)
        throw e
    }
  }

  //"1418342850000\ti\tv\t168756793\ttalk_user_id\t{\"country_iso\":\"KR\"}"
  def toVertex(parts: Array[String]): Option[Vertex] = {
    try {
      val (ts, operation, logType, srcId, serviceName, colName) = (parts(0), parts(1), parts(2), parts(3), parts(4), parts(5))
      val props = if (parts.length >= 7) parts(6) else "{}"
      Some(Management.toVertex(ts.toLong, operation, srcId, serviceName, colName, props))
    } catch {
      case e: Throwable =>
        Logger.error(s"toVertex: $e", e)
        throw e
    }
  }

}
