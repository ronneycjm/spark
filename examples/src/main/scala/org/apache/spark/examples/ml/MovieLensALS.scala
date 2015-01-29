/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.examples.ml

import scopt.OptionParser

import org.apache.spark.{SparkConf, SparkContext}
import org.apache.spark.examples.mllib.AbstractParams
import org.apache.spark.ml.recommendation.ALS
import org.apache.spark.sql.{Row, SQLContext}
import org.apache.spark.sql.api.scala.dsl._

/**
 * An example app for ALS on MovieLens data (http://grouplens.org/datasets/movielens/).
 * Run with
 * {{{
 * bin/run-example ml.MovieLensALS
 * }}}
 */
object MovieLensALS {

  case class Rating(userId: Int, movieId: Int, rating: Float, timestamp: Long)

  object Rating {
    def parseRating(str: String): Rating = {
      val fields = str.split("::")
      assert(fields.size == 4)
      Rating(fields(0).toInt, fields(1).toInt, fields(2).toFloat, fields(3).toLong)
    }
  }

  case class Movie(movieId: Int, title: String, genres: Seq[String])

  object Movie {
    def parseMovie(str: String): Movie = {
      val fields = str.split("::")
      assert(fields.size == 3)
      Movie(fields(0).toInt, fields(1), fields(2).split("|"))
    }
  }

  case class Params(
      ratings: String = null,
      movies: String = null,
      maxIter: Int = 10,
      regParam: Double = 0.1,
      rank: Int = 10,
      numBlocks: Int = 10) extends AbstractParams[Params]

  def main(args: Array[String]) {
    val defaultParams = Params()

    val parser = new OptionParser[Params]("MovieLensALS") {
      head("MovieLensALS: an example app for ALS on MovieLens data.")
      opt[String]("ratings")
        .required()
        .text("path to a MovieLens dataset of ratings")
        .action((x, c) => c.copy(ratings = x))
      opt[String]("movies")
        .required()
        .text("path to a MovieLens dataset of movies")
        .action((x, c) => c.copy(movies = x))
      opt[Int]("rank")
        .text(s"rank, default: ${defaultParams.rank}}")
        .action((x, c) => c.copy(rank = x))
      opt[Int]("maxIter")
        .text(s"max number of iterations, default: ${defaultParams.maxIter}")
        .action((x, c) => c.copy(maxIter = x))
      opt[Double]("regParam")
        .text(s"regularization parameter, default: ${defaultParams.regParam}")
        .action((x, c) => c.copy(regParam = x))
      opt[Int]("numBlocks")
        .text(s"number of blocks, default: ${defaultParams.numBlocks}")
        .action((x, c) => c.copy(numBlocks = x))
      note(
        """
          |Example command line to run this app:
          |
          | bin/spark-submit --class org.apache.spark.examples.ml.MovieLensALS \
          |  examples/target/scala-*/spark-examples-*.jar \
          |  --rank 10 --maxIter 15 --regParam 0.1 \
          |  --movies path/to/movielens/movies.dat \
          |  --ratings path/to/movielens/ratings.dat
        """.stripMargin)
    }

    parser.parse(args, defaultParams).map { params =>
      run(params)
    } getOrElse {
      System.exit(1)
    }
  }

  def run(params: Params) {
    val conf = new SparkConf().setAppName(s"MovieLensALS with $params")
    val sc = new SparkContext(conf)
    implicit val sqlContext = new SQLContext(sc)

    val ratings = sc.textFile(params.ratings).map(Rating.parseRating).cache()

    val numRatings = ratings.count()
    val numUsers = ratings.map(_.userId).distinct().count()
    val numMovies = ratings.map(_.movieId).distinct().count()

    println(s"Got $numRatings ratings from $numUsers users on $numMovies movies.")

    val splits = ratings.randomSplit(Array(0.8, 0.2), 0L)
    val training = splits(0).cache()
    val test = splits(1).cache()

    val numTraining = training.count()
    val numTest = test.count()
    println(s"Training: $numTraining, test: $numTest.")

    ratings.unpersist(blocking = false)

    val als = new ALS()
      .setUserCol("userId")
      .setItemCol("movieId")
      .setRank(params.rank)
      .setMaxIter(params.maxIter)
      .setRegParam(params.regParam)
      .setNumBlocks(params.numBlocks)

    val model = als.fit(training)

    val predictions = model.transform(test).cache()

    // Evaluate the model.
    // TODO: Create an evaluator to compute RMSE.
    val mse = predictions.select("rating", "prediction").rdd
      .flatMap { case Row(rating: Float, prediction: Float) =>
        val err = rating.toDouble - prediction
        val err2 = err * err
        if (err2.isNaN) {
          None
        } else {
          Some(err2)
        }
      }.mean()
    val rmse = math.sqrt(mse)
    println(s"Test RMSE = $rmse.")

    // Inspect false positives.
    predictions.registerTempTable("prediction")
    sc.textFile(params.movies).map(Movie.parseMovie).registerTempTable("movie")
    sqlContext.sql(
      """
        |SELECT userId, prediction.movieId, title, rating, prediction
        |  FROM prediction JOIN movie ON prediction.movieId = movie.movieId
        |  WHERE rating <= 1 AND prediction >= 4
        |  LIMIT 100
      """.stripMargin)
      .collect()
      .foreach(println)

    sc.stop()
  }
}
