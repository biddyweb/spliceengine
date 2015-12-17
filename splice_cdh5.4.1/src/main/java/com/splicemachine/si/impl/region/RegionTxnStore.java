package com.splicemachine.si.impl.region;

import com.carrotsearch.hppc.LongArrayList;
import com.splicemachine.encoding.Encoding;
import com.splicemachine.primitives.Bytes;
import com.splicemachine.storage.DataCell;
import com.splicemachine.si.api.data.SDataLib;
import com.splicemachine.si.api.data.ExceptionFactory;
import com.splicemachine.si.api.txn.STransactionLib;
import com.splicemachine.si.api.txn.Txn;
import com.splicemachine.si.api.txn.TxnDecoder;
import com.splicemachine.si.api.txn.TxnSupplier;
import com.splicemachine.si.api.txn.lifecycle.TxnPartition;
import com.splicemachine.si.constants.SIConstants;
import com.splicemachine.si.coprocessor.TxnMessage;
import com.splicemachine.si.impl.HCannotCommitException;
import com.splicemachine.si.impl.TxnUtils;
import com.splicemachine.si.impl.driver.SIDriver;
import com.splicemachine.utils.Source;
import com.splicemachine.utils.SpliceLogUtils;
import org.apache.hadoop.hbase.Cell;
import org.apache.hadoop.hbase.client.*;
import org.apache.hadoop.hbase.regionserver.HRegion;
import org.apache.hadoop.hbase.regionserver.RegionScanner;
import org.apache.log4j.Logger;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * Uses an HRegion to access Txn information.
 * <p/>
 * Intended <em>only</em> to be used within a coprocessor on a
 * region of the Transaction table.
 *
 * @author Scott Fines
 *         Date: 6/19/14
 */
public class RegionTxnStore<Filter, TableBuffer> implements TxnPartition{
    private static final Logger LOG=Logger.getLogger(RegionTxnStore.class);
    /*
     * The region in which to access data
     */
    private final STransactionLib<TxnMessage.Txn, TableBuffer> transactionlib=SIDriver.getTransactionLib();

    private final TxnDecoder<OperationWithAttributes, Cell, Delete, Filter,
            Get, Put, RegionScanner, Result, Scan> newTransactionDecoder=transactionlib.getV2TxnDecoder();
    private final SDataLib<OperationWithAttributes, Cell, Delete, Filter, Get,
            Put, RegionScanner, Result, Scan> dataLib=SIDriver.getDataLib();
    private final TransactionResolver resolver;
    private final ExceptionFactory exceptionLib=SIDriver.getExceptionLib();
    private final TxnSupplier txnSupplier;
    private final HRegion region;

    public RegionTxnStore(HRegion region,TxnSupplier txnSupplier,TransactionResolver resolver){
        this.txnSupplier = txnSupplier;
        this.region=region;
        this.resolver = resolver;
    }

    @Override
    public IOException cannotCommit(long txnId,Txn.State state){
        return new HCannotCommitException(txnId,state);
    }

    @Override
    public TxnMessage.Txn getTransaction(long txnId) throws IOException{
        if(LOG.isTraceEnabled())
            SpliceLogUtils.trace(LOG,"getTransaction txnId=%d",txnId);
        Get get=dataLib.newGet(TxnUtils.getRowKey(txnId));
        Result result=region.get(get);
        if(dataLib.noResult(result))
            return null; //no transaction
        return decode(txnId,result);
    }

    @Override
    public void addDestinationTable(long txnId,byte[] destinationTable) throws IOException{
        if(LOG.isTraceEnabled())
            SpliceLogUtils.trace(LOG,"addDestinationTable txnId=%d, desinationTable",txnId,destinationTable);
        Get get=dataLib.newGet(TxnUtils.getRowKey(txnId));
        byte[] destTableQualifier=AbstractV2TxnDecoder.DESTINATION_TABLE_QUALIFIER_BYTES;
        dataLib.addFamilyQualifierToGet(get,FAMILY,destTableQualifier);
        /*
		 * We only need to check the new transaction format, because we will never attempt to elevate
		 * a transaction created using the old transaction format.
		 */

        Result result=region.get(get);
        //should never happen, this is in place to protect against programmer error
        if(result==null)
            throw exceptionLib.readOnlyModification("Transaction "+txnId+" is read-only, and was not properly elevated.");
        DataCell kv=dataLib.getColumnLatest(result,FAMILY,destTableQualifier);
        byte[] newBytes;
        if(kv==null){
			/*
			 * this shouldn't happen, but you never know--someone might create a writable transaction
			 * without specifying a table directly. In that case, this will still work
			 */
            newBytes=destinationTable;
        }else{
            int valueLength=kv.valueLength();
            newBytes=new byte[valueLength+destinationTable.length+1];
            System.arraycopy(kv.valueArray(),kv.valueOffset(),newBytes,0,valueLength);
            System.arraycopy(destinationTable,0,newBytes,valueLength+1,destinationTable.length);
        }
        Put put=dataLib.newPut(dataLib.getGetRow(get));
        dataLib.addKeyValueToPut(put,FAMILY,destTableQualifier,newBytes);
        region.put(put);
    }

    @Override
    public boolean keepAlive(long txnId) throws IOException{
        if(LOG.isTraceEnabled())
            SpliceLogUtils.trace(LOG,"keepAlive txnId=%d",txnId);
        byte[] rowKey=TxnUtils.getRowKey(txnId);
        Get get=dataLib.newGet(rowKey);
        dataLib.addFamilyQualifierToGet(get,FAMILY,AbstractV2TxnDecoder.KEEP_ALIVE_QUALIFIER_BYTES);
        dataLib.addFamilyQualifierToGet(get,FAMILY,AbstractV2TxnDecoder.STATE_QUALIFIER_BYTES);
        //we don't try to keep alive transactions with an old form
        Result result=region.get(get);
        if(result==null) return false; //attempted to keep alive a read-only transaction? a waste, but whatever

        Cell stateKv=result.getColumnLatestCell(FAMILY,AbstractV2TxnDecoder.STATE_QUALIFIER_BYTES);
        if(stateKv==null){
            // couldn't find the transaction data, it's fine under Restore Mode, issue a warning nonetheless
            LOG.warn("Couldn't load data for keeping alive transaction "+txnId+". This isn't an issue under Restore Mode");
            return false;
        }
        Txn.State state=Txn.State.decode(stateKv.getValueArray(),stateKv.getValueOffset(),stateKv.getValueLength());
        if(state!=Txn.State.ACTIVE) return false; //skip the put if we don't need to do it
        Cell oldKAKV=result.getColumnLatestCell(FAMILY,AbstractV2TxnDecoder.KEEP_ALIVE_QUALIFIER_BYTES);
        long currTime=System.currentTimeMillis();
        Txn.State adjustedState=AbstractV2TxnDecoder.adjustStateForTimeout(state,oldKAKV,false);
        if(adjustedState!=Txn.State.ACTIVE)
            throw exceptionLib.transactionTimeout(txnId);

        Put newPut=dataLib.newPut(TxnUtils.getRowKey(txnId));
        dataLib.addKeyValueToPut(newPut,FAMILY,AbstractV2TxnDecoder.KEEP_ALIVE_QUALIFIER_BYTES,Encoding.encode(currTime));
        region.put(newPut); //TODO -sf- does this work when the region is splitting?
        return true;
    }

    @Override
    public Txn.State getState(long txnId) throws IOException{
        if(LOG.isTraceEnabled())
            SpliceLogUtils.trace(LOG,"getState txnId=%d",txnId);
        byte[] rowKey=TxnUtils.getRowKey(txnId);
        Get get=dataLib.newGet(rowKey);
        dataLib.addFamilyQualifierToGet(get,FAMILY,AbstractV2TxnDecoder.STATE_QUALIFIER_BYTES);
        dataLib.addFamilyQualifierToGet(get,FAMILY,AbstractV2TxnDecoder.KEEP_ALIVE_QUALIFIER_BYTES);
        //add the columns for the new encoding
        Result result=region.get(get);
        if(result==null)
            return null; //indicates that the transaction was probably read only--external callers can figure out what that means

        Cell keepAliveKv;
        Cell stateKv=result.getColumnLatestCell(FAMILY,AbstractV2TxnDecoder.STATE_QUALIFIER_BYTES);
        keepAliveKv=result.getColumnLatestCell(FAMILY,AbstractV2TxnDecoder.KEEP_ALIVE_QUALIFIER_BYTES);
        Txn.State state=Txn.State.decode(stateKv.getValueArray(),stateKv.getValueOffset(),stateKv.getValueLength());
        if(state==Txn.State.ACTIVE)
            state=AbstractV2TxnDecoder.adjustStateForTimeout(state,keepAliveKv,false);

        if(LOG.isTraceEnabled())
            SpliceLogUtils.trace(LOG,"getState returnedState state=%s",state);

        return state;
    }

    @Override
    public void recordTransaction(TxnMessage.TxnInfo txn) throws IOException{
        if(LOG.isTraceEnabled())
            SpliceLogUtils.trace(LOG,"recordTransaction txn=%s",txn);
        Put put=newTransactionDecoder.encodeForPut(txn);
        region.put(put);
    }

    @Override
    public void recordCommit(long txnId,long commitTs) throws IOException{
        if(LOG.isTraceEnabled())
            SpliceLogUtils.trace(LOG,"recordCommit txnId=%d, commitTs=%d",txnId,commitTs);
        Put put=dataLib.newPut(TxnUtils.getRowKey(txnId));
        dataLib.addKeyValueToPut(put,FAMILY,AbstractV2TxnDecoder.COMMIT_QUALIFIER_BYTES,Encoding.encode(commitTs));
        dataLib.addKeyValueToPut(put,FAMILY,AbstractV2TxnDecoder.STATE_QUALIFIER_BYTES,Txn.State.COMMITTED.encode());
        region.put(put);
    }

    @Override
    public void recordGlobalCommit(long txnId,long globalCommitTs) throws IOException{
        Put put=new Put(TxnUtils.getRowKey(txnId));
        put.addColumn(FAMILY,AbstractV2TxnDecoder.GLOBAL_COMMIT_QUALIFIER_BYTES,Encoding.encode(globalCommitTs));
//        dataLib.addKeyValueToPut(put,FAMILY,AbstractV2TxnDecoder.COMMIT_QUALIFIER_BYTES,Encoding.encode(commitTs));
        region.put(put);
    }

    @Override
    public long getCommitTimestamp(long txnId) throws IOException{
        if(LOG.isTraceEnabled())
            SpliceLogUtils.trace(LOG,"getCommitTimestamp txnId=%d",txnId);
        Get get=dataLib.newGet(TxnUtils.getRowKey(txnId));
        dataLib.addFamilyQualifierToGet(get,FAMILY,AbstractV2TxnDecoder.COMMIT_QUALIFIER_BYTES);
        Result result=region.get(get);
        if(result==null) return -1l; //no commit timestamp for read-only transactions
        DataCell kv;
        if((kv=dataLib.getColumnLatest(result,FAMILY,AbstractV2TxnDecoder.COMMIT_QUALIFIER_BYTES))!=null)
            return Encoding.decodeLong(kv.valueArray(),kv.valueOffset(),false);
        else{
            throw new IOException("V1 Decoder Required?");
        }
    }

    @Override
    public void recordRollback(long txnId) throws IOException{
        if(LOG.isTraceEnabled())
            SpliceLogUtils.trace(LOG,"recordRollback txnId=%d",txnId);
        Put put=dataLib.newPut(TxnUtils.getRowKey(txnId));
        dataLib.addKeyValueToPut(put,FAMILY,AbstractV2TxnDecoder.STATE_QUALIFIER_BYTES,Txn.State.ROLLEDBACK.encode());
        dataLib.addKeyValueToPut(put,FAMILY,AbstractV2TxnDecoder.COMMIT_QUALIFIER_BYTES,Encoding.encode(-1));
        dataLib.addKeyValueToPut(put,FAMILY,AbstractV2TxnDecoder.GLOBAL_COMMIT_QUALIFIER_BYTES,Encoding.encode(-1));
        region.put(put);
    }

    @Override
    public long[] getActiveTxnIds(long afterTs,long beforeTs,byte[] destinationTable) throws IOException{
        if(LOG.isTraceEnabled())
            SpliceLogUtils.trace(LOG,"getActiveTxnIds beforeTs=%d, afterTs=%s, destinationTable=%s",beforeTs,afterTs,destinationTable);

        Source<TxnMessage.Txn> activeTxn=getActiveTxns(afterTs,beforeTs,destinationTable);
        LongArrayList lal=LongArrayList.newInstance();
        while(activeTxn.hasNext()){
            lal.add(transactionlib.getTxnId(activeTxn.next()));
        }
        return lal.toArray();
    }

    public Source<TxnMessage.Txn> getAllTxns(long minTs,long maxTs) throws IOException{
        if(LOG.isTraceEnabled())
            SpliceLogUtils.trace(LOG,"getAllTxns minTs=%d, maxTs=%s",minTs,maxTs);
        Scan scan=setupScanOnRange(minTs,maxTs);

        RegionScanner scanner=region.getScanner(scan);

        return new ScanIterator(scanner);
    }

    @Override
    public Source<TxnMessage.Txn> getActiveTxns(long afterTs,long beforeTs, byte[] destinationTable) throws IOException{
		if (LOG.isTraceEnabled())
			SpliceLogUtils.trace(LOG, "getActiveTxns afterTs=%d, beforeTs=%s",afterTs, beforeTs);
    	Scan scan = setupScanOnRange(afterTs, beforeTs);
        dataLib.setFilterOnScan(scan, dataLib.getActiveTransactionFilter(beforeTs, afterTs, destinationTable));

        final RegionScanner scanner = region.getScanner(scan);
        return new ScanIterator(scanner){
            @Override
            public TxnMessage.Txn next() throws IOException{
                TxnMessage.Txn txn = super.next();
                /*
                 * In normal circumstances, we would say that this transaction is active
                 * (since it passed the ActiveTxnFilter).
                 *
                 * However, a child transaction may need to be returned even though
                 * he is committed, because a parent along the chain remains active. In this case,
                 * we need to resolve the effective commit timestamp of the parent, and if that value
                 * is -1, then we return it. Otherwise, just mark the child transaction with a global
                 * commit timestamp and move on.
                 */

                long parentTxnId =transactionlib.getParentTxnId(txn);
                if(parentTxnId<0){
                    //we are a top-level transaction
                    return txn;
                }

                switch(txnSupplier.getTransaction(parentTxnId).getEffectiveState()){
                    case ACTIVE:
                        return txn;
                    case ROLLEDBACK:
                        resolver.resolveTimedOut(RegionTxnStore.this,txn);
                        return null;
                    case COMMITTED:
                        resolver.resolveGlobalCommitTimestamp(RegionTxnStore.this,txn);
                        return null;
                }

                return txn;
            }
        };
    }

    /******************************************************************************************************************/
		/*private helper methods*/

    //easy reference for code clarity
    private static final byte[] FAMILY=SIConstants.DEFAULT_FAMILY_BYTES;

    private Scan setupScanOnRange(long afterTs,long beforeTs){
			  /*
			   * Get the bucket id for the region.
			   *
			   * The way the transaction table is built, a region may have an empty start
			   * OR an empty end, but will never have both
			   */
        byte[] regionKey=region.getStartKey();
        byte bucket;
        if(regionKey.length<=0)
            bucket=0;
        else
            bucket=regionKey[0];
        byte[] startKey=Bytes.concat(Arrays.asList(new byte[]{bucket},Bytes.toBytes(afterTs)));
        if(Bytes.startComparator.compare(region.getStartKey(),startKey)>0)
            startKey=region.getStartKey();
        byte[] stopKey=Bytes.concat(Arrays.asList(new byte[]{bucket},Bytes.toBytes(beforeTs+1)));
        if(Bytes.endComparator.compare(region.getEndKey(),stopKey)<0)
            stopKey=region.getEndKey();
        Scan scan=dataLib.newScan(startKey,stopKey);
        dataLib.setScanMaxVersions(scan,1);
        return scan;
    }


    private TxnMessage.Txn decode(long txnId,Result result) throws IOException{
        TxnMessage.Txn txn=newTransactionDecoder.decode(dataLib,txnId,result);
        resolveTxn(txn);
        return txn;

    }

    private TxnMessage.Txn decode(List<Cell> keyValues) throws IOException{
        TxnMessage.Txn txn=newTransactionDecoder.decode(dataLib,keyValues);
        resolveTxn(txn);
        return txn;
    }

    private void resolveTxn(TxnMessage.Txn txn){
        switch(transactionlib.getTransactionState(txn)){
            case ROLLEDBACK:
                if(transactionlib.isTimedOut(txn)){
                    resolver.resolveTimedOut(RegionTxnStore.this,txn);
                }
                break;
            case COMMITTED:
                if(transactionlib.getParentTxnId(txn)>0 && transactionlib.getGlobalCommitTimestamp(txn)<0){
                /*
                 * Just because the transaction was committed and has a parent doesn't mean that EVERY parent
                 * has been committed; still, submit this to the resolver on the off chance that it
                 * has been fully committed, so we can get away with the global commit work.
                 */
                    resolver.resolveGlobalCommitTimestamp(RegionTxnStore.this,txn);
                }
        }
    }

    private class ScanIterator implements Source<TxnMessage.Txn>{
        private final RegionScanner regionScanner;
        protected TxnMessage.Txn next;
        private List<Cell> currentResults;

        public ScanIterator(RegionScanner scanner){
            this.regionScanner = scanner;
        }

        @Override
        public boolean hasNext() throws IOException {
            if(next!=null) return true;
            if(currentResults==null)
                currentResults = new ArrayList<>(10);
            boolean shouldContinue;
            do{
                shouldContinue = regionScanner.next(currentResults);
                if(currentResults.size()<0) return false;

                this.next= decode(currentResults);
            }while(next==null && shouldContinue);

            return true;
        }

        @Override
        public TxnMessage.Txn next() throws IOException {
            if(!hasNext()) throw new NoSuchElementException();
            TxnMessage.Txn n = next;
            next = null;
            return n;
        }

        @Override public void close() throws IOException { regionScanner.close(); }
    }
}