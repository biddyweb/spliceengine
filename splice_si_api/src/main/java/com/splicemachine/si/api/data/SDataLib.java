package com.splicemachine.si.api.data;

import com.splicemachine.kvpair.KVPair;
import com.splicemachine.si.api.server.ConstraintChecker;
import com.splicemachine.storage.*;
import com.splicemachine.utils.ByteSlice;

import java.io.IOException;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Defines an abstraction over the construction and manipulate of HBase operations. Having this abstraction allows an
 * alternate lightweight store to be used instead of HBase (e.g. for rapid testing).
 */
public interface SDataLib<OperationWithAttributes,
        Data,
        Delete extends OperationWithAttributes,
        Filter,
        Get extends OperationWithAttributes,
        Put extends OperationWithAttributes,
        RegionScanner,
        Result,
        Scan extends OperationWithAttributes>{
    byte[] newRowKey(Object[] args);

    byte[] encode(Object value);

    <T> T decode(byte[] value,Class<T> type);

    <T> T decode(byte[] value,int offset,int length,Class<T> type);

    List<DataCell> listResult(Result result);

    Put newPut(byte[] key);

    Put newPut(ByteSlice key);

    DataPut newDataPut(ByteSlice key);

    DataPut newDataPut(byte[] key);

    Put newPut(byte[] key,Integer lock);

    void addKeyValueToPut(Put put,byte[] family,byte[] qualifier,long timestamp,byte[] value);

    void addKeyValueToPut(Put put,byte[] family,byte[] qualifier,byte[] value);

    Iterable<Data> listPut(Put put);

    byte[] getPutKey(Put put);

    Get newGet(byte[] key);

    Get newGet(byte[] rowKey,List<byte[]> families,List<List<byte[]>> columns,Long effectiveTimestamp);

    Get newGet(byte[] rowKey,List<byte[]> families,List<List<byte[]>> columns,Long effectiveTimestamp,int maxVersions);

    byte[] getGetRow(Get get);

    void setGetTimeRange(Get get,long minTimestamp,long maxTimestamp);

    void setGetMaxVersions(Get get);

    void setGetMaxVersions(Get get,int max);

    void addFamilyToGet(Get read,byte[] family);

    void addFamilyQualifierToGet(Get read,byte[] family,byte[] column);

    void addFamilyToGetIfNeeded(Get get,byte[] family);

    Scan newScan();

    Scan newScan(byte[] startRowKey,byte[] endRowKey,List<byte[]> families,List<List<byte[]>> columns,Long effectiveTimestamp);

    Scan newScan(byte[] startRowKey,byte[] endRowKey);

    DataScan newDataScan();

    void setScanTimeRange(Scan get,long minTimestamp,long maxTimestamp);

    void setScanMaxVersions(Scan get);

    void setScanMaxVersions(Scan get,int maxVersions);

    void addFamilyToScan(Scan read,byte[] family);

    void addFamilyToScanIfNeeded(Scan get,byte[] family);

    Delete newDelete(byte[] rowKey);

    void addFamilyQualifierToDelete(Delete delete,byte[] family,byte[] qualifier,long timestamp);

    void addDataToDelete(Delete delete,Data data,long timestamp);

    KVPair toKVPair(Put put);

    Put toPut(KVPair kvPair,byte[] family,byte[] column,long longTransactionId);

    DataPut toDataPut(KVPair kvPair,byte[] family,byte[] column,long timestamp);

    void setWriteToWAL(Put put,boolean writeToWAL);

    boolean singleMatchingColumn(Data element,byte[] family,byte[] qualifier);

    boolean singleMatchingFamily(Data element,byte[] family);

    boolean singleMatchingQualifier(Data element,byte[] qualifier);

    boolean matchingQualifier(Data element,byte[] qualifier);

    boolean matchingValue(Data element,byte[] value);

    boolean matchingFamilyKeyValue(Data element,Data other);

    boolean matchingQualifierKeyValue(Data element,Data other);

    boolean matchingRowKeyValue(Data element,Data other);

    Data newValue(Data element,byte[] value);

    Data newValue(byte[] rowKey,byte[] family,byte[] qualifier,Long timestamp,byte[] value);

    boolean isAntiTombstone(Data element,byte[] antiTombstone);

    Comparator getComparator();

    long getTimestamp(Data element);

    String getFamilyAsString(Data element);

    String getQualifierAsString(Data element);

    void setRowInSlice(Data element,ByteSlice slice);

    boolean isFailedCommitTimestamp(Data element);

    Data newTransactionTimeStampKeyValue(Data element,byte[] value);

    long getValueLength(Data element);

    long getValueToLong(Data element);

    byte[] getDataFamily(Data element);

    byte[] getDataQualifier(Data element);

    byte[] getDataValue(Data element);

    byte[] getDataRow(Data element);

    byte[] getDataValueBuffer(Data element);

    byte[] getDataRowBuffer(Data element);

    byte[] getDataQualifierBuffer(Data element);

    int getDataQualifierOffset(Data element);

    int getDataRowOffset(Data element);

    int getDataRowlength(Data element);

    int getDataValueOffset(Data element);

    int getDataValuelength(Data element);

    int getLength(Data element);

    Data[] getDataFromResult(Result result);

    DataCell getColumnLatest(Result result,byte[] family,byte[] qualifier);

    boolean regionScannerNext(RegionScanner regionScanner,List<Data> data) throws IOException;

    void setThreadReadPoint(RegionScanner delegate);

    boolean regionScannerNextRaw(RegionScanner regionScanner,List<Data> data) throws IOException;

    Filter getActiveTransactionFilter(long beforeTs,long afterTs,byte[] destinationTable);

    Map<byte[], byte[]> getFamilyMap(Result result,byte[] family);

    void setAttribute(OperationWithAttributes operation,String name,byte[] value);

    byte[] getAttribute(OperationWithAttributes operation,String attributeName);

    boolean noResult(Result result); //result==null || result.size()<=0

    void setFilterOnScan(Scan scan,Filter filter);

    int getResultSize(Result result);

    boolean isResultEmpty(Result result);

    Data matchKeyValue(Iterable<Data> kvs,byte[] columnFamily,byte[] qualifier);

    Data matchKeyValue(Data[] kvs,byte[] columnFamily,byte[] qualifier);

    Data matchDataColumn(Data[] kvs);

    Data matchDataColumn(List<Data> kvs);

    Data matchDataColumn(Result result);

    DataResult newResult(List<DataCell> visibleColumns);

    DataDelete newDataDelete(byte[] key);

}