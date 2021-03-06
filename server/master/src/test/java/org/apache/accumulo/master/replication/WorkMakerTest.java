/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.accumulo.master.replication;

import java.util.HashSet;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import org.apache.accumulo.core.client.BatchWriter;
import org.apache.accumulo.core.client.Connector;
import org.apache.accumulo.core.client.Scanner;
import org.apache.accumulo.core.client.mock.MockInstance;
import org.apache.accumulo.core.conf.Property;
import org.apache.accumulo.core.data.Key;
import org.apache.accumulo.core.data.Mutation;
import org.apache.accumulo.core.data.Value;
import org.apache.accumulo.core.replication.ReplicationSchema.StatusSection;
import org.apache.accumulo.core.replication.ReplicationSchema.WorkSection;
import org.apache.accumulo.core.replication.ReplicationTable;
import org.apache.accumulo.core.replication.ReplicationTarget;
import org.apache.accumulo.core.security.TablePermission;
import org.apache.accumulo.server.AccumuloServerContext;
import org.apache.accumulo.server.conf.ServerConfigurationFactory;
import org.apache.accumulo.server.replication.StatusUtil;
import org.apache.accumulo.server.replication.proto.Replication.Status;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.Text;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;

public class WorkMakerTest {

  private MockInstance instance;
  private Connector conn;

  @Rule
  public TestName name = new TestName();
  private AccumuloServerContext context;

  @Before
  public void createMockAccumulo() throws Exception {
    instance = new MockInstance();
    context = new AccumuloServerContext(new ServerConfigurationFactory(instance));
    conn = context.getConnector();
    conn.securityOperations().grantTablePermission("root", ReplicationTable.NAME, TablePermission.WRITE);
    conn.tableOperations().deleteRows(ReplicationTable.NAME, null, null);
  }

  @Test
  public void singleUnitSingleTarget() throws Exception {
    String table = name.getMethodName();
    conn.tableOperations().create(name.getMethodName());
    String tableId = conn.tableOperations().tableIdMap().get(table);
    String file = "hdfs://localhost:8020/accumulo/wal/123456-1234-1234-12345678";

    // Create a status record for a file
    long timeCreated = System.currentTimeMillis();
    Mutation m = new Mutation(new Path(file).toString());
    m.put(StatusSection.NAME, new Text(tableId), StatusUtil.fileCreatedValue(timeCreated));
    BatchWriter bw = ReplicationTable.getBatchWriter(conn);
    bw.addMutation(m);
    bw.flush();

    // Assert that we have one record in the status section
    Scanner s = ReplicationTable.getScanner(conn);
    StatusSection.limit(s);
    Assert.assertEquals(1, Iterables.size(s));

    WorkMaker workMaker = new WorkMaker(context, conn);

    // Invoke the addWorkRecord method to create a Work record from the Status record earlier
    ReplicationTarget expected = new ReplicationTarget("remote_cluster_1", "4", tableId);
    workMaker.setBatchWriter(bw);
    workMaker.addWorkRecord(new Text(file), StatusUtil.fileCreatedValue(timeCreated), ImmutableMap.of("remote_cluster_1", "4"), tableId);

    // Scan over just the WorkSection
    s = ReplicationTable.getScanner(conn);
    WorkSection.limit(s);

    Entry<Key,Value> workEntry = Iterables.getOnlyElement(s);
    Key workKey = workEntry.getKey();
    ReplicationTarget actual = ReplicationTarget.from(workKey.getColumnQualifier());

    Assert.assertEquals(file, workKey.getRow().toString());
    Assert.assertEquals(WorkSection.NAME, workKey.getColumnFamily());
    Assert.assertEquals(expected, actual);
    Assert.assertEquals(workEntry.getValue(), StatusUtil.fileCreatedValue(timeCreated));
  }

  @Test
  public void singleUnitMultipleTargets() throws Exception {
    String table = name.getMethodName();
    conn.tableOperations().create(name.getMethodName());

    String tableId = conn.tableOperations().tableIdMap().get(table);

    String file = "hdfs://localhost:8020/accumulo/wal/123456-1234-1234-12345678";

    Mutation m = new Mutation(new Path(file).toString());
    m.put(StatusSection.NAME, new Text(tableId), StatusUtil.fileCreatedValue(System.currentTimeMillis()));
    BatchWriter bw = ReplicationTable.getBatchWriter(context.getConnector());
    bw.addMutation(m);
    bw.flush();

    // Assert that we have one record in the status section
    Scanner s = ReplicationTable.getScanner(conn);
    StatusSection.limit(s);
    Assert.assertEquals(1, Iterables.size(s));

    WorkMaker workMaker = new WorkMaker(context, conn);

    Map<String,String> targetClusters = ImmutableMap.of("remote_cluster_1", "4", "remote_cluster_2", "6", "remote_cluster_3", "8");
    Set<ReplicationTarget> expectedTargets = new HashSet<>();
    for (Entry<String,String> cluster : targetClusters.entrySet()) {
      expectedTargets.add(new ReplicationTarget(cluster.getKey(), cluster.getValue(), tableId));
    }
    workMaker.setBatchWriter(bw);
    workMaker.addWorkRecord(new Text(file), StatusUtil.fileCreatedValue(System.currentTimeMillis()), targetClusters, tableId);

    s = ReplicationTable.getScanner(conn);
    WorkSection.limit(s);

    Set<ReplicationTarget> actualTargets = new HashSet<>();
    for (Entry<Key,Value> entry : s) {
      Assert.assertEquals(file, entry.getKey().getRow().toString());
      Assert.assertEquals(WorkSection.NAME, entry.getKey().getColumnFamily());

      ReplicationTarget target = ReplicationTarget.from(entry.getKey().getColumnQualifier());
      actualTargets.add(target);
    }

    for (ReplicationTarget expected : expectedTargets) {
      Assert.assertTrue("Did not find expected target: " + expected, actualTargets.contains(expected));
      actualTargets.remove(expected);
    }

    Assert.assertTrue("Found extra replication work entries: " + actualTargets, actualTargets.isEmpty());
  }

  @Test
  public void dontCreateWorkForEntriesWithNothingToReplicate() throws Exception {
    String table = name.getMethodName();
    conn.tableOperations().create(name.getMethodName());
    String tableId = conn.tableOperations().tableIdMap().get(table);
    String file = "hdfs://localhost:8020/accumulo/wal/123456-1234-1234-12345678";

    Mutation m = new Mutation(new Path(file).toString());
    m.put(StatusSection.NAME, new Text(tableId), StatusUtil.fileCreatedValue(System.currentTimeMillis()));
    BatchWriter bw = ReplicationTable.getBatchWriter(conn);
    bw.addMutation(m);
    bw.flush();

    // Assert that we have one record in the status section
    Scanner s = ReplicationTable.getScanner(conn);
    StatusSection.limit(s);
    Assert.assertEquals(1, Iterables.size(s));

    WorkMaker workMaker = new WorkMaker(context, conn);

    conn.tableOperations().setProperty(ReplicationTable.NAME, Property.TABLE_REPLICATION_TARGET.getKey() + "remote_cluster_1", "4");

    workMaker.setBatchWriter(bw);

    // If we don't shortcircuit out, we should get an exception because ServerConfiguration.getTableConfiguration
    // won't work with MockAccumulo
    workMaker.run();

    s = ReplicationTable.getScanner(conn);
    WorkSection.limit(s);

    Assert.assertEquals(0, Iterables.size(s));
  }

  @Test
  public void closedStatusRecordsStillMakeWork() throws Exception {
    WorkMaker workMaker = new WorkMaker(context, conn);

    Assert.assertFalse(workMaker.shouldCreateWork(StatusUtil.fileCreated(System.currentTimeMillis())));
    Assert.assertTrue(workMaker.shouldCreateWork(StatusUtil.ingestedUntil(1000)));
    // We don't need to re-create work for something that's already replicated.
    Assert.assertFalse(workMaker.shouldCreateWork(Status.newBuilder().setBegin(Long.MAX_VALUE).setEnd(0).setInfiniteEnd(true).setClosed(true).build()));
  }
}
