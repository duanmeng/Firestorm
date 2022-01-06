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

import java.util.Arrays;
import java.util.List;

import com.google.common.collect.Lists;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.tencent.rss.common.util.RssUtils;

public class AccessManager {

  private static final Logger LOG = LoggerFactory.getLogger(AccessManager.class);

  private final CoordinatorConf coordinatorConf;
  private final ClusterManager clusterManager;
  private List<AccessChecker> accessCheckers = Lists.newArrayList();

  public AccessManager(CoordinatorConf conf, ClusterManager clusterManager) throws RuntimeException {
    this.coordinatorConf = conf;
    this.clusterManager = clusterManager;
    init();
  }

  private void init() throws RuntimeException {
    String checkers = coordinatorConf.get(CoordinatorConf.COORDINATOR_ACCESS_CHECKERS);
    if (StringUtils.isEmpty(checkers)) {
      String msg = "Access checkers can not be empty once access manager enabled.";
      LOG.error(msg);
      throw new RuntimeException(msg);
    }

    String[] names = checkers.trim().split(",");
    if (ArrayUtils.isEmpty(names)) {
      String msg = String.format("%s is wrong and empty access checkers, AccessManager will do nothing.",
          CoordinatorConf.COORDINATOR_ACCESS_CHECKERS.toString());
      throw new RuntimeException(msg);
    }

    accessCheckers = RssUtils.loadExtensions(AccessChecker.class, Arrays.asList(names), this);
    if (accessCheckers.isEmpty()) {
      String msg = String.format("%s is wrong and empty access checkers, AccessManager will do nothing.",
          CoordinatorConf.COORDINATOR_ACCESS_CHECKERS.toString());
      throw new RuntimeException(msg);
    }
  }

  public AccessCheckResult handleAccessRequest(String accessInfo) {
    for (AccessChecker checker : accessCheckers) {
      AccessCheckResult accessCheckResult = checker.check(accessInfo);
      if (!accessCheckResult.isSuccess()) {
        return accessCheckResult;
      }
    }

    return new AccessCheckResult(true, "");
  }

  public CoordinatorConf getCoordinatorConf() {
    return coordinatorConf;
  }

  public ClusterManager getClusterManager() {
    return clusterManager;
  }

  public List<AccessChecker> getAccessCheckers() {
    return accessCheckers;
  }

  public void close() {
    for (AccessChecker checker : accessCheckers) {
      checker.stop();
    }
  }
}
