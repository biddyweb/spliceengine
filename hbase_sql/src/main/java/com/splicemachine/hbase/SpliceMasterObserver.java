package com.splicemachine.hbase;

import com.splicemachine.SQLConfiguration;
import com.splicemachine.access.HConfiguration;
import com.splicemachine.access.api.SConfiguration;
import com.splicemachine.concurrent.SystemClock;
import com.splicemachine.derby.lifecycle.EngineLifecycleService;
import com.splicemachine.lifecycle.DatabaseLifecycleManager;
import com.splicemachine.lifecycle.MasterLifecycle;
import com.splicemachine.si.api.SIConfigurations;
import com.splicemachine.si.data.hbase.coprocessor.HBaseSIEnvironment;
import com.splicemachine.si.impl.driver.SIDriver;
import com.splicemachine.timestamp.api.TimestampBlockManager;
import com.splicemachine.timestamp.hbase.ZkTimestampBlockManager;
import com.splicemachine.timestamp.impl.TimestampServer;
import com.splicemachine.utils.SpliceLogUtils;
import org.apache.hadoop.ha.HAServiceStatus;
import org.apache.hadoop.hbase.*;
import org.apache.hadoop.hbase.coprocessor.BaseMasterObserver;
import org.apache.hadoop.hbase.coprocessor.MasterCoprocessorEnvironment;
import org.apache.hadoop.hbase.coprocessor.ObserverContext;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.hbase.zookeeper.RecoverableZooKeeper;
import org.apache.hadoop.hbase.zookeeper.ZooKeeperWatcher;
import org.apache.log4j.Logger;
import java.io.IOException;

/**
 * Responsible for actions (create system tables, restore tables) that should only happen on one node.
 */
public class SpliceMasterObserver extends BaseMasterObserver {

    private static final Logger LOG = Logger.getLogger(SpliceMasterObserver.class);

    public static final byte[] INIT_TABLE = Bytes.toBytes("SPLICE_INIT");

    private TimestampServer timestampServer;
    private DatabaseLifecycleManager manager;

    @Override
    public void start(CoprocessorEnvironment ctx) throws IOException {
        LOG.info("Starting SpliceMasterObserver");

        LOG.info("Starting Timestamp Master Observer");

        ZooKeeperWatcher zkw = ((MasterCoprocessorEnvironment)ctx).getMasterServices().getZooKeeper();
        RecoverableZooKeeper rzk = zkw.getRecoverableZooKeeper();

        HBaseSIEnvironment env=HBaseSIEnvironment.loadEnvironment(new SystemClock(),rzk);
        SConfiguration configuration=env.configuration();

        String timestampReservedPath=configuration.getString(HConfiguration.SPLICE_ROOT_PATH)+HConfiguration.MAX_RESERVED_TIMESTAMP_PATH;
        int timestampPort=configuration.getInt(SIConfigurations.TIMESTAMP_SERVER_BIND_PORT);
        int timestampBlockSize = configuration.getInt(HConfiguration.TIMESTAMP_BLOCK_SIZE);

        TimestampBlockManager tbm= new ZkTimestampBlockManager(rzk,timestampReservedPath);
        this.timestampServer =new TimestampServer(timestampPort,tbm,timestampBlockSize);

        this.timestampServer.startServer();

        /*
         * We create a new instance here rather than referring to the singleton because we have
         * a problem when booting the master and the region server in the same JVM; the singleton
         * then is unable to boot on the master side because the regionserver has already started it.
         *
         * Generally, this isn't a problem because the underlying singleton is constructed on demand, so we
         * will still only create a single manager per JVM in a production environment, and we avoid the deadlock
         * issue during testing
         */
        this.manager = new DatabaseLifecycleManager();
        super.start(ctx);
    }

    @Override
    public void stop(CoprocessorEnvironment ctx) throws IOException {
        LOG.warn("Stopping SpliceMasterObserver");
        manager.shutdown();
        this.timestampServer.stopServer();
    }

    @Override
    public void preCreateTable(ObserverContext<MasterCoprocessorEnvironment> ctx, HTableDescriptor desc, HRegionInfo[] regions) throws IOException {
        SpliceLogUtils.info(LOG, "preCreateTable %s", Bytes.toString(desc.getTableName().getName()));
        if (Bytes.equals(desc.getTableName().getName(), INIT_TABLE)) {
            switch(manager.getState()){
                case NOT_STARTED:
                    boot();
                case BOOTING_ENGINE:
                case BOOTING_GENERAL_SERVICES:
                case BOOTING_SERVER:
                    throw new PleaseHoldException("Please Hold - Starting");
                case RUNNING:
                    throw new DoNotRetryIOException("Success");
                case STARTUP_FAILED:
                case SHUTTING_DOWN:
                case SHUTDOWN:
                    throw new IllegalStateException("Startup failed");
            }
        }
    }

    private synchronized void boot() throws IOException{
        //make sure the SIDriver is booted
        if (! manager.getState().equals(DatabaseLifecycleManager.State.NOT_STARTED))
            return; // Race Condition, only load one...

        //ensure that the SI environment is booted properly
        HBaseSIEnvironment env=HBaseSIEnvironment.loadEnvironment(new SystemClock(),ZkUtils.getRecoverableZooKeeper());
        SIDriver driver = env.getSIDriver();

        //make sure the configuration is correct
        SConfiguration config=driver.getConfiguration();
        config.addDefaults(SQLConfiguration.defaults);

        //register the engine boot service
        try{
            MasterLifecycle distributedStartupSequence=new MasterLifecycle();
            manager.registerEngineService(new EngineLifecycleService(distributedStartupSequence,config));
            manager.start();
        }catch(Exception e1){
            throw new DoNotRetryIOException(e1);
        }
    }

}