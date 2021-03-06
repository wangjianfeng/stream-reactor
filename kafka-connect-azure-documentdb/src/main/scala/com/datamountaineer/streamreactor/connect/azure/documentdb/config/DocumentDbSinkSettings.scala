/**
  * Copyright 2017 Datamountaineer.
  *
  * Licensed under the Apache License, Version 2.0 (the "License");
  * you may not use this file except in compliance with the License.
  * You may obtain a copy of the License at
  *
  * http://www.apache.org/licenses/LICENSE-2.0
  *
  * Unless required by applicable law or agreed to in writing, software
  * distributed under the License is distributed on an "AS IS" BASIS,
  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  * See the License for the specific language governing permissions and
  * limitations under the License.
  **/

package com.datamountaineer.streamreactor.connect.azure.documentdb.config

import com.datamountaineer.connector.config.{Config, WriteModeEnum}
import com.datamountaineer.streamreactor.connect.errors.{ErrorPolicy, ErrorPolicyEnum}
import com.microsoft.azure.documentdb.ConsistencyLevel
import com.typesafe.scalalogging.slf4j.StrictLogging
import org.apache.kafka.common.config.{AbstractConfig, ConfigException}

import scala.collection.JavaConversions._
import scala.util.{Failure, Success, Try}


case class DocumentDbSinkSettings(endpoint: String,
                                  masterKey: String,
                                  database: String,
                                  kcql: Seq[Config],
                                  keyBuilderMap: Map[String, Set[String]],
                                  fields: Map[String, Map[String, String]],
                                  ignoredField: Map[String, Set[String]],
                                  errorPolicy: ErrorPolicy,
                                  consistency: ConsistencyLevel,
                                  createDatabase: Boolean,
                                  proxy: Option[String],
                                  taskRetries: Int = DocumentDbConfig.NBR_OF_RETIRES_DEFAULT,
                                  batchSize: Int = DocumentDbConfig.BATCH_SIZE_CONFIG_DEFAULT) {

}


object DocumentDbSinkSettings extends StrictLogging {

  def apply(config: AbstractConfig): DocumentDbSinkSettings = {
    val endpoint = config.getString(DocumentDbConfig.CONNECTION_CONFIG)
    require(endpoint.nonEmpty, s"Invalid endpoint provided.${DocumentDbConfig.CONNECTION_CONFIG_DOC}")

    val masterKey = Option(config.getPassword(DocumentDbConfig.MASTER_KEY_CONFIG))
      .map(_.value())
      .getOrElse(throw new ConfigException(s"Missing ${DocumentDbConfig.MASTER_KEY_CONFIG}"))
    require(masterKey.trim.nonEmpty, s"Invalid ${DocumentDbConfig.MASTER_KEY_CONFIG}")

    val database = config.getString(DocumentDbConfig.DATABASE_CONFIG)
    if (database == null || database.trim.length == 0)
      throw new ConfigException(s"Invalid ${DocumentDbConfig.DATABASE_CONFIG}")
    val kcql = config.getString(DocumentDbConfig.KCQL_CONFIG)
    val routes = kcql.split(";").map(r => Try(Config.parse(r)) match {
      case Success(query) => query
      case Failure(t) => throw new ConfigException(s"Invalid ${DocumentDbConfig.KCQL_CONFIG}.${t.getMessage}", t)
    })
    if (routes.isEmpty)
      throw new ConfigException(s"Invalid ${DocumentDbConfig.KCQL_CONFIG}. You need to provide at least one route")

    val batchSize = config.getInt(DocumentDbConfig.BATCH_SIZE_CONFIG)
    val errorPolicyE = ErrorPolicyEnum.withName(config.getString(DocumentDbConfig.ERROR_POLICY_CONFIG).toUpperCase)
    val errorPolicy = ErrorPolicy(errorPolicyE)
    val retries = config.getInt(DocumentDbConfig.NBR_OF_RETRIES_CONFIG)

    val rowKeyBuilderMap = routes
      .filter(c => c.getWriteMode == WriteModeEnum.UPSERT)
      .map { r =>
        val keys = r.getPrimaryKeys.toSet
        if (keys.isEmpty) throw new ConfigException(s"${r.getTarget} is set up with upsert, you need primary keys setup")
        (r.getSource, keys)
      }.toMap


    val fieldsMap = routes.map { rm =>
      (rm.getSource, rm.getFieldAlias.map(fa => (fa.getField, fa.getAlias)).toMap)
    }.toMap

    val ignoreFields = routes.map(r => (r.getSource, r.getIgnoredField.toSet)).toMap

    val consistencyLevel = Try(ConsistencyLevel.valueOf(config.getString(DocumentDbConfig.CONSISTENCY_CONFIG))) match {
      case Failure(e) => throw new ConfigException(
        s"""
           |${config.getString(DocumentDbConfig.CONSISTENCY_CONFIG)} is not a valid entry for ${DocumentDbConfig.CONSISTENCY_CONFIG}
           |Available values are ${ConsistencyLevel.values().mkString(",")}""".stripMargin)

      case Success(c) => c
    }
    new DocumentDbSinkSettings(endpoint,
      masterKey,
      database,
      routes,
      rowKeyBuilderMap,
      fieldsMap,
      ignoreFields,
      errorPolicy,
      consistencyLevel,
      config.getBoolean(DocumentDbConfig.CREATE_DATABASE_CONFIG),
      Option(config.getString(DocumentDbConfig.PROXY_HOST_CONFIG)),
      retries,
      batchSize)
  }
}
