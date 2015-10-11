/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.chukwa.datacollection.writer;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.LinkedList;
import java.util.List;
import java.util.Date;
import java.text.SimpleDateFormat;

import junit.framework.Assert;
import junit.framework.TestCase;

import org.apache.avro.generic.GenericRecord;
import org.apache.hadoop.chukwa.Chunk;
import org.apache.hadoop.chukwa.ChunkBuilder;
import org.apache.hadoop.chukwa.datacollection.writer.localfs.LocalWriter;
import org.apache.hadoop.chukwa.datacollection.writer.parquet.ChukwaParquetWriter;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.parquet.avro.AvroParquetReader;

public class TestChukwaWriters extends TestCase{

  public void testWriters() {
    try {

      File tempDir = new File(System.getProperty("test.build.data", "/tmp"));
      if (!tempDir.exists()) {
        tempDir.mkdirs();
      }

      String outputDirectory = tempDir.getPath() + "/testChukwaWriters_testWriters_" + System.currentTimeMillis() + "/";

      Configuration confParquetWriter = new Configuration();
      confParquetWriter.set("chukwaCollector.rotateInterval", "300000");
      confParquetWriter.set("writer.hdfs.filesystem", "file:///");
      String parquetWriterOutputDir = outputDirectory +"/parquetWriter/parquetOutputDir";
      confParquetWriter.set(ChukwaParquetWriter.OUTPUT_DIR_OPT, parquetWriterOutputDir );

      Configuration conf = new Configuration();
      FileSystem fs = FileSystem.getLocal(conf);

      ChukwaWriter parquetWriter = new ChukwaParquetWriter(confParquetWriter);

      List<Chunk> chunksParquetWriter = new LinkedList<Chunk>();
      List<Chunk> chunksLocalWriter = new LinkedList<Chunk>();
      for(int i=0;i<10;i++) {
        ChunkBuilder cb1 = new ChunkBuilder();
        cb1.addRecord(("record-" +i) .getBytes());
        cb1.addRecord("foo" .getBytes());
        cb1.addRecord("bar".getBytes());
        cb1.addRecord("baz".getBytes());
        chunksParquetWriter.add(cb1.getChunk());
        
        ChunkBuilder cb2 = new ChunkBuilder();
        cb2.addRecord(("record-" +i) .getBytes());
        cb2.addRecord("foo" .getBytes());
        cb2.addRecord("bar".getBytes());
        cb2.addRecord("baz".getBytes());
        chunksLocalWriter.add(cb2.getChunk());
        
      }
      
      Thread.sleep(5000);
      parquetWriter.add(chunksParquetWriter);
      parquetWriter.close();
      
      String parquetWriterFile = null;
      
      File directory = new File(parquetWriterOutputDir);
      String[] files = directory.list();
      for(String file: files) {
        if ( file.endsWith(".done") ){
          parquetWriterFile = parquetWriterOutputDir + File.separator + file;
          break;
        }
      }
      
      Assert.assertFalse(parquetWriterFile == null);
      
      String parquetWriterDump = dumpArchive(fs,conf,parquetWriterFile);
      
      Configuration confLocalWriter = new Configuration();
      confLocalWriter.set("writer.hdfs.filesystem", "file:///");
      String localWriterOutputDir = outputDirectory +"/localWriter/localOutputDir";
      confLocalWriter.set("chukwaCollector.localOutputDir",localWriterOutputDir);
      confLocalWriter.set("chukwaCollector.rotateInterval", "300000");
      confLocalWriter.set("chukwaCollector.minPercentFreeDisk", "2");//so unit tests pass on 
      //machines with mostly-full disks

      ChukwaWriter localWriter = new LocalWriter(confLocalWriter);
      String localWriterFile = null;
      localWriter.init(confLocalWriter);
      Thread.sleep(5000);
      localWriter.add(chunksLocalWriter);
      localWriter.close();

      directory = new File(localWriterOutputDir);
      files = directory.list();
      for(String file: files) {
        if ( file.endsWith(".done") ){
          localWriterFile = localWriterOutputDir + File.separator + file;
          break;
        }
      }
      
      Assert.assertFalse(localWriterFile == null);
      String localWriterDump = dumpArchive(fs,conf,localWriterFile);

      Assert.assertTrue(parquetWriterDump.intern() == localWriterDump.intern());

      File fOutputDirectory = new File(outputDirectory);
      fOutputDirectory.delete();
    } catch (Throwable e) {
      e.printStackTrace();
      Assert.fail("Exception in TestChukwaWriters," + e.getMessage());
    }
    
  }
  
  protected String dumpArchive(FileSystem fs,Configuration conf, String file) throws Throwable {
    AvroParquetReader<GenericRecord> reader = null;
    try {
      reader = new AvroParquetReader<GenericRecord>(conf, new Path(file));

      StringBuilder sb = new StringBuilder();
      while (true) {
        GenericRecord record = reader.read();
        if(record == null) {
          break;
        }
        sb.append("DataType: " + record.get("dataType"));
        sb.append("StreamName: " + record.get("stream"));
        sb.append("SeqId: " + record.get("seqId"));
        sb.append("\t\t =============== ");

        sb.append("Cluster : " + record.get("tags"));
        sb.append("DataType : " + record.get("dataType"));
        sb.append("Source : " + record.get("source"));
        sb.append("Application : " + record.get("stream"));
        sb.append("SeqID : " + record.get("seqId"));
        byte[] data = ((ByteBuffer)record.get("data")).array();
        sb.append("Data : " + new String(data));
        return sb.toString();
      }
    } catch (Throwable e) {
     Assert.fail("Exception while reading ParquetFile"+ e.getMessage());
     throw e;
    }
    
    finally {
      if (reader != null) {
        reader.close();
      }
    }
    return null;    
  }

//  /**
//   * Test to check if the .chukwa files are closing at the time we expect them
//   * to close. This test sets the rotateInterval and offsetInterval to small
//   * values, reads the filename of the first .chukwa file, extracts the
//   * timestamp from its name, calculates the timestamp when the next .chukwa
//   * file should be closed, sleeps for some time (enough for producing the next
//   * .chukwa file), reads the timestamp on the second .chukwa file, and
//   * compares the expected close timestamp with the actual closing timestamp of
//   * the second file.
//   */
//  public void testParquetWriterFixedCloseInterval() {
//    try {
//      long rotateInterval = 10000;
//      long intervalOffset = 3000;
//
//      File tempDir = new File(System.getProperty("test.build.data", "/tmp"));
//      if (!tempDir.exists()) {
//        tempDir.mkdirs();
//      }
//
//      String outputDirectory = tempDir.getPath() + "/testChukwaWriters_testChukwaParquetWriterFixedCloseInterval_" +
//              System.currentTimeMillis() + "/";
//      String parquetWriterOutputDir = outputDirectory +"/parquetWriter/parquetOutputDir";
//
//      File directory = new File(parquetWriterOutputDir);
//      // if some files already exist in this directory then delete them. Files
//      // may exist due to an old test run.
//      File[] files = directory.listFiles();
//      if (files != null) {
//        for(File file: files) {
//          file.delete();
//        }
//      }
//
//      Configuration confParquetWriter = new Configuration();
//      confParquetWriter.set("chukwaCollector.rotateInterval", String.valueOf(rotateInterval));
//      confParquetWriter.set("writer.hdfs.filesystem", "file:///");
//      confParquetWriter.set(ChukwaParquetWriter.OUTPUT_DIR_OPT, parquetWriterOutputDir );
//      confParquetWriter.set("chukwaCollector.isFixedTimeRotatorScheme", "true");
//      confParquetWriter.set("chukwaCollector.fixedTimeIntervalOffset", String.valueOf(intervalOffset));
//
//      ChukwaWriter parquetWriter = new ChukwaParquetWriter(confParquetWriter);
//
//      // we do not want our test to fail due to a lag in calling the
//      // scheduleNextRotation() method and creating of first .chukwa file.
//      // So, we will make sure that the rotation starts in the middle (approx)
//      // of the rotateInterval
//      long currentTime = System.currentTimeMillis();
//      long currentTimeInSec = currentTime/1000;
//      long timeAfterPrevRotateInterval = currentTimeInSec % rotateInterval;
//      if(timeAfterPrevRotateInterval > (rotateInterval - 2)){
//        Thread.sleep(2000);
//      }
//
//      String [] fileNames = directory.list();
//      String firstFileName = "";
//      String initialTimestamp = "";
//      // extracting the close time of first .chukwa file. This timestamp can be
//      // extracted from the file name. An example filename is
//      // 20110531122600002_<host-name>_5f836ece1302899d9a0727e.chukwa
//      for(String file: fileNames) {
//        if ( file.endsWith(".chukwa") ){
//          // set a flag so that later we can identify that this file has been
//          // visited
//          firstFileName = file;
//          // getting just the timestamp part i.e. 20110531122600002 in the
//          // example filename mentioned in the above comment
//          initialTimestamp = file.split("_")[0];
//          // stripping off the millisecond part of timestamp. The timestamp
//          // now becomes 20110531122600
//          initialTimestamp = initialTimestamp.substring(0, initialTimestamp.length()-3);
//          break;
//        }
//      }
//
//      SimpleDateFormat formatter = new SimpleDateFormat("yyyyMMddhhmmss");
//      Date initialDate = formatter.parse(initialTimestamp);
//	    long initialDateInMillis = initialDate.getTime();
//
//      // calculate the expected close time of the next .chukwa file.
//      long prevRoundedInterval = initialDateInMillis - (initialDateInMillis %
//              rotateInterval);
//      long expectedNextCloseDate = prevRoundedInterval +
//              rotateInterval + intervalOffset;
//
//      // sleep for a time interval equal to (rotateInterval + offsetInterval).
//      // Only one more .chukwa file will be will be produced in this time
//      // interval.
//      long sleepTime = rotateInterval + intervalOffset;
//
//      Thread.sleep(sleepTime);
//      fileNames = directory.list();
//      String nextTimestamp = "";
//      // extract the timestamp of the second .chukwa file
//      for(String file: fileNames) {
//        if ( file.endsWith(".chukwa") && !file.equals(firstFileName)){
//          nextTimestamp = file.split("_")[0];
//          nextTimestamp = nextTimestamp.substring(0, nextTimestamp.length()-3);
//          break;
//        }
//      }
//
//      Date nextDate = formatter.parse(nextTimestamp);
//      System.out.println("initialTimestamp:"+nextTimestamp);
//      long nextDateInMillis = nextDate.getTime();
//
//      long threshold = 500; //milliseconds
//
//      // test will be successful only if the timestamp on the second .chukwa
//      // file is very close (differs by < 500 ms) to the expected closing
//      // timestamp we calculated.
//      Assert.assertTrue("File not closed at expected time",
//              (nextDateInMillis - expectedNextCloseDate < threshold));
//      parquetWriter.close();
//
//    } catch (Throwable e) {
//      e.printStackTrace();
//      Assert.fail("Exception in TestChukwaWriters - " +
//              "testParquetFileFixedCloseInterval()," + e.getMessage());
//    }
//}

  /**
   * Test to check the calculation of the delay interval for rotation in
   * ParquetFileWriter. It uses an array of known currentTimestamps and their
   * corresponding expectedRotateTimestamps (the next timestamp when the
   * rotation should happen). The actual timestamp of next rotation is
   * calculated by adding delay (obtained from getDelayForFixedInterval()) to
   * the currentTimestamp.
   */
  public void testFixedIntervalOffsetCalculation(){
    try {
      String tmpDir = System.getProperty("test.build.data", "/tmp");
      long ts = System.currentTimeMillis();
      String dataDir = tmpDir + "/TestChukwaWriters_" + ts;

      Configuration conf = new Configuration();
      conf.set("chukwaCollector.outputDir", dataDir  + "/log/");

      ChukwaParquetWriter parquetWriter = new ChukwaParquetWriter(conf);
      SimpleDateFormat formatter = new SimpleDateFormat("yyyy/MM/dd hh:mm:ssZ");

      //rotateInterval >> offsetInterval
      long rotateInterval = 300000; //5 min
      long offsetInterval = 60000;  //1 min
      long currentTimestamps[] = new long[5] ;
      long expectedRotateTimestamps[] = new long[5];

      Date date = formatter.parse("2011/06/15 01:05:00+0000");
	    currentTimestamps[0] = date.getTime();
      expectedRotateTimestamps[0] = 1308100260000L; //2011/06/15 01:11:00

      date = formatter.parse("2011/06/15 01:06:00+0000");
	    currentTimestamps[1] = date.getTime();
      expectedRotateTimestamps[1] = 1308100260000L; //2011/06/15 01:11:00

      date = formatter.parse("2011/06/15 01:02:00+0000");
      currentTimestamps[2] = date.getTime();
      expectedRotateTimestamps[2] = 1308099960000L; //2011/06/15 01:06:00

      date = formatter.parse("2011/06/15 01:04:00+0000");
      currentTimestamps[3] = date.getTime();
      expectedRotateTimestamps[3] = 1308099960000L; //2011/06/15 01:06:00

      //edge case, when there is a change in the "hour"
      date = formatter.parse("2011/06/15 01:56:00+0000");
      currentTimestamps[4] = date.getTime();
      expectedRotateTimestamps[4] = 1308103260000L; //2011/06/15 02:01:00

      int i=0;
      long expectedDelay = 0;
      long actualRotateTimestamp = 0;
      for(; i<5; i++){
        expectedDelay = parquetWriter.getDelayForFixedInterval(
                currentTimestamps[i], rotateInterval, offsetInterval);
        actualRotateTimestamp = currentTimestamps[i] + expectedDelay;
        Assert.assertTrue("Incorrect value for delay",
                (actualRotateTimestamp==expectedRotateTimestamps[i]));
      }

      //rotateInterval > offsetInterval
      rotateInterval = 60000; //1 min
      offsetInterval = 30000; //30 sec

      date = formatter.parse("2011/06/15 01:05:00+0000");
	    currentTimestamps[0] = date.getTime();
      expectedRotateTimestamps[0] = 1308099990000L; //2011/06/15 01:06:30

      date = formatter.parse("2011/06/15 01:04:30+0000");
	    currentTimestamps[1] = date.getTime();
      expectedRotateTimestamps[1] = 1308099930000L; //2011/06/15 01:05:30

      date = formatter.parse("2011/06/15 01:05:30+0000");
      currentTimestamps[2] = date.getTime();
      expectedRotateTimestamps[2] = 1308099990000L; //2011/06/15 01:06:30

      date = formatter.parse("2011/06/15 01:04:00+0000");
      currentTimestamps[3] = date.getTime();
      expectedRotateTimestamps[3] = 1308099930000L; //2011/06/15 01:05:30

      //edge case, when there is a change in the "hour"
      date = formatter.parse("2011/06/15 01:59:30+0000");
      currentTimestamps[4] = date.getTime();
      expectedRotateTimestamps[4] = 1308103230000L; //2011/06/15 02:00:30

      for(i=0; i<5; i++){
        expectedDelay = parquetWriter.getDelayForFixedInterval(
                currentTimestamps[i], rotateInterval, offsetInterval);
        actualRotateTimestamp = currentTimestamps[i] + expectedDelay;
        Assert.assertTrue("Incorrect value for delay",
                (actualRotateTimestamp==expectedRotateTimestamps[i]));
      }

      //rotateInterval = offsetInterval
      rotateInterval = 60000; //1 min
      offsetInterval = 60000; //1 min

      date = formatter.parse("2011/06/15 01:02:00+0000");
      currentTimestamps[0] = date.getTime();
      expectedRotateTimestamps[0] = 1308099840000L; //2011/06/15 01:04:00

      date = formatter.parse("2011/06/15 01:02:30+0000");
      currentTimestamps[1] = date.getTime();
      expectedRotateTimestamps[1] = 1308099840000L; //2011/06/15 01:04:00

      //edge case, when there is a change in the "hour"
      date = formatter.parse("2011/06/15 01:59:30+0000");
      currentTimestamps[2] = date.getTime();
      expectedRotateTimestamps[2] = 1308103260000L; //2011/06/15 02:01:00

      for(i=0; i<3; i++){
        expectedDelay = parquetWriter.getDelayForFixedInterval(
                currentTimestamps[i], rotateInterval, offsetInterval);
        actualRotateTimestamp = currentTimestamps[i] + expectedDelay;
        Assert.assertTrue("Incorrect value for delay",
                (actualRotateTimestamp==expectedRotateTimestamps[i]));
      }

      //rotateInterval < offsetInterval
      rotateInterval = 60000; //1 min
      offsetInterval = 120000; //2 min

      date = formatter.parse("2011/06/15 01:02:00+0000");
      currentTimestamps[0] = date.getTime();
      expectedRotateTimestamps[0] = 1308099900000L; //2011/06/15 01:05:00

      date = formatter.parse("2011/06/15 01:02:30+0000");
      currentTimestamps[1] = date.getTime();
      expectedRotateTimestamps[1] = 1308099900000L; //2011/06/15 01:05:00

      //edge case, when there is a change in the "hour"
      date = formatter.parse("2011/06/15 01:59:30+0000");
      currentTimestamps[2] = date.getTime();
      expectedRotateTimestamps[2] = 1308103320000L; //2011/06/15 02:02:00

      for(i=0; i<3; i++){
        expectedDelay = parquetWriter.getDelayForFixedInterval(
                currentTimestamps[i], rotateInterval, offsetInterval);
        actualRotateTimestamp = currentTimestamps[i] + expectedDelay;
        Assert.assertTrue("Incorrect value for delay",
                (actualRotateTimestamp==expectedRotateTimestamps[i]));
      }

    } catch (Throwable e) {
      e.printStackTrace();
      Assert.fail("Exception in TestChukwaWriters - " +
              "testFixedIntervalOffsetCalculation()," + e.getMessage());
    }
  }
}
