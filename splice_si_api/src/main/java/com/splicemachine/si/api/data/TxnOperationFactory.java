package com.splicemachine.si.api.data;

import com.splicemachine.si.api.txn.TxnView;
import com.splicemachine.storage.*;

import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;

/**
 * A Factory for creating transactionally aware operations (e.g. Puts, Scans, Deletes, Gets, etc.)
 *
 * @author Scott Fines
 *         Date: 7/8/14
 */
public interface TxnOperationFactory<OperationWithAttributes,
        Get extends OperationWithAttributes,
        Mutation extends OperationWithAttributes,
        Put extends OperationWithAttributes,
        Scan extends OperationWithAttributes>{

    Put newPut(TxnView txn,byte[] rowKey) throws IOException;

    Scan newScan(TxnView txn);

    Scan newScan(TxnView txn,boolean isCountStar);

    DataScan newDataScan(TxnView txn);

    Get newGet(TxnView txn,byte[] rowKey);

    DataGet newDataGet(TxnView txn,byte[] rowKey,DataGet previous);

    Mutation newDelete(TxnView txn,byte[] rowKey) throws IOException;

    TxnView fromReads(OperationWithAttributes op) throws IOException;

    TxnView fromWrites(OperationWithAttributes op) throws IOException;

    TxnView fromReads(Attributable op) throws IOException;

    TxnView fromWrites(Attributable op) throws IOException;

    void writeTxn(TxnView txn,ObjectOutput oo) throws IOException;

    TxnView readTxn(ObjectInput oi) throws IOException;

    byte[] encode(TxnView txn);

    TxnView decode(byte[] data,int offset,int length);

    DataPut newDataPut(TxnView txn,byte[] key);

    DataDelete newDataDelete(TxnView txn,byte[] key);
}