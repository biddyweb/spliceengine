package com.splicemachine.derby.impl.sql.execute.operations;


import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collections;
import java.util.List;

import com.google.common.base.Strings;
import com.splicemachine.derby.utils.marshall.*;
import com.splicemachine.encoding.MultiFieldDecoder;
import org.apache.derby.catalog.types.ReferencedColumnsDescriptorImpl;
import org.apache.derby.iapi.error.StandardException;
import org.apache.derby.iapi.services.io.FormatableBitSet;
import org.apache.derby.iapi.services.loader.GeneratedMethod;
import org.apache.derby.iapi.sql.Activation;
import org.apache.derby.iapi.sql.execute.CursorResultSet;
import org.apache.derby.iapi.sql.execute.ExecRow;
import org.apache.derby.iapi.sql.execute.NoPutResultSet;
import org.apache.derby.iapi.store.access.DynamicCompiledOpenConglomInfo;
import org.apache.derby.iapi.store.access.StaticCompiledOpenConglomInfo;
import org.apache.derby.iapi.store.access.TransactionController;
import org.apache.derby.iapi.types.DataValueDescriptor;
import org.apache.derby.iapi.types.RowLocation;
import org.apache.derby.impl.sql.GenericPreparedStatement;
import org.apache.derby.impl.sql.GenericStorablePreparedStatement;
import org.apache.hadoop.hbase.KeyValue;
import org.apache.hadoop.hbase.client.Get;
import org.apache.hadoop.hbase.client.HTableInterface;
import org.apache.hadoop.hbase.client.Result;
import org.apache.log4j.Logger;

import com.splicemachine.derby.iapi.sql.execute.SpliceNoPutResultSet;
import com.splicemachine.derby.iapi.sql.execute.SpliceOperation;
import com.splicemachine.derby.iapi.sql.execute.SpliceOperationContext;
import com.splicemachine.derby.iapi.storage.RowProvider;
import com.splicemachine.derby.impl.store.access.SpliceAccessManager;
import com.splicemachine.derby.utils.SpliceUtils;
import com.splicemachine.utils.SpliceLogUtils;

/**
 * Maps between an Index Table and a data Table.
 */
public class IndexRowToBaseRowOperation extends SpliceBaseOperation implements CursorResultSet{

	private static Logger LOG = Logger.getLogger(IndexRowToBaseRowOperation.class);
	protected int lockMode;
	protected int isolationLevel;
//	protected ExecRow candidate;
	protected FormatableBitSet accessedCols;
	protected String resultRowAllocatorMethodName;
	protected StaticCompiledOpenConglomInfo scoci;
	protected DynamicCompiledOpenConglomInfo dcoci;
	protected SpliceOperation source;
	protected String indexName;
	protected boolean forUpdate;
	protected GeneratedMethod restriction;
	protected String restrictionMethodName;
	protected FormatableBitSet accessedHeapCols;
	protected FormatableBitSet heapOnlyCols;
	protected FormatableBitSet accessedAllCols;
	protected int[] indexCols;
	protected ExecRow resultRow;
	protected DataValueDescriptor[]	rowArray;
	protected int scociItem;
	protected long conglomId;
	protected int heapColRefItem;
	protected int allColRefItem;
	protected int heapOnlyColRefItem;
	protected int indexColMapItem;
	private ExecRow compactRow;
	RowLocation baseRowLocation = null;
//	FormatableBitSet accessFromTableCols;
	boolean copiedFromSource = false;

	/*
 	 * Variable here to stash pre-generated DataValue definitions for use in
 	 * getExecRowDefinition(). Save a little bit of performance by caching it
 	 * once created.
 	 */
//	private ExecRow definition;
	private HTableInterface table;
    private MultiFieldDecoder fieldDecoder;
    private FormatableBitSet heapCols; //used during the fetch to access the correct heap columns --0-indexed
    private int[] adjustedBaseColumnMap;


    public IndexRowToBaseRowOperation () {
		super();
	}

	public IndexRowToBaseRowOperation(long conglomId, int scociItem,
			Activation activation, NoPutResultSet source,
			GeneratedMethod resultRowAllocator, int resultSetNumber,
			String indexName, int heapColRefItem, int allColRefItem,
			int heapOnlyColRefItem, int indexColMapItem,
			GeneratedMethod restriction, boolean forUpdate,
			double optimizerEstimatedRowCount, double optimizerEstimatedCost) throws StandardException {
		super(activation, resultSetNumber, optimizerEstimatedRowCount, optimizerEstimatedCost);
		SpliceLogUtils.trace(LOG,"instantiate with parameters");
		this.resultRowAllocatorMethodName = resultRowAllocator.getMethodName();
		this.source = (SpliceOperation) source;
		this.indexName = indexName;
		this.forUpdate = forUpdate;
		this.scociItem = scociItem;
		this.conglomId = conglomId;
		this.heapColRefItem = heapColRefItem;
		this.allColRefItem = allColRefItem;
		this.heapOnlyColRefItem = heapOnlyColRefItem;
		this.indexColMapItem = indexColMapItem;
		this.restrictionMethodName = restriction==null? null: restriction.getMethodName();
		init(SpliceOperationContext.newContext(activation));
		recordConstructorTime(); 
	}

	@Override
	public void readExternal(ObjectInput in) throws IOException, ClassNotFoundException {
//		SpliceLogUtils.trace(LOG,"readExternal");
		super.readExternal(in);
		scociItem = in.readInt();
		conglomId = in.readLong();
		heapColRefItem = in.readInt();
		allColRefItem = in.readInt();
		heapOnlyColRefItem = in.readInt();
		indexColMapItem = in.readInt();
		source = (SpliceOperation) in.readObject();
		accessedCols = (FormatableBitSet) in.readObject();
		resultRowAllocatorMethodName = in.readUTF();
		indexName = in.readUTF();
		restrictionMethodName = readNullableString(in);
	}

	@Override
	public void writeExternal(ObjectOutput out) throws IOException {
//		SpliceLogUtils.trace(LOG,"writeExternal");
		super.writeExternal(out);
		out.writeInt(scociItem);
		out.writeLong(conglomId);
		out.writeInt(heapColRefItem);
		out.writeInt(allColRefItem);
		out.writeInt(heapOnlyColRefItem);
		out.writeInt(indexColMapItem);
		out.writeObject(source);
		out.writeObject(accessedCols);
		out.writeUTF(resultRowAllocatorMethodName);
		out.writeUTF(indexName);
		writeNullableString(restrictionMethodName, out);
	}

	@Override
	public void init(SpliceOperationContext context) throws StandardException{
//		SpliceLogUtils.trace(LOG,"init called");
		super.init(context);
		source.init(context);
		try {
			GenericStorablePreparedStatement statement = context.getPreparedStatement();
			if(restrictionMethodName !=null){
				SpliceLogUtils.trace(LOG,"%s:restrictionMethodName=%s",indexName,restrictionMethodName);
				restriction = statement.getActivationClass().getMethod(restrictionMethodName);
			}
			GeneratedMethod generatedMethod = statement.getActivationClass().getMethod(resultRowAllocatorMethodName);
			final GenericPreparedStatement gp = (GenericPreparedStatement)activation.getPreparedStatement();
			final Object[] saved = gp.getSavedObjects();
			scoci = (StaticCompiledOpenConglomInfo)saved[scociItem];
			TransactionController tc = activation.getTransactionController();
			dcoci = tc.getDynamicCompiledConglomInfo(conglomId);

			// the saved objects, if it exists
			if (heapColRefItem != -1) {
				this.accessedHeapCols = (FormatableBitSet)saved[heapColRefItem];
			}
			if (allColRefItem != -1) {
				this.accessedAllCols = (FormatableBitSet)saved[allColRefItem];
			}

            // retrieve the array of columns coming from the index
            indexCols = ((ReferencedColumnsDescriptorImpl) saved[indexColMapItem]).getReferencedColumnPositions();
			/* Get the result row template */
            resultRow = (ExecRow) generatedMethod.invoke(activation);

            compactRow = getCompactRow(activation.getLanguageConnectionContext(),resultRow, accessedAllCols, false);

			if(heapOnlyColRefItem!=-1){
				this.heapOnlyCols = (FormatableBitSet)saved[heapOnlyColRefItem];
                adjustedBaseColumnMap = new int[heapOnlyCols.getNumBitsSet()];
                int pos=0;
                for(int i=heapOnlyCols.anySetBit();i>=0;i=heapOnlyCols.anySetBit(i)){
                    adjustedBaseColumnMap[pos] = baseColumnMap[i];
                    pos++;
                }
//                if(heapOnlyCols!=null){
//                    adjustedBaseColumnMap = new int[heapOnlyCols.getNumBitsSet()];
//                    this.heapCols = new FormatableBitSet(heapOnlyCols.size());
//                    for(int i=heapOnlyCols.anySetBit();i>=0;i=heapOnlyCols.anySetBit(i)){
//                        adjustedBaseColumnMap[i-1] = baseColumnMap[i];
//                    }
//                }else{
//                    heapCols = heapOnlyCols;
//                    adjustedBaseColumnMap = baseColumnMap;
//                }
			}

            if (accessedHeapCols == null) {
                rowArray = resultRow.getRowArray();

            }
            else {
                // Figure out how many columns are coming from the heap
                final DataValueDescriptor[] resultRowArray = resultRow.getRowArray();
                final int heapOnlyLen = heapOnlyCols.getLength();

                // Need a separate DataValueDescriptor array in this case
                rowArray = new DataValueDescriptor[heapOnlyLen];
                final int minLen = Math.min(resultRowArray.length, heapOnlyLen);

                // Make a copy of the relevant part of rowArray
                for (int i = 0; i < minLen; ++i) {
                    if (resultRowArray[i] != null && heapOnlyCols.isSet(i)) {
                        rowArray[i] = resultRowArray[i];
                    }
                }
                if (indexCols != null) {
                    for (int index = 0; index < indexCols.length; index++) {
                        if (indexCols[index] != -1) {
                            compactRow.setColumn(index + 1,source.getExecRowDefinition().getColumn(indexCols[index] + 1));
                        }
                    }
                }
            }
            SpliceLogUtils.trace(LOG,"accessedAllCols=%s,accessedHeapCols=%s,heapOnlyCols=%s,accessedCols=%s",accessedAllCols,accessedHeapCols,heapOnlyCols,accessedCols);
            SpliceLogUtils.trace(LOG,"rowArray=%s,compactRow=%s,resultRow=%s,resultSetNumber=%d",
                    Arrays.asList(rowArray),compactRow,resultRow,resultSetNumber);
        } catch (StandardException e) {
            SpliceLogUtils.logAndThrowRuntime(LOG, "Operation Init Failed!",e);
        }

    }

    @Override
    public NoPutResultSet executeScan() throws StandardException {
        SpliceLogUtils.trace(LOG,"executeScan");
        final List<SpliceOperation> operationStack = getOperationStack();
        SpliceLogUtils.trace(LOG,"operationStack=%s",operationStack);
        SpliceOperation regionOperation = operationStack.get(0);
        SpliceLogUtils.trace(LOG,"regionOperation=%s",regionOperation);
        RowProvider provider;
        RowDecoder decoder = getRowEncoder().getDual(getExecRowDefinition());
        if(regionOperation.getNodeTypes().contains(NodeType.REDUCE)&&this!=regionOperation){
            SpliceLogUtils.trace(LOG,"Scanning temp tables");
			provider = regionOperation.getReduceRowProvider(this,decoder);
		}else {
			SpliceLogUtils.trace(LOG,"scanning Map table");
			provider = regionOperation.getMapRowProvider(this,decoder);
		}
		return new SpliceNoPutResultSet(activation,this, provider);
	}

    @Override
    public RowProvider getMapRowProvider(SpliceOperation top, RowDecoder decoder) throws StandardException {
        return source.getMapRowProvider(top, decoder);
    }

    @Override
    public RowProvider getReduceRowProvider(SpliceOperation top, RowDecoder decoder) throws StandardException {
        return source.getReduceRowProvider(top, decoder);
    }

    @Override
    public RowEncoder getRowEncoder() throws StandardException {
        ExecRow template = getExecRowDefinition();
        return RowEncoder.create(template.nColumns(),
                null, null, null,
                KeyType.BARE, RowMarshaller.packedCompressed());
    }

    @Override
	public SpliceOperation getLeftOperation() {
//		SpliceLogUtils.trace(LOG,"getLeftOperation ",source);
		return this.source;
	}
	
	@Override
	public RowLocation getRowLocation() throws StandardException {
		return currentRowLocation;
	}

	@Override
	public ExecRow getCurrentRow() throws StandardException {
		return currentRow;
	}

	@Override
	public List<NodeType> getNodeTypes() {
		return Collections.singletonList(NodeType.SCAN);
	}

	@Override
	public List<SpliceOperation> getSubOperations() {
		SpliceLogUtils.trace(LOG,"getSubOperations");
		return Collections.singletonList(source);
	}

	@Override
	public ExecRow getNextRowCore() throws StandardException {
		SpliceLogUtils.trace(LOG,"<%s> getNextRowCore",indexName);
		ExecRow sourceRow;
		ExecRow retRow;
		boolean restrict = false;
		DataValueDescriptor restrictBoolean;


        do{
            sourceRow = source.getNextRowCore();
            SpliceLogUtils.trace(LOG,"<%s> retrieved index row %s",indexName,sourceRow);
            if(sourceRow==null){
                //No Rows remaining
                clearCurrentRow();
                baseRowLocation= null;
                retRow = null;
                if(table!=null){
                    try {
                        table.close();
                    } catch (IOException e) {
                        SpliceLogUtils.warn(LOG,"Unable to close HTable");
                    }
                }
                break;
            }

            //we have a row, get it
            if(table==null)
                table = SpliceAccessManager.getHTable(conglomId);
            baseRowLocation = (RowLocation)sourceRow.getColumn(sourceRow.nColumns());
            Get get =  SpliceUtils.createGet(baseRowLocation, rowArray, heapOnlyCols, getTransactionID());
            boolean rowExists = false;
            try{
                Result result = table.get(get);
                SpliceLogUtils.trace(LOG,"<%s> rowArray=%s,accessedHeapCols=%s,heapOnlyCols=%s,baseColumnMap=%s",
                        indexName,Arrays.toString(rowArray),accessedHeapCols,heapOnlyCols,Arrays.toString(baseColumnMap));
                rowExists = result!=null && !result.isEmpty();
                if(rowExists){

                    if(fieldDecoder==null)
                        fieldDecoder = MultiFieldDecoder.create();
                    fieldDecoder.reset();
                    for(KeyValue kv:result.raw()){
                        RowMarshaller.sparsePacked().decode(kv, compactRow.getRowArray(),adjustedBaseColumnMap, fieldDecoder);
                    }
                }
            }catch(IOException ioe){
                SpliceLogUtils.logAndThrowRuntime(LOG,ioe);
            }
            SpliceLogUtils.trace(LOG,"<%s>,rowArray=%s,compactRow=%s",indexName,rowArray,compactRow);
            if(rowExists){
                if(!copiedFromSource){
                    copiedFromSource=true;
                    for(int index=0;index < indexCols.length;index++){
                        if(indexCols[index] != -1) {
                            SpliceLogUtils.trace(LOG,"<%s> indexCol overwrite for value %d" ,indexName,indexCols[index]);
                            compactRow.setColumn(index+1,sourceRow.getColumn(indexCols[index]+1));
                        }
                    }
                }

                SpliceLogUtils.trace(LOG, "<%s>compactRow=%s", indexName,compactRow);
                setCurrentRow(compactRow);
                currentRowLocation = baseRowLocation;

                restrictBoolean = (DataValueDescriptor)
                        ((restriction == null) ? null: restriction.invoke(activation));
                restrict = (restrictBoolean ==null) ||
                        ((!restrictBoolean.isNull()) && restrictBoolean.getBoolean());
            }

            if(!restrict || !rowExists){
                clearCurrentRow();
                baseRowLocation = null;
                currentRowLocation=null;
            }else{
                currentRow = compactRow;
            }
            retRow = currentRow;

		}while(!restrict);
		SpliceLogUtils.trace(LOG, "emitting row %s",retRow);
//        setCurrentRow(retRow);
		return retRow;
	}

	@Override
	public void close() throws StandardException {
		SpliceLogUtils.trace(LOG, "close in IndexRowToBaseRow");
		beginTime = getCurrentTimeMillis();
		source.close();
		super.close();
		closeTime += getElapsedMillis(beginTime);
	}

	@Override
	public ExecRow getExecRowDefinition() {
		return compactRow.getClone();
	}

    @Override
    public int[] getRootAccessedCols(long tableNumber) {
        return source.getRootAccessedCols(tableNumber);
    }

    @Override
    public boolean isReferencingTable(long tableNumber) {
        return source.isReferencingTable(tableNumber);
    }

    public String getIndexName() {
		return this.indexName;
	}
	
	public  FormatableBitSet getAccessedHeapCols() {
		return this.accessedHeapCols;
	}
	
	public SpliceOperation getSource() {
		return this.source;
	}
	
	@Override
	public long getTimeSpent(int type)
	{
		long totTime = constructorTime + openTime + nextTime + closeTime;

		if (type == CURRENT_RESULTSET_ONLY)
			return	totTime - source.getTimeSpent(ENTIRE_RESULTSET_TREE);
		else
			return totTime;
	}
	
	@Override
	public String toString() {
		return String.format("IndexRowToBaseRow {source=%s,indexName=%s,conglomId=%d,resultSetNumber=%d}",
                                                                        source,indexName,conglomId,resultSetNumber);
	}

    @Override
    public void openCore() throws StandardException {
        super.openCore();
        if(source!=null)source.openCore();
    }

    @Override
    public String prettyPrint(int indentLevel) {
        String indent = "\n"+ Strings.repeat("\t",indentLevel);

        return new StringBuilder("IndexRowToBaseRow:")
                .append(indent).append("resultSetNumber:").append(resultSetNumber)
                .append(indent).append("accessedCols:").append(accessedCols)
                .append(indent).append("resultRowAllocatorMethodName:").append(resultRowAllocatorMethodName)
                .append(indent).append("indexName:").append(indexName)
                .append(indent).append("accessedHeapCols:").append(accessedHeapCols)
                .append(indent).append("heapOnlyCols:").append(heapOnlyCols)
                .append(indent).append("accessedAllCols:").append(accessedAllCols)
                .append(indent).append("indexCols:").append(Arrays.toString(indexCols))
                .append(indent).append("source:").append(source.prettyPrint(indentLevel+1))
                .toString();
    }
}
