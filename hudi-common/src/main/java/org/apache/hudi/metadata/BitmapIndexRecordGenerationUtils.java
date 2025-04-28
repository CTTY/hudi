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

package org.apache.hudi.metadata;

import org.apache.hudi.avro.HoodieAvroUtils;
import org.apache.hudi.avro.model.HoodieBitmapIndexInfo;
import org.apache.hudi.common.config.HoodieMetadataConfig;
import org.apache.hudi.common.data.HoodieData;
import org.apache.hudi.common.engine.EngineType;
import org.apache.hudi.common.engine.HoodieEngineContext;
import org.apache.hudi.common.fs.FSUtils;
import org.apache.hudi.common.model.FileSlice;
import org.apache.hudi.common.model.HoodieAvroRecord;
import org.apache.hudi.common.model.HoodieBaseFile;
import org.apache.hudi.common.model.HoodieKey;
import org.apache.hudi.common.model.HoodieLogFile;
import org.apache.hudi.common.model.HoodieRecord;
import org.apache.hudi.common.model.HoodieRecordMerger;
import org.apache.hudi.common.model.HoodieWriteStat;
import org.apache.hudi.common.table.HoodieTableMetaClient;
import org.apache.hudi.common.table.TableSchemaResolver;
import org.apache.hudi.common.table.log.HoodieFileSliceReader;
import org.apache.hudi.common.table.log.HoodieMergedLogRecordScanner;
import org.apache.hudi.common.table.log.HoodieUnMergedLogRecordScanner;
import org.apache.hudi.common.table.log.LogReaderUtils;
import org.apache.hudi.common.table.timeline.HoodieInstant;
import org.apache.hudi.common.table.view.HoodieTableFileSystemView;
import org.apache.hudi.common.util.CollectionUtils;
import org.apache.hudi.common.util.FileIOUtils;
import org.apache.hudi.common.util.HoodieRecordUtils;
import org.apache.hudi.common.util.Option;
import org.apache.hudi.common.util.StringUtils;
import org.apache.hudi.common.util.VisibleForTesting;
import org.apache.hudi.common.util.collection.ClosableIterator;
import org.apache.hudi.common.util.collection.Pair;
import org.apache.hudi.exception.HoodieException;
import org.apache.hudi.exception.HoodieIOException;
import org.apache.hudi.exception.HoodieIndexException;
import org.apache.hudi.exception.HoodieMetadataException;
import org.apache.hudi.io.storage.HoodieFileReader;
import org.apache.hudi.io.storage.HoodieIOFactory;
import org.apache.hudi.storage.StorageConfiguration;
import org.apache.hudi.storage.StoragePath;

import org.apache.avro.LogicalTypes;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericRecord;
import org.roaringbitmap.longlong.LongIterator;
import org.roaringbitmap.longlong.Roaring64NavigableMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.apache.hudi.avro.AvroSchemaUtils.resolveNullableSchema;
import static org.apache.hudi.common.config.HoodieCommonConfig.DEFAULT_MAX_MEMORY_FOR_SPILLABLE_MAP_IN_BYTES;
import static org.apache.hudi.common.config.HoodieCommonConfig.DISK_MAP_BITCASK_COMPRESSION_ENABLED;
import static org.apache.hudi.common.config.HoodieCommonConfig.MAX_MEMORY_FOR_COMPACTION;
import static org.apache.hudi.common.config.HoodieCommonConfig.SPILLABLE_DISK_MAP_TYPE;
import static org.apache.hudi.common.util.ConfigUtils.getReaderConfigs;
import static org.apache.hudi.metadata.HoodieMetadataPayload.BITMAP_INDEX_RECORD_KEY_SEPARATOR;
import static org.apache.hudi.metadata.HoodieTableMetadata.EMPTY_PARTITION_NAME;
import static org.apache.hudi.metadata.HoodieTableMetadata.NON_PARTITIONED_NAME;
import static org.apache.hudi.metadata.HoodieTableMetadataUtil.PARTITION_NAME_BITMAP_INDEX;
import static org.apache.hudi.metadata.HoodieTableMetadataUtil.filePath;
import static org.apache.hudi.metadata.HoodieTableMetadataUtil.getPartitionLatestFileSlicesIncludingInflight;

/**
 * Utility methods for generating bitmap index records during initialization and updates.
 */
public class BitmapIndexRecordGenerationUtils {

  private static final Logger LOG = LoggerFactory.getLogger(BitmapIndexRecordGenerationUtils.class);

  /**
   * Converts the write stats to bitmap index records.
   *
   * @param allWriteStats   list of write stats
   * @param instantTime     instant time
   * @param metadata        table metadata
   * @param metadataConfig  metadata config
   * @param fsView          file system view as of instant time
   * @param dataMetaClient  data table meta client
   * @param engineContext   engine context
   * @param engineType      engine type (e.g. SPARK, FLINK or JAVA)
   * @return {@link HoodieData} of {@link HoodieRecord} to be updated in the metadata table under bitmap index partition
   */
  @VisibleForTesting
  public static HoodieData<HoodieRecord> convertWriteStatsToBitmapIndexRecords(List<HoodieWriteStat> allWriteStats,
                                                                               String instantTime,
                                                                               HoodieBackedTableMetadata metadata,
                                                                               HoodieMetadataConfig metadataConfig,
                                                                               HoodieTableFileSystemView fsView,
                                                                               HoodieTableMetaClient dataMetaClient,
                                                                               HoodieEngineContext engineContext,
                                                                               EngineType engineType) {
    // Bitmap index cannot support logs having inserts with current offering. So, lets validate that.
    if (allWriteStats.stream().anyMatch(writeStat -> {
      String fileName = FSUtils.getFileName(writeStat.getPath(), writeStat.getPartitionPath());
      return FSUtils.isLogFile(fileName) && writeStat.getNumInserts() > 0;
    })) {
      throw new HoodieIOException("Bitmap index cannot support logs having inserts with current offering. Please disable bitmap index.");
    }

    Schema tableSchema;
    try {
      // TODO fix this and use a different logic to init bitmap index
      // cannot use HoodieTableMetadataUtil.tryResolveSchemaForTable because this may be the table's first commit
      tableSchema = new TableSchemaResolver(dataMetaClient).getTableAvroSchema();
    } catch (Exception e) {
      throw new HoodieException("Failed to get latest schema for " + dataMetaClient.getBasePath(), e);
    }
    Map<String, List<HoodieWriteStat>> writeStatsByFileId = allWriteStats.stream().collect(Collectors.groupingBy(HoodieWriteStat::getFileId));
    int parallelism = Math.max(Math.min(writeStatsByFileId.size(), metadataConfig.getBitmapIndexParallelism()), 1);

    HoodieData<HoodieRecord> ret = engineContext.parallelize(new ArrayList<>(writeStatsByFileId.entrySet()), parallelism).flatMap(writeStatsByFileIdEntry -> {
      String fileId = writeStatsByFileIdEntry.getKey();
      List<HoodieWriteStat> writeStats = writeStatsByFileIdEntry.getValue();
      String partition = writeStats.get(0).getPartitionPath();
      FileSlice previousFileSliceForFileId = fsView.getLatestFileSlice(partition, fileId).orElse(null);
      Map<String, PositionedColumnInfo> recordKeyToColInfoForPreviousFileSlice;
      if (previousFileSliceForFileId == null) {
        // new file slice, so empty mapping for previous slice
        recordKeyToColInfoForPreviousFileSlice = Collections.emptyMap();
      } else {
        StoragePath previousBaseFile = previousFileSliceForFileId.getBaseFile().map(HoodieBaseFile::getStoragePath).orElse(null);
        List<String> logFiles =
                previousFileSliceForFileId.getLogFiles()
                        .sorted(HoodieLogFile.getLogFileComparator())
                        .map(HoodieLogFile::getPath)
                        .map(StoragePath::toString)
                        .collect(Collectors.toList());
        recordKeyToColInfoForPreviousFileSlice =
                getRecordKeyToPositionedColumnInfo(dataMetaClient, engineType, logFiles, tableSchema,
                        partition, Option.ofNullable(previousBaseFile),
                        metadataConfig.getColumnsEnabledForBitmapIndex(), instantTime);
      }
      List<FileSlice> latestIncludingInflightFileSlices = getPartitionLatestFileSlicesIncludingInflight(dataMetaClient, Option.empty(), partition);
      FileSlice currentFileSliceForFileId = latestIncludingInflightFileSlices.stream().filter(fs -> fs.getFileId().equals(fileId)).findFirst()
              .orElseThrow(() -> new HoodieException("Could not find any file slice for fileId " + fileId));
      StoragePath currentBaseFile = currentFileSliceForFileId.getBaseFile().map(HoodieBaseFile::getStoragePath).orElse(null);
      List<String> logFilesIncludingInflight = currentFileSliceForFileId
              .getLogFiles()
              .sorted(HoodieLogFile.getLogFileComparator())
              .map(HoodieLogFile::getPath)
              .map(StoragePath::toString)
              .collect(Collectors.toList());
      Map<String, PositionedColumnInfo> recordKeyToColInfoForCurrentFileSlice =
              getRecordKeyToPositionedColumnInfo(dataMetaClient, engineType, logFilesIncludingInflight, tableSchema,
                      partition, Option.ofNullable(currentBaseFile),
                      metadataConfig.getColumnsEnabledForBitmapIndex(), instantTime);
      List<HoodieRecord> records = new ArrayList<>();
      Set<String> bitmapRecordKeys = new HashSet<>();
      // get updated map<bitmapRecordKey, bitmap> and convert it into bitmap records
      getUpdatedBitmaps(recordKeyToColInfoForPreviousFileSlice, recordKeyToColInfoForCurrentFileSlice, metadata, partition, fileId)
              .forEach((bitmapRecordKey, bitmap) -> {
                records.add(HoodieMetadataPayload.createBitmapIndexRecord(bitmapRecordKey, bitmap));
                if (!bitmapRecordKeys.add(bitmapRecordKey)) {
                  // TODO remove this
                  LOG.error("shawn: DUPLICATE bitmapRecordKey FOUND!!!: {}", bitmapRecordKey);
                }
              });
      return records.iterator();
    });

    // TODO remove this
    Set<String> bitmapRecordKeys = new HashSet<>();
    List<HoodieRecord> collectedRecords = ret.collectAsList();
    collectedRecords.forEach(record -> {
      if (!bitmapRecordKeys.add(record.getRecordKey())) {
        LOG.error("shawn: DUPLICATE bitmapRecordKey FOUND AFTER flatMap!!!: {}", record.getRecordKey());
      }
    });
    LOG.warn("shawn: Generated bitmap index records based on write stats");
    return ret;
  }

  /*
    Need to find what bitmap index record should be deleted, and what should be inserted.
    for each entry in recordKeyToColumnPairsForCurrentFileSlice,
      if it is not present in recordKeyToColumnPairsForPreviousFileSlice
        meaning it's a new record
        update bitmap for every indexed columns (add new positions)
      else (if is present in recordKeyToColumnPairsForPreviousFileSlice)
        meaning it's an existing record
        only update bitmap for every column that has changed (remove old positions and add new positions)

    for each entry in recordKeyToColumnPairsForPreviousFileSlice
      if it is not present in recordKeyToColumnPairsForCurrentFileSlice
        meaning it's deleted
        update the bitmap for every indexed columns (remove positions) and decrement the existing positions after the deleted position
  */
  private static Map<String, Roaring64NavigableMap> getUpdatedBitmaps(Map<String, PositionedColumnInfo> recordKeyToColInfoForPreviousFileSlice,
                                                                      Map<String, PositionedColumnInfo> recordKeyToColInfoForCurrentFileSlice,
                                                                      HoodieBackedTableMetadata metadata,
                                                                      String partition,
                                                                      String fileId) {
    /* TODO in the cached bitmap, we can only store colName$colVal to save some memory because file group id is the same for this write */
    // map<columnPairsKeyString, bitmap>
    Map<String, Roaring64NavigableMap> updatedBitmaps = new HashMap<>();
    recordKeyToColInfoForCurrentFileSlice.forEach((recordKey, newPositionedColumnInfo) -> {
      if (!recordKeyToColInfoForPreviousFileSlice.containsKey(recordKey)) {
        // new record, update bitmap for every indexed columns
        newPositionedColumnInfo.columnInfos.forEach((column, colVal) ->
                addPosToBitmap(metadata, partition, fileId, updatedBitmaps, column, colVal, newPositionedColumnInfo.pos));
      } else {
        // update existing record, only update bitmap for changed columns
        PositionedColumnInfo oldPositionedColumnInfo = recordKeyToColInfoForPreviousFileSlice.get(recordKey);
        for (String column : newPositionedColumnInfo.columnInfos.keySet()) {
          String oldVal = oldPositionedColumnInfo.columnInfos.get(column);
          String newVal = newPositionedColumnInfo.columnInfos.get(column);
          if (oldVal == null || newVal == null) {
            throw new HoodieIndexException("Column does not exist in the record! Bitmap index doesn't support schema evolution as of now. "
                    + "Please check your indexing config: " + column);
          }
          if (!oldVal.equals(newVal)) {
            removePosFromBitmap(metadata, partition, fileId, updatedBitmaps, column, oldVal, oldPositionedColumnInfo.pos, false);
            addPosToBitmap(metadata, partition, fileId, updatedBitmaps, column, newVal, newPositionedColumnInfo.pos);
          }
        }
      }
    });

    recordKeyToColInfoForPreviousFileSlice.forEach((recordKey, oldPositionedColumnInfo) -> {
      if (!recordKeyToColInfoForCurrentFileSlice.containsKey(recordKey)) {
        // deleted record, remove positions from all associated bitmaps
        oldPositionedColumnInfo.columnInfos.forEach((column, colVal) ->
                removePosFromBitmap(metadata, partition, fileId, updatedBitmaps, column, colVal, oldPositionedColumnInfo.pos, true));
      }
    });

    return updatedBitmaps;
  }

  private static void addPosToBitmap(HoodieBackedTableMetadata metadata,
                                     String partition,
                                     String fileId,
                                     Map<String, Roaring64NavigableMap> cachedBitmaps,
                                     String columnName,
                                     String columnVal,
                                     long pos) {
    String bitmapRecordKey = constructBitmapRecordKey(columnName, columnVal, partition, fileId);
    cachedBitmaps
            .computeIfAbsent(bitmapRecordKey, bitmap -> loadBitmapFromMetadata(metadata, bitmapRecordKey))
            .addLong(pos);
  }

  private static void removePosFromBitmap(HoodieBackedTableMetadata metadata,
                                          String partition,
                                          String fileId,
                                          Map<String, Roaring64NavigableMap> cachedBitmaps,
                                          String columnName,
                                          String columnVal,
                                          long pos,
                                          boolean isDelete) {
    String bitmapRecordKey = constructBitmapRecordKey(columnName, columnVal, partition, fileId);
    cachedBitmaps
            .computeIfAbsent(bitmapRecordKey, bitmap -> loadBitmapFromMetadata(metadata, bitmapRecordKey))
            .removeLong(pos);

    // decrement the positions after the deleted position by one
    if (isDelete) {
      Roaring64NavigableMap bitmap = cachedBitmaps.get(bitmapRecordKey);
      LongIterator iterator =  bitmap.getReverseLongIterator();
      List<Long> posToDecrement = new ArrayList<>();
      while (iterator.hasNext()) {
        long curPos = iterator.next();
        if (curPos <= pos) {
          break; // passed the deleted position
        }
        bitmap.removeLong(curPos);
        posToDecrement.add(curPos);
      }
      posToDecrement.forEach(position -> bitmap.addLong(position - 1));
      cachedBitmaps.put(bitmapRecordKey, bitmap);
    }
  }

  private static Roaring64NavigableMap loadBitmapFromMetadata(HoodieBackedTableMetadata metadata, String bitmapRecordKey) {
    return metadata
            .getRecordByKey(bitmapRecordKey, MetadataPartitionType.BITMAP_INDEX.getPartitionPath())
            .map(record -> {
              try {
                return LogReaderUtils.decodeRecordPositionsHeader(record.getData().getBitmapIndexMetadata().get().getBitmap());
              } catch (IOException ioe) {
                LOG.error("Failed to get bitmap for bitmapRecordKey: {}", bitmapRecordKey);
              }
              return null;
            }).orElseGet(() -> {
              LOG.warn("Cannot get bitmap for bitmapRecordKey: {}, using a new bitmap", bitmapRecordKey);
              return new Roaring64NavigableMap();
            });
  }

  // return map <recordKey, [(colName, colValue), (colName, colValue)]>
  private static Map<String, PositionedColumnInfo> getRecordKeyToPositionedColumnInfo(HoodieTableMetaClient metaClient,
                                                                                      EngineType engineType, List<String> logFilePaths,
                                                                                      Schema tableSchema, String partition,
                                                                                      Option<StoragePath> dataFilePath,
                                                                                      List<String> indexedColumns,
                                                                                      String instantTime) throws Exception {
    final String basePath = metaClient.getBasePath().toString();
    final StorageConfiguration<?> storageConf = metaClient.getStorageConf();

    HoodieRecordMerger recordMerger = HoodieRecordUtils.createRecordMerger(
            basePath,
            engineType,
            Collections.emptyList(),
            metaClient.getTableConfig().getRecordMergeStrategyId());

    HoodieMergedLogRecordScanner mergedLogRecordScanner = HoodieMergedLogRecordScanner.newBuilder()
            .withStorage(metaClient.getStorage())
            .withBasePath(metaClient.getBasePath())
            .withLogFilePaths(logFilePaths)
            .withReaderSchema(tableSchema)
            .withLatestInstantTime(instantTime)
            .withReverseReader(false)
            .withMaxMemorySizeInBytes(storageConf.getLong(MAX_MEMORY_FOR_COMPACTION.key(), DEFAULT_MAX_MEMORY_FOR_SPILLABLE_MAP_IN_BYTES))
            .withBufferSize(HoodieMetadataConfig.MAX_READER_BUFFER_SIZE_PROP.defaultValue())
            .withSpillableMapBasePath(FileIOUtils.getDefaultSpillableMapBasePath())
            .withPartition(partition)
            .withOptimizedLogBlocksScan(storageConf.getBoolean("hoodie" + HoodieMetadataConfig.OPTIMIZED_LOG_BLOCKS_SCAN, false))
            .withDiskMapType(storageConf.getEnum(SPILLABLE_DISK_MAP_TYPE.key(), SPILLABLE_DISK_MAP_TYPE.defaultValue()))
            .withBitCaskDiskMapCompressionEnabled(storageConf.getBoolean(DISK_MAP_BITCASK_COMPRESSION_ENABLED.key(), DISK_MAP_BITCASK_COMPRESSION_ENABLED.defaultValue()))
            .withRecordMerger(recordMerger)
            .withTableMetaClient(metaClient)
            .build();

    Option<HoodieFileReader> baseFileReader = Option.empty();
    if (dataFilePath.isPresent()) {
      baseFileReader = Option.of(HoodieIOFactory.getIOFactory(metaClient.getStorage())
              .getReaderFactory(recordMerger.getRecordType())
              .getFileReader(getReaderConfigs(storageConf), dataFilePath.get()));
    }
    HoodieFileSliceReader fileSliceReader =
            new HoodieFileSliceReader(baseFileReader, mergedLogRecordScanner, tableSchema,
                    metaClient.getTableConfig().getPreCombineField(), recordMerger,
                    metaClient.getTableConfig().getProps(), Option.empty(), Option.empty());
    // Collect the records from the iterator in a map by record key to secondary key
    Map<String, PositionedColumnInfo> recordKeyToColumnPairsAndPos = new HashMap<>();
    long rowPosition = 0L;
    while (fileSliceReader.hasNext()) {
      HoodieRecord record = (HoodieRecord) fileSliceReader.next();
      Map<String, String> columnPairs = getColumnInfos(record, tableSchema, indexedColumns);
      if (columnPairs != null) {
        // no delete records here
        recordKeyToColumnPairsAndPos.put(record.getRecordKey(tableSchema, HoodieRecord.RECORD_KEY_METADATA_FIELD), new PositionedColumnInfo(columnPairs, rowPosition));
      }
      rowPosition++;
    }
    return recordKeyToColumnPairsAndPos;
  }

  private static Map<String, String> getColumnInfos(HoodieRecord record, Schema tableSchema, List<String> indexedColumns) {
    Map<String, String> columnInfos = new HashMap<>();
    try {
      if (record.toIndexedRecord(tableSchema, CollectionUtils.emptyProps()).isPresent()) {
        GenericRecord genericRecord = (GenericRecord) (record.toIndexedRecord(tableSchema, CollectionUtils.emptyProps()).get()).getData();
        for (String column : indexedColumns) {
          columnInfos.put(
                  column,
                  HoodieAvroUtils.getNestedFieldValAsString(genericRecord, column, true, false));
        }
        return columnInfos;
      }
    } catch (IOException e) {
      LOG.debug("Failed to fetch bitmap column pairs for record key " + record.getKey().toString());
    }
    return null;
  }

  public static HoodieData<HoodieRecord> readBitmapRecordsFromFileSlices(HoodieEngineContext engineContext,
                                                                         List<Pair<String, FileSlice>> partitionFileSlicePairs,
                                                                         int bitmapIndexMaxParallelism,
                                                                         String activeModule,
                                                                         HoodieTableMetaClient metaClient,
                                                                         EngineType engineType,
                                                                         List<String> columnsToIndex) {
    if (partitionFileSlicePairs.isEmpty()) {
      return engineContext.emptyHoodieData();
    }
    final int parallelism = Math.min(partitionFileSlicePairs.size(), bitmapIndexMaxParallelism);
    final StoragePath basePath = metaClient.getBasePath();
    Schema tableSchema;
    try {
      tableSchema = new TableSchemaResolver(metaClient).getTableAvroSchema();
    } catch (Exception e) {
      throw new HoodieException("Failed to get latest schema for " + metaClient.getBasePath(), e);
    }

    engineContext.setJobStatus(activeModule, "Secondary Index: reading secondary keys from " + partitionFileSlicePairs.size() + " file slices");
    return engineContext.parallelize(partitionFileSlicePairs, parallelism).flatMap(partitionAndBaseFile -> {
      final String partition = partitionAndBaseFile.getKey();
      final FileSlice fileSlice = partitionAndBaseFile.getValue();
      List<String> logFilePaths = fileSlice.getLogFiles().sorted(HoodieLogFile.getLogFileComparator()).map(l -> l.getPath().toString()).collect(Collectors.toList());
      Option<StoragePath> dataFilePath = Option.ofNullable(fileSlice.getBaseFile().map(baseFile -> filePath(basePath, partition, baseFile.getFileName())).orElseGet(null));
      Schema readerSchema;
      if (dataFilePath.isPresent()) {
        readerSchema = HoodieIOFactory.getIOFactory(metaClient.getStorage())
                .getFileFormatUtils(metaClient.getTableConfig().getBaseFileFormat())
                .readAvroSchema(metaClient.getStorage(), dataFilePath.get());
      } else {
        readerSchema = tableSchema;
      }
      return createBitmapIndexRecordsIterator(metaClient, engineType, logFilePaths, readerSchema, partition, dataFilePath,
              metaClient.getActiveTimeline().filterCompletedInstants().lastInstant().map(HoodieInstant::requestedTime).orElse(""),
              columnsToIndex);
    });
  }

  private static ClosableIterator<HoodieRecord> createBitmapIndexRecordsIterator(HoodieTableMetaClient metaClient,
                                                                                 EngineType engineType, List<String> logFilePaths,
                                                                                 Schema tableSchema, String partition,
                                                                                 Option<StoragePath> dataFilePath,
                                                                                 String instantTime,
                                                                                 List<String> columnsToIndex) throws Exception {
    final String basePath = metaClient.getBasePath().toString();
    final StorageConfiguration<?> storageConf = metaClient.getStorageConf();

    HoodieRecordMerger recordMerger = HoodieRecordUtils.createRecordMerger(
            basePath,
            engineType,
            Collections.emptyList(),
            metaClient.getTableConfig().getRecordMergeStrategyId());

    HoodieMergedLogRecordScanner mergedLogRecordScanner = HoodieMergedLogRecordScanner.newBuilder()
            .withStorage(metaClient.getStorage())
            .withBasePath(metaClient.getBasePath())
            .withLogFilePaths(logFilePaths)
            .withReaderSchema(tableSchema)
            .withLatestInstantTime(instantTime)
            .withReverseReader(false)
            .withMaxMemorySizeInBytes(storageConf.getLong(MAX_MEMORY_FOR_COMPACTION.key(), DEFAULT_MAX_MEMORY_FOR_SPILLABLE_MAP_IN_BYTES))
            .withBufferSize(HoodieMetadataConfig.MAX_READER_BUFFER_SIZE_PROP.defaultValue())
            .withSpillableMapBasePath(FileIOUtils.getDefaultSpillableMapBasePath())
            .withPartition(partition)
            .withOptimizedLogBlocksScan(storageConf.getBoolean("hoodie" + HoodieMetadataConfig.OPTIMIZED_LOG_BLOCKS_SCAN, false))
            .withDiskMapType(storageConf.getEnum(SPILLABLE_DISK_MAP_TYPE.key(), SPILLABLE_DISK_MAP_TYPE.defaultValue()))
            .withBitCaskDiskMapCompressionEnabled(storageConf.getBoolean(DISK_MAP_BITCASK_COMPRESSION_ENABLED.key(), DISK_MAP_BITCASK_COMPRESSION_ENABLED.defaultValue()))
            .withRecordMerger(recordMerger)
            .withTableMetaClient(metaClient)
            .build();

    Option<HoodieFileReader> baseFileReader = Option.empty();
    String fileId;
    if (dataFilePath.isPresent()) {
      baseFileReader = Option.of(HoodieIOFactory.getIOFactory(metaClient.getStorage()).getReaderFactory(recordMerger.getRecordType()).getFileReader(getReaderConfigs(storageConf), dataFilePath.get()));
      fileId = FSUtils.getFileId(dataFilePath.get().getName());
    } else {
      fileId = FSUtils.getFileId(new StoragePath(logFilePaths.get(0).toString()).getName());
    }
    HoodieFileSliceReader fileSliceReader = new HoodieFileSliceReader(baseFileReader, mergedLogRecordScanner, tableSchema, metaClient.getTableConfig().getPreCombineField(), recordMerger,
            metaClient.getTableConfig().getProps(),
            Option.empty(), Option.empty());

    Map<String, Roaring64NavigableMap> indexRecordKeyToBitmap = new HashMap<>();
    try (ClosableIterator<HoodieRecord> fileSliceIterator = ClosableIterator.wrap(fileSliceReader)) {
      long rowPosition = 0L;
      // scan all recrods and update bitmaps
      while (fileSliceIterator.hasNext()) {
        HoodieRecord record = fileSliceIterator.next();
        Map<String, String> columnInfos = getColumnInfos(record, tableSchema, columnsToIndex);
        final long curRow = rowPosition; // has to assign this to a final to use it in lambda
        columnInfos.forEach((colName, colValue) -> {
          String bitmapRecordKey = constructBitmapRecordKey(colName, colValue, partition, fileId);
          indexRecordKeyToBitmap
                  .computeIfAbsent(bitmapRecordKey, bitmap -> new Roaring64NavigableMap())
                  .addLong(curRow);
        });
        rowPosition++;
      }
    }

    Set<String> bitmapRecordKeys = new HashSet<>();
    List<HoodieRecord> records = new ArrayList<>();
    indexRecordKeyToBitmap.forEach((bitmapRecordKey, bitmap) -> {
      records.add(HoodieMetadataPayload.createBitmapIndexRecord(bitmapRecordKey, bitmap));
      if (!bitmapRecordKeys.add(bitmapRecordKey)) {
        LOG.error("shawn: DUPLICATE bitmapRecordKey FOUND!!!: {}", bitmapRecordKey);
      }
    });
    return ClosableIterator.wrap(records.iterator());
  }

  // TODO remove this
  public static Stream<HoodieRecord> createBitmapFromFile(HoodieTableMetaClient metaClient, String partitionPath,
                                                          String fileName, List<String> columnsToIndex,
                                                          Schema tableSchema, int maxBufferSize, EngineType engineType) {
    String partitionPathFileName = (partitionPath.equals(EMPTY_PARTITION_NAME) || partitionPath.equals(NON_PARTITIONED_NAME)) ? fileName
            : partitionPath + "/" + fileName;
    StoragePath fullFilePath = new StoragePath(metaClient.getBasePath(), partitionPathFileName);
    String fileId = FSUtils.getFileId(fileName);

    HoodieRecordMerger recordMerger = HoodieRecordUtils.createRecordMerger(
            metaClient.getBasePath().toString(),
            engineType,
            Collections.emptyList(),
            metaClient.getTableConfig().getRecordMergeStrategyId());

    ClosableIterator<HoodieRecord> records = FSUtils.isBaseFile(fullFilePath)
            ? getRecordsFromBaseFile(metaClient, fullFilePath, recordMerger)
            : getRecordsFromLogFile(metaClient, fullFilePath, tableSchema, maxBufferSize);

    List<Pair<String, Schema.Field>> fieldsToIndex = columnsToIndex.stream()
            .map(fieldName -> HoodieAvroUtils.getSchemaForField(tableSchema, fieldName))
            .collect(Collectors.toList());

    // colName$colValue -> bitmap
    Map<String, Roaring64NavigableMap> toBitmap = new HashMap<>();
    while (records.hasNext()) {
      HoodieRecord record = records.next();
      // for every record, update bitmap for all cols to index
      fieldsToIndex.forEach(field -> {
        String fieldName = field.getKey();
        Schema fieldSchema = resolveNullableSchema(field.getValue().schema());
        Object fieldValue;
        // get field value
        if (record.getRecordType() == HoodieRecord.HoodieRecordType.AVRO) {
          fieldValue = HoodieAvroUtils.getRecordColumnValues(record, new String[]{fieldName}, tableSchema, false)[0];
          if (fieldSchema.getType() == Schema.Type.INT && fieldSchema.getLogicalType() != null && fieldSchema.getLogicalType() == LogicalTypes.date()) {
            fieldValue = java.sql.Date.valueOf(fieldValue.toString());
          }

        } else if (record.getRecordType() == HoodieRecord.HoodieRecordType.SPARK) {
          fieldValue = record.getColumnValues(tableSchema, new String[]{fieldName}, false)[0];
          if (fieldSchema.getType() == Schema.Type.INT && fieldSchema.getLogicalType() != null && fieldSchema.getLogicalType() == LogicalTypes.date()) {
            fieldValue = java.sql.Date.valueOf(LocalDate.ofEpochDay((Integer) fieldValue).toString());
          }
        } else {
          throw new HoodieException(String.format("Unknown record type: %s", record.getRecordType()));
        }

        // update bitmap
        String mapKey = String.format("%s$%s", fieldName, fieldValue);
        toBitmap.computeIfAbsent(mapKey, v -> new Roaring64NavigableMap()).add(record.getCurrentPosition());
      });
    }
    records.close();

    return toBitmap.keySet().stream().map(mapKey -> {
      // the payload key is in the format of "partitionPath_fileId$bitmapKey"
      HoodieKey hoodieKey = new HoodieKey(
              constructBitmapRecordKey(mapKey, partitionPath, fileId),
              PARTITION_NAME_BITMAP_INDEX);
      try {
        HoodieMetadataPayload payload = new HoodieMetadataPayload(hoodieKey.getRecordKey(),
                new HoodieBitmapIndexInfo(LogReaderUtils.encodePositions(toBitmap.get(mapKey))));
        return new HoodieAvroRecord<>(hoodieKey, payload);
      } catch (IOException ioe) {
        throw new HoodieMetadataException("Failed to create bitmap index record!", ioe);
      }
    });
  }

  private static ClosableIterator<HoodieRecord> getRecordsFromBaseFile(HoodieTableMetaClient metaClient, StoragePath baseFilePath, HoodieRecordMerger recordMerger) {
    try {
      HoodieFileReader baseFileReader = HoodieIOFactory.getIOFactory(metaClient.getStorage())
              .getReaderFactory(recordMerger.getRecordType())
              .getFileReader(getReaderConfigs(metaClient.getStorageConf()), baseFilePath);
      return baseFileReader.getRecordIterator();
    } catch (IOException ioe) {
      throw new HoodieMetadataException("Failed to read records from bitmap index file: " + baseFilePath, ioe);
    }
  }

  private static ClosableIterator<HoodieRecord> getRecordsFromLogFile(HoodieTableMetaClient metaClient, StoragePath logFilePath, Schema tableSchema, int maxBufferSize) {
    // read log file records without merging
    List<HoodieRecord> records = new ArrayList<>();
    HoodieUnMergedLogRecordScanner scanner = HoodieUnMergedLogRecordScanner.newBuilder()
            .withStorage(metaClient.getStorage())
            .withBasePath(metaClient.getBasePath())
            .withLogFilePaths(Collections.singletonList(logFilePath.toString()))
            .withBufferSize(maxBufferSize)
            .withLatestInstantTime(metaClient.getActiveTimeline().getCommitsTimeline().lastInstant().get().requestedTime())
            .withReaderSchema(tableSchema)
            .withTableMetaClient(metaClient)
            .withLogRecordScannerCallback(records::add)
            .build();
    scanner.scan();

    return ClosableIterator.wrap(records.iterator());
  }

  public static String constructBitmapRecordKey(String bitmapKey, String partitionPath, String fileId) {
    String partition = StringUtils.isNullOrEmpty(partitionPath) ? "." : partitionPath;
    return String.format("%s%s%s%s%s",
            bitmapKey, BITMAP_INDEX_RECORD_KEY_SEPARATOR,
            partition, BITMAP_INDEX_RECORD_KEY_SEPARATOR,
            fileId);
  }

  public static String constructBitmapRecordKey(String colName, String colValue, String partitionPath, String fileId) {
    String bitmapKey = String.format("%s%s%s", colName, BITMAP_INDEX_RECORD_KEY_SEPARATOR, colValue);
    return constructBitmapRecordKey(bitmapKey, partitionPath, fileId);
  }

  static class PositionedColumnInfo {
    // map<column_name, column_value>
    Map<String, String> columnInfos;
    long pos;

    public PositionedColumnInfo(Map<String, String> columnInfos, long pos) {
      this.columnInfos = columnInfos;
      this.pos = pos;
    }
  }
}

