package org.apache.derby.impl.sql.execute.operations;

import com.google.common.collect.Lists;
import com.splicemachine.derby.test.framework.SpliceDataWatcher;
import com.splicemachine.derby.test.framework.SpliceSchemaWatcher;
import com.splicemachine.derby.test.framework.SpliceTableWatcher;
import com.splicemachine.derby.test.framework.SpliceWatcher;
import org.apache.log4j.Logger;
import org.junit.Assert;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.RuleChain;
import org.junit.rules.TestRule;
import org.junit.runner.Description;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;

/**
 * Test to validate that GENERATED columns work correctly.
 *
 * @author Scott Fines
 * Created on: 5/28/13
 */
public class GeneratedColumnIT {
    protected static SpliceWatcher spliceClassWatcher = new SpliceWatcher();
    private static final Logger LOG = Logger.getLogger(GeneratedColumnIT.class);
    private static final String CLASS_NAME = GeneratedColumnIT.class.getSimpleName().toUpperCase();

    protected  static SpliceSchemaWatcher spliceSchemaWatcher = new SpliceSchemaWatcher(CLASS_NAME);
    protected static SpliceTableWatcher generatedAlwaysTable = new SpliceTableWatcher("A",spliceSchemaWatcher.schemaName,"(adr_id integer NOT NULL GENERATED ALWAYS AS IDENTITY, adr_catid integer)");
    protected static SpliceTableWatcher generatedDefaultTable = new SpliceTableWatcher("B",spliceSchemaWatcher.schemaName,"(adr_id integer NOT NULL GENERATED BY DEFAULT AS IDENTITY, adr_catid integer)");
    protected static SpliceTableWatcher generatedAlwaysTableStartsWith10 = new SpliceTableWatcher("C",spliceSchemaWatcher.schemaName,"(adr_id integer NOT NULL GENERATED ALWAYS AS IDENTITY( START WITH 10), adr_catid integer)");
    protected static SpliceTableWatcher generatedAlwaysTableIncBy10 = new SpliceTableWatcher("D",spliceSchemaWatcher.schemaName,"(adr_id integer NOT NULL GENERATED ALWAYS AS IDENTITY( INCREMENT BY 10), adr_catid integer)");

    private static int size = 10;
    @ClassRule
    public static TestRule chain = RuleChain.outerRule(spliceClassWatcher)
            .around(spliceSchemaWatcher)
            .around(generatedAlwaysTable)
            .around(generatedAlwaysTableStartsWith10)
            .around(generatedAlwaysTableIncBy10)
            .around(generatedDefaultTable)
            .around(new SpliceDataWatcher() {
                @Override
                protected void starting(Description description) {
                    try {
                        PreparedStatement ps = spliceClassWatcher.prepareStatement(String.format("insert into %s (adr_catid) values ?", generatedAlwaysTable.toString()));
                        for(int i=0;i<size;i++){
                            ps.setInt(1,i*10);
                            ps.execute();
                        }
                        ps = spliceClassWatcher.prepareStatement(String.format("insert into %s (adr_catid) values ?", generatedAlwaysTableStartsWith10));
                        for(int i=0;i<size;i++){
                            ps.setInt(1,i*10);
                            ps.execute();
                        }

												ps = spliceClassWatcher.prepareStatement(String.format("insert into %s (adr_id,adr_catid) values (DEFAULT,?)",generatedAlwaysTableStartsWith10));
												ps.setInt(1,10*size);
												ps.execute(); //make sure that we can add from default

                        ps = spliceClassWatcher.prepareStatement(String.format("insert into %s (adr_catid) values ?", generatedAlwaysTableIncBy10));
                        for(int i=0;i<size;i++){
                            ps.setInt(1,i*10);
                            ps.execute();
                        }

                        ps = spliceClassWatcher.prepareStatement(String.format("insert into %s (adr_catid) values ?",generatedDefaultTable));
                        for(int i=0;i<size/2;i++){
                            ps.setInt(1,i*10);
                            ps.execute();
                        }

                        ps = spliceClassWatcher.prepareStatement(String.format("insert into %s (adr_id,adr_catid) values (?,?)",generatedDefaultTable));
                        for(int i=size/2;i<size;i++){
                            ps.setInt(1,2*i);
                            ps.setInt(2,i*10);
                            ps.execute();
                        }

                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }finally{
                        spliceClassWatcher.closeAll();
                    }
                }
            });


    @Rule public SpliceWatcher methodWatcher = new SpliceWatcher();

    @Test
    public void testCanInsertDefaultGeneratedData() throws Exception {
        ResultSet rs = methodWatcher.executeQuery(String.format("select * from %s", generatedDefaultTable));
        List<String> results = Lists.newArrayList();
        while(rs.next()){
            Integer adrId = rs.getInt(1);

            Assert.assertTrue("No adr_id specified!",!rs.wasNull());
//            Assert.assertTrue("adrId falls in incorrect range! adrId = "+ adrId,size <= adrId || size/2 >= adrId);
            int addrCatId = rs.getInt(2);
            results.add(String.format("addrId=%d,addrCatId=%d",adrId,addrCatId));
        }
        for(String result:results){
            LOG.warn(result);
        }
        Assert.assertEquals("Incorrect number of rows returned!",size,results.size());
    }


    @Test
    public void testCanInsertGeneratedDataStartingWithValue() throws Exception {
       /*
        * Regression test for Bug 315. Make sure that the insertion which occurred during initialization is correct
        */
        ResultSet rs = methodWatcher.executeQuery(String.format("select * from %s", generatedAlwaysTableStartsWith10));
        List<String> results = Lists.newArrayList();
        while(rs.next()){
            Integer adrId = rs.getInt(1);

            Assert.assertTrue("No adr_id specified!",!rs.wasNull());
            Assert.assertTrue("adr_id outside correct range!adrId = "+ adrId,10<=adrId);
            int addrCatId = rs.getInt(2);
            results.add(String.format("addrId=%d,addrCatId=%d",adrId,addrCatId));
        }
        for(String result:results){
            LOG.debug(result);
        }
        Assert.assertEquals("Incorrect number of rows returned!",size+1,results.size());
    }

    @Test
    public void testCanInsertGeneratedDataWithIncrement() throws Exception {
       /*
        * Regression test for Bug 315. Make sure that the insertion which occurred during initialization is correct
        */
        ResultSet rs = methodWatcher.executeQuery(String.format("select * from %s", generatedAlwaysTableIncBy10));
        List<String> results = Lists.newArrayList();
        while(rs.next()){
            Integer adrId = rs.getInt(1);

            Assert.assertTrue("No adr_id specified!",!rs.wasNull());
            Assert.assertTrue("(adrId-1)%10!=0, adrId="+adrId,(adrId-1)%10==0);
            int addrCatId = rs.getInt(2);
            results.add(String.format("addrId=%d,addrCatId=%d",adrId,addrCatId));
        }
        for(String result:results){
            LOG.debug(result);
        }
        Assert.assertEquals("Incorrect number of rows returned!",size,results.size());
    }

    @Test
    public void testCanInsertGeneratedData() throws Exception {
       /*
        * Regression test for Bug 315. Make sure that the insertion which occurred during initialization is correct
        */
        ResultSet rs = methodWatcher.executeQuery(String.format("select * from %s", generatedAlwaysTable));
        List<String> results = Lists.newArrayList();
        while(rs.next()){
            Integer adrId = rs.getInt(1);

            Assert.assertTrue("No adr_id specified!",!rs.wasNull());
            int addrCatId = rs.getInt(2);
            results.add(String.format("addrId=%d,addrCatId=%d",adrId,addrCatId));
        }
        for(String result:results){
            LOG.debug(result);
        }
        Assert.assertEquals("Incorrect number of rows returned!",size,results.size());
    }
}