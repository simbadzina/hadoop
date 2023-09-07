/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hdfs.server.namenode.ha;

import static org.apache.hadoop.hdfs.server.namenode.ha.BootstrapStandby.ERR_CODE_INVALID_VERSION;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import java.util.function.Supplier;
import org.apache.hadoop.hdfs.DistributedFileSystem;
import org.apache.hadoop.hdfs.protocol.HdfsConstants.RollingUpgradeAction;
import org.apache.hadoop.hdfs.protocol.HdfsConstants.SafeModeAction;
import org.apache.hadoop.hdfs.server.common.HttpGetFailedException;
import org.apache.hadoop.hdfs.server.namenode.FSImage;
import org.apache.hadoop.hdfs.server.namenode.NameNodeLayoutVersion;
import org.apache.hadoop.hdfs.server.protocol.NamespaceInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileUtil;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hdfs.DFSConfigKeys;
import org.apache.hadoop.hdfs.DFSUtilClient;
import org.apache.hadoop.hdfs.MiniDFSCluster;
import org.apache.hadoop.hdfs.MiniDFSNNTopology;
import org.apache.hadoop.hdfs.server.namenode.CheckpointSignature;
import org.apache.hadoop.hdfs.server.namenode.FSImageTestUtil;
import org.apache.hadoop.hdfs.server.namenode.NNStorage;
import org.apache.hadoop.hdfs.server.namenode.NameNode;
import org.apache.hadoop.hdfs.server.namenode.NameNodeAdapter;
import org.apache.hadoop.test.GenericTestUtils;
import org.apache.hadoop.test.GenericTestUtils.LogCapturer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import org.apache.hadoop.thirdparty.com.google.common.collect.ImmutableList;

public class TestBootstrapStandby {
  private static final Logger LOG =
      LoggerFactory.getLogger(TestBootstrapStandby.class);

  private static final int maxNNCount = 3;
  private static final int STARTING_PORT = 20000;

  private MiniDFSCluster cluster;
  private NameNode nn0;

  @Before
  public void setupCluster() throws IOException {
    Configuration conf = new Configuration();

    // duplicate code with MiniQJMHACluster#createDefaultTopology, but don't want to cross
    // dependencies or munge too much code to support it all correctly
    MiniDFSNNTopology.NSConf nameservice = new MiniDFSNNTopology.NSConf("ns1");
    for (int i = 0; i < maxNNCount; i++) {
      nameservice.addNN(new MiniDFSNNTopology.NNConf("nn" + i).setHttpPort(STARTING_PORT + i + 1));
    }

    MiniDFSNNTopology topology = new MiniDFSNNTopology().addNameservice(nameservice);

    cluster = new MiniDFSCluster.Builder(conf)
        .nnTopology(topology)
        .numDataNodes(0)
        .build();
    cluster.waitActive();

    nn0 = cluster.getNameNode(0);
    cluster.transitionToActive(0);
    // shutdown the other NNs
    for (int i = 1; i < maxNNCount; i++) {
      cluster.shutdownNameNode(i);
    }
  }

  @After
  public void shutdownCluster() {
    if (cluster != null) {
      cluster.shutdown();
      cluster = null;
    }
  }

  /**
   * Test for the base success case. The primary NN
   * hasn't made any checkpoints, and we copy the fsimage_0
   * file over and start up.
   */
  @Test
  public void testSuccessfulBaseCase() throws Exception {
    removeStandbyNameDirs();

    // skip the first NN, its up
    for (int index = 1; index < maxNNCount; index++) {
      try {
        cluster.restartNameNode(index);
        fail("Did not throw");
      } catch (IOException ioe) {
        GenericTestUtils.assertExceptionContains(
            "storage directory does not exist or is not accessible", ioe);
      }

      int expectedCheckpointTxId = (int)NameNodeAdapter.getNamesystem(nn0)
          .getFSImage().getMostRecentCheckpointTxId();

      int rc = BootstrapStandby.run(new String[] { "-nonInteractive" },
          cluster.getConfiguration(index));
      assertEquals(0, rc);

      // Should have copied over the namespace from the active
      FSImageTestUtil.assertNNHasCheckpoints(cluster, index,
          ImmutableList.of(expectedCheckpointTxId));
    }

    // We should now be able to start the standbys successfully.
    restartNameNodesFromIndex(1);
  }

  /**
   * Test for downloading a checkpoint made at a later checkpoint
   * from the active.
   */
  @Test
  public void testDownloadingLaterCheckpoint() throws Exception {
    // Roll edit logs a few times to inflate txid
    nn0.getRpcServer().rollEditLog();
    nn0.getRpcServer().rollEditLog();
    // Make checkpoint
    NameNodeAdapter.enterSafeMode(nn0, false);
    NameNodeAdapter.saveNamespace(nn0);
    NameNodeAdapter.leaveSafeMode(nn0);
    long expectedCheckpointTxId = NameNodeAdapter.getNamesystem(nn0)
        .getFSImage().getMostRecentCheckpointTxId();
    assertEquals(6, expectedCheckpointTxId);

    // advance the current txid
    cluster.getFileSystem(0).create(new Path("/test_txid"), (short)1).close();

    // obtain the content of seen_txid
    URI editsUri = cluster.getSharedEditsDir(0, maxNNCount - 1);
    long seen_txid_shared = FSImageTestUtil.getStorageTxId(nn0, editsUri);

    for (int i = 1; i < maxNNCount; i++) {
      assertEquals(0, forceBootstrap(i));

      // Should have copied over the namespace from the active
      LOG.info("Checking namenode: " + i);
      FSImageTestUtil.assertNNHasCheckpoints(cluster, i,
          ImmutableList.of((int) expectedCheckpointTxId));
    }
    FSImageTestUtil.assertNNFilesMatch(cluster);

    // Make sure the seen_txid was not modified by the standby
    assertEquals(seen_txid_shared,
        FSImageTestUtil.getStorageTxId(nn0, editsUri));

    // We should now be able to start the standby successfully.
    restartNameNodesFromIndex(1);
  }

  /**
   * Test for downloading a checkpoint while the cluster is in rolling upgrade.
   */
  @Test
  public void testRollingUpgradeBootstrapStandby() throws Exception {
    removeStandbyNameDirs();

    int futureVersion = NameNodeLayoutVersion.CURRENT_LAYOUT_VERSION - 1;

    DistributedFileSystem fs = cluster.getFileSystem(0);
    fs.setSafeMode(SafeModeAction.SAFEMODE_ENTER);
    fs.saveNamespace();
    fs.setSafeMode(SafeModeAction.SAFEMODE_LEAVE);

    // Setup BootstrapStandby to think it is a future NameNode version
    BootstrapStandby bs = spy(new BootstrapStandby());
    doAnswer(nsInfo ->  {
      NamespaceInfo nsInfoSpy = (NamespaceInfo) spy(nsInfo.callRealMethod());
      doReturn(futureVersion).when(nsInfoSpy).getServiceLayoutVersion();
      return nsInfoSpy;
    }).when(bs).getProxyNamespaceInfo(any());

    // BootstrapStandby should fail if the node has a future version
    // and the cluster isn't in rolling upgrade
    bs.setConf(cluster.getConfiguration(1));
//    assertEquals("BootstrapStandby should return ERR_CODE_INVALID_VERSION",
//        ERR_CODE_INVALID_VERSION, bs.run(new String[]{"-force"}));

    // Start rolling upgrade
    fs.rollingUpgrade(RollingUpgradeAction.PREPARE);
    nn0 = spy(nn0);

    // Make nn0 think it is a future version
    doAnswer(fsImage -> {
      FSImage fsImageSpy = (FSImage) spy(fsImage.callRealMethod());
      doAnswer(storage -> {
        NNStorage storageSpy = (NNStorage) spy(storage.callRealMethod());
        doReturn(futureVersion).when(storageSpy).getServiceLayoutVersion();
        return storageSpy;
      }).when(fsImageSpy).getStorage();
      return fsImageSpy;
    }).when(nn0).getFSImage();

    // Roll edit logs a few times to inflate txid
    nn0.getRpcServer().rollEditLog();
    nn0.getRpcServer().rollEditLog();

    // Make checkpoint
    NameNodeAdapter.enterSafeMode(nn0, false);
    NameNodeAdapter.saveNamespace(nn0);
    NameNodeAdapter.leaveSafeMode(nn0);

    long expectedCheckpointTxId = NameNodeAdapter.getNamesystem(nn0)
        .getFSImage().getMostRecentCheckpointTxId();
    assertEquals(11, expectedCheckpointTxId);

    for (int i = 1; i < maxNNCount; i++) {
      // BootstrapStandby on Standby NameNode
      bs.setConf(cluster.getConfiguration(i));
      bs.run(new String[]{"-force"});
      FSImageTestUtil.assertNNHasCheckpoints(cluster, i,
          ImmutableList.of((int) expectedCheckpointTxId));
    }

    // Make sure the bootstrap was successful
    FSImageTestUtil.assertNNFilesMatch(cluster);

    // We should now be able to start the standby successfully
    restartNameNodesFromIndex(1, "-rollingUpgrade", "started");

    // Cleanup standby dirs
    for (int i = 1; i < maxNNCount; i++) {
      cluster.shutdownNameNode(i);
    }
    removeStandbyNameDirs();

    // BootstrapStandby should fail if it thinks it's version is future version
    // before rolling upgrade is finalized;
    doAnswer(nsInfo -> {
      NamespaceInfo nsInfoSpy = (NamespaceInfo) spy(nsInfo.callRealMethod());
      nsInfoSpy.layoutVersion = futureVersion;
      doReturn(futureVersion).when(nsInfoSpy).getServiceLayoutVersion();
      return nsInfoSpy;
    }).when(bs).getProxyNamespaceInfo(any());

    for (int i = 1; i < maxNNCount; i++) {
      bs.setConf(cluster.getConfiguration(i));
//      assertThrows("BootstrapStandby should fail the image transfer request",
//          HttpGetFailedException.class, () -> {
//            try {
//              bs.run(new String[]{"-force"});
//            } catch (RuntimeException e) {
//              throw e.getCause();
//            }
//          });
    }
  }

  /**
   * Test for the case where the shared edits dir doesn't have
   * all of the recent edit logs.
   */
  @Test
  public void testSharedEditsMissingLogs() throws Exception {
    removeStandbyNameDirs();

    CheckpointSignature sig = nn0.getRpcServer().rollEditLog();
    assertEquals(3, sig.getCurSegmentTxId());

    // Should have created edits_1-2 in shared edits dir
    URI editsUri = cluster.getSharedEditsDir(0, maxNNCount - 1);
    File editsDir = new File(editsUri);
    File currentDir = new File(editsDir, "current");
    File editsSegment = new File(currentDir,
        NNStorage.getFinalizedEditsFileName(1, 2));
    GenericTestUtils.assertExists(editsSegment);
    GenericTestUtils.assertExists(currentDir);

    // Delete the segment.
    assertTrue(editsSegment.delete());

    // Trying to bootstrap standby should now fail since the edit
    // logs aren't available in the shared dir.
    LogCapturer logs = GenericTestUtils.LogCapturer.captureLogs(
        LoggerFactory.getLogger(BootstrapStandby.class));
    try {
      assertEquals(BootstrapStandby.ERR_CODE_LOGS_UNAVAILABLE, forceBootstrap(1));
    } finally {
      logs.stopCapturing();
    }
    assertTrue(logs.getOutput().contains(
        "Unable to read transaction ids 1-3 from the configured shared"));
  }

  /**
   * Show that bootstrapping will fail on a given NameNode if its directories already exist. Its not
   * run across all the NN because its testing the state local on each node.
   * @throws Exception on unexpected failure
   */
  @Test
  public void testStandbyDirsAlreadyExist() throws Exception {
    // Should not pass since standby dirs exist, force not given
    int rc = BootstrapStandby.run(
        new String[]{"-nonInteractive"},
        cluster.getConfiguration(1));
    assertEquals(BootstrapStandby.ERR_CODE_ALREADY_FORMATTED, rc);

    // Should pass with -force
    assertEquals(0, forceBootstrap(1));
  }

  /**
   * Test that, even if the other node is not active, we are able
   * to bootstrap standby from it.
   */
  @Test(timeout=30000)
  public void testOtherNodeNotActive() throws Exception {
    cluster.transitionToStandby(0);
    assertSuccessfulBootstrapFromIndex(1);
  }

  /**
   * Test that bootstrapping standby NN is not limited by
   * {@link DFSConfigKeys#DFS_IMAGE_TRANSFER_RATE_KEY}, but is limited by
   * {@link DFSConfigKeys#DFS_IMAGE_TRANSFER_BOOTSTRAP_STANDBY_RATE_KEY}
   * created by HDFS-8808.
   */
  @Test(timeout=180000)
  public void testRateThrottling() throws Exception {
    cluster.getConfiguration(0).setLong(
        DFSConfigKeys.DFS_IMAGE_TRANSFER_RATE_KEY, 1);
    cluster.restartNameNode(0);
    cluster.waitActive();
    nn0 = cluster.getNameNode(0);
    cluster.transitionToActive(0);
    // Any reasonable test machine should be able to transfer 1 byte per MS
    // (which is ~1K/s)
    final int minXferRatePerMS = 1;
    int imageXferBufferSize = DFSUtilClient.getIoFileBufferSize(
        new Configuration());
    File imageFile = null;
    int dirIdx = 0;
    while (imageFile == null || imageFile.length() < imageXferBufferSize) {
      for (int i = 0; i < 5; i++) {
        cluster.getFileSystem(0).mkdirs(new Path("/foo" + dirIdx++));
      }
      nn0.getRpcServer().rollEditLog();
      NameNodeAdapter.enterSafeMode(nn0, false);
      NameNodeAdapter.saveNamespace(nn0);
      NameNodeAdapter.leaveSafeMode(nn0);
      imageFile = FSImageTestUtil.findLatestImageFile(FSImageTestUtil
          .getFSImage(nn0).getStorage().getStorageDir(0));
    }

    final int timeOut = (int)(imageFile.length() / minXferRatePerMS) + 1;
    // A very low DFS_IMAGE_TRANSFER_RATE_KEY value won't affect bootstrapping
    final AtomicBoolean bootStrapped = new AtomicBoolean(false);
    new Thread(
        new Runnable() {
          @Override
          public void run() {
            try {
              testSuccessfulBaseCase();
              bootStrapped.set(true);
            } catch (Exception e) {
              fail(e.getMessage());
            }
          }
        }
    ).start();
    GenericTestUtils.waitFor(new Supplier<Boolean>() {
      public Boolean get() {
        return bootStrapped.get();
      }
    }, 50, timeOut);

    shutdownCluster();
    setupCluster();
    cluster.getConfiguration(0).setLong(
        DFSConfigKeys.DFS_IMAGE_TRANSFER_BOOTSTRAP_STANDBY_RATE_KEY, 1);
    cluster.restartNameNode(0);
    cluster.waitActive();
    nn0 = cluster.getNameNode(0);
    cluster.transitionToActive(0);
    // A very low DFS_IMAGE_TRANSFER_BOOTSTRAP_STANDBY_RATE_KEY value should
    // cause timeout
    bootStrapped.set(false);
    new Thread(
        new Runnable() {
          @Override
          public void run() {
            try {
              testSuccessfulBaseCase();
              bootStrapped.set(true);
            } catch (Exception e) {
              LOG.info(e.getMessage());
            }
          }
        }
    ).start();
    try {
      GenericTestUtils.waitFor(new Supplier<Boolean>() {
        public Boolean get() {
          return bootStrapped.get();
        }
      }, 50, timeOut);
      fail("Did not timeout");
    } catch (TimeoutException e) {
      LOG.info("Encountered expected timeout.");
    }
  }
  private void removeStandbyNameDirs() {
    for (int i = 1; i < maxNNCount; i++) {
      for (URI u : cluster.getNameDirs(i)) {
        assertTrue(u.getScheme().equals("file"));
        File dir = new File(u.getPath());
        LOG.info("Removing standby dir " + dir);
        assertTrue(FileUtil.fullyDelete(dir));
      }
    }
  }

  private void restartNameNodesFromIndex(int start, String... args) throws IOException {
    for (int i = start; i < maxNNCount; i++) {
      // We should now be able to start the standby successfully.
      cluster.restartNameNode(i, false, args);
    }

    cluster.waitClusterUp();
    cluster.waitActive();
  }

  /**
   * Force boot strapping on a namenode
   * @param i index of the namenode to attempt
   * @return exit code
   * @throws Exception on unexpected failure
   */
  private int forceBootstrap(int i) throws Exception {
    return BootstrapStandby.run(new String[] { "-force" },
        cluster.getConfiguration(i));
  }

  private void assertSuccessfulBootstrapFromIndex(int start) throws Exception {
    for (int i = start; i < maxNNCount; i++) {
      assertEquals(0, forceBootstrap(i));
    }
  }
}
