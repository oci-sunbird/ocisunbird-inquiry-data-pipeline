package org.sunbird.job.util

import java.util

import org.neo4j.driver.v1.{Config, GraphDatabase}
import org.slf4j.LoggerFactory
import org.sunbird.job.BaseJobConfig

import scala.collection.JavaConverters._

class Neo4JUtil(routePath: String, graphId: String, config: BaseJobConfig) {

  private[this] val logger = LoggerFactory.getLogger(classOf[Neo4JUtil])


  val maxIdleSession = 20
  val driver = GraphDatabase.driver(routePath, getConfig)
  val isrRelativePathEnabled = config.getBoolean("cloudstorage.metadata.replace_absolute_path", false)

  def getConfig: Config = {
    val config = Config.build
    config.withEncryptionLevel(Config.EncryptionLevel.NONE)
    config.withMaxIdleSessions(maxIdleSession)
    config.withTrustStrategy(Config.TrustStrategy.trustAllCertificates)
    config.toConfig
  }

  Runtime.getRuntime.addShutdownHook(new Thread() {
    override def run(): Unit = {
      try {
        driver.close()
      } catch {
        case e: Throwable => e.printStackTrace()
      }
    }
  })

  def getNodeProperties(identifier: String): java.util.Map[String, AnyRef] = {
    val session = driver.session()
    val query = s"""MATCH (n:${graphId}{IL_UNIQUE_ID:"${identifier}"}) return n;"""
    val statementResult = session.run(query)
    if (statementResult.hasNext) {
      val data = statementResult.single().get("n").asMap()
      if(isrRelativePathEnabled) CSPMetaUtil.updateAbsolutePath(data)(config) else data
    } else null
  }

  def getNodePropertiesWithObjectType(objectType: String): util.List[util.Map[String, AnyRef]] = {
    val session = driver.session()
    val query = s"""MATCH (n:${graphId}) where n.IL_FUNC_OBJECT_TYPE = "${objectType}" AND n.IL_SYS_NODE_TYPE="DATA_NODE" return n;"""
    val statementResult = session.run(query)
    if (statementResult.hasNext){
      val data = statementResult.list().asScala.toList.map(record => record.get("n").asMap()).asJava
      if(isrRelativePathEnabled) CSPMetaUtil.updateAbsolutePath(data)(config) else data
    } else null
  }

  def getNodesName(identifiers: List[String]): Map[String, String] = {
    val query = s"""MATCH(n:domain) WHERE n.IL_UNIQUE_ID IN ${JSONUtil.serialize(identifiers.asJava)} RETURN n.IL_UNIQUE_ID AS id, n.name AS name;"""
    logger.info("Neo4jUril :: getNodesName :: Query : " + query)
    val statementResult = executeQuery(query)
    if (null != statementResult) {
      statementResult.list().asScala.toList.flatMap(record => Map(record.get("id").asString() -> record.get("name").asString())).toMap
    } else {
      logger.info("Neo4j Nodes Not Found For " + identifiers.asJava)
      Map()
    }
  }

  def updateNodeProperty(identifier: String, key: String, value: String): Unit = {
    val query = s"""MATCH (n:$graphId {IL_UNIQUE_ID:"$identifier"}) SET n.$key=$value return n;"""
    val updatedQuery = if(isrRelativePathEnabled) CSPMetaUtil.updateRelativePath(query)(config) else query
    logger.info("Query: " + updatedQuery)
    val session = driver.session()
    val result = session.run(updatedQuery)
    if (result.hasNext)
      logger.info("Successfully Updated node with identifier: $identifier")
    else throw new Exception(s"Unable to update the node with identifier: $identifier")
  }

  def executeQuery(query: String) = {
    val updatedQuery = if(isrRelativePathEnabled) CSPMetaUtil.updateRelativePath(query)(config)  else query
    val session = driver.session()
    session.run(updatedQuery)
  }

  //Return a map of id and node
  def getNodesProps(identifiers: List[String]): Map[String, AnyRef] = {
    Map()
  }

  def updateNode(identifier: String, metadata: Map[String, AnyRef]): Unit = {
    val updatedMetadata = if(isrRelativePathEnabled) CSPMetaUtil.updateRelativePath(metadata.asJava)(config) else metadata.asJava
    val query = s"""MATCH (n:$graphId {IL_UNIQUE_ID:"$identifier"}) SET n = """ + "$properties return n;"
    logger.info(s"Query for updating metadata for identifier : ${identifier} is : ${query}")
    val session = driver.session()
    val properties: java.util.Map[String, AnyRef] = Map[String, AnyRef]("properties" -> updatedMetadata).asJava
    val result = session.run(query, properties)
    if (result.hasNext)
      logger.info(s"Successfully Updated node with identifier: $identifier")
    else throw new Exception(s"Unable to update the node with identifier: $identifier")
  }

}
