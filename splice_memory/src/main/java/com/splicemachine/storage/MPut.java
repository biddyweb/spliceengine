package com.splicemachine.storage;

import com.splicemachine.si.constants.SIConstants;
import com.splicemachine.utils.ByteSlice;

import java.util.HashMap;
import java.util.Map;
import java.util.NavigableSet;
import java.util.TreeSet;

/**
 * @author Scott Fines
 *         Date: 12/16/15
 */
public class MPut implements DataPut{
    private NavigableSet<DataCell> data = new TreeSet<>();
    private byte[] key;
    private Map<String,byte[]> attributes = new HashMap<>();
    public MPut(ByteSlice key){
        this.key = key.getByteCopy();
    }

    public MPut(byte[] key){
        this.key = key;
    }

    @Override
    public void tombstone(long txnIdLong){
        DataCell tCell = new MCell(key,SIConstants.DEFAULT_FAMILY_BYTES,SIConstants.SNAPSHOT_ISOLATION_TOMBSTONE_COLUMN_BYTES,txnIdLong,SIConstants.EMPTY_BYTE_ARRAY,CellType.TOMBSTONE);
        data.add(tCell);
    }

    @Override
    public void antiTombstone(long txnIdLong){
        DataCell tCell = new MCell(key,SIConstants.DEFAULT_FAMILY_BYTES,
                SIConstants.SNAPSHOT_ISOLATION_TOMBSTONE_COLUMN_BYTES,txnIdLong,
                SIConstants.SNAPSHOT_ISOLATION_ANTI_TOMBSTONE_VALUE_BYTES,CellType.ANTI_TOMBSTONE);
        data.add(tCell);

    }

    @Override
    public void addCell(byte[] family,byte[] qualifier,long timestamp,byte[] value){
        CellType ct;
        if(qualifier==SIConstants.SNAPSHOT_ISOLATION_COMMIT_TIMESTAMP_COLUMN_BYTES)
            ct = CellType.COMMIT_TIMESTAMP;
        else if(qualifier==SIConstants.SNAPSHOT_ISOLATION_FK_COUNTER_COLUMN_BYTES)
            ct = CellType.FOREIGN_KEY_COUNTER;
        else if(qualifier==SIConstants.SNAPSHOT_ISOLATION_TOMBSTONE_COLUMN_BYTES)
            ct = CellType.TOMBSTONE;
        else if(qualifier==SIConstants.PACKED_COLUMN_BYTES)
            ct = CellType.USER_DATA;
        else ct =CellType.OTHER;
        DataCell tCell = new MCell(key,family, qualifier,timestamp,value,ct);
        data.add(tCell);

    }

    @Override
    public byte[] key(){
        return key;
    }

    @Override
    public Iterable<DataCell> cells(){
        return data;
    }

    @Override
    public void addCell(DataCell kv){
        data.add(kv);
    }

    @Override
    public void addAttribute(String key,byte[] value){
        this.attributes.put(key,value);
    }

    @Override
    public byte[] getAttribute(String key){
        return attributes.get(key);
    }

    @Override
    public Map<String, byte[]> allAttributes(){
        return attributes;
    }

    @Override
    public void setAllAttributes(Map<String, byte[]> attrMap){
        attributes.putAll(attrMap);
    }

}