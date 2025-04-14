/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hudi

import org.apache.hudi.common.config.HoodieMetadataConfig
import org.apache.hudi.common.model.{FileSlice, HoodieLogFile}
import org.apache.hudi.common.table.HoodieTableMetaClient
import org.apache.hudi.common.table.log.LogReaderUtils
import org.apache.hudi.metadata.{HoodieTableMetadataUtil, MetadataPartitionType}
import org.apache.hudi.metadata.BitmapIndexRecordGenerationUtils.constructBitmapRecordKey
import org.apache.hudi.util.JavaScalaConverters

import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.catalyst.expressions.{AttributeReference, EqualTo, Expression, Literal, Not}
import org.roaringbitmap.longlong.Roaring64NavigableMap
import org.slf4j.LoggerFactory

import java.util.stream.Collectors

import scala.collection.mutable.ArrayBuffer

class BitmapIndexSupport(spark: SparkSession,
                         metadataConfig: HoodieMetadataConfig,
                         metaClient: HoodieTableMetaClient) extends SparkBaseIndexSupport(spark, metadataConfig, metaClient) {

  private val log = LoggerFactory.getLogger(getClass)

  override def getIndexName: String = {
    BitmapIndexSupport.INDEX_NAME
  }

  override def isIndexAvailable: Boolean = {
    metadataConfig.isEnabled &&
      metadataConfig.isBitmapIndexEnabled &&
      metaClient.getTableConfig.getMetadataPartitions.contains(HoodieTableMetadataUtil.PARTITION_NAME_BITMAP_INDEX)
  }

  override def computeCandidateFileNames(fileIndex: HoodieFileIndex,
                                         queryFilters: Seq[Expression],
                                         queryReferencedColumns: Seq[String],
                                         prunedPartitionsAndFileSlices: Seq[(Option[BaseHoodieTableFileIndex.PartitionPath], Seq[FileSlice])],
                                         shouldPushDownFilesFilter: Boolean): Option[Set[String]] = {
    val indexedColumns = metadataConfig.getColumnsEnabledForBitmapIndex
    val intersectedColumns = queryReferencedColumns.intersect(JavaScalaConverters.convertJavaListToScalaSeq(indexedColumns))
    if (intersectedColumns.isEmpty) {
      log.debug(s"No columns in the query are indexed with bitmap index, skip pruning")
      prunedPartitionsAndFileSlices.flatMap(pair => getAllFileNames(pair._2)).toSet
    }

    // bitmap index should filter on EqualTo NotEqualTo
    // construct keys and use the keys to search for bitmaps optimistically
    // query should be providing column name and column values
    val equalToArray = ArrayBuffer[EqualTo]()
    val notEqualToArray = ArrayBuffer[EqualTo]()

    // TODO consider if we should support IN as well
    queryFilters.foreach {
      case eq @ EqualTo(left: AttributeReference, _: Literal) if intersectedColumns.contains(left.name) =>
        equalToArray += eq
      case Not(child @ EqualTo(left: AttributeReference, _: Literal)) if intersectedColumns.contains(left.name) =>
        notEqualToArray += child
      case _ =>
    }

    if (equalToArray.isEmpty && notEqualToArray.isEmpty) {
      log.debug(s"No EqualTo or NotEqualTo query filters to utilize bitmap index, skip pruning")
      prunedPartitionsAndFileSlices.flatMap(pair => getAllFileNames(pair._2)).toSet
    }

    val candidateFileNames: Set[String] = prunedPartitionsAndFileSlices.flatMap(pair => {
      val partition: String = pair._1.map(_.path).getOrElse(".")
      val fileSlices: Seq[FileSlice] = pair._2
      // determine if the entire file slice is a candidate
      val fileId = fileSlices.head.getFileId
      var bitmap: Roaring64NavigableMap = null
      bitmap = checkEqualTo(bitmap, equalToArray, notEqualTo = false, partition, fileId)
      bitmap = checkEqualTo(bitmap, notEqualToArray, notEqualTo = true, partition, fileId)
      if (bitmap.getIntCardinality > 0) {
        // is a candidate
        // get all filenames in the file slice
        getAllFileNames(fileSlices)
      } else {
        // eliminate this file slice
        Array.empty[String]
      }
    }).toSet

    Option(candidateFileNames)
  }

  override def invalidateCaches(): Unit = {
    // do nothing
  }

  private def checkEqualTo(bitmap: Roaring64NavigableMap,
                           equalToArray: ArrayBuffer[EqualTo],
                           notEqualTo: Boolean,
                           partition: String,
                           fileId: String): Roaring64NavigableMap = {
    var varBitmap = bitmap
    for (eq: EqualTo <- equalToArray) {
      val colName = eq.left.asInstanceOf[AttributeReference].name
      val colVal = eq.right.asInstanceOf[Literal].toString()
      val bitmapRecordKey = constructBitmapRecordKey(colName, colVal, partition, fileId)
      val bitmapKeyList = new java.util.ArrayList[String]
      bitmapKeyList.add(bitmapRecordKey)
      if (varBitmap == null) {
        // initialize bitmap
        varBitmap = getBitmapFromMetadataTable(bitmapKeyList)
      } else {
        // try joining bitmaps
        val another = getBitmapFromMetadataTable(bitmapKeyList)
        if (another != null) {
          if (notEqualTo) {
            varBitmap.andNot(another)
          } else {
            varBitmap.and(another)
          }
        }
      }
    }
    varBitmap
  }

  private def getBitmapFromMetadataTable(bitmapKeyList: java.util.ArrayList[String]): Roaring64NavigableMap = {
    // TODO maybe add try-catch here and skip if bitmap is not found
    metadataTable
      .getRecordsByKeyPrefixes(bitmapKeyList, MetadataPartitionType.BITMAP_INDEX.getPartitionPath, false)
      .map(record =>
        LogReaderUtils.decodeRecordPositionsHeader(record.getData.getBitmapIndexMetadata.get().getBitmap))
      .collectAsList()
      .get(0)
  }

  private def getAllFileNames(fileSlices: Seq[FileSlice]): Seq[String] = {
    fileSlices.flatMap(fileSlice => {
      val logFileNames: Array[String] = JavaScalaConverters.convertJavaListToScalaSeq(
        fileSlice.getLogFiles.collect(Collectors.toList[HoodieLogFile])).map(_.getFileName).toArray
      val baseFileOpt = fileSlice.getBaseFile
      val allFileNames: Array[String] = if (baseFileOpt.isPresent) {
        val baseFileName: String = baseFileOpt.get().getFileName
        baseFileName +: logFileNames
      } else {
        logFileNames
      }
      allFileNames
    })
  }

  object BitmapIndexSupport {
    val INDEX_NAME = "BITMAP_INDEX"
  }
}
