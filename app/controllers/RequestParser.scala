package controllers

import com.daumkakao.s2graph.core._
import com.daumkakao.s2graph.core.mysqls._
import config.Config

//import com.daumkakao.s2graph.core.models._
import com.daumkakao.s2graph.core.parsers.WhereParser
import com.daumkakao.s2graph.core.types2._
import play.api.Logger
import play.api.libs.json._

trait RequestParser extends JSONParser {

  val hardLimit = Config.QUERY_HARD_LIMIT
  val defaultLimit = 100

  private def extractScoring(labelId: Int, value: JsValue) = {
    val ret = for {
      js <- parse[Option[JsObject]](value, "scoring")
    } yield {
        for {
          (k, v) <- js.fields
          labelOrderType <- LabelMeta.findByName(labelId, k)
        } yield {
          val value = v match {
            case n: JsNumber => n.as[Double]
            case _ => throw new Exception("scoring weight should be double.")
          }
          (labelOrderType.seq, value)
        }
      }
    ret
  }

  def extractInterval(label: Label, jsValue: JsValue) = {
    val ret = for {
      js <- parse[Option[JsObject]](jsValue, "interval")
      fromJs <- parse[Option[JsObject]](js, "from")
      toJs <- parse[Option[JsObject]](js, "to")
    } yield {
        val from = Management.toProps(label, fromJs)
        val to = Management.toProps(label, toJs)
        (from, to)
      }
    //    Logger.debug(s"extractInterval: $ret")
    ret
  }

  def extractDuration(label: Label, jsValue: JsValue) = {
    for {
      js <- parse[Option[JsObject]](jsValue, "duration")
    } yield {
      val minTs = parse[Option[Long]](js, "from").getOrElse(Long.MaxValue)
      val maxTs = parse[Option[Long]](js, "to").getOrElse(Long.MinValue)
      (minTs, maxTs)
    }
  }

  def extractHas(label: Label, jsValue: JsValue) = {
    val ret = for {
      js <- parse[Option[JsObject]](jsValue, "has")
    } yield {
        for {
          (k, v) <- js.fields
          labelMeta <- LabelMeta.findByName(label.id.get, k)
          value <- jsValueToInnerVal(v, labelMeta.dataType, label.schemaVersion)
        } yield {
          (labelMeta.seq -> value)
        }
      }
    ret.map(_.toMap).getOrElse(Map.empty[Byte, InnerValLike])
  }

  //  def hasOrWhere(label: HLabel, jsValue: JsValue): Set[InnerVal] = {
  //    for {
  //      (propName, v) <- jsValue.as[JsObject].fields
  //      propMeta <- HLabelMeta.findByName(label.id.get, propName)
  ////      innerVal <- jsValueToInnerVal(v, propMeta.dataType)
  //    }  yield {
  //      propName -> (v match {
  //        case arr: JsArray => // set
  //          Set(arr.as[List[JsValue]].flatMap { e =>
  //            jsValueToInnerVal(e, propMeta.dataType)
  //          })
  //        case value: JsValue => // exact
  //          Set(List(v).flatMap { jsValue =>
  //            jsValueToInnerVal(jsValue, propMeta.dataType)
  //          })
  //        case obj: JsObject => // from, to
  //          val (fromJsVal, toJsVal) = ((obj \ "from").as[JsValue], (obj \ "to").as[JsValue])
  //          val (from, to) = (toInnerVal(fromJsVal), toInnerVal(toJsVal))
  //          (from, to)
  //      })
  //    }
  //  }
  def extractWhere(label: Label, jsValue: JsValue) = {
    (jsValue \ "where").asOpt[String].flatMap { where =>
      WhereParser(label).parse(where)
    }
  }

  val errorLogger = Logger("error")

  def toVertices(labelName: String, direction: String, ids: Seq[JsValue]): Seq[Vertex] = {
    val vertices = for {
      label <- Label.findByName(labelName).toSeq
      serviceColumn = if (direction == "out") label.srcColumn else label.tgtColumn
      id <- ids
      innerId <- jsValueToInnerVal(id, serviceColumn.columnType, label.schemaVersion)
    } yield {
      Vertex(SourceVertexId(serviceColumn.id.get, innerId), System.currentTimeMillis())
    }
    vertices.toSeq
  }

  def toQuery(jsValue: JsValue, isEdgeQuery: Boolean = true): Query = {
    try {
      val vertices =
        (for {
          value <- parse[List[JsValue]](jsValue, "srcVertices")
          serviceName = parse[String](value, "serviceName")
          service <- Service.findByName(serviceName)
          column <- parse[Option[String]](value, "columnName")
          col <- ServiceColumn.find(service.id.get, column)
        } yield {
            val (idOpt, idsOpt) = ((value \ "id").asOpt[JsValue], (value \ "ids").asOpt[List[JsValue]])
            for {
              idVal <- idOpt ++ idsOpt.toSeq.flatten
              /** bug, need to use labels schemaVersion  */
              innerVal <- jsValueToInnerVal(idVal, col.columnType, col.schemaVersion)
            } yield {
              Vertex(SourceVertexId(col.id.get, innerVal), System.currentTimeMillis())
            }
          }).flatten

      val filterOutQuery = (jsValue \ "filterOut").asOpt[JsValue].map { v => toQuery(v) }
      val steps = parse[List[JsValue]](jsValue, "steps")
      val removeCycle = (jsValue \ "removeCycle").asOpt[Boolean].getOrElse(true)
      val selectColumns = (jsValue \ "select").asOpt[List[String]].getOrElse(List.empty)
      val groupByColumns = (jsValue \ "groupBy").asOpt[List[String]].getOrElse(List.empty)
      val querySteps =
        steps.map { step =>
          val labelWeights = step match {
            case obj: JsObject =>
              val converted = for {
                (k, v) <- (obj \ "weights").asOpt[JsObject].getOrElse(Json.obj()).fields
                l <- Label.findByName(k)
              } yield {
                  l.id.get -> v.toString().toDouble
                }
              converted.toMap
            case _ => Map.empty[Int, Double]
          }
          val queryParamJsVals = step match {
            case arr: JsArray => arr.as[List[JsValue]]
            case obj: JsObject => (obj \ "step").as[List[JsValue]]
            case _ => List.empty[JsValue]
          }
//          val stepThreshold = step match {
//            case obj: JsObject => (obj \ "stepThreshold").asOpt[Double].getOrElse(0.0)
//            case _ => 0.0
//          }
          val nextStepScoreThreshold = step match {
            case obj: JsObject => (obj \ "nextStepThreshold").asOpt[Double].getOrElse(0.0)
            case _ => 0.0
          }
          val nextStepLimit = step match {
            case obj: JsObject => (obj \ "nextStepLimit").asOpt[Int].getOrElse(-1)
            case _ => -1
          }
          val queryParams =
            for {
              labelGroup <- queryParamJsVals
              queryParam <- parseQueryParam(labelGroup)
            } yield {
              queryParam
            }
          Step(queryParams.toList, labelWeights = labelWeights,
//            scoreThreshold = stepThreshold,
            nextStepScoreThreshold = nextStepScoreThreshold, nextStepLimit = nextStepLimit)
        }

      val ret = Query(vertices, querySteps, removeCycle = removeCycle,
        selectColumns = selectColumns, groupByColumns = groupByColumns, filterOutQuery = filterOutQuery)
      //          Logger.debug(ret.toString)
      ret
    } catch {
      case e: Throwable =>
        errorLogger.error(s"$e", e)
        throw new KGraphExceptions.BadQueryException(s"$jsValue", e)
    }
  }

  private def parseQueryParam(labelGroup: JsValue): Option[QueryParam] = {
    for {
      labelName <- parse[Option[String]](labelGroup, "label")
      label <- Label.findByName(labelName)
    } yield {
      val direction = parse[Option[String]](labelGroup, "direction").map(GraphUtil.toDirection(_)).getOrElse(0)
      val limit = {
        parse[Option[Int]](labelGroup, "limit") match {
          case None => defaultLimit
          case Some(l) if l < 0 => Int.MaxValue
          case Some(l) if l >= 0 =>
            val default = hardLimit
            Math.min(l, default)
        }
      }
      val offset = parse[Option[Int]](labelGroup, "offset").getOrElse(0)
      val interval = extractInterval(label, labelGroup)
      val duration = extractDuration(label, labelGroup)
      val scorings = extractScoring(label.id.get, labelGroup).getOrElse(List.empty[(Byte, Double)]).toList
      val labelOrderSeq = label.findLabelIndexSeq(scorings)
      val exclude = parse[Option[Boolean]](labelGroup, "exclude").getOrElse(false)
      val include = parse[Option[Boolean]](labelGroup, "include").getOrElse(false)
      val hasFilter = extractHas(label, labelGroup)
      val labelWithDir = LabelWithDirection(label.id.get, direction)
      val indexSeq = label.indexSeqsMap.get(scorings.map(kv => kv._1)).map(x => x.seq).getOrElse(LabelIndex.defaultSeq)
      val where = extractWhere(label, labelGroup)
      val includeDegree = (labelGroup \ "includeDegree").asOpt[Boolean].getOrElse(true)
      val rpcTimeout = (labelGroup \ "rpcTimeout").asOpt[Int].getOrElse(Config.RPC_TIMEOUT)
      val maxAttempt = (labelGroup \ "maxAttempt").asOpt[Int].getOrElse(Config.MAX_ATTEMPT)
      val tgtVertexInnerIdOpt = (labelGroup \ "_to").asOpt[JsValue].flatMap { jsVal =>
        jsValueToInnerVal(jsVal, label.tgtColumnWithDir(direction).columnType, label.schemaVersion)
      }
      val cacheTTL = (labelGroup \ "cacheTTL").asOpt[Long].getOrElse(-1L)
      val timeDecayFactor = (labelGroup \ "timeDecay").asOpt[JsObject].map { jsVal =>
        val initial = (jsVal \ "initial").asOpt[Double].getOrElse(1.0)
        val decayRate = (jsVal \ "decayRate").asOpt[Double].getOrElse(0.1)
        val timeUnit = (jsVal \ "timeUnit").asOpt[Double].getOrElse(60 * 60 * 24.0)
        TimeDecay(initial, decayRate, timeUnit)
      }
      val threshold = (labelGroup \ "threshold").asOpt[Double].getOrElse(0.0)
      // TODO: refactor this. dirty
      val duplicate = parse[Option[String]](labelGroup, "duplicate").map(s => Query.DuplicatePolicy(s))

      val outputField = (labelGroup \ "outputField").asOpt[String].map(s => Json.arr(Json.arr(s)))
      val transformer = if (outputField.isDefined) outputField else (labelGroup \ "transform").asOpt[JsValue]

      QueryParam(labelWithDir).labelOrderSeq(labelOrderSeq)
        .limit(offset, limit)
        .rank(RankParam(label.id.get, scorings))
        .exclude(exclude)
        .include(include)
        .interval(interval)
        .duration(duration)
        .has(hasFilter)
        .labelOrderSeq(indexSeq)
//        .outputField(outputField)
//        .outputFields(outputFields)
        .where(where)
        .duplicatePolicy(duplicate)
        .includeDegree(includeDegree)
        .rpcTimeout(rpcTimeout)
        .maxAttempt(maxAttempt)
        .tgtVertexInnerIdOpt(tgtVertexInnerIdOpt)
        .cacheTTLInMillis(cacheTTL)
        .timeDecay(timeDecayFactor)
        .threshold(threshold)
        .transformer(transformer)
//        .excludeBy(excludeBy)
    }
  }

  private def parse[R](js: JsValue, key: String)(implicit read: Reads[R]): R = {
    (js \ key).validate[R]
      .fold(
        errors => {
          val msg = (JsError.toFlatJson(errors) \ "obj").as[List[JsValue]].map(x => x \ "msg")
          val e = Json.obj("args" -> key, "error" -> msg)
          throw new KGraphExceptions.JsonParseException(Json.obj("error" -> key).toString)
        },
        r => {
          r
        })
  }

  def toJsValues(jsValue: JsValue): List[JsValue] = {

    jsValue match {
      case obj: JsObject => List(obj)
      case arr: JsArray => arr.as[List[JsValue]]
      case _ => List.empty[JsValue]
    }

  }

  def toEdges(jsValue: JsValue, operation: String): List[Edge] = {

    toJsValues(jsValue).map(toEdge(_, operation))

  }

  def toEdge(jsValue: JsValue, operation: String) = {

    val srcId = parse[JsValue](jsValue, "from") match {
      case s: JsString => s.as[String]
      case o@_ => s"${o}"
    }
    val tgtId = parse[JsValue](jsValue, "to") match {
      case s: JsString => s.as[String]
      case o@_ => s"${o}"
    }
    val label = parse[String](jsValue, "label")
    val timestamp = parse[Long](jsValue, "timestamp")
    val direction = parse[Option[String]](jsValue, "direction").getOrElse("")
    val props = (jsValue \ "props").asOpt[JsValue].getOrElse("{}")
    Management.toEdge(timestamp, operation, srcId, tgtId, label, direction, props.toString)

  }

  def toVertices(jsValue: JsValue, operation: String, serviceName: Option[String] = None, columnName: Option[String] = None) = {
    toJsValues(jsValue).map(toVertex(_, operation, serviceName, columnName))
  }

  def toVertex(jsValue: JsValue, operation: String, serviceName: Option[String] = None, columnName: Option[String] = None): Vertex = {
    val id = parse[JsValue](jsValue, "id")
    val ts = parse[Option[Long]](jsValue, "timestamp").getOrElse(System.currentTimeMillis())
    val sName = if (serviceName.isEmpty) parse[String](jsValue, "serviceName") else serviceName.get
    val cName = if (columnName.isEmpty) parse[String](jsValue, "columnName") else columnName.get
    val props = (jsValue \ "props").asOpt[JsObject].getOrElse(Json.obj())
    Management.toVertex(ts, operation, id.toString, sName, cName, props.toString)
  }

  private[RequestParser] def jsObjDuplicateKeyCheck(jsObj: JsObject) = {
    assert(jsObj != null)
    if (jsObj.fields.map(_._1).groupBy(_.toString).map(r => r match {
      case (k, v) => v
    }).filter(_.length > 1).isEmpty == false)
      throw new KGraphExceptions.JsonParseException(Json.obj("error" -> s"$jsObj --> some key is duplicated").toString)
  }

  def parsePropsElements(jsValue: JsValue) = {
    for {
      jsObj <- jsValue.asOpt[List[JsValue]].getOrElse(Nil)
    } yield {
      val propName = (jsObj \ "name").as[String]
      val dataType = InnerVal.toInnerDataType((jsObj \ "dataType").as[String])
      val defaultValue = (jsObj \ "defaultValue")
      (propName, defaultValue, dataType)
    }
  }

  def toLabelElements(jsValue: JsValue) = {
    val labelName = parse[String](jsValue, "label")
    val srcServiceName = parse[String](jsValue, "srcServiceName")
    val tgtServiceName = parse[String](jsValue, "tgtServiceName")
    val srcColumnName = parse[String](jsValue, "srcColumnName")
    val tgtColumnName = parse[String](jsValue, "tgtColumnName")
    val srcColumnType = parse[String](jsValue, "srcColumnType")
    val tgtColumnType = parse[String](jsValue, "tgtColumnType")
    val serviceName = (jsValue \ "serviceName").asOpt[String].getOrElse(tgtServiceName)
    //    parse[String](jsValue, "serviceName")
    val isDirected = (jsValue \ "isDirected").asOpt[Boolean].getOrElse(true)
    val idxProps = parsePropsElements((jsValue \ "indexProps"))
    val metaProps = parsePropsElements((jsValue \ "props"))
    val consistencyLevel = (jsValue \ "consistencyLevel").asOpt[String].getOrElse("weak")
    // expect new label don`t provide hTableName
    val hTableName = (jsValue \ "hTableName").asOpt[String]
    val hTableTTL = (jsValue \ "hTableTTL").asOpt[Int]
    val schemaVersion = (jsValue \ "schemaVersion").asOpt[String].getOrElse(HBaseType.DEFAULT_VERSION)
    val isAsync = (jsValue \ "isAsync").asOpt[Boolean].getOrElse(false)
    val t = (labelName, srcServiceName, srcColumnName, srcColumnType,
      tgtServiceName, tgtColumnName, tgtColumnType, isDirected, serviceName,
      idxProps, metaProps, consistencyLevel, hTableName, hTableTTL, schemaVersion, isAsync)
    Logger.info(s"createLabel $t")
    t
  }

  def toIndexElements(jsValue: JsValue) = {
    val labelName = parse[String](jsValue, "label")
    val idxProps = parsePropsElements((jsValue \ "indexProps"))
    //    val js = (jsValue \ "indexProps").asOpt[JsObject].getOrElse(Json.parse("{}").as[JsObject])
    //    val props = for ((k, v) <- js.fields) yield (k, v)
    val t = (labelName, idxProps)
    t
  }

  def toServiceElements(jsValue: JsValue) = {
    val serviceName = parse[String](jsValue, "serviceName")
    val cluster = (jsValue \ "cluster").asOpt[String].getOrElse(Graph.config.getString("hbase.zookeeper.quorum"))
    val hTableName = (jsValue \ "hTableName").asOpt[String].getOrElse(s"${serviceName}-${Config.PHASE}")
    val preSplitSize = (jsValue \ "preSplitSize").asOpt[Int].getOrElse(1)
    val hTableTTL = (jsValue \ "hTableTTL").asOpt[Int]
    (serviceName, cluster, hTableName, preSplitSize, hTableTTL)
  }

  def toServiceColumnElements(jsValue: JsValue) = {
    val serviceName = parse[String](jsValue, "serviceName")
    val columnName = parse[String](jsValue, "columnName")
    val columnType = parse[String](jsValue, "columnType")
    val props = parsePropsElements(jsValue \ "props")
    (serviceName, columnName, columnType, props)
  }

  def toPropElements(jsValue: JsValue) = {
    val propName = parse[String](jsValue, "name")
    val defaultValue = parse[JsValue](jsValue, "defaultValue")
    val dataType = parse[String](jsValue, "dataType")
    val usedInIndex = parse[Option[Boolean]](jsValue, "usedInIndex").getOrElse(false)
    (propName, defaultValue, dataType, usedInIndex)
  }
}
