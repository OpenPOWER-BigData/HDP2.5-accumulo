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
package org.apache.accumulo.test.functional;

import static org.junit.Assert.assertEquals;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map.Entry;
import java.util.Set;

import org.apache.accumulo.core.Constants;
import org.apache.accumulo.core.client.AccumuloException;
import org.apache.accumulo.core.client.AccumuloSecurityException;
import org.apache.accumulo.core.client.BatchDeleter;
import org.apache.accumulo.core.client.BatchWriterConfig;
import org.apache.accumulo.core.client.Connector;
import org.apache.accumulo.core.client.MutationsRejectedException;
import org.apache.accumulo.core.client.Scanner;
import org.apache.accumulo.core.client.TableExistsException;
import org.apache.accumulo.core.client.TableNotFoundException;
import org.apache.accumulo.core.client.impl.Tables;
import org.apache.accumulo.core.data.Key;
import org.apache.accumulo.core.data.Value;
import org.apache.accumulo.core.data.impl.KeyExtent;
import org.apache.accumulo.core.master.state.tables.TableState;
import org.apache.accumulo.core.master.thrift.MasterState;
import org.apache.accumulo.core.metadata.MetadataTable;
import org.apache.accumulo.core.metadata.schema.MetadataSchema;
import org.apache.accumulo.core.security.Authorizations;
import org.apache.accumulo.core.zookeeper.ZooUtil;
import org.apache.accumulo.fate.zookeeper.ZooCache;
import org.apache.accumulo.harness.SharedMiniClusterIT;
import org.apache.accumulo.server.master.state.CurrentState;
import org.apache.accumulo.server.master.state.MergeInfo;
import org.apache.accumulo.server.master.state.MetaDataTableScanner;
import org.apache.accumulo.server.master.state.TServerInstance;
import org.apache.accumulo.server.master.state.TabletStateChangeIterator;
import org.apache.accumulo.server.zookeeper.ZooLock;
import org.apache.hadoop.io.Text;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import com.google.common.base.Predicate;
import com.google.common.collect.Sets;

/**
 * Test to ensure that the {@link TabletStateChangeIterator} properly skips over tablet information in the metadata table when there is no work to be done on
 * the tablet (see ACCUMULO-3580)
 */
public class TabletStateChangeIteratorIT extends SharedMiniClusterIT {

  @Override
  public int defaultTimeoutSeconds() {
    return 2 * 60;
  }

  @BeforeClass
  public static void setup() throws Exception {
    SharedMiniClusterIT.startMiniCluster();
  }

  @AfterClass
  public static void teardown() throws Exception {
    SharedMiniClusterIT.stopMiniCluster();
  }

  @Test
  public void test() throws AccumuloException, AccumuloSecurityException, TableExistsException, TableNotFoundException {
    String[] tables = getUniqueNames(4);
    final String t1 = tables[0];
    final String t2 = tables[1];
    final String t3 = tables[2];
    final String cloned = tables[3];

    // create some metadata
    createTable(t1, true);
    createTable(t2, false);
    createTable(t3, true);

    // examine a clone of the metadata table, so we can manipulate it
    cloneMetadataTable(cloned);

    assertEquals("No tables should need attention", 0, findTabletsNeedingAttention(cloned));

    // test the assigned case (no location)
    removeLocation(cloned, t3);
    assertEquals("Should have one tablet without a loc", 1, findTabletsNeedingAttention(cloned));

    // TODO test the cases where the assignment is to a dead tserver
    // TODO test the cases where there is ongoing merges
    // TODO test the bad tablet location state case (active split, inconsistent metadata)

    // clean up
    dropTables(t1, t2, t3);
  }

  private void removeLocation(String table, String tableNameToModify) throws TableNotFoundException, MutationsRejectedException {
    String tableIdToModify = getConnector().tableOperations().tableIdMap().get(tableNameToModify);
    BatchDeleter deleter = getConnector().createBatchDeleter(table, Authorizations.EMPTY, 1, new BatchWriterConfig());
    deleter.setRanges(Collections.singleton(new KeyExtent(new Text(tableIdToModify), null, null).toMetadataRange()));
    deleter.fetchColumnFamily(MetadataSchema.TabletsSection.CurrentLocationColumnFamily.NAME);
    deleter.delete();
    deleter.close();
  }

  private int findTabletsNeedingAttention(String table) throws TableNotFoundException {
    int results = 0;
    Scanner scanner = getConnector().createScanner(table, Authorizations.EMPTY);
    MetaDataTableScanner.configureScanner(scanner, new State());
    scanner.updateScanIteratorOption("tabletChange", "debug", "1");
    for (Entry<Key,Value> e : scanner) {
      if (e != null)
        results++;
    }
    return results;
  }

  private void createTable(String t, boolean online) throws AccumuloSecurityException, AccumuloException, TableNotFoundException, TableExistsException {
    Connector conn = getConnector();
    conn.tableOperations().create(t);
    conn.tableOperations().online(t, true);
    if (!online) {
      conn.tableOperations().offline(t, true);
    }
  }

  private void cloneMetadataTable(String cloned) throws AccumuloException, AccumuloSecurityException, TableNotFoundException, TableExistsException {
    getConnector().tableOperations().clone(MetadataTable.NAME, cloned, true, null, null);
  }

  private void dropTables(String... tables) throws AccumuloException, AccumuloSecurityException, TableNotFoundException {
    for (String t : tables) {
      getConnector().tableOperations().delete(t);
    }
  }

  private final class State implements CurrentState {

    @Override
    public Set<TServerInstance> onlineTabletServers() {
      HashSet<TServerInstance> tservers = new HashSet<TServerInstance>();
      for (String tserver : getConnector().instanceOperations().getTabletServers()) {
        try {
          String zPath = ZooUtil.getRoot(getConnector().getInstance()) + Constants.ZTSERVERS + "/" + tserver;
          long sessionId = ZooLock.getSessionId(new ZooCache(getCluster().getZooKeepers(), getConnector().getInstance().getZooKeepersSessionTimeOut()), zPath);
          tservers.add(new TServerInstance(tserver, sessionId));
        } catch (Exception e) {
          throw new RuntimeException(e);
        }
      }
      return tservers;
    }

    @Override
    public Set<String> onlineTables() {
      HashSet<String> onlineTables = new HashSet<String>(getConnector().tableOperations().tableIdMap().values());
      return Sets.filter(onlineTables, new Predicate<String>() {
        @Override
        public boolean apply(String tableId) {
          return Tables.getTableState(getConnector().getInstance(), tableId) == TableState.ONLINE;
        }
      });
    }

    @Override
    public Collection<MergeInfo> merges() {
      return Collections.emptySet();
    }

    @Override
    public Collection<KeyExtent> migrations() {
      return Collections.emptyList();
    }

    @Override
    public MasterState getMasterState() {
      return MasterState.NORMAL;
    }

    @Override
    public Set<TServerInstance> shutdownServers() {
      return Collections.emptySet();
    }

  }

}
