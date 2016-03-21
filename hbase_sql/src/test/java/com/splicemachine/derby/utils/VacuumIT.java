package com.splicemachine.derby.utils;

import com.splicemachine.derby.test.framework.SpliceWatcher;
import com.splicemachine.test.SerialTest;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.HTableDescriptor;
import org.apache.hadoop.hbase.client.HBaseAdmin;
import org.junit.ClassRule;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.rules.TestRule;

import java.sql.CallableStatement;
import java.util.Arrays;

/**
 * @author Scott Fines
 * Date: 3/19/14
 */
@Category(SerialTest.class)
@Ignore("Vacuum feature is not implemented on K2")
public class VacuumIT {
		private static final SpliceWatcher spliceClassWatcher = new SpliceWatcher();
		@ClassRule
		public static TestRule classRule = spliceClassWatcher;

		@Rule
		public SpliceWatcher methodRule = new SpliceWatcher();

		@Test
		public void testVacuumDoesNotBreakStuff() throws Exception {
				/*
				 * Simple test to make sure that Vacuum works and doesn't delete anything <1168.
				 */
			//TODO -sf- this probably doesn't work quite right.
				HBaseAdmin admin = new HBaseAdmin(new Configuration());

				try{
						HTableDescriptor[] beforeTables = admin.listTables();
						CallableStatement callableStatement = methodRule.prepareCall("call SYSCS_UTIL.VACUUM()");
						callableStatement.execute();
						HTableDescriptor[] afterTables = admin.listTables();
						//TODO -sf- make this a real test
						System.out.println(Arrays.toString(beforeTables));
						System.out.println(Arrays.toString(afterTables));
				}finally{
						admin.close();
				}
		}
}