package com.daumkakao.s2graph.core.models

import com.daumkakao.s2graph.core.models.Model.{VAL, KEY}

/**
 * Created by shon on 5/15/15.
 */

object ColumnMeta {
  val timeStampSeq = 0.toByte
  val countSeq = -1.toByte
  val lastModifiedAtColumnSeq = 0.toByte

  val lastModifiedAtColumn = ColumnMeta(Map("id" -> 0, "columnId" -> 0,
    "name" -> "lastModifiedAt", "seq" -> lastModifiedAtColumnSeq, "dataType" -> "long"))
  val maxValue = Byte.MaxValue


  def findById(id: Int, useCache: Boolean = true): ColumnMeta = {

    Model.find[ColumnMeta](useCache)(Seq(("id" -> id))).get
  }
  def findAllByColumn(columnId: Int, useCache: Boolean = true) = {
    Model.findsMatch[ColumnMeta](useCache)(Seq(("columnId" -> columnId)))
  }
  def findByName(columnId: Int, name: String, useCache: Boolean = true) = {
    Model.find[ColumnMeta](useCache)(Seq(("columnId" -> columnId), ("name" -> name)))
  }
  def findByIdAndSeq(columnId: Int, seq: Byte, useCache: Boolean = true) = {
    Model.find[ColumnMeta](useCache)(Seq(("columnId" -> columnId), ("seq" -> seq)))
  }
  def findOrInsert(columnId: Int, name: String, dataType: String): ColumnMeta = {
    findByName(columnId, name, useCache = false) match {
      case Some(s) => s
      case None =>
        val id = Model.getAndIncrSeq[ColumnMeta]
        val allMetas = findAllByColumn(columnId, useCache = false)
        val seq = (allMetas.length + 1).toByte
        val model = ColumnMeta(Map("id" -> id, "columnId" -> columnId, "name" -> name,
          "seq" -> seq, "dataType" -> dataType))
        model.create
        model
    }
  }
}
/** add dataType on HColumnMeta */

case class ColumnMeta(kvsParam: Map[KEY, VAL]) extends Model[ColumnMeta]("HColumnMeta", kvsParam) {
  override val columns = Seq("id", "columnId", "name", "seq", "dataType")

  val pk = Seq(("id", kvs("id")))
  val idxColumnIdName = Seq(("columnId", kvs("columnId")), ("name", kvs("name")))
  val idxColumnIdSeq = Seq(("columnId", kvs("columnId")), ("seq", kvs("seq")))

  override val idxs = List(pk, idxColumnIdName, idxColumnIdSeq)
  validate(columns)

  val id = Some(kvs("id").toString.toInt)
  val columnId = kvs("columnId").toString.toInt
  val name = kvs("name").toString
  val seq = kvs("seq").toString.toByte
  val dataType = kvs("dataType").toString
}


