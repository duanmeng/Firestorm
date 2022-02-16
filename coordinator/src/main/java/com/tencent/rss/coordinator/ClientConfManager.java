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

package com.tencent.rss.coordinator;

import java.io.Closeable;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import com.google.common.collect.Maps;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.tencent.rss.storage.util.ShuffleStorageUtils;

public class ClientConfManager implements Closeable {
  private static final Logger LOG = LoggerFactory.getLogger(ClientConfManager.class);

  private final AtomicReference<Map<String, String>> clientConf = new AtomicReference<>();
  private final AtomicLong lastCandidatesUpdateMS = new AtomicLong(0L);
  private final Path path;
  private final ScheduledExecutorService updateClientConfSES;
  private final FileSystem fileSystem;
  private static final String WHITESPACE_REGEX = "\\s+";


  public ClientConfManager(CoordinatorConf conf, Configuration hadoopConf) throws Exception {
    String pathStr = conf.get(CoordinatorConf.COORDINATOR_DYNAMIC_CLIENT_CONF_PATH);
    this.path = new Path(pathStr);

    this.fileSystem = ShuffleStorageUtils.getFileSystemForPath(path, hadoopConf);
    if (!fileSystem.isFile(path)) {
      String msg = String.format("Fail to init ClientConfManager, %s is not a file", path.toUri());
      LOG.error(msg);
      throw new IllegalStateException(msg);
    }

    FileStatus[] fileStatus = fileSystem.listStatus(path);
    if (ArrayUtils.isEmpty(fileStatus)) {
      throw new IllegalStateException("Fail to list file " + path);
    }

    int updateIntervalS = conf.getInteger(CoordinatorConf.COORDINATOR_DYNAMIC_CLIENT_CONF_UPDATE_INTERVAL_SEC);
    updateClientConfSES = Executors.newSingleThreadScheduledExecutor(
        new ThreadFactoryBuilder().setDaemon(true).setNameFormat("ClientConfManager-%d").build());
    updateClientConfSES.scheduleAtFixedRate(
        this::updateClientConf, 0, updateIntervalS, TimeUnit.SECONDS);
  }

  private void updateClientConf() {
    try {
      FileStatus[] fileStatus = fileSystem.listStatus(path);
      if (!ArrayUtils.isEmpty(fileStatus)) {
        long lastModifiedMS = fileStatus[0].getModificationTime();
        if (lastCandidatesUpdateMS.get() != lastModifiedMS) {
          updateClientConfInternal();
          lastCandidatesUpdateMS.set(lastModifiedMS);
        }
      } else {
        clientConf.set(Maps.newHashMap());
      }
    } catch (Exception e) {
      LOG.warn("Error when update client conf, ignore this attempt.", e);
    }
  }

  private void updateClientConfInternal() {
    Map<String, String> newClientConf = Maps.newHashMap();
    String content = loadClientConfContent();
    if (StringUtils.isEmpty(content)) {
      LOG.warn("Load empty content from {}", path.toUri().toString());
      clientConf.set(newClientConf);
      return;
    }

    for (String item : content.split(IOUtils.LINE_SEPARATOR_UNIX)) {
      String confItem = item.trim();
      if (!StringUtils.isEmpty(confItem)) {
        String[] confKV = confItem.split(WHITESPACE_REGEX);
        if (confKV.length == 2) {
          newClientConf.put(confKV[0], confKV[1]);
        }
      }
    }

    if (newClientConf.isEmpty()) {
      LOG.warn("Empty content in {}, client conf in coordinator will be empty", path.toUri().toString());
    }

    clientConf.set(newClientConf);
  }

  private String loadClientConfContent() {
    String content = null;
    try (FSDataInputStream in = fileSystem.open(path)) {
      content = IOUtils.toString(in, StandardCharsets.UTF_8);
    } catch (Exception e) {
      LOG.error("Fail to load content from {}", path.toUri().toString());
    }
    return content;
  }

  Map<String, String> getClientConf() {
    return clientConf.get();
  }

  @Override
  public void close() {
    updateClientConfSES.shutdownNow();
  }
}
