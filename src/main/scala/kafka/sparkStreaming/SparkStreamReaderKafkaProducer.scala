package kafka.sparkStreaming

import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.functions._
import org.slf4j.LoggerFactory


class SparkStreamReaderKafkaProducer {

  def beginStreaming : Unit = {
    val logger = LoggerFactory.getLogger(SparkStreamReaderKafkaProducer.getClass.getName)

    val bootStrapServers : String = "localhost:9092"

    val spark = SparkSession.builder.
      appName("SparkStreamingKafkaProducer").
      config("spark.master", "local").
      getOrCreate()

    spark.conf.set("spark.sql.shuffle.partitions", "5")

    val staticDataFrame = spark.read.option("multiline","true").
      json("/home/hadoop/Spark-The-Definitive-Guide/data/flight-data/json")

    val streamingDataFrame = spark.readStream.schema(staticDataFrame.schema).
      option("maxFilesPerTrigger", 1).
      json("/home/hadoop/Spark-The-Definitive-Guide/" +
        "data/flight-data/json")

    streamingDataFrame.createOrReplaceTempView("country_total_routes")

    val aggregatedCountryRoutes =
      streamingDataFrame.
      where("DEST_COUNTRY_NAME <> ORIGIN_COUNTRY_NAME").
      selectExpr("DEST_COUNTRY_NAME||'-'||ORIGIN_COUNTRY_NAME as country", "COUNT as total_routes").
      groupBy("COUNTRY").agg(sum("total_routes")).
      selectExpr("country", "`sum(total_routes)` as total_routes").
      orderBy(desc("total_routes"))

    /*
    val queryDF = spark.sql("SELECT dest_country_name||'-'||origin_country_name as country" +
      ", sum(count) as total_routes from country_total_routes " +
      "group by dest_country_name||'-'||origin_country_name")
    */
    aggregatedCountryRoutes.selectExpr("CAST(country AS STRING) as key",
      "CAST(total_routes AS STRING) as value").
      writeStream.
      format("kafka").
      option("kafka.bootstrap.servers",bootStrapServers).
      // start of optimizing configs
      // following configs ensure resilient kafka producer.
      option("kafka.enable.idempotence","true"). // This parameter ensures minimal data loss and fail safe
      option("kafka.acks","all").
      option("kafka.reties",Integer.toString(Integer.MAX_VALUE)).
      option("kafka.max.in.flight.requests.per.connection","5").
      // optimizing the kafka producer to be highly perfomant
      option("kafka.compression.type","snappy").
      option("kafka.linger.ms","20").
      option("kafka.batch.size",Integer.toString(32*1024)).
      // end of optimizing configs
      option("checkpointLocation", "/home/hadoop/checkpoint").
      option("topic","tweet_topic").
      outputMode("complete").
      start().
      awaitTermination()

  }
}

object SparkStreamReaderKafkaProducer extends App {
  val sparkStreamReaderKafkaProducer = new SparkStreamReaderKafkaProducer
  sparkStreamReaderKafkaProducer.beginStreaming
}
