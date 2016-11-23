package edu.brown.cs.systems.tpcds.spark;

import java.io.File;
import java.io.FileNotFoundException;

import org.apache.spark.SparkConf;
import org.apache.spark.SparkContext;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SQLContext;
import org.apache.spark.sql.hive.HiveContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.databricks.spark.sql.perf.tpcds.Tables;

import edu.brown.cs.systems.tpcds.QueryUtils;
import edu.brown.cs.systems.tpcds.QueryUtils.Benchmark;
import edu.brown.cs.systems.tpcds.QueryUtils.Benchmark.Query;

public class SparkTPCDSWorkloadGenerator {
	
	public static final Logger log = LoggerFactory.getLogger(SparkTPCDSWorkloadGenerator.class);

	public final String name;
	public final TPCDSSettings settings;
	public final SparkConf sparkConf;
	public final SparkContext sparkContext;
	public final SQLContext sqlContext;
	public final Tables tables;
	
	private SparkTPCDSWorkloadGenerator(String name, TPCDSSettings settings) {
		this.name = name;
		this.settings = settings;
		this.sparkConf = new SparkConf().setAppName(name);
		this.sparkConf.set("spark.scheduler.mode", "FAIR");
		this.sparkContext = new SparkContext(sparkConf);
		this.sqlContext = new SQLContext(sparkContext);
		
		// Load the tables into memory using the spark-sql-perf Tables code
		this.tables = new Tables(sqlContext, settings.scaleFactor);
		tables.createTemporaryTables(settings.dataLocation, settings.dataFormat, "");
	}
	
	/** Load TPC-DS tables into memory using default configuration */
	public static SparkTPCDSWorkloadGenerator spinUpWithDefaults() {
		return spinUp("SparkTPCDSWorkloadGenerator", TPCDSSettings.createWithDefaults());
	}
	
	/** Load TPC-DS tables into memory sourced using the provided settings */
	public static SparkTPCDSWorkloadGenerator spinUp(String name, TPCDSSettings settings) {
		return new SparkTPCDSWorkloadGenerator(name, settings);
	}
	
	
	public static void main(String[] args) throws FileNotFoundException {
		if (args.length != 1) {
			System.out.println("Expected argument specifying dataset and query, eg impala-tpcds-modified-queries/q19.sql");
			return;
		}

		long preLoad = System.currentTimeMillis();

		// Load the benchmark
		String[] splits = args[0].split(File.separator);
		Benchmark b = QueryUtils.load().get(splits[0]);
		
		// Bad benchmark
		if (b == null) {
			System.out.println("Unknown benchmark " + splits[0]);
			return;
		}

		// Create from default settings
		TPCDSSettings settings = TPCDSSettings.createWithDefaults();
		SparkTPCDSWorkloadGenerator gen = spinUp("SparkTPCDSWorkloadGenerator", settings);
		long postLoad = System.currentTimeMillis();

		// all queries in the set of specific one
		if (splits.length <= 1) {
			int i = 0;
			for (Query q: b.benchmarkQueries.values()) {
				System.out.printf("Running query %s on %s dataset %s\n", q, settings.dataFormat, settings.dataLocation);
				// Bad query
				if (q == null) {
					System.out.println("Unknown query " + args[0]);
					return;
				}

				QueryRunnable runnable = new QueryRunnable(i, q, gen.sqlContext);
				runnable.start();
			}
		} else {
			// Get the query
			Query q = b.benchmarkQueries.get(splits[1]);
			System.out.printf("Running query %s on %s dataset %s\n", q, settings.dataFormat, settings.dataLocation);
			// Bad query
			if (q == null) {
				System.out.println("Unknown query " + args[0]);
				return;
			}

			// Run the query
			Row[] rows = gen.sqlContext.sql(q.queryText).collect();

			// Print the output rows
			for (Row r : rows) {
				System.out.println(r);
			}
		}
		


		long postQ = System.currentTimeMillis();
		System.out.printf("Load time: %d, Query time: %d\n", postLoad-preLoad, postQ-postLoad);
	}

	static class QueryRunnable implements Runnable {
		private Thread mThread;
		private Query mQuery;
		private int mIndex;
		private SQLContext mContext;

		QueryRunnable (int index, Query q, SQLContext context) {
			mIndex = index;
			mQuery = q;
			mContext = context;
		}

		public void run() {
			try {
				Thread.sleep(mIndex * mIndex * 5000);
				Row[] rows = mContext.sql(mQuery.queryText).collect();
				for (Row r : rows) {
					System.out.println(r);
				}
			} catch (InterruptedException e) {
				e.printStackTrace();
			}

		}

		public void start() {
			if (mThread == null) {
				mThread = new Thread(this, "Thread " + mIndex);
				mThread.start();
			}
		}
	}

}
