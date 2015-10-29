package com.splicemachine.derby.impl.sql.execute.operations.sort;

import com.splicemachine.metrics.MetricFactory;
import com.splicemachine.db.iapi.sql.execute.ExecRow;
import com.splicemachine.derby.impl.sql.execute.operations.framework.AbstractAggregateBuffer;
import com.splicemachine.derby.impl.sql.execute.operations.framework.BufferedAggregator;
import com.splicemachine.derby.impl.sql.execute.operations.framework.SpliceGenericAggregator;
import com.splicemachine.derby.utils.StandardSupplier;

/**
 * @author Scott Fines
 * Created on: 11/1/13
 */
public class DistinctSortAggregateBuffer extends AbstractAggregateBuffer {
    protected final StandardSupplier<ExecRow> emptyRowSupplier;
    
    public DistinctSortAggregateBuffer(int maxSize,
                           SpliceGenericAggregator[] aggregators,
													 StandardSupplier<ExecRow> emptyRowSupplier,
													 MetricFactory metricFactory){
    	super(maxSize,aggregators,metricFactory);
    	this.emptyRowSupplier = emptyRowSupplier;
    }
        
	@Override
	public BufferedAggregator createBufferedAggregator() {
		return new DistinctSortBufferedAggregator(emptyRowSupplier);
	}

	@Override
	public void intializeAggregator() {
		values = new DistinctSortBufferedAggregator[bufferSize];
	}
}