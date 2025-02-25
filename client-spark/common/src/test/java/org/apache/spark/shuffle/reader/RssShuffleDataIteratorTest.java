/*
 * Tencent is pleased to support the open source community by making
 * Firestorm-Spark remote shuffle server available. 
 *
 * Copyright (C) 2021 THL A29 Limited, a Tencent company.  All rights reserved. 
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use
 * this file except in compliance with the License. You may obtain a copy of the
 * License at
 *
 * https://opensource.org/licenses/Apache-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OF ANY KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations under the License.
 */

package org.apache.spark.shuffle.reader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.tencent.rss.client.impl.ShuffleReadClientImpl;
import com.tencent.rss.client.util.ClientUtils;
import com.tencent.rss.common.util.ChecksumUtils;
import com.tencent.rss.common.util.Constants;
import com.tencent.rss.storage.handler.impl.HdfsShuffleWriteHandler;
import com.tencent.rss.storage.util.StorageType;
import java.nio.ByteBuffer;
import java.util.Map;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileUtil;
import org.apache.hadoop.fs.Path;
import org.apache.spark.SparkConf;
import org.apache.spark.executor.ShuffleReadMetrics;
import org.apache.spark.serializer.KryoSerializer;
import org.apache.spark.serializer.Serializer;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.roaringbitmap.longlong.Roaring64NavigableMap;

public class RssShuffleDataIteratorTest extends AbstractRssReaderTest {

  private static final Serializer KRYO_SERIALIZER = new KryoSerializer(new SparkConf(false));
  private static final String EXPECTED_EXCEPTION_MESSAGE = "Exception should be thrown";

  @Test
  public void readTest1() throws Exception {
    String basePath = HDFS_URI + "readTest1";
    HdfsShuffleWriteHandler writeHandler =
        new HdfsShuffleWriteHandler("appId", 0, 0, 1, basePath, "test1", conf);

    Map<String, String> expectedData = Maps.newHashMap();
    Roaring64NavigableMap blockIdBitmap = Roaring64NavigableMap.bitmapOf();
    Roaring64NavigableMap taskIdBitmap = Roaring64NavigableMap.bitmapOf(0);
    writeTestData(writeHandler, 2, 5, expectedData,
        blockIdBitmap, "key", KRYO_SERIALIZER, 0);

    RssShuffleDataIterator rssShuffleDataIterator = getDataIterator(basePath, blockIdBitmap, taskIdBitmap);

    validateResult(rssShuffleDataIterator, expectedData, 10);

    blockIdBitmap.add(ClientUtils.getBlockId(0, 0, Constants.MAX_SEQUENCE_NO));
    rssShuffleDataIterator = getDataIterator(basePath, blockIdBitmap, taskIdBitmap);
    int recNum = 0;
    try {
      // can't find all expected block id, data loss
      while (rssShuffleDataIterator.hasNext()) {
        rssShuffleDataIterator.next();
        recNum++;
      }
      fail(EXPECTED_EXCEPTION_MESSAGE);
    } catch (Exception e) {
      assertTrue(e.getMessage().startsWith("Blocks read inconsistent:"));
    }
    assertEquals(10, recNum);
  }

  private RssShuffleDataIterator getDataIterator(String basePath, Roaring64NavigableMap blockIdBitmap, Roaring64NavigableMap taskIdBitmap) {
    ShuffleReadClientImpl readClient = new ShuffleReadClientImpl(
        StorageType.HDFS.name(), "appId", 0, 1, 100, 2,
        10, 10000, basePath, blockIdBitmap, taskIdBitmap, Lists.newArrayList(), new Configuration());
    return new RssShuffleDataIterator(KRYO_SERIALIZER, readClient,
        new ShuffleReadMetrics());
  }

  @Test
  public void readTest2() throws Exception {
    String basePath = HDFS_URI + "readTest2";
    HdfsShuffleWriteHandler writeHandler1 =
        new HdfsShuffleWriteHandler("appId", 0, 0, 1, basePath, "test2_1", conf);
    HdfsShuffleWriteHandler writeHandler2 =
        new HdfsShuffleWriteHandler("appId", 0, 0, 1, basePath, "test2_2", conf);

    Map<String, String> expectedData = Maps.newHashMap();
    Roaring64NavigableMap blockIdBitmap = Roaring64NavigableMap.bitmapOf();
    Roaring64NavigableMap taskIdBitmap = Roaring64NavigableMap.bitmapOf(0);
    writeTestData(writeHandler1, 2, 5, expectedData,
        blockIdBitmap, "key1", KRYO_SERIALIZER, 0);
    writeTestData(writeHandler2, 2, 5, expectedData,
        blockIdBitmap, "key2", KRYO_SERIALIZER, 0);

    RssShuffleDataIterator rssShuffleDataIterator = getDataIterator(basePath, blockIdBitmap, taskIdBitmap);

    validateResult(rssShuffleDataIterator, expectedData, 20);
    assertEquals(20, rssShuffleDataIterator.getShuffleReadMetrics().recordsRead());
    assertEquals(256, rssShuffleDataIterator.getShuffleReadMetrics().remoteBytesRead());
    assertTrue(rssShuffleDataIterator.getShuffleReadMetrics().fetchWaitTime() > 0);
  }

  @Test
  public void readTest3() throws Exception {
    String basePath = HDFS_URI + "readTest3";
    HdfsShuffleWriteHandler writeHandler1 =
        new HdfsShuffleWriteHandler("appId", 0, 0, 1, basePath, "test3_1", conf);
    HdfsShuffleWriteHandler writeHandler2 =
        new HdfsShuffleWriteHandler("appId", 0, 0, 1, basePath, "test3_2", conf);

    Map<String, String> expectedData = Maps.newHashMap();
    Roaring64NavigableMap blockIdBitmap = Roaring64NavigableMap.bitmapOf();
    Roaring64NavigableMap taskIdBitmap = Roaring64NavigableMap.bitmapOf(0);
    writeTestData(writeHandler1, 2, 5, expectedData,
        blockIdBitmap, "key1", KRYO_SERIALIZER, 0);
    writeTestData(writeHandler2, 2, 5, expectedData,
        blockIdBitmap, "key2", KRYO_SERIALIZER, 0);

    // duplicate file created, it should be used in product environment
    String shuffleFolder = basePath + "/appId/0/0-1";
    FileUtil.copy(fs, new Path(shuffleFolder + "/test3_1_0.data"), fs,
        new Path(shuffleFolder + "/test3_1_0.cp.data"), false, conf);
    FileUtil.copy(fs, new Path(shuffleFolder + "/test3_1_0.index"), fs,
        new Path(shuffleFolder + "/test3_1_0.cp.index"), false, conf);
    FileUtil.copy(fs, new Path(shuffleFolder + "/test3_2_0.data"), fs,
        new Path(shuffleFolder + "/test3_2_0.cp.data"), false, conf);
    FileUtil.copy(fs, new Path(shuffleFolder + "/test3_2_0.index"), fs,
        new Path(shuffleFolder + "/test3_2_0.cp.index"), false, conf);

    RssShuffleDataIterator rssShuffleDataIterator = getDataIterator(basePath, blockIdBitmap, taskIdBitmap);

    validateResult(rssShuffleDataIterator, expectedData, 20);
  }

  @Test
  public void readTest4() throws Exception {
    String basePath = HDFS_URI + "readTest4";
    HdfsShuffleWriteHandler writeHandler =
        new HdfsShuffleWriteHandler("appId", 0, 0, 1, basePath, "test1", conf);

    Map<String, String> expectedData = Maps.newHashMap();
    Roaring64NavigableMap blockIdBitmap = Roaring64NavigableMap.bitmapOf();
    Roaring64NavigableMap taskIdBitmap = Roaring64NavigableMap.bitmapOf(0);
    writeTestData(writeHandler, 2, 5, expectedData,
        blockIdBitmap, "key", KRYO_SERIALIZER, 0);

    RssShuffleDataIterator rssShuffleDataIterator = getDataIterator(basePath, blockIdBitmap, taskIdBitmap);
    // data file is deleted after iterator initialization
    Path dataFile = new Path(basePath + "/appId/0/0-1/test1_0.data");
    fs.delete(dataFile, true);
    // sleep to wait delete operation
    Thread.sleep(10000);
    try {
      fs.listStatus(dataFile);
      fail("Index file should be deleted");
    } catch (Exception e) {
    }

    try {
      while (rssShuffleDataIterator.hasNext()) {
        rssShuffleDataIterator.next();
      }
      fail(EXPECTED_EXCEPTION_MESSAGE);
    } catch (Exception e) {
      assertTrue(e.getMessage().startsWith("Blocks read inconsistent: expected"));
    }
  }

  @Test
  public void readTest5() throws Exception {
    String basePath = HDFS_URI + "readTest5";
    HdfsShuffleWriteHandler writeHandler =
        new HdfsShuffleWriteHandler("appId", 0, 0, 1, basePath, "test", conf);

    Map<String, String> expectedData = Maps.newHashMap();
    Roaring64NavigableMap blockIdBitmap = Roaring64NavigableMap.bitmapOf();
    Roaring64NavigableMap taskIdBitmap = Roaring64NavigableMap.bitmapOf(0);
    writeTestData(writeHandler, 2, 5, expectedData,
        blockIdBitmap, "key", KRYO_SERIALIZER, 0);

    RssShuffleDataIterator rssShuffleDataIterator = getDataIterator(basePath, blockIdBitmap, taskIdBitmap);
    // index file is deleted after iterator initialization, it should be ok, all index infos are read already
    Path indexFile = new Path(basePath + "/appId/0/0-1/test.index");
    fs.delete(indexFile, true);
    // sleep to wait delete operation
    Thread.sleep(10000);
    try {
      fs.listStatus(indexFile);
      fail("Index file should be deleted");
    } catch (Exception e) {
    }
    validateResult(rssShuffleDataIterator, expectedData, 10);
  }

  @Test
  public void readTest7() throws Exception {
    String basePath = HDFS_URI + "readTest7";
    HdfsShuffleWriteHandler writeHandler =
        new HdfsShuffleWriteHandler("appId", 0, 0, 1, basePath, "test", conf);

    Map<String, String> expectedData = Maps.newHashMap();
    Roaring64NavigableMap blockIdBitmap = Roaring64NavigableMap.bitmapOf();
    Roaring64NavigableMap taskIdBitmap = Roaring64NavigableMap.bitmapOf(0);
    writeTestData(writeHandler, 2, 5, expectedData,
        blockIdBitmap, "key", KRYO_SERIALIZER, 0);

    RssShuffleDataIterator rssShuffleDataIterator = getDataIterator(basePath, blockIdBitmap, taskIdBitmap);

    // crc32 is incorrect
    try (MockedStatic<ChecksumUtils> checksumUtilsMock = Mockito.mockStatic(ChecksumUtils.class)) {
      checksumUtilsMock.when(() -> ChecksumUtils.getCrc32((ByteBuffer) any())).thenReturn(-1L);
      try {
        while (rssShuffleDataIterator.hasNext()) {
          rssShuffleDataIterator.next();
        }
        fail(EXPECTED_EXCEPTION_MESSAGE);
      } catch (Exception e) {
        assertTrue(e.getMessage().startsWith("Unexpected crc value"));
      }
    }
  }

}
