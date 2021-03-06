package com.daumkakao.s2graph.core.models

import com.daumkakao.s2graph.core._
import com.typesafe.config.Config
import org.apache.hadoop.hbase.util.Bytes
import org.apache.hadoop.hbase.{CellUtil, TableName}
import org.apache.hadoop.hbase.client._
import collection.JavaConversions._
import scala.collection.mutable
import scala.reflect.ClassTag

object Model extends LocalCache[Result] {
  val DELIMITER = ":"
  val KEY_VAL_DELIMITER = "^"
  val KEY_VAL_DELIMITER_WITH_ESCAPE = "\\^"
  val INNER_DELIMITER_WITH_ESCAPE = "\\|"
  val INNER_DELIMITER = "|"
  val META_SEQ_DELIMITER = "~"

  val modelCf = "m"
  val idQualifier = "i"
  val qualifier = "q"
  var zkQuorum: String = "localhost"
  var modelTableName = s"models-${Graph.defaultConfigs("phase")}"
  var cacheTTL: Int = 10
  var maxCacheSize: Int = 1000
  type KEY = String
  type VAL = Any
  def apply(config: Config) = {
    zkQuorum = config.getString("hbase.zookeeper.quorum")
    modelTableName = config.getString("s2graph.models.table.name")
    cacheTTL = config.getInt("cache.ttl.seconds")
    maxCacheSize = config.getInt("cache.max.size")
  }
//  def getTypeTag[T: ru.TypeTag](obj: T) = ru.typeTag[T]
//  def newInstance[T](clazz: Class)(implicit tag: ru.TypeTag[T]) = {
//    val m = ru.runtimeMirror(getClass.getClassLoader)
//    ru.typeTag[]
//    val symbol = ru.typeOf[].typeSymbol.asClass
//  }
  def getClassName[T: ClassTag] = {
    val clazz = implicitly[ClassTag[T]].runtimeClass
    clazz.getName().split("\\.").last
  }

  def newInstance[T: ClassTag](kvs: Map[KEY, VAL]) = {
    val clazz = implicitly[ClassTag[T]].runtimeClass
    val ctr = clazz.getConstructors()(0)
    ctr.newInstance(kvs).asInstanceOf[T]
 }

//  def newInstance(tableName: String)(kvs: Map[KEY, VAL]) = {
//    tableName match {
//      case "HService" => HService(kvs)
//      case "HServiceColumn" => HServiceColumn(kvs)
//      case "HColumnMeta" => HColumnMeta(kvs)
//      case "HLabelMeta" => HLabelMeta(kvs)
//      case "HLabelIndex" => HLabelIndex(kvs)
//      case "HLabel" => HLabel(kvs)
//      case _ => new HBaseModel(tableName, kvs)
//    }
//  }
  def apply(zkQuorum: String) = {
    this.zkQuorum = zkQuorum
  }
  def padZeros(v: VAL): String = {
    v match {
      case b: Byte => "%03d".format(b)
      case s: Short => "%05d".format(s)
      case i: Int => "%08d".format(i)
      case l: Long => "%08d".format(l)
      case ls: List[Any] => ls.mkString(",")
      case _ => v.toString
    }
  }

  def toKVTuplesMap(s: String) = {
    val tupleLs = for {
      kv <- s.split(KEY_VAL_DELIMITER_WITH_ESCAPE)
      t = kv.split(INNER_DELIMITER_WITH_ESCAPE) if t.length == 2
    } yield (t.head, t.last)
    tupleLs.toMap
  }
  def toKVs(kvs: Seq[(KEY, VAL)]) =  {
    val idxKVs = for {
      (k, v) <- kvs
    } yield s"$k$INNER_DELIMITER${padZeros(v)}"
    idxKVs.mkString(KEY_VAL_DELIMITER)
  }
  def toKVsWithFilter(kvs: Map[KEY, VAL],filterKeys: Seq[(KEY, VAL)]) = {
    val tgt = filterKeys.map(_._1).toSet
    val filtered = for {
      (k, v) <- kvs if !tgt.contains(k)
    } yield (k, padZeros(v))
    filtered.toSeq
  }
  def toRowKey(tableName: String, idxKeyVals: Seq[(KEY, VAL)]) = {
    List(tableName, toKVs(idxKeyVals)).mkString(DELIMITER)
  }
  def fromResultOnlyVal(r: Result): Array[Byte] = {
    if (r == null || r.isEmpty) Array.empty[Byte]
    val cell = r.getColumnLatestCell(modelCf.getBytes, qualifier.getBytes)
    CellUtil.cloneValue(cell)
  }
  def fromResult[T : ClassTag](r: Result): Option[T] = {
    if (r == null || r.isEmpty) None
    else {
       r.listCells().headOption.map { cell =>
        val rowKey = Bytes.toString(CellUtil.cloneRow(cell))
        val value = Bytes.toString(CellUtil.cloneValue(cell))
        val elements = rowKey.split(DELIMITER)
        val (tName, idxKeyVals) = (elements(0), elements(1))
        val merged = toKVTuplesMap(idxKeyVals) ++ toKVTuplesMap(value)
        newInstance[T](merged)
      }
    }
  }
  def fromResultLs[T : ClassTag](r: Result): List[T] = {
    if (r == null || r.isEmpty) List.empty[T]
    else {
      r.listCells().map { cell =>
        val rowKey = Bytes.toString(CellUtil.cloneRow(cell))
        val value = Bytes.toString(CellUtil.cloneValue(cell))
        val elements = rowKey.split(DELIMITER)
        val (tName, idxKeyVals) = (elements(0), elements(1))
        val merged = toKVTuplesMap(idxKeyVals) ++ toKVTuplesMap(value)
        newInstance[T](merged)
      } toList
    }
  }
  def toCacheKey[T: ClassTag](kvs: Seq[(KEY, VAL)]) = {
    val postfix = kvs.map { kv =>
      List(kv._1, kv._2).mkString(INNER_DELIMITER)
    }.mkString(KEY_VAL_DELIMITER)
    val prefix = getClassName[T]
    s"$prefix$KEY_VAL_DELIMITER$postfix"
  }
  def distincts[T: ClassTag](ls: List[T]): List[T] = {
    val uniq = new mutable.HashSet[String]
    for {
      r <- ls
      m = r.asInstanceOf[Model[_]]
      if m.kvs.containsKey("id") && !uniq.contains(m.kvs("id").toString)
    } yield {
      uniq += m.kvs("id").toString
      r
    }
  }
  def find[T : ClassTag](useCache: Boolean = true)(idxKeyVals: Seq[(KEY, VAL)]): Option[T] = {
    val fetchOp = {
      val table = Graph.getConn(zkQuorum).getTable(TableName.valueOf(modelTableName))
      try {

        val rowKey = toRowKey(getClassName[T], idxKeyVals)
//        println(s"${getClassName[T]}\t$rowKey")
        val get = new Get(rowKey.getBytes)
        get.addColumn(modelCf.getBytes, qualifier.getBytes)
        get.setMaxVersions(1)
        //        val res = table.get(get)
        val res = table.get(get)
//        println(s"result: ${res.listCells()}")
        res
        //        fromResult[T](res)
      } finally {
        table.close()
      }
    }
    val result =
      if (useCache) withCache(toCacheKey[T](idxKeyVals))(fetchOp)
      else fetchOp
    fromResult[T](result)
  }
  def findsRange[T : ClassTag](useCache: Boolean = true)(idxKeyVals: Seq[(KEY, VAL)],
                                                              endIdxKeyVals: Seq[(KEY, VAL)]): List[T] = {
    val fetchOp = {
      val table = Graph.getConn(zkQuorum).getTable(TableName.valueOf(modelTableName))
      try {
        val scan = new Scan()
        scan.setStartRow(toRowKey(getClassName[T], idxKeyVals).getBytes)
        scan.setStopRow(toRowKey(getClassName[T], endIdxKeyVals).getBytes)
        scan.addColumn(modelCf.getBytes, qualifier.getBytes)
        val resScanner = table.getScanner(scan)
        resScanner.toList
        //        val models = for {r <- resScanner; m <- fromResult[T](r)} yield m
        //        models.toList
      } finally {
        table.close()
      }
    }
    val results =
      if (useCache) withCaches(toCacheKey[T](idxKeyVals ++ endIdxKeyVals))(fetchOp)
      else fetchOp
    val rs = results.flatMap { r => fromResult[T](r) }
    distincts[T](rs)
  }
  def findsMatch[T : ClassTag](useCache: Boolean = true)(idxKeyVals: Seq[(KEY, VAL)]): List[T] = {
    val fetchOp = {
      val table = Graph.getConn(zkQuorum).getTable(TableName.valueOf(modelTableName))
      try {
        val scan = new Scan()
        scan.setStartRow(toRowKey(getClassName[T], idxKeyVals).getBytes)
        val endBytes = Bytes.add(toRowKey(getClassName[T], idxKeyVals).getBytes, Array.fill(1)(Byte.MinValue.toByte))
        scan.setStopRow(endBytes)
        scan.addColumn(modelCf.getBytes, qualifier.getBytes)
        val resScanner = table.getScanner(scan)
        resScanner.toList
        //        val models = for {r <- resScanner; m <- fromResult[T](r)} yield m
        //        models.toList
      } finally {
        table.close()
      }
    }
    val results =
      if (useCache) withCaches(toCacheKey[T](idxKeyVals))(fetchOp)
      else fetchOp
    val rs = results.flatMap { r => fromResult[T](r) }
    distincts[T](rs)
  }

  def getSequence[T : ClassTag]: Long = {
    val table = Graph.getConn(zkQuorum).getTable(TableName.valueOf(modelTableName))
    try {
      val get = new Get(getClassName[T].getBytes)
      get.addColumn(modelCf.getBytes, idQualifier.getBytes)
      val result = table.get(get)
      if (result == null || result.isEmpty) 0L
      else {
        val cell = result.getColumnLatestCell(modelCf.getBytes, idQualifier.getBytes)
        Bytes.toLong(CellUtil.cloneValue(cell))
      }
    } finally {
      table.close()
    }
  }
  def getAndIncrSeq[T : ClassTag]: Long = {
    val table = Graph.getConn(zkQuorum).getTable(TableName.valueOf(modelTableName))
    try {
      table.incrementColumnValue(getClassName[T].getBytes, modelCf.getBytes, idQualifier.getBytes, 1L)
    } finally {
      table.close()
    }
  }
  def insertForce[T: ClassTag](idxKVs: Seq[(KEY, VAL)], valKVs: Seq[(KEY, VAL)]) = {
    val table = Graph.getConn(zkQuorum).getTable(TableName.valueOf(modelTableName))
    try {
      val rowKey = toRowKey(getClassName[T], idxKVs).getBytes
      val put = new Put(rowKey)
      put.addColumn(modelCf.getBytes, qualifier.getBytes, toKVs(valKVs).getBytes)
      /** reset negative cache */
      expireCache(toCacheKey[T](idxKVs))

      table.put(put)

    } finally {
      table.close()
    }
  }
  def insert[T : ClassTag](idxKVs: Seq[(KEY, VAL)], valKVs: Seq[(KEY, VAL)]) = {
    val table = Graph.getConn(zkQuorum).getTable(TableName.valueOf(modelTableName))
    try {
      val rowKey = toRowKey(getClassName[T], idxKVs).getBytes
      val put = new Put(rowKey)
      put.addColumn(modelCf.getBytes, qualifier.getBytes, toKVs(valKVs).getBytes)
      /** reset negative cache */
      expireCache(toCacheKey[T](idxKVs))

      /** expecte null **/
      table.checkAndPut(rowKey, modelCf.getBytes, qualifier.getBytes, null, put)

    } finally {
      table.close()
    }
  }
  def deleteForce[T: ClassTag](idxKVs: Seq[(KEY, VAL)]) = {
    expireCache(toCacheKey[T](idxKVs))
    val table = Graph.getConn(zkQuorum).getTable(TableName.valueOf(modelTableName))
    try {
      val rowKey = toRowKey(getClassName[T], idxKVs).getBytes
      val delete = new Delete(rowKey)
      delete.addColumn(modelCf.getBytes, qualifier.getBytes)
      table.delete(delete)
    } finally {
      table.close()
    }
  }
  def delete[T : ClassTag](idxKVs: Seq[(KEY, VAL)], valKVs: Seq[(KEY, VAL)]) = {
    expireCache(toCacheKey[T](idxKVs))
    val table = Graph.getConn(zkQuorum).getTable(TableName.valueOf(modelTableName))
    try {
      val rowKey = toRowKey(getClassName[T], idxKVs).getBytes
      val delete = new Delete(rowKey)
      delete.addColumn(modelCf.getBytes, qualifier.getBytes)
      table.checkAndDelete(rowKey, modelCf.getBytes, qualifier.getBytes, toKVs(valKVs).getBytes, delete)
    } finally {
      table.close()
    }
  }

}

class Model[T : ClassTag](protected val tableName: String, protected val kvs: Map[Model.KEY, Model.VAL])  {
  import Model._
  import scala.reflect.runtime._
  import scala.reflect.runtime.universe._

  /** act like columns in table */
  protected val columns = Seq.empty[String]
  /** act like index */
  protected val idxs = List.empty[Seq[(KEY, VAL)]]
  /** act like foreign key */
  protected def foreignKeys() = {
    List.empty[List[Model[_]]]
  }

  override def toString(): String = (kvs ++ Map("tableName" -> tableName)).toString

  def validate(columns: Seq[String], optionalColumns: Seq[String] = Seq.empty[String]): Unit = {
    for (c <- columns) {
      if (!kvs.contains(c) && !optionalColumns.contains(c)) {
//        Logger.error(s"$columns, $kvs")
        throw new RuntimeException(s"$tableName expect ${columns.toList.sorted}, found ${kvs.toList.sortBy { kv => kv._1 }}, $c")
      }
    }
  }

  def create() = {
    val rets = for {
      idxKVs <- idxs
    } yield {
      Model.insert[T](idxKVs, toKVsWithFilter(kvs, idxKVs))
    }
    rets.forall(r => r)
  }
  def destroy() = {
    val rets = for (idxKVs <- idxs) yield {
      Model.delete[T](idxKVs, toKVsWithFilter(kvs, idxKVs))
    }
    rets.forall(r => r)
  }
  def update(key: KEY, value: VAL) = {
    for {
      idxKVs <- idxs
    } {
      val idxKVsMap = idxKVs.toMap
      for {
        oldRaw <- Model.find[T] (useCache = false) (idxKVs)
        old = oldRaw.asInstanceOf[Model[T]]
      } {
        val oldMetaKVs = old.kvs.filter(kv => !idxKVsMap.containsKey(kv._1))
        val (newIdxKVs, newMetaKVs) = if (idxKVsMap.containsKey(key)) {
          (idxKVs.filter(kv => kv._1 != key) ++ Seq(key -> value), oldMetaKVs)
        } else {
          (idxKVs, oldMetaKVs.filter(kv => kv._1 != key) ++ Seq(key -> value))
        }
        Model.deleteForce[T](idxKVs)
        Model.insertForce[T](newIdxKVs, newMetaKVs.toSeq)
      }
    }
  }
//  def deleteAll(): Boolean = destroy()
  def deleteAll(): Boolean = {
    val rets = for {
      models <- foreignKeys()
      model <- models
    } yield {
      model.deleteAll()
    }
    destroy()
  }
}




