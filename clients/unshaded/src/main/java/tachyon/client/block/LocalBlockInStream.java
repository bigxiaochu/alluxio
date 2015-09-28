/*
 * Licensed to the University of California, Berkeley under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for additional information regarding
 * copyright ownership. The ASF licenses this file to You under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License. You may obtain a
 * copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package tachyon.client.block;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.InetSocketAddress;
import java.nio.channels.FileChannel;

import com.google.common.base.Preconditions;
import com.google.common.io.Closer;

import tachyon.client.ClientContext;
import tachyon.util.io.BufferUtils;
import tachyon.util.network.NetworkAddressUtils;
import tachyon.worker.WorkerClient;

/**
 * This class provides a streaming API to read a block in Tachyon. The data will be directly read
 * from the local machine's storage. The instances of this class should only be used by one
 * thread and are not thread safe.
 */
public final class LocalBlockInStream extends BufferedBlockInStream {
  private final Closer mCloser;
  private final WorkerClient mWorkerClient;

  /**
   * Creates a new local block input stream.
   *
   * @param blockId the block id
   * @throws IOException if I/O error occurs
   */
  public LocalBlockInStream(long blockId, long blockSize, InetSocketAddress location)
      throws IOException {
    super(blockId, blockSize, location);
    mCloser = Closer.create();
    mWorkerClient =
        mContext.acquireWorkerClient(NetworkAddressUtils.getLocalHostName(ClientContext.getConf()));

    try {
      String blockPath = mWorkerClient.lockBlock(blockId);
      if (blockPath == null) {
        throw new IOException("Block " + mBlockId + " is not available on local machine.");
      }
      RandomAccessFile localFile = mCloser.register(new RandomAccessFile(blockPath, "r"));
      FileChannel localFileChannel = mCloser.register(localFile.getChannel());
    } catch (IOException e) {
      mContext.releaseWorkerClient(mWorkerClient);
      throw e;
    }
  }

  @Override
  public void close() throws IOException {
    if (mClosed) {
      return;
    }
    if (mBytesReadLocal > 0) {
      ClientContext.getClientMetrics().incBlocksReadLocal(1);
    }
    mWorkerClient.unlockBlock(mBlockId);
    mContext.releaseWorkerClient(mWorkerClient);
    // TODO(calvin): Evaluate if this is necessary.
    BufferUtils.cleanDirectBuffer(mData);
    mClosed = true;
  }

  @Override
  public int read() throws IOException {
    checkIfClosed();
    if (mData.remaining() == 0) {
      close();
      return -1;
    }
    mBytesReadLocal ++;
    ClientContext.getClientMetrics().incBytesReadLocal(1);
    return BufferUtils.byteToInt(mData.get());
  }

  @Override
  public int read(byte[] b) throws IOException {
    checkIfClosed();
    return read(b, 0, b.length);
  }

  @Override
  public int read(byte[] b, int off, int len) throws IOException {
    checkIfClosed();
    Preconditions.checkArgument(b != null, "Buffer is null");
    Preconditions.checkArgument(off >= 0 && len >= 0 && len + off <= b.length, String
        .format("Buffer length (%d), offset(%d), len(%d)", b.length, off, len));
    if (len == 0) {
      return 0;
    }

    int ret = Math.min(len, mData.remaining());
    if (ret == 0) {
      close();
      return -1;
    }
    mData.get(b, off, ret);
    mBytesReadLocal += ret;
    ClientContext.getClientMetrics().incBytesReadLocal(ret);
    return ret;
  }

  @Override
  public long remaining() {
    return mData.remaining();
  }

  @Override
  public void seek(long pos) throws IOException {
    checkIfClosed();
    Preconditions.checkArgument(pos >= 0, "Seek position is negative: " + pos);
    Preconditions.checkArgument(pos <= mData.limit(), "Seek position is past buffer limit: " + pos
        + ", Buffer Size = " + mData.limit());
    mData.position((int) pos);
  }

  @Override
  public long skip(long n) throws IOException {
    checkIfClosed();
    if (n <= 0) {
      return 0;
    }

    int ret = mData.remaining();
    if (ret > n) {
      ret = (int) n;
    }
    mData.position(mData.position() + ret);
    return ret;
  }

  private void checkIfClosed() throws IOException {
    Preconditions.checkState(!mClosed, "Cannot do operations on a closed BlockInStream");
  }
}
