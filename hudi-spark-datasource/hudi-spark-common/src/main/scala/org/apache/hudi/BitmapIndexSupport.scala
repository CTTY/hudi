package org.apache.hudi

import org.apache.hudi.common.config.HoodieMetadataConfig
import org.apache.hudi.common.model.FileSlice
import org.apache.hudi.common.table.HoodieTableMetaClient
import org.apache.hudi.common.table.log.LogReaderUtils
import org.apache.hudi.metadata.{BitmapIndexRecordGenerationUtils, HoodieTableMetadataUtil, MetadataPartitionType}
import org.apache.hudi.util.JavaScalaConverters
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.catalyst.expressions.{AttributeReference, EqualTo, Expression, Literal, Not}
import org.roaringbitmap.longlong.{Roaring64Bitmap, Roaring64NavigableMap}
import org.slf4j.LoggerFactory

import java.util
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
      // TODO return all file names here
    }
    // bitmap index should filter on EqualTo NotEqualTo
    // construct keys and use the keys to search for bitmaps optimistically
    // query should be providing column name and column values

    val equalToArray = ArrayBuffer[EqualTo]()
    val notEqualToArray = ArrayBuffer[EqualTo]()

    // TODO maybe match isNull and isNotNull??
    queryFilters.foreach {
      case eq @ EqualTo(left: AttributeReference, _: Literal) if intersectedColumns.contains(left.name) =>
        equalToArray += eq
      case Not(child @ EqualTo(left: AttributeReference, _: Literal)) if intersectedColumns.contains(left.name) =>
        notEqualToArray += child
      case _ =>
    }

    if (equalToArray.isEmpty && notEqualToArray.isEmpty) {
      // TODO return early
    }

    val fileNames = prunedPartitionsAndFileSlices.map(pair => {
      val partition = pair._1.map(_.path).getOrElse(".")
      val fileSlices = pair._2
      val fileId = fileSlices.head.getFileId
      var bitmap : Roaring64NavigableMap = null
      for (eq: EqualTo <- equalToArray) {
        val colName = eq.left.asInstanceOf[AttributeReference].name
        val colVal = eq.right.asInstanceOf[Literal].toString()
        val bitmapRecordKey = BitmapIndexRecordGenerationUtils
          .constructBitmapKey(colName, colVal, partition, fileId)
        val bitmapKeyList = new java.util.ArrayList[String]
        bitmapKeyList.add(bitmapRecordKey)
        // TODO get the actual
        if (bitmap == null) {
          // TODO remove this test block
          val test = metadataTable
            .getRecordsByKeyPrefixes(bitmapKeyList, MetadataPartitionType.BITMAP_INDEX.getPartitionPath, false)
            .collectAsList()
          bitmap = metadataTable
            .getRecordsByKeyPrefixes(bitmapKeyList, MetadataPartitionType.BITMAP_INDEX.getPartitionPath, false)
            .map(record =>
              LogReaderUtils.decodeRecordPositionsHeader(record.getData.getBitmapIndexMetadata.get().getBitmap)).collectAsList().get(0)
        } else {
          val another = metadataTable
            .getRecordsByKeyPrefixes(bitmapKeyList, MetadataPartitionType.BITMAP_INDEX.getPartitionPath, false)
            .map(record =>
              LogReaderUtils.decodeRecordPositionsHeader(record.getData.getBitmapIndexMetadata.get().getBitmap)).collectAsList().get(0)
          if (another != null) bitmap.and(another)
        }
      }

      for (neq <- notEqualToArray) {
        val colName = neq.left.asInstanceOf[AttributeReference].name
        val colVal = neq.right.asInstanceOf[Literal].toString()
        val bitmapRecordKey = BitmapIndexRecordGenerationUtils
          .constructBitmapKey(colName, colVal, partition, fileId)
        val bitmapKeyList = new java.util.ArrayList[String]
        bitmapKeyList.add(bitmapRecordKey)
        // TODO maybe add a new method in HoodieMetadataTable interface
        if (bitmap == null) {
          bitmap = metadataTable
            .getRecordsByKeyPrefixes(bitmapKeyList, MetadataPartitionType.BITMAP_INDEX.getPartitionPath, false)
            .map(record =>
              LogReaderUtils.decodeRecordPositionsHeader(record.getData.getBitmapIndexMetadata.get().getBitmap)).collectAsList().get(0)
        } else {
          val another = metadataTable
            .getRecordsByKeyPrefixes(bitmapKeyList, MetadataPartitionType.BITMAP_INDEX.getPartitionPath, false)
            .map(record =>
              LogReaderUtils.decodeRecordPositionsHeader(record.getData.getBitmapIndexMetadata.get().getBitmap)).collectAsList().get(0)
          if (another != null) bitmap.andNot(another)
        }
      }
      if (bitmap.getIntCardinality != 0) {
        // is a candidate
        // TODO fix this logic, need to figure out what is file name exactly
        fileSlices.head.getBaseFile.get().getFileName
      } else {
        // eliminate
        Option.empty
      }
    }).collect {
      case fileName: String => fileName
    }.toSet

    Option(fileNames)
  }

  override def invalidateCaches(): Unit = {
    // do nothing
  }

  object BitmapIndexSupport {
    val INDEX_NAME = "BITMAP_INDEX"
  }
}
