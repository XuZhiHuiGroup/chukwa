package org.apache.hadoop.chukwa.datacollection.adaptor;

import java.util.*;
import org.apache.hadoop.chukwa.Chunk;
import org.apache.hadoop.chukwa.datacollection.ChunkReceiver;
import org.apache.hadoop.chukwa.datacollection.agent.AdaptorManager;

public class MemBuffered extends AbstractWrapper {
  
  static final String BUF_SIZE_OPT = "adaptor.memBufWrapper.size";
  static final int DEFAULT_BUF_SIZE = 1024*1024; //1 MB
  
  static class MemBuf {
    long dataSizeBytes;
    final long maxDataSize;
    final ArrayDeque<Chunk> chunks;
    
    public MemBuf(long maxDataSize) {
      dataSizeBytes = 0;
      this.maxDataSize = maxDataSize;
      chunks = new ArrayDeque<Chunk>();
    }
    
    synchronized void add(Chunk c) throws InterruptedException{
      int len = c.getData().length;
      while(len + dataSizeBytes > maxDataSize)
        wait();
      dataSizeBytes += len;
      chunks.add(c);
    }
    
    synchronized void removeUpTo(long l) {

      long bytesFreed = 0;
      while(!chunks.isEmpty()) {
        Chunk c = chunks.getFirst();
        if(c.getSeqID() > l)
          chunks.addFirst(c);
        else
          bytesFreed += c.getData().length;
      }
      
      if(bytesFreed > 0) {
        dataSizeBytes -= bytesFreed;
        notifyAll();
      }
    }
    
  }

  static Map<String, MemBuf> buffers;
  static {
    buffers = new HashMap<String, MemBuf>();
  }
  
  MemBuf myBuffer;
  
  @Override
  public void add(Chunk event) throws InterruptedException {
    myBuffer.add(event);
    dest.add(event);
  }
  
  @Override
  public void start(String adaptorID, String type, long offset,
      ChunkReceiver dest) throws AdaptorException {
    try {
      String dummyAdaptorID = adaptorID;
      this.dest = dest;
      
      long bufSize = manager.getConfiguration().getInt(BUF_SIZE_OPT, DEFAULT_BUF_SIZE);
      synchronized(buffers) {
        myBuffer = buffers.get(adaptorID);
        if(myBuffer == null) {
          myBuffer = new MemBuf(bufSize);
          buffers.put(adaptorID, myBuffer);
        }
      }

      //Drain buffer into output queue
      for(Chunk c:myBuffer.chunks)
        dest.add(c);
      
      inner.start(dummyAdaptorID, innerType, offset, this);
    } catch(InterruptedException e) {
     throw new AdaptorException(e);
    }
  }
  
  @Override
  public void committed(long l) {
    myBuffer.removeUpTo(l);
  }

}
