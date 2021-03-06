package com.daumkakao.s2graph.core.mysqls

/**
 * Created by shon on 6/3/15.
 */

import java.util.UUID

import com.daumkakao.s2graph.core.Management
import play.api.Logger
import play.api.libs.json.Json
import scalikejdbc._

object Service extends Model[Service] {


  def apply(rs: WrappedResultSet): Service = {
    Service(rs.intOpt("id"), rs.string("service_name"), rs.string("access_token"),
      rs.string("cluster"), rs.string("hbase_table_name"), rs.int("pre_split_size"), rs.intOpt("hbase_table_ttl"))
  }
  def findByAccessToken(accessToken: String): Option[Service] = {
    val cacheKey = s"accessToken=$accessToken"
    withCache(cacheKey)(sql"""select * from services where access_token = ${accessToken}""".map { rs => Service(rs) }.single.apply)
  }
  def findById(id: Int): Service = {
//    val cacheKey = s"id=$id"
    val cacheKey = "id=" + id
    withCache(cacheKey)(sql"""select * from services where id = ${id}""".map { rs => Service(rs) }.single.apply).get
  }
  def findByName(serviceName: String, useCache: Boolean = true): Option[Service] = {
//    val cacheKey = s"serviceName=$serviceName"
    val cacheKey = "serviceName=" + serviceName

    if (useCache) {
      withCache(cacheKey)(sql"""
        select * from services where service_name = ${serviceName}
    """.map { rs => Service(rs) }.single.apply())
    } else {
      sql"""
        select * from services where service_name = ${serviceName}
      """.map { rs => Service(rs) }.single.apply()
    }
  }
  def insert(serviceName: String, cluster: String, hTableName: String, preSplitSize: Int, hTableTTL: Option[Int]) = {
    Logger.info(s"$serviceName, $cluster, $hTableName, $preSplitSize, $hTableTTL")
    val accessToken = UUID.randomUUID().toString()
    sql"""insert into services(service_name, access_token, cluster, hbase_table_name, pre_split_size, hbase_table_ttl)
    values(${serviceName}, ${accessToken}, ${cluster}, ${hTableName}, ${preSplitSize}, ${hTableTTL})""".execute.apply()
    Management.createTable(cluster, hTableName, List("e", "v"), preSplitSize, hTableTTL)
  }
  def delete(id: Int) = {
    val service = findById(id)
    val serviceName = service.serviceName
    sql"""delete from service_columns where id = ${id}""".execute.apply()
    val cacheKeys = List(s"id=$id", s"serviceName=$serviceName")
    cacheKeys.foreach(expireCache(_))
  }
  def findOrInsert(serviceName: String, cluster: String, hTableName: String, preSplitSize: Int, hTableTTL: Option[Int]): Service = {
    findByName(serviceName) match {
      case Some(s) => s
      case None =>
        insert(serviceName, cluster, hTableName, preSplitSize, hTableTTL)
//        val cacheKey = s"serviceName=$serviceName"
        val cacheKey = "serviceName=" + serviceName
        expireCache(cacheKey)
        findByName(serviceName).get
    }
  }
  def findAll() = {
    val ls = sql"""select * from services""".map { rs => Service(rs) }.list.apply
    putsToCache(ls.map { x =>
      var cacheKey = s"id=${x.id.get}"
      (cacheKey -> x)
    })
    putsToCache(ls.map { x =>
      var cacheKey = s"serviceName=${x.serviceName}"
      (cacheKey -> x)
    })
  }
  def findAllConn(): List[String] = {
    sql"""select distinct(cluster) from services""".map { rs => rs.string("cluster") }.list.apply
  }
}
case class Service(id: Option[Int], serviceName: String, accessToken: String, cluster: String, hTableName: String, preSplitSize: Int, hTableTTL: Option[Int]) {
  lazy val toJson =
    id match {
      case Some(_id) =>
        Json.obj("id" -> _id, "name" -> serviceName, "accessToken" -> accessToken,
          "hbaseTableName" -> hTableName, "preSplitSize" -> preSplitSize, "hTableTTL" -> hTableTTL)
      case None =>
        Json.parse("{}")
    }
}
