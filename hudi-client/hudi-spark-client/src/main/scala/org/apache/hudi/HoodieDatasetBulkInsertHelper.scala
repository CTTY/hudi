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

package org.apache.hudi

import org.apache.hudi.HoodieSparkUtils.injectSQLConf
import org.apache.hudi.client.WriteStatus
import org.apache.hudi.client.model.HoodieInternalRow
import org.apache.hudi.common.config.TypedProperties
import org.apache.hudi.common.data.HoodieData
import org.apache.hudi.common.engine.TaskContextSupplier
import org.apache.hudi.common.model.HoodieRecord
import org.apache.hudi.common.util.ReflectionUtils
import org.apache.hudi.config.HoodieWriteConfig
import org.apache.hudi.data.HoodieJavaRDD
import org.apache.hudi.exception.HoodieException
import org.apache.hudi.index.HoodieIndex.BucketIndexEngineType
import org.apache.hudi.index.{HoodieIndex, SparkHoodieIndexFactory}
import org.apache.hudi.keygen.{AutoRecordGenWrapperKeyGenerator, BuiltinKeyGenerator, KeyGenUtils}
import org.apache.hudi.table.action.commit.{BulkInsertDataInternalWriterHelper, ConsistentBucketBulkInsertDataInternalWriterHelper, ParallelismHelper}
import org.apache.hudi.table.{BulkInsertPartitioner, HoodieTable}
import org.apache.hudi.util.JFunction.toJavaSerializableFunctionUnchecked

import org.apache.spark.TaskContext
import org.apache.spark.internal.Logging
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.HoodieUnsafeRowUtils.{composeNestedFieldPath, getNestedInternalRowValue}
import org.apache.spark.sql.HoodieUnsafeUtils.getNumPartitions
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.expressions.{Alias, Literal}
import org.apache.spark.sql.catalyst.plans.logical.Project
import org.apache.spark.sql.internal.SQLConf
import org.apache.spark.sql.types.{StringType, StructField, StructType}
import org.apache.spark.sql.{DataFrame, Dataset, HoodieUnsafeUtils, Row}
import org.apache.spark.unsafe.types.UTF8String

import scala.collection.JavaConverters.asScalaBufferConverter
import scala.collection.mutable

object HoodieDatasetBulkInsertHelper
  extends ParallelismHelper[DataFrame](toJavaSerializableFunctionUnchecked(df => getNumPartitions(df))) with Logging {

  /**
   * Prepares [[DataFrame]] for bulk-insert into Hudi table, taking following steps:
   *
   * <ol>
   *   <li>Invoking configured [[org.apache.hudi.keygen.KeyGenerator]] to produce record key, alas partition-path value</li>
   *   <li>Prepends Hudi meta-fields to every row in the dataset</li>
   *   <li>Dedupes rows (if necessary)</li>
   *   <li>Partitions dataset using provided [[partitioner]]</li>
   * </ol>
   */
  def prepareForBulkInsert(df: DataFrame,
                           config: HoodieWriteConfig,
                           partitioner: BulkInsertPartitioner[Dataset[Row]],
                           instantTime: String): Dataset[Row] = {
    val populateMetaFields = config.populateMetaFields()
    val schema = df.schema
    val autoGenerateRecordKeys = KeyGenUtils.isAutoGeneratedRecordKeysEnabled(config.getProps)

    val metaFields = Seq(
      StructField(HoodieRecord.COMMIT_TIME_METADATA_FIELD, StringType),
      StructField(HoodieRecord.COMMIT_SEQNO_METADATA_FIELD, StringType),
      StructField(HoodieRecord.RECORD_KEY_METADATA_FIELD, StringType),
      StructField(HoodieRecord.PARTITION_PATH_METADATA_FIELD, StringType),
      StructField(HoodieRecord.FILENAME_METADATA_FIELD, StringType))

    val updatedSchema = StructType(metaFields ++ schema.fields)

    val targetParallelism =
      deduceShuffleParallelism(df, config.getBulkInsertShuffleParallelism)

    val updatedDF = if (populateMetaFields) {
      val keyGeneratorClassName = config.getStringOrThrow(HoodieWriteConfig.KEYGENERATOR_CLASS_NAME,
        "Key-generator class name is required")

      val prependedRdd: RDD[InternalRow] = {
        injectSQLConf(df.queryExecution.toRdd.mapPartitions { iter =>
          val typedProps = new TypedProperties(config.getProps)
          if (autoGenerateRecordKeys) {
            typedProps.setProperty(KeyGenUtils.RECORD_KEY_GEN_PARTITION_ID_CONFIG, String.valueOf(TaskContext.getPartitionId()))
            typedProps.setProperty(KeyGenUtils.RECORD_KEY_GEN_INSTANT_TIME_CONFIG, instantTime)
          }
          val sparkKeyGenerator =
            ReflectionUtils.loadClass(keyGeneratorClassName, typedProps)
              .asInstanceOf[BuiltinKeyGenerator]
              val keyGenerator: BuiltinKeyGenerator = if (autoGenerateRecordKeys) {
                new AutoRecordGenWrapperKeyGenerator(typedProps, sparkKeyGenerator).asInstanceOf[BuiltinKeyGenerator]
              } else {
                sparkKeyGenerator
              }

          iter.map { row =>
            // auto generate record keys if needed
            val recordKey = keyGenerator.getRecordKey(row, schema)
            val partitionPath = keyGenerator.getPartitionPath(row, schema)
            val commitTimestamp = UTF8String.EMPTY_UTF8
            val commitSeqNo = UTF8String.EMPTY_UTF8
            val filename = UTF8String.EMPTY_UTF8

            // TODO use mutable row, avoid re-allocating
            new HoodieInternalRow(commitTimestamp, commitSeqNo, recordKey, partitionPath, filename, row, false)
          }
        }, SQLConf.get)
      }

      val dedupedRdd = if (config.shouldCombineBeforeInsert) {
        dedupeRows(prependedRdd, updatedSchema, config.getPreCombineField, SparkHoodieIndexFactory.isGlobalIndex(config), targetParallelism)
      } else {
        prependedRdd
      }

      HoodieUnsafeUtils.createDataFrameFromRDD(df.sparkSession, dedupedRdd, updatedSchema)
    } else {
      // NOTE: In cases when we're not populating meta-fields we actually don't
      //       need access to the [[InternalRow]] and therefore can avoid the need
      //       to dereference [[DataFrame]] into [[RDD]]
      val query = df.queryExecution.logical
      val metaFieldsStubs = metaFields.map(f => Alias(Literal(UTF8String.EMPTY_UTF8, dataType = StringType), f.name)())
      val prependedQuery = Project(metaFieldsStubs ++ query.output, query)

      HoodieUnsafeUtils.createDataFrameFrom(df.sparkSession, prependedQuery)
    }

    partitioner.repartitionRecords(updatedDF, targetParallelism)
  }

  /**
   * Perform bulk insert for [[Dataset<Row>]], will not change timeline/index, return
   * information about write files.
   */
  def bulkInsert(dataset: Dataset[Row],
                 instantTime: String,
                 table: HoodieTable[_, _, _, _],
                 writeConfig: HoodieWriteConfig,
                 arePartitionRecordsSorted: Boolean,
                 shouldPreserveHoodieMetadata: Boolean): HoodieData[WriteStatus] = {
    val schema = dataset.schema
    HoodieJavaRDD.of(
      injectSQLConf(dataset.queryExecution.toRdd.mapPartitions(iter => {
        val taskContextSupplier: TaskContextSupplier = table.getTaskContextSupplier
        val taskPartitionId = taskContextSupplier.getPartitionIdSupplier.get
        val taskId = taskContextSupplier.getStageIdSupplier.get.toLong
        val taskEpochId = taskContextSupplier.getAttemptIdSupplier.get

        val writer = writeConfig.getIndexType match {
          case HoodieIndex.IndexType.BUCKET if writeConfig.getBucketIndexEngineType
            == BucketIndexEngineType.CONSISTENT_HASHING =>
            new ConsistentBucketBulkInsertDataInternalWriterHelper(
              table,
              writeConfig,
              instantTime,
              taskPartitionId,
              taskId,
              taskEpochId,
              schema,
              writeConfig.populateMetaFields,
              arePartitionRecordsSorted,
              shouldPreserveHoodieMetadata)
          case _ =>
            new BulkInsertDataInternalWriterHelper(
              table,
              writeConfig,
              instantTime,
              taskPartitionId,
              taskId,
              taskEpochId,
              schema,
              writeConfig.populateMetaFields,
              arePartitionRecordsSorted,
              shouldPreserveHoodieMetadata)
        }

        try {
          iter.foreach(writer.write)
        } catch {
          case t: Throwable =>
            writer.abort()
            throw t
        } finally {
          writer.close()
        }

        writer.getWriteStatuses.asScala.iterator
      }), SQLConf.get).toJavaRDD())
  }

  private def dedupeRows(rdd: RDD[InternalRow], schema: StructType, preCombineFieldRef: String, isGlobalIndex: Boolean, targetParallelism: Int): RDD[InternalRow] = {
    val recordKeyMetaFieldOrd = schema.fieldIndex(HoodieRecord.RECORD_KEY_METADATA_FIELD)
    val partitionPathMetaFieldOrd = schema.fieldIndex(HoodieRecord.PARTITION_PATH_METADATA_FIELD)
    // NOTE: Pre-combine field could be a nested field
    val preCombineFieldPath = composeNestedFieldPath(schema, preCombineFieldRef)
      .getOrElse(throw new HoodieException(s"Pre-combine field $preCombineFieldRef is missing in $schema"))

    rdd.map { row =>
        val rowKey = if (isGlobalIndex) {
          row.getString(recordKeyMetaFieldOrd)
        } else {
          val partitionPath = row.getString(partitionPathMetaFieldOrd)
          val recordKey = row.getString(recordKeyMetaFieldOrd)
          s"$partitionPath:$recordKey"
        }
        // NOTE: It's critical whenever we keep the reference to the row, to make a copy
        //       since Spark might be providing us with a mutable copy (updated during the iteration)
        (rowKey, row.copy())
      }
      .reduceByKey ((oneRow, otherRow) => {
        val onePreCombineVal = getNestedInternalRowValue(oneRow, preCombineFieldPath).asInstanceOf[Comparable[AnyRef]]
        val otherPreCombineVal = getNestedInternalRowValue(otherRow, preCombineFieldPath).asInstanceOf[Comparable[AnyRef]]
        if (onePreCombineVal.isInstanceOf[UTF8String]
          && onePreCombineVal.asInstanceOf[UTF8String].binaryCompare(otherPreCombineVal.asInstanceOf[UTF8String]) >= 0) {
          oneRow
        } else if (onePreCombineVal.compareTo(otherPreCombineVal.asInstanceOf[AnyRef]) >= 0) {
          oneRow
        } else {
          otherRow
        }
      }, targetParallelism)
      .values
  }

  override protected def deduceShuffleParallelism(input: DataFrame, configuredParallelism: Int): Int = {
    val deduceParallelism = super.deduceShuffleParallelism(input, configuredParallelism)
    // NOTE: In case parallelism deduction failed to accurately deduce parallelism level of the
    //       incoming dataset we fallback to default parallelism level set for this Spark session
    if (deduceParallelism > 0) {
      deduceParallelism
    } else {
      input.sparkSession.sparkContext.defaultParallelism
    }
  }

  private def getPartitionPathFields(config: HoodieWriteConfig): mutable.Seq[String] = {
    val keyGeneratorClassName = config.getString(HoodieWriteConfig.KEYGENERATOR_CLASS_NAME)
    val keyGenerator = ReflectionUtils.loadClass(keyGeneratorClassName, new TypedProperties(config.getProps)).asInstanceOf[BuiltinKeyGenerator]
    keyGenerator.getPartitionPathFields.asScala
  }

  def getPartitionPathCols(config: HoodieWriteConfig): Seq[String] = {
    val partitionPathFields = getPartitionPathFields(config).toSet
    val nestedPartitionPathFields = partitionPathFields.filter(f => f.contains('.'))

    (partitionPathFields -- nestedPartitionPathFields).toSeq
  }
}
