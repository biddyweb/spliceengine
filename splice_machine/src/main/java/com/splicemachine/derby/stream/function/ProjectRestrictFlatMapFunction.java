package com.splicemachine.derby.stream.function;

import com.splicemachine.db.iapi.sql.execute.ExecRow;
import com.splicemachine.db.iapi.sql.execute.ExecutionFactory;
import com.splicemachine.derby.iapi.sql.execute.SpliceOperation;
import com.splicemachine.derby.impl.sql.execute.operations.LocatedRow;
import com.splicemachine.derby.impl.sql.execute.operations.ProjectRestrictOperation;
import com.splicemachine.derby.stream.iapi.OperationContext;

import java.util.Collections;

/**
 * Created by jleach on 5/1/15.
 */
public class ProjectRestrictFlatMapFunction<Op extends SpliceOperation> extends SpliceFlatMapFunction<Op,LocatedRow,LocatedRow> {
    protected boolean initialized;
    protected ProjectRestrictOperation op;
    protected ExecutionFactory executionFactory;
    protected int numberOfColumns;

    public ProjectRestrictFlatMapFunction() {
        super();
    }

    public ProjectRestrictFlatMapFunction(OperationContext<Op> operationContext) {
        super(operationContext);
    }

    @Override
    public Iterable<LocatedRow> call(LocatedRow from) throws Exception {
        if (!initialized) {
            initialized = true;
            op = (ProjectRestrictOperation) getOperation();
            executionFactory = op.getExecutionFactory();
            numberOfColumns = op.getExecRowDefinition().nColumns();
        }
        op.source.setCurrentRow(from.getRow());
        if (!op.getRestriction().apply(from.getRow())) {
            operationContext.recordFilter();
            return Collections.EMPTY_LIST;
        }
        ExecRow execRow = executionFactory.getValueRow(numberOfColumns);
        ExecRow preCopy = op.doProjection(from.getRow()).getClone();
        LocatedRow locatedRow = new LocatedRow(from.getRowLocation(),
                ProjectRestrictOperation.copyProjectionToNewRow(preCopy, execRow));
        op.setCurrentLocatedRow(locatedRow);
        return Collections.singletonList(locatedRow);
    }
}