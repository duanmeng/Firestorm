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

package com.tencent.rss.storage.handler.impl;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.tencent.rss.common.BufferSegment;
import com.tencent.rss.common.ShuffleDataResult;
import com.tencent.rss.common.ShufflePartitionedBlock;
import com.tencent.rss.common.util.ChecksumUtils;
import com.tencent.rss.common.util.Constants;
import com.tencent.rss.storage.HdfsTestBase;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;

public class HdfsClientReadHandlerTest extends HdfsTestBase {

  private static AtomicLong ATOMIC_LONG = new AtomicLong(0);

  @Test
  public void test() {
    try {
      String basePath = HDFS_URI + "clientReadTest1";
      HdfsShuffleWriteHandler writeHandler =
          new HdfsShuffleWriteHandler(
              "appId",
              0,
              1,
              1,
              basePath,
              "test1",
              conf);

      Map<Long, byte[]> expectedData = Maps.newHashMap();

      int readBufferSize = 13;
      int total = 0;
      int totalBlockNum = 0;
      int expectTotalBlockNum = 0;
      for (int i = 0; i < 5; i++) {
        writeHandler.setFailTimes(i);
        int num = new Random().nextInt(17);
        writeTestData(writeHandler,  num, 3, 0, expectedData);
        total += calcExpectedSegmentNum(num, 3, readBufferSize);
        expectTotalBlockNum += num;
      }

      HdfsClientReadHandler handler = new HdfsClientReadHandler(
          "appId",
          0,
          1,
          1024 * 10214,
          1,
          10,
          readBufferSize,
          basePath,
          conf);
      Set<Long> actualBlockIds = Sets.newHashSet();

      for (int i = 0; i < total; ++i) {
        ShuffleDataResult shuffleDataResult = handler.readShuffleData(i);
        totalBlockNum += shuffleDataResult.getBufferSegments().size();
        checkData(shuffleDataResult, expectedData);
        for (BufferSegment bufferSegment : shuffleDataResult.getBufferSegments()) {
          actualBlockIds.add(bufferSegment.getBlockId());
        }
      }

      assertNull(handler.readShuffleData(total));
      assertEquals(
          total,
          handler.getHdfsShuffleFileReadHandlers()
              .stream()
              .mapToInt(i -> i.getShuffleDataSegments().size())
              .sum());
      assertEquals(expectTotalBlockNum, totalBlockNum);
      assertEquals(expectedData.keySet(), actualBlockIds);
      assertEquals(5, handler.getReadHandlerIndex());
    } catch (Exception e) {
      e.printStackTrace();
      fail(e.getMessage());
    }
  }

  private void writeTestData(
      HdfsShuffleWriteHandler writeHandler,
      int num, int length, long taskAttemptId,
      Map<Long, byte[]> expectedData) throws Exception {
    List<ShufflePartitionedBlock> blocks = Lists.newArrayList();
    for (int i = 0; i < num; i++) {
      byte[] buf = new byte[length];
      new Random().nextBytes(buf);
      long blockId = (ATOMIC_LONG.getAndIncrement()
          << (Constants.PARTITION_ID_MAX_LENGTH + Constants.TASK_ATTEMPT_ID_MAX_LENGTH)) + taskAttemptId;
      blocks.add(new ShufflePartitionedBlock(
          length, length, ChecksumUtils.getCrc32(buf), blockId, taskAttemptId, buf));
      expectedData.put(blockId, buf);
    }
    writeHandler.write(blocks);
  }

  private int calcExpectedSegmentNum(int num, int size, int bufferSize) {
    int segmentNum = 0;
    int cur = 0;
    for (int i = 0; i < num; ++i) {
      cur += size;
      if (cur >= bufferSize) {
        segmentNum++;
        cur = 0;
      }
    }

    if (cur > 0) {
      ++segmentNum;
    }

    return segmentNum;
  }

  protected void checkData(ShuffleDataResult shuffleDataResult, Map<Long, byte[]> expectedData) {

    byte[] buffer = shuffleDataResult.getData();
    List<BufferSegment> bufferSegments = shuffleDataResult.getBufferSegments();

    for (BufferSegment bs : bufferSegments) {
      byte[] data = new byte[bs.getLength()];
      System.arraycopy(buffer, bs.getOffset(), data, 0, bs.getLength());
      assertEquals(bs.getCrc(), ChecksumUtils.getCrc32(data));
      assertArrayEquals(expectedData.get(bs.getBlockId()), data);
    }
  }
}
