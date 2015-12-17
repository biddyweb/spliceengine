package com.splicemachine.si.impl.server;

import com.carrotsearch.hppc.IntObjectOpenHashMap;
import com.carrotsearch.hppc.LongOpenHashSet;
import com.carrotsearch.hppc.cursors.IntObjectCursor;
import com.carrotsearch.hppc.cursors.LongCursor;
import com.google.common.base.Predicate;
import com.google.common.collect.Collections2;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.splicemachine.kvpair.KVPair;
import com.splicemachine.primitives.Bytes;
import com.splicemachine.si.api.data.*;
import com.splicemachine.si.api.filter.TxnFilter;
import com.splicemachine.si.api.readresolve.RollForward;
import com.splicemachine.si.api.server.ConstraintChecker;
import com.splicemachine.si.api.server.Transactor;
import com.splicemachine.si.api.txn.ConflictType;
import com.splicemachine.si.api.txn.Txn;
import com.splicemachine.si.api.txn.TxnSupplier;
import com.splicemachine.si.api.txn.TxnView;
import com.splicemachine.si.constants.SIConstants;
import com.splicemachine.si.impl.ConflictResults;
import com.splicemachine.si.impl.DataStore;
import com.splicemachine.si.impl.SimpleTxnFilter;
import com.splicemachine.si.impl.readresolve.NoOpReadResolver;
import com.splicemachine.si.impl.store.IgnoreTxnCacheSupplier;
import com.splicemachine.storage.*;
import com.splicemachine.utils.ByteSlice;
import com.splicemachine.utils.Pair;
import com.splicemachine.utils.SpliceLogUtils;
import org.apache.log4j.Logger;

import javax.annotation.Nullable;
import java.io.IOException;
import java.lang.reflect.Array;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.Lock;

import static com.splicemachine.si.constants.SIConstants.*;

/**
 * Central point of implementation of the "snapshot isolation" MVCC algorithm that provides transactions across atomic
 * row updates in the underlying store. This is the core brains of the SI logic.
 */
@SuppressWarnings("unchecked")
public class SITransactor<OperationWithAttributes,Data,Delete extends OperationWithAttributes,
        Get extends OperationWithAttributes,Filter,
        Mutation extends OperationWithAttributes,
        Put extends OperationWithAttributes,RegionScanner,Result,ReturnCode,RowLock,Scan extends OperationWithAttributes>
        implements Transactor<Put, RowLock>{
    private static final Logger LOG=Logger.getLogger(SITransactor.class);
    private final SDataLib<OperationWithAttributes, Data, Delete, Filter, Get, Put, RegionScanner, Result, Scan> dataLib;
    private final DataStore<OperationWithAttributes, Data, Delete, Filter, Get, Put, RegionScanner, Result, Scan> dataStore;
    private final OperationStatusFactory operationStatusLib;
    private final ExceptionFactory exceptionLib;

    private final TxnOperationFactory<OperationWithAttributes, Get, Mutation, Put, Scan> txnOperationFactory;
    private final TxnSupplier txnSupplier;
    private final IgnoreTxnCacheSupplier ignoreTxnSupplier;

    public SITransactor(TxnSupplier txnSupplier,
                        IgnoreTxnCacheSupplier ignoreTxnSupplier,
                        TxnOperationFactory<OperationWithAttributes, Get, Mutation, Put, Scan> txnOperationFactory,
                        DataStore<OperationWithAttributes, Data, Delete, Filter, Get, Put, RegionScanner, Result, Scan> dataStore,
                        OperationStatusFactory operationStatusLib,
                        ExceptionFactory exceptionFactory){
        this.txnSupplier=txnSupplier;
        this.txnOperationFactory=txnOperationFactory;
        this.dataStore = dataStore;
        this.dataLib = dataStore.getDataLib();
        this.operationStatusLib = operationStatusLib;
        this.exceptionLib = exceptionFactory;
        this.ignoreTxnSupplier = ignoreTxnSupplier;
    }

    // Operation pre-processing. These are to be called "server-side" when we are about to process an operation.

    // Process update operations

    @Override
    public boolean processPut(Partition table,RollForward rollForwardQueue,Put put) throws IOException{
        if(!isFlaggedForSITreatment(put)) return false;
        final Put[] mutations=(Put[])Array.newInstance(put.getClass(),1);
        mutations[0]=put;
        MutationStatus[] operationStatuses=processPutBatch(table,rollForwardQueue,mutations);
        return operationStatusLib.processPutStatus(operationStatuses[0]);
    }

    @Override
    public boolean processPut(Partition table,RollForward rollForwardQueue,DataPut put) throws IOException{
        if(!isFlaggedForSITreatment(put)) return false;
        final DataPut[] mutations=new DataPut[]{put};
        mutations[0]=put;
        MutationStatus[] operationStatuses=processPutBatch(table,rollForwardQueue,mutations);
        return operationStatusLib.processPutStatus(operationStatuses[0]);
    }

    @Override
    public MutationStatus[] processPutBatch(Partition table,RollForward rollForwardQueue,DataPut[] mutations) throws IOException{
        if(mutations.length==0){
            //short-circuit special case of empty batch
            //noinspection unchecked
            return new MutationStatus[0];
        }

        Map<Long, Map<byte[], Map<byte[], List<KVPair>>>> kvPairMap=SITransactorUtil.putToKvPairMap(mutations,txnOperationFactory,dataStore);
        final Map<byte[], MutationStatus> statusMap=Maps.newTreeMap(Bytes.BASE_COMPARATOR);
        for(Map.Entry<Long, Map<byte[], Map<byte[], List<KVPair>>>> entry : kvPairMap.entrySet()){
            long txnId=entry.getKey();
            Map<byte[], Map<byte[], List<KVPair>>> familyMap=entry.getValue();
            for(Map.Entry<byte[], Map<byte[], List<KVPair>>> familyEntry : familyMap.entrySet()){
                byte[] family=familyEntry.getKey();
                Map<byte[], List<KVPair>> columnMap=familyEntry.getValue();
                for(Map.Entry<byte[], List<KVPair>> columnEntry : columnMap.entrySet()){
                    byte[] qualifier=columnEntry.getKey();
                    List<KVPair> kvPairs=Lists.newArrayList(Collections2.filter(columnEntry.getValue(),new Predicate<KVPair>(){
                        @Override
                        public boolean apply(@Nullable KVPair input){
                            assert input!=null;
                            return !statusMap.containsKey(input.getRowKey()) || statusMap.get(input.getRowKey()).isSuccess();
                        }
                    }));
                    MutationStatus[] statuses=processKvBatch(table,null,family,qualifier,kvPairs,txnId,operationStatusLib.getNoOpConstraintChecker());
                    for(int i=0;i<statuses.length;i++){
                        byte[] row=kvPairs.get(i).getRowKey();
                        MutationStatus status=statuses[i];
                        if(statusMap.containsKey(row)){
                            MutationStatus oldStatus=statusMap.get(row);
                            status=getCorrectStatus(status,oldStatus);
                        }
                        statusMap.put(row,status);
                    }
                }
            }
        }
        MutationStatus[] retStatuses=new MutationStatus[mutations.length];
        for(int i=0;i<mutations.length;i++){
            DataPut put=mutations[i];
            retStatuses[i]=statusMap.get(put.key());
        }
        return retStatuses;
    }

    @Override
    public MutationStatus[] processPutBatch(Partition table,RollForward rollForwardQueue,Put[] mutations)
            throws IOException{
        if(mutations.length==0){
            //short-circuit special case of empty batch
            //noinspection unchecked
            return new MutationStatus[0];
        }
        /*
         * Here we convert a Put into a KVPair.
         *
         * Each Put represents a single row, but a KVPair represents a single column. Each row
         * is written with a single transaction.
         *
         * What we do here is we group up the puts by their Transaction id (just in case they are different),
         * then we group them up by family and column to create proper KVPair groups. Then, we attempt
         * to write all the groups in sequence.
         *
         * Note the following:
         *
         * 1) We do all this as support for things that probably don't happen. With Splice's Packed Row
         * Encoding, it is unlikely that people will send more than a single column of data over each
         * time. Additionally, people likely won't send over a batch of Puts that have more than one
         * transaction id (as that would be weird). Still, better safe than sorry.
         *
         * 2). This method is, because of all the regrouping and the partial writes and stuff,
         * Significantly slower than the equivalent KVPair method, so It is highly recommended that you
         * use the BulkWrite pipeline along with the KVPair abstraction to improve your overall throughput.
         *
         *
         * To be frank, this is only here to support legacy code without needing to rewrite everything under
         * the sun. You should almost certainly NOT use it.
         */
        Map<Long, Map<byte[], Map<byte[], List<KVPair>>>> kvPairMap=Maps.newHashMap();
        for(Put mutation : mutations){
            long txnId=txnOperationFactory.fromWrites(mutation).getTxnId();
            boolean isDelete=dataStore.getDeletePutAttribute(mutation);
            byte[] row=dataLib.getPutKey(mutation);
            Iterable<Data> dataValues=dataLib.listPut(mutation);
            boolean isSIDataOnly=true;
            for(Data data : dataValues){
                byte[] family=dataLib.getDataFamily(data);
                byte[] column=dataLib.getDataQualifier(data);
                if(!Bytes.equals(column,SIConstants.PACKED_COLUMN_BYTES)){
                    continue; //skip SI columns
                }

                isSIDataOnly=false;
                byte[] value=dataLib.getDataValue(data);
                Map<byte[], Map<byte[], List<KVPair>>> familyMap=kvPairMap.get(txnId);
                if(familyMap==null){
                    familyMap=Maps.newTreeMap(Bytes.BASE_COMPARATOR);
                    kvPairMap.put(txnId,familyMap);
                }
                Map<byte[], List<KVPair>> columnMap=familyMap.get(family);
                if(columnMap==null){
                    columnMap=Maps.newTreeMap(Bytes.BASE_COMPARATOR);
                    familyMap.put(family,columnMap);
                }
                List<KVPair> kvPairs=columnMap.get(column);
                if(kvPairs==null){
                    kvPairs=Lists.newArrayList();
                    columnMap.put(column,kvPairs);
                }
                kvPairs.add(new KVPair(row,value,isDelete?KVPair.Type.DELETE:KVPair.Type.INSERT));
            }
            if(isSIDataOnly){
                /*
                 * Someone attempted to write only SI data, which means that the values column is empty.
                 * Put a KVPair which is an empty byte[] for all the columns in the data
                 */
                byte[] family=DEFAULT_FAMILY_BYTES;
                byte[] column=PACKED_COLUMN_BYTES;
                byte[] value=new byte[]{};
                Map<byte[], Map<byte[], List<KVPair>>> familyMap=kvPairMap.get(txnId);
                if(familyMap==null){
                    familyMap=Maps.newTreeMap(Bytes.BASE_COMPARATOR);
                    kvPairMap.put(txnId,familyMap);
                }
                Map<byte[], List<KVPair>> columnMap=familyMap.get(family);
                if(columnMap==null){
                    columnMap=Maps.newTreeMap(Bytes.BASE_COMPARATOR);
                    familyMap.put(family,columnMap);
                }
                List<KVPair> kvPairs=columnMap.get(column);
                if(kvPairs==null){
                    kvPairs=Lists.newArrayList();
                    columnMap.put(column,kvPairs);
                }
                kvPairs.add(new KVPair(row,value,isDelete?KVPair.Type.DELETE:KVPair.Type.EMPTY_COLUMN));
            }
        }
        final Map<byte[], MutationStatus> statusMap=Maps.newTreeMap(Bytes.BASE_COMPARATOR);
        for(Map.Entry<Long, Map<byte[], Map<byte[], List<KVPair>>>> entry : kvPairMap.entrySet()){
            long txnId=entry.getKey();
            Map<byte[], Map<byte[], List<KVPair>>> familyMap=entry.getValue();
            for(Map.Entry<byte[], Map<byte[], List<KVPair>>> familyEntry : familyMap.entrySet()){
                byte[] family=familyEntry.getKey();
                Map<byte[], List<KVPair>> columnMap=familyEntry.getValue();
                for(Map.Entry<byte[], List<KVPair>> columnEntry : columnMap.entrySet()){
                    byte[] qualifier=columnEntry.getKey();
                    List<KVPair> kvPairs=Lists.newArrayList(Collections2.filter(columnEntry.getValue(),new Predicate<KVPair>(){
                        @Override
                        public boolean apply(@Nullable KVPair input){
                            assert input!=null;
                            return !statusMap.containsKey(input.getRowKey()) || statusMap.get(input.getRowKey()).isSuccess();
                        }
                    }));
                    MutationStatus[] statuses=processKvBatch(table,null,family,qualifier,kvPairs,txnId,operationStatusLib.getNoOpConstraintChecker());
                    for(int i=0;i<statuses.length;i++){
                        byte[] row=kvPairs.get(i).getRowKey();
                        MutationStatus status=statuses[i];
                        if(statusMap.containsKey(row)){
                            MutationStatus oldStatus=statusMap.get(row);
                            status=getCorrectStatus(status,oldStatus);
                        }
                        statusMap.put(row,status);
                    }
                }
            }
        }
        MutationStatus[] retStatuses=new MutationStatus[mutations.length];
        for(int i=0;i<mutations.length;i++){
            Put put=mutations[i];
            retStatuses[i]=statusMap.get(dataLib.getPutKey(put));
        }
        return retStatuses;
    }

    @Override
    public MutationStatus[] processKvBatch(Partition table,
                                            RollForward rollForward,
                                            byte[] defaultFamilyBytes,
                                            byte[] packedColumnBytes,
                                            Collection<KVPair> toProcess,
                                            long txnId,
                                            ConstraintChecker constraintChecker) throws IOException{
        TxnView txn=txnSupplier.getTransaction(txnId);
        ensureTransactionAllowsWrites(txnId,txn);
        return processInternal(table,rollForward,txn,defaultFamilyBytes,packedColumnBytes,toProcess,constraintChecker);
    }

    @Override
    public MutationStatus[] processKvBatch(Partition table,
                                            RollForward rollForwardQueue,
                                            TxnView txn,
                                            byte[] family,byte[] qualifier,
                                            Collection<KVPair> mutations,
                                            ConstraintChecker constraintChecker) throws IOException{
        ensureTransactionAllowsWrites(txn.getTxnId(),txn);
        return processInternal(table,rollForwardQueue,txn,family,qualifier,mutations,constraintChecker);
    }


    private MutationStatus getCorrectStatus(MutationStatus status,MutationStatus oldStatus){
        return operationStatusLib.getCorrectStatus(status,oldStatus);
    }

    protected MutationStatus[] processInternal(Partition<OperationWithAttributes, Delete, Get, Put, Result, Scan> table,
                                                RollForward rollForwardQueue,
                                                TxnView txn,
                                                byte[] family,byte[] qualifier,
                                                Collection<KVPair> mutations,
                                                ConstraintChecker constraintChecker) throws IOException{
//                if (LOG.isTraceEnabled()) LOG.trace(String.format("processInternal: table = %s, txnId = %s", table.toString(), txn.getTxnId()));
        MutationStatus[] finalStatus=new MutationStatus[mutations.size()];
        Pair<KVPair, Lock>[] lockPairs=new Pair[mutations.size()];
        TxnFilter constraintState=null;
        if(constraintChecker!=null)
            constraintState=new SimpleTxnFilter(null,txn,NoOpReadResolver.INSTANCE,txnSupplier,ignoreTxnSupplier,dataStore);
        @SuppressWarnings("unchecked") final LongOpenHashSet[] conflictingChildren=new LongOpenHashSet[mutations.size()];
        try{
            lockRows(table,mutations,lockPairs,finalStatus);

            /*
             * You don't need a low-level operation check here, because this code can only be called from
             * 1 of 2 paths (bulk write pipeline and SIObserver). Since both of those will externally ensure that
             * the region can't close until after this method is complete, we don't need the calls.
             */
            IntObjectOpenHashMap<DataPut> writes=checkConflictsForKvBatch(table,rollForwardQueue,lockPairs,
                    conflictingChildren,txn,family,qualifier,constraintChecker,constraintState,finalStatus);

            //TODO -sf- this can probably be made more efficient
            //convert into array for usefulness
            DataPut[] toWrite=new DataPut[writes.size()];
            int i=0;
            for(IntObjectCursor<DataPut> write : writes){
                toWrite[i]=write.value;
                i++;
            }
            Iterator<MutationStatus> status=table.writeBatch(toWrite);

            //convert the status back into the larger array
            i=0;
            for(IntObjectCursor<DataPut> write : writes){
                if(!status.hasNext())
                    throw new IllegalStateException("Programmer Error: incorrect length for returned status");
                finalStatus[write.key]=status.next().getClone(); //TODO -sf- is clone needed here?
                //resolve child conflicts
                try{
                    resolveChildConflicts(table,write.value,conflictingChildren[i]);
                }catch(Exception e){
                    finalStatus[i] = operationStatusLib.failure(e);
                }
                i++;
            }
            return finalStatus;
        }finally{
            releaseLocksForKvBatch(lockPairs);
        }
    }

    @SuppressWarnings("UnusedDeclaration")
    private void checkPermission(Partition table,Mutation[] mutations) throws IOException{
        final String tableName=dataStore.getTableName(table);
        throw new UnsupportedOperationException("IMPLEMENT");
    }

    private void releaseLocksForKvBatch(Pair<KVPair, Lock>[] locks){
        if(locks==null) return;
        for(Pair<KVPair, Lock> lock : locks){
            if(lock==null || lock.getSecond()==null) continue;
            lock.getSecond().unlock();
        }
    }

    private IntObjectOpenHashMap<DataPut> checkConflictsForKvBatch(Partition table,
                                                                   RollForward rollForwardQueue,
                                                                   Pair<KVPair, Lock>[] dataAndLocks,
                                                                   LongOpenHashSet[] conflictingChildren,
                                                                   TxnView transaction,
                                                                   byte[] family,byte[] qualifier,
                                                                   ConstraintChecker constraintChecker,
                                                                   TxnFilter constraintStateFilter,
                                                                   MutationStatus[] finalStatus) throws IOException{
        IntObjectOpenHashMap<DataPut> finalMutationsToWrite=IntObjectOpenHashMap.newInstance(dataAndLocks.length,0.9f);
        DataResult possibleConflicts=null;
        for(int i=0;i<dataAndLocks.length;i++){
            Pair<KVPair, Lock> baseDataAndLock=dataAndLocks[i];
            if(baseDataAndLock==null) continue;

            ConflictResults conflictResults=ConflictResults.NO_CONFLICT;
            KVPair kvPair=baseDataAndLock.getFirst();
            KVPair.Type writeType=kvPair.getType();
            if(constraintChecker!=null || !KVPair.Type.INSERT.equals(writeType)){
                /*
                 *
                 * If the table has no keys, then the hbase row key is a randomly generated UUID, so it's not
                 * going to incur a write/write penalty, because there isn't any other row there (as long as we are inserting).
                 * Therefore, we do not need to perform a write/write conflict check or a constraint check
                 *
                 * We know that this is the case because there is no constraint checker (constraint checkers are only
                 * applied on key elements.
                 */
                //todo -sf remove the Row key copy here
                possibleConflicts=table.getLatest(kvPair.getRowKey(),possibleConflicts);
                if(possibleConflicts!=null){
                    //we need to check for write conflicts
                    conflictResults=ensureNoWriteConflict(transaction,writeType,possibleConflicts);
                }
                if(applyConstraint(constraintChecker,constraintStateFilter,i,kvPair,possibleConflicts,finalStatus,conflictResults.hasAdditiveConflicts())){
                    //filter this row out, it fails the constraint
                    continue;
                }
                //TODO -sf- if type is an UPSERT, and conflict type is ADDITIVE_CONFLICT, then we
                //set the status on the row to ADDITIVE_CONFLICT_DURING_UPSERT
                if(KVPair.Type.UPSERT.equals(writeType)){
                    /*
                     * If the type is an upsert, then we want to check for an ADDITIVE conflict. If so,
                     * we fail this row with an ADDITIVE_UPSERT_CONFLICT.
                     */
                    if(conflictResults.hasAdditiveConflicts()){
                        finalStatus[i]=operationStatusLib.failure(exceptionLib.additiveWriteConflict());
                    }
                }
            }

            conflictingChildren[i]=conflictResults.getChildConflicts();
            DataPut mutationToRun=getMutationToRun(table,rollForwardQueue,kvPair,
                    family,qualifier,transaction,conflictResults);
            finalMutationsToWrite.put(i,mutationToRun);
        }
        return finalMutationsToWrite;
    }

    private boolean applyConstraint(ConstraintChecker constraintChecker,
                                    TxnFilter<Data, ReturnCode> constraintStateFilter,
                                    int rowPosition,
                                    KVPair mutation,
                                    DataResult row,
                                    MutationStatus[] finalStatus,
                                    boolean additiveConflict) throws IOException{
        /*
         * Attempts to apply the constraint (if there is any). When this method returns true, the row should be filtered
         * out.
         */
        if(constraintChecker==null) return false;

        if(row==null || row.size()<=0) return false;

        //we need to make sure that this row is visible to the current transaction
        List<DataCell> visibleColumns=Lists.newArrayListWithExpectedSize(row.size());
        for(DataCell data : row){
            DataFilter.ReturnCode code=constraintStateFilter.filterKeyValue(data);
            switch(code){
                case NEXT_ROW:
                case NEXT_COL:
                case SEEK:
                    return false;
                case SKIP:
                    continue;
                default:
                    visibleColumns.add(data.getClone()); //TODO -sf- remove this clone
            }
        }
        constraintStateFilter.nextRow();
        if(!additiveConflict && visibleColumns.size()<=0) return false; //no visible values to check

        MutationStatus operationStatus=constraintChecker.checkConstraint(mutation,dataLib.newResult(visibleColumns));
        if(operationStatus!=null && !operationStatus.isSuccess()){
            finalStatus[rowPosition]=operationStatus;
            return true;
        }
        return false;
    }


    private void lockRows(Partition table,Collection<KVPair> mutations,Pair<KVPair, Lock>[] mutationsAndLocks,MutationStatus[] finalStatus) throws IOException{
        /*
         * We attempt to lock each row in the collection.
         *
         * If the lock is acquired, we place it into mutationsAndLocks (at the position equal
         * to the position in the collection's iterator).
         *
         * If the lock cannot be acquired, then we set NOT_RUN into the finalStatus array. Those rows will be filtered
         * out and must be retried by the writer. mutationsAndLocks at the same location will be null
         */
        int position=0;
        for(KVPair mutation : mutations){
            ByteSlice byteSlice=mutation.rowKeySlice();
            Lock lock=table.getRowLock(byteSlice.array(),byteSlice.offset(),byteSlice.length());//tableWriter.getRowLock(table, mutation.rowKeySlice());
            if(lock.tryLock())
                mutationsAndLocks[position]=Pair.newPair(mutation,lock);
            else
                finalStatus[position]=operationStatusLib.notRun();

            position++;
        }
    }


    private DataPut getMutationToRun(Partition table,RollForward rollForwardQueue,KVPair kvPair,
                                     byte[] family,byte[] column,
                                     TxnView transaction,ConflictResults conflictResults) throws IOException{
        long txnIdLong=transaction.getTxnId();
//                if (LOG.isTraceEnabled()) LOG.trace(String.format("table = %s, kvPair = %s, txnId = %s", table.toString(), kvPair.toString(), txnIdLong));
        DataPut newPut;
        if(kvPair.getType()==KVPair.Type.EMPTY_COLUMN){
            /*
             * WARNING: This requires a read of column data to populate! Try not to use
             * it unless no other option presents itself.
             *
             * In point of fact, this only occurs if someone sends over a non-delete Put
             * which has only SI data. In the event that we send over a row with all nulls
             * from actual Splice system, we end up with a KVPair that has a non-empty byte[]
             * for the values column (but which is nulls everywhere)
             */
            newPut=dataLib.newDataPut(kvPair.rowKeySlice());
            dataStore.setTombstonesOnColumns(table,txnIdLong,newPut);
        }else if(kvPair.getType()==KVPair.Type.DELETE){
            newPut=dataLib.newDataPut(kvPair.rowKeySlice());
            newPut.tombstone(txnIdLong);
        }else
            newPut=dataLib.toDataPut(kvPair,family,column,txnIdLong);

        newPut.addAttribute(SIConstants.SUPPRESS_INDEXING_ATTRIBUTE_NAME,SIConstants.SUPPRESS_INDEXING_ATTRIBUTE_VALUE);
        if(kvPair.getType()!=KVPair.Type.DELETE && conflictResults.hasTombstone())
            newPut.antiTombstone(txnIdLong);

        if(rollForwardQueue!=null)
            rollForwardQueue.submitForResolution(kvPair.rowKeySlice(),txnIdLong);
        return newPut;
    }

    private void resolveChildConflicts(Partition table,DataPut put,LongOpenHashSet conflictingChildren) throws IOException{
        if(conflictingChildren!=null && !conflictingChildren.isEmpty()){
            DataDelete delete=dataLib.newDataDelete(put.key());
            Iterable<DataCell> cells=put.cells();
            for(LongCursor lc : conflictingChildren){
                for(DataCell dc : cells){
                    delete.deleteColumn(dc.family(),dc.qualifier(),lc.value);
                }
                delete.deleteColumn(SIConstants.DEFAULT_FAMILY_BYTES,SIConstants.SNAPSHOT_ISOLATION_TOMBSTONE_COLUMN_BYTES,lc.value);
                delete.deleteColumn(SIConstants.DEFAULT_FAMILY_BYTES,SIConstants.SNAPSHOT_ISOLATION_COMMIT_TIMESTAMP_COLUMN_BYTES,lc.value);
            }
            delete.addAttribute(SIConstants.SUPPRESS_INDEXING_ATTRIBUTE_NAME,SIConstants.SUPPRESS_INDEXING_ATTRIBUTE_VALUE);
            table.delete(delete);
        }
    }

    /**
     * While we hold the lock on the row, check to make sure that no transactions have updated the row since the
     * updating transaction started.
     */
    private ConflictResults ensureNoWriteConflict(TxnView updateTransaction,KVPair.Type updateType,DataResult row) throws IOException{

        DataCell commitTsKeyValue=row.commitTimestamp();//dataLib.getColumnLatest(result, DEFAULT_FAMILY_BYTES, SNAPSHOT_ISOLATION_COMMIT_TIMESTAMP_COLUMN_BYTES);
//        Data tombstoneKeyValue = dataLib.getColumnLatest(result, DEFAULT_FAMILY_BYTES, SNAPSHOT_ISOLATION_TOMBSTONE_COLUMN_BYTES);
//        Data userDataKeyValue = dataLib.getColumnLatest(result, DEFAULT_FAMILY_BYTES, PACKED_COLUMN_BYTES);

        ConflictResults conflictResults=null;
        if(commitTsKeyValue!=null){
            conflictResults=checkCommitTimestampForConflict(updateTransaction,null,commitTsKeyValue);
        }
        DataCell tombstoneKeyValue=row.tombstone();
        if(tombstoneKeyValue!=null){
            long dataTransactionId=tombstoneKeyValue.version();
            conflictResults=checkDataForConflict(updateTransaction,conflictResults,tombstoneKeyValue,dataTransactionId);
            conflictResults=(conflictResults==null)?new ConflictResults():conflictResults;
            conflictResults.setHasTombstone(hasCurrentTransactionTombstone(updateTransaction,tombstoneKeyValue));
        }
        DataCell userDataKeyValue=row.userData();
        if(userDataKeyValue!=null){
            long dataTransactionId=userDataKeyValue.version();
            conflictResults=checkDataForConflict(updateTransaction,conflictResults,userDataKeyValue,dataTransactionId);
        }
        // FK counter -- can only conflict with DELETE
        if(updateType==KVPair.Type.DELETE){
            DataCell fkCounterKeyValue=row.fkCounter();
            if(fkCounterKeyValue!=null){
                long dataTransactionId=fkCounterKeyValue.valueAsLong();
                conflictResults=checkDataForConflict(updateTransaction,conflictResults,fkCounterKeyValue,dataTransactionId);
            }
        }

        return conflictResults==null?ConflictResults.NO_CONFLICT:conflictResults;
    }

    private boolean hasCurrentTransactionTombstone(TxnView updateTxn,DataCell tombstoneCell) throws IOException{
        if(tombstoneCell==null) return false; //no tombstone at all
        if(tombstoneCell.dataType()==CellType.ANTI_TOMBSTONE) return false;
        TxnView tombstoneTxn=txnSupplier.getTransaction(tombstoneCell.version());
        return updateTxn.conflicts(tombstoneTxn)==ConflictType.NONE;
    }

    @SuppressWarnings("StatementWithEmptyBody")
    private ConflictResults checkCommitTimestampForConflict(TxnView updateTransaction,
                                                            ConflictResults conflictResults,
                                                            DataCell commitCell) throws IOException{
//        final long dataTransactionId = dataLib.getTimestamp(dataCommitKeyValue);
        long txnId=commitCell.version();
        if(commitCell.valueLength()>0){
            long globalCommitTs=commitCell.valueAsLong();
            if(globalCommitTs<0){
                // Unknown transaction status
                final TxnView dataTransaction=txnSupplier.getTransaction(txnId);
                if(dataTransaction.getState()==Txn.State.ROLLEDBACK)
                    return conflictResults; //can't conflict with a rolled back transaction
                final ConflictType conflictType=updateTransaction.conflicts(dataTransaction);
                switch(conflictType){
                    case CHILD:
                        if(conflictResults==null)
                            conflictResults=new ConflictResults();
                        conflictResults.addChild(txnId);
                        break;
                    case ADDITIVE:
                        if(conflictResults==null)
                            conflictResults=new ConflictResults();
                        conflictResults.addAdditive(txnId);
                        break;
                    case SIBLING:
                        if(LOG.isTraceEnabled()){
                            SpliceLogUtils.trace(LOG,"Write conflict on row "
                                    +Bytes.toHex(commitCell.keyArray(),commitCell.keyOffset(),commitCell.keyLength()));
                        }

                        throw exceptionLib.writeWriteConflict(txnId,updateTransaction.getTxnId());
                }
            }else{
                // Committed transaction
                if(globalCommitTs>updateTransaction.getBeginTimestamp()){
                    if(LOG.isTraceEnabled()){
                        SpliceLogUtils.trace(LOG,"Write conflict on row "
                                +Bytes.toHex(commitCell.keyArray(),commitCell.keyOffset(),commitCell.keyLength()));
                    }
                    throw exceptionLib.writeWriteConflict(txnId,updateTransaction.getTxnId());
                }
            }
        }
        return conflictResults;
    }

    private ConflictResults checkDataForConflict(TxnView updateTransaction,
                                                 ConflictResults conflictResults,
                                                 DataCell cell,
                                                 long dataTransactionId) throws IOException{
        if(updateTransaction.getTxnId()!=dataTransactionId){
            final TxnView dataTransaction=txnSupplier.getTransaction(dataTransactionId);
            if(dataTransaction.getState()==Txn.State.ROLLEDBACK){
                return conflictResults; //can't conflict with a rolled back transaction
            }
            final ConflictType conflictType=updateTransaction.conflicts(dataTransaction);
            switch(conflictType){
                case CHILD:
                    if(conflictResults==null){
                        conflictResults=new ConflictResults();
                    }
                    conflictResults.addChild(dataTransactionId);
                    break;
                case ADDITIVE:
                    if(conflictResults==null){
                        conflictResults=new ConflictResults();
                    }
                    conflictResults.addAdditive(dataTransactionId);
                    break;
                case SIBLING:
                    if(LOG.isTraceEnabled()){
                        SpliceLogUtils.trace(LOG,"Write conflict on row "
                                +Bytes.toHex(cell.keyArray(),cell.keyOffset(),cell.keyLength()));
                    }
                    throw exceptionLib.writeWriteConflict(dataTransactionId,updateTransaction.getTxnId());
            }
        }
        return conflictResults;
    }

    // Helpers

    /**
     * Is this operation supposed to be handled by "snapshot isolation".
     */
    private boolean isFlaggedForSITreatment(OperationWithAttributes operation){
        return dataStore.getSINeededAttribute(operation)!=null;
    }

    private boolean isFlaggedForSITreatment(Attributable operation){
        return dataStore.getSINeededAttribute(operation)!=null;
    }

    private void ensureTransactionAllowsWrites(long txnId,TxnView transaction) throws IOException{
        if(transaction==null || !transaction.allowsWrites()){
            throw exceptionLib.readOnlyModification("transaction is read only: "+txnId);
        }
    }

    @Override
    public DataStore getDataStore(){
        return dataStore;
    }

    @Override
    public SDataLib getDataLib(){
        return dataLib;
    }

    @Override
    public void updateCounterColumn(Partition hbRegion,TxnView txnView,RowLock rowLock,byte[] rowKey) throws IOException{
        // Get the current counter value.
//        Get get = dataLib.newGet(rowKey);
//        dataLib.addFamilyQualifierToGet(get,DEFAULT_FAMILY_BYTES, SNAPSHOT_ISOLATION_FK_COUNTER_COLUMN_BYTES);
        DataResult result=hbRegion.getFkCounter(rowKey,null);
        long counterTransactionId=result==null?0L:result.fkCounter().valueAsLong();
        // Update counter value if the calling transaction started after counter value.
        if(txnView.getTxnId()>counterTransactionId){
            long offset=txnView.getTxnId()-counterTransactionId;
            hbRegion.increment(rowKey,DEFAULT_FAMILY_BYTES,SNAPSHOT_ISOLATION_FK_COUNTER_COLUMN_BYTES,offset);
        }
        // Throw WriteConflict exception if the target row has already been deleted concurrently.
        DataResult possibleConflicts=hbRegion.getLatest(rowKey,result);//dataStore.getCommitTimestampsAndTombstonesSingle(hbRegion, rowKey);
        ensureNoWriteConflict(txnView,KVPair.Type.FOREIGN_KEY_PARENT_EXISTENCE_CHECK,possibleConflicts);
    }
}