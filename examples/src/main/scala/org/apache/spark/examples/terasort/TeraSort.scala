/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.examples.terasort

import org.apache.hadoop.io.BytesWritable

import org.apache.spark.{SparkConf, SparkContext}
import org.apache.spark.SparkContext._


object TeraSort {


  def main(args: Array[String]) {

    if (args.length < 3) {
      println("usage:")
      println("MASTER=[spark-master] bin/run-example org.apache.spark.examples.terasort.GenSort " +
        " [num-parts] [records-per-part] [output-parts]")
      System.exit(0)
    }

    // Process command line arguments
    val master = sys.env.getOrElse("MASTER", "local")
    val parts = args(0).toInt
    val recordsPerPartition = args(1).toInt
    val numRecords = parts.toLong * recordsPerPartition.toLong
    val outputParts = args(2).toInt

    println(s"Total number of records: $numRecords")
    println(s"Number of input partitions: $parts")
    println(s"Number of output partitions: $outputParts")
    println("Total sorting size: " + (numRecords * 100) + " bytes")

    val conf = new SparkConf().setMaster(master).setAppName(s"TeraSort ($numRecords records)")
      .set("spark.serializer", "org.apache.spark.serializer.KryoSerializer")
      .set("spark.kryo.registrator", "org.apache.spark.examples.terasort.TeraSortKryoRegistrator")
    val sc = new SparkContext(conf)

    // Generate the data on the fly.
    val dataset = sc.parallelize(1 to parts, parts).mapPartitionsWithIndex { case (index, _) =>
      val one = new Unsigned16(1)
      val firstRecordNumber = new Unsigned16(index * recordsPerPartition)
      val recordsToGenerate = new Unsigned16(recordsPerPartition)

      val recordNumber = new Unsigned16(firstRecordNumber)
      val lastRecordNumber = new Unsigned16(firstRecordNumber)
      lastRecordNumber.add(recordsToGenerate)

      val rand = Random16.skipAhead(firstRecordNumber)
      val row: Array[Byte] = new Array[Byte](100)

      Iterator.tabulate(recordsPerPartition) { offset =>
        Random16.nextRand(rand)
        generateRecord(row, rand, recordNumber)
        recordNumber.add(one)
        row
      }
    }

    // Convert the data to key, value pair to be sorted
    val pairs = dataset.map { row =>
      val key = new BytesWritable
      val value = new Array[Byte](90)
      key.set(row, 0, 10)
      System.arraycopy(row, 10, value, 0, 90)
      (key, value)
    }

    // Now sort the data, and count the number of records after sorting.
    implicit val ordering = new Ordering[BytesWritable] {
      override def compare(x: BytesWritable, y: BytesWritable): Int = x.compareTo(y)
    }

    val finalCount = pairs.sortByKey(ascending = true, numPartitions = outputParts).count()
    println("Number of records after sorting: " + finalCount)
  }

  /**
   * Generate a binary record suitable for all sort benchmarks except PennySort.
   *
   * @param recBuf record to return
   */
  def generateRecord(recBuf: Array[Byte], rand: Unsigned16, recordNumber: Unsigned16): Unit = {
    // Generate the 10-byte key using the high 10 bytes of the 128-bit random number
    var i = 0
    while (i < 10) {
      recBuf(i) = rand.getByte(i)
      i += 1
    }

    // Add 2 bytes of "break"
    recBuf(10) = 0x00.toByte
    recBuf(11) = 0x11.toByte

    // Convert the 128-bit record number to 32 bits of ascii hexadecimal
    // as the next 32 bytes of the record.
    i = 0
    while (i < 32) {
      recBuf(12 + i) = recordNumber.getHexDigit(i).toByte
      i += 1
    }

    // Add 4 bytes of "break" data
    recBuf(44) = 0x88.toByte
    recBuf(45) = 0x99.toByte
    recBuf(46) = 0xAA.toByte
    recBuf(47) = 0xBB.toByte

    // Add 48 bytes of filler based on low 48 bits of random number
    i = 0
    while (i < 12) {
      val v = rand.getHexDigit(20 + i).toByte
      recBuf(48 + i * 4) = v
      recBuf(49 + i * 4) = v
      recBuf(50 + i * 4) = v
      recBuf(51 + i * 4) = v
      i += 1
    }

    // Add 4 bytes of "break" data
    recBuf(96) = 0xCC.toByte
    recBuf(97) = 0xDD.toByte
    recBuf(98) = 0xEE.toByte
    recBuf(99) = 0xFF.toByte
  }
}
