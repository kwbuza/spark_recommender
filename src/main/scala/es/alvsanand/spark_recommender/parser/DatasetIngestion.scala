package es.alvsanand.spark_recommender.parser

import java.net.InetAddress
import java.text.SimpleDateFormat
import java.util.Locale

import com.mongodb.casbah.commons.MongoDBObject
import com.mongodb.casbah.{MongoClient, MongoClientURI, WriteConcern => MongodbWriteConcern}
import com.stratio.datasource.mongodb._
import com.stratio.datasource.mongodb.config.MongodbConfig._
import com.stratio.datasource.mongodb.config._
import es.alvsanand.spark_recommender.model
import es.alvsanand.spark_recommender.model.Review
import es.alvsanand.spark_recommender.utils.{ESConfig, MongoConfig}
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.{Row, SQLContext}
import org.apache.spark.{SparkConf, SparkContext}
import org.elasticsearch.action.admin.indices.delete.DeleteIndexRequest
import org.elasticsearch.action.admin.indices.exists.indices.IndicesExistsRequest
import org.elasticsearch.client.transport.TransportClient
import org.elasticsearch.common.transport.InetSocketTransportAddress

import scala.collection.mutable


/**
  * Created by alvsanand on 7/05/16.
  */
object DatasetIngestion {
  val PRODUCTS_COLLECTION_NAME = "products"
  val REVIEWS_COLLECTION_NAME = "reviews"
  val PRODUCTS_INDEX_NAME= "products"
  val ES_HOST_PORT_REGEX = "(.+):(\\d+)".r

  def storeData(dataset: String, datasetFile: Option[String] = None)(implicit _conf: SparkConf, mongoConf: MongoConfig, esConf: ESConfig): Unit = {
    val sc = SparkContext.getOrCreate(_conf)
    val sqlContext = SQLContext.getOrCreate(sc)

    val jsonFiles = datasetFile match {
      case Some(s) => "%s/%s".format(dataset, s)
      case None => "%s/*.json".format(dataset)
    }

    val products = sqlContext.read.json(jsonFiles)

    val productReviewsRDD = products.mapPartitions(mapPartitions).cache()

    storeDataInMongo(productReviewsRDD)
    storeDataInES(productReviewsRDD.map { case (product, reviews) => product })
  }

  private def storeDataInMongo(productReviewsRDD: RDD[(model.Product, Option[List[Review]])])(implicit _conf: SparkConf, mongoConf: MongoConfig): Unit = {
    val productConfig = MongodbConfigBuilder(Map(Host -> mongoConf.hosts.split(";").toList, Database -> mongoConf.db, Collection -> PRODUCTS_COLLECTION_NAME))
    val reviewsConfig = MongodbConfigBuilder(Map(Host -> mongoConf.hosts.split(";").toList, Database -> mongoConf.db, Collection -> REVIEWS_COLLECTION_NAME))

    val mongoClient = MongoClient(MongoClientURI("mongodb://%s".format(mongoConf.hosts.split(";").mkString(","))))

    val sc = SparkContext.getOrCreate(_conf)
    val sqlContext = SQLContext.getOrCreate(sc)
    import sqlContext.implicits._

    mongoClient(mongoConf.db)(PRODUCTS_COLLECTION_NAME).dropCollection()
    mongoClient(mongoConf.db)(REVIEWS_COLLECTION_NAME).dropCollection()

    productReviewsRDD.map { case (product, reviews) => product }.toDF().distinct().saveToMongodb(productConfig.build)
    productReviewsRDD.flatMap { case (product, reviews) => reviews.getOrElse(List[Review]()) }.toDF().distinct().saveToMongodb(reviewsConfig.build)

    mongoClient(mongoConf.db)(PRODUCTS_COLLECTION_NAME).createIndex(MongoDBObject("productId" -> 1))
    mongoClient(mongoConf.db)(REVIEWS_COLLECTION_NAME).createIndex(MongoDBObject("productId" -> 1))
    mongoClient(mongoConf.db)(REVIEWS_COLLECTION_NAME).createIndex(MongoDBObject("userId" -> 1))
  }

  private def storeDataInES(productReviewsRDD: RDD[model.Product])(implicit _conf: SparkConf, esConf: ESConfig): Unit = {
    val options = Map("es.nodes" -> esConf.httpHosts, "es.http.timeout" -> "100m", "es.mapping.id" -> "productId")
    val indexName = esConf.index
    val typeName = "%s/%s".format(indexName, PRODUCTS_INDEX_NAME)

    val esClient = TransportClient.builder().build()
    esConf.transportHosts.split(";").foreach { case ES_HOST_PORT_REGEX(host: String, port: String) => esClient.addTransportAddress(new InetSocketTransportAddress(InetAddress.getByName(host), port.toInt)) }

    if(esClient.admin().indices().exists(new IndicesExistsRequest(indexName)).actionGet().isExists){
      esClient.admin().indices().delete(new DeleteIndexRequest(indexName)).actionGet()
    }

    val sc = SparkContext.getOrCreate(_conf)

    val sqlContext = SQLContext.getOrCreate(sc)
    import org.elasticsearch.spark.sql._
    import sqlContext.implicits._

    productReviewsRDD.toDF().distinct().saveToEs(typeName, options)
  }

  private def mapPartitions(rows: Iterator[Row]): Iterator[(model.Product, Option[List[Review]])] = {
    val df = new SimpleDateFormat("MMMM dd, yyyy", Locale.US)

    rows.flatMap { row =>
      if (row.fieldIndex("ProductInfo") == -1 || row.getAs[Row]("ProductInfo").fieldIndex("ProductID") == -1 || row.getAs[Row]("ProductInfo").getAs[String]("ProductID") == null) {
        None
      }
      else {
        val productRow = row.getAs[Row]("ProductInfo")
        val product = new model.Product(productRow.getAs[String]("ProductID"), productRow.getAs[String]("Name"), productRow.getAs[String]("Price"), productRow.getAs[String]("Features"), productRow.getAs[String]("ImgURL"))

        val reviews = row.fieldIndex("Reviews") match {
          case i if i > -1 =>
            Option(row(i).asInstanceOf[mutable.WrappedArray[Row]].map { reviewRow =>
              val date: java.sql.Timestamp = reviewRow.getAs[String]("Date") match {
                case s: String => new java.sql.Timestamp(df.parse(s).getTime)
                case null => null
              }
              val overall: Option[Double] = reviewRow.getAs[String]("Overall") match {
                case "None" => None
                case s: String => Option(s.toDouble)
              }
              new Review(reviewRow.getAs[String]("ReviewID"), reviewRow.getAs[String]("Author"), product.productId, reviewRow.getAs[String]("Title"), overall, reviewRow.getAs[String]("Content"), date)
            }.toList)
          case -1 => None
        }

        Option((product, reviews))
      }
    }
  }
}
