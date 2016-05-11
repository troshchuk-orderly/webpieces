package org.webpieces.nio.impl.cm.basic;

import java.io.IOException;
import java.net.BindException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousCloseException;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.webpieces.nio.api.channels.Channel;
import org.webpieces.nio.api.channels.ChannelSession;
import org.webpieces.nio.api.exceptions.NioClosedChannelException;
import org.webpieces.nio.api.exceptions.NioException;
import org.webpieces.nio.api.exceptions.NioTimeoutException;
import org.webpieces.nio.api.handlers.DataListener;
import org.webpieces.nio.impl.util.ChannelSessionImpl;

import com.webpieces.data.api.BufferPool;

/**
 * @author Dean Hiller
 */
public abstract class BasChannelImpl
	extends RegisterableChannelImpl
	implements Channel {

	private static final Logger apiLog = LoggerFactory.getLogger(Channel.class);
	private static final Logger log = LoggerFactory.getLogger(BasChannelImpl.class);

    private ChannelSession session = new ChannelSessionImpl();
    private long waitingBytesCounter = 0;
	private ConcurrentLinkedQueue<WriteRunnable> waitingWriters = new ConcurrentLinkedQueue<WriteRunnable>();
	private boolean isConnecting = false;
	private boolean isClosed = false;
    private boolean registered;
	private boolean doNotAllowWrites;
	private int writeTimeoutMs = 5_000;
	protected final DataListener listener;
	private int maxBytesWaitingSize = 250000;
	private boolean applyingBackpressure;
	private boolean isRegisterdForReads;
	private boolean failOnNoBackPressure;
	private BufferPool pool;
	
	public BasChannelImpl(IdObject id, SelectorManager2 selMgr, DataListener dataListener, BufferPool pool) {
		super(id, selMgr);
		this.listener = dataListener;
		this.pool = pool;
	}
	
	/* (non-Javadoc)
	 * @see biz.xsoftware.nio.RegisterableChannelImpl#getRealChannel()
	 */
	public abstract SelectableChannel getRealChannel();

	/* (non-Javadoc)
	 * @see api.biz.xsoftware.nio.RegisterableChannel#isBlocking()
	 */
	public abstract boolean isBlocking();

	public abstract int readImpl(ByteBuffer b);
	protected abstract int writeImpl(ByteBuffer b);
   
	@Override
	public CompletableFuture<Channel> connect(SocketAddress addr) {
		return connectImpl(addr);
	}
	
    protected abstract CompletableFuture<Channel> connectImpl(SocketAddress addr);

	private void unqueueAndFailWritesThenClose(CloseRunnable action) {
    	List<CompletableFuture<Channel>> promises;
    	synchronized(this) { //put here for emphasis that we are synchronizing here but not below
			promises = failAllWritesInQueue();
    	}
    	
		//TODO: This should really be inlined now.  It's a remnant of an old design since close didn't
		//work well outside the selector thread previously
		action.runDelayedAction();
		
		//we used to do this to put the close on the selector thread but if writes are held up it won't work
    	//registerForWritesOrClose();
    	
    	//notify clients outside the synchronization block!!!
		for(CompletableFuture<Channel> promise : promises) {
    		log.info("WRITES outstanding while close was called, notifying client through his failure method of the exception");
    		//we only incur the cost of Throwable.fillInStackTrace() if we will use this exception
    		//(it's called in the Throwable constructor) so we don't do this on every close channel
        	NioClosedChannelException closeExc = new NioClosedChannelException("There are "+promises.size()
        			+" writes that are not complete yet(you called write but "
        			+ "they did not call success back to the client).");
        	promise.completeExceptionally(closeExc);
		}
    }
    
    /**
     * This is the method where writes are added to the queue to be written later when the selector
     * fires and tells me we have room to write again.
     * 
     * @param id
     * @return true if the whole ByteBuffer was written, false if only part of it or none of it was written.
     * @throws IOException
     * @throws InterruptedException
     */
	private void queueTheWrite(WriteRunnable action) {
		List<CompletableFuture<Channel>> promisesToFail = null;
		String msg = null;
		boolean accepted = waitingWriters.add(action);
		if(!accepted) {
			throw new RuntimeException(this+"registered="+registered+" This should never occur");
		}
		
		long numBytesWaitingToBeWritten = 0;
		synchronized (this) {
			waitingBytesCounter += action.getBufferSize();
			numBytesWaitingToBeWritten = waitingBytesCounter;
			if(numBytesWaitingToBeWritten > maxBytesWaitingSize && !applyingBackpressure) {
				applyingBackpressure = true;
				listener.applyBackPressure(this);
			} else if(numBytesWaitingToBeWritten > 2*maxBytesWaitingSize && failOnNoBackPressure) {
				msg = numBytesWaitingToBeWritten + "(numBytesWaitingToBeWritten) > " 
						+ (4*maxBytesWaitingSize) + "(4*maxBytesWaitingSize)";
				log.info("FAILING all writes in the queue due to client not backpressuring. " + msg);
				promisesToFail = failAllWritesInQueue();			
			}

//			WriteRunnable lastVal = waitingWriters.peek();
//			if(lastVal != null) {
//				long currentTime = System.currentTimeMillis();
//				long delay = currentTime - lastVal.getCreationTime();
//				if(delay > writeTimeoutMs) {
//					msg = delay+"ms(write delay) >"+writeTimeoutMs+"ms(writeTimeout)";
//					log.info("FAILING all writes in the queue due to timeout. "+msg);
//					promisesToFail = failAllWritesInQueue();
//				} else if(numBytesWaitingToBeWritten > maxBytesWaitingSize) {
//					msg = numBytesWaitingToBeWritten+"(numBytesWaitingToBeWritten) > "
//							+maxBytesWaitingSize+"(maxBytesWaitingSize)";
//					log.info("FAILING all writes in the queue due to maxQueueSize. "+msg);
//					promisesToFail = failAllWritesInQueue();					
//				}
//			}
		}

		//MUST be outside the synchronization block to notify clients so we don't deadlock
		if(promisesToFail != null) {
			log.warn("FAILING all writes in the queue due to writes backing up(client is not backpressuring). "
					+ "sending clients the exception", new RuntimeException("clients not backpressuring"));
			for(CompletableFuture<Channel> promise : promisesToFail) {
	    		//we only incur the cost of Throwable.fillInStackTrace() if we will use this exception
	    		//(it's called in the Throwable constructor) so we don't do this on every close channel
				NioTimeoutException exc = new NioTimeoutException(
				"Writes are backing up meaning the\n"
				+ "client or server you are writing to is not keeping up(this fails all outstanding writes).  You\n"
				+ "probably need to throttle your app from writing downstream too fast. write queue size="
				+ promisesToFail.size()+"\nfailure reason="+msg+" To counter this, you should probably do a\n"
				+ "channel.unregisterForReads() on the channel that gives you data and throttle that side\n"
				+ "to not overload the client/server you are writing out to");
				promise.completeExceptionally(exc);
			}
			return;
		}
		
		registerForWritesOrClose();
	}
	
	//synchronized with writeAll as both try to go through every element in the queue
	//while most of the time there will be no contention(only on the close do we hit this)
	private synchronized List<CompletableFuture<Channel>> failAllWritesInQueue() {
		doNotAllowWrites = true;
		List<CompletableFuture<Channel>> copy = new ArrayList<>();
		while(!waitingWriters.isEmpty()) {
			WriteRunnable runnable = waitingWriters.remove();
			runnable.markBufferRead(); //in case clients releaseBuffer
			copy.add(runnable.getPromise());
		}
		
		waitingBytesCounter = 0;
		return copy;
	}

	private void registerForWritesOrClose() {
		//if not already registered, then register for writes.....
        //NOTE: we must do this after waitingWriters.offer so there is something on the queue to read
        //otherwise, that could be bad.
        if(!registered) {
            registered = true;
            if(log.isTraceEnabled())
                log.trace(this+"registering channel for write msg. size="+waitingWriters.size());
            getSelectorManager().registerSelectableChannel(this, SelectionKey.OP_WRITE, null, false);
        }
	}
       
    /**
     * This method is reading from the queue and writing out to the socket buffers that
     * did not get written out when client called write.
     *
     */
	 void writeAll() {
		List<CompletableFuture<Channel>> finishedPromises = new ArrayList<>();
		synchronized(this) {
	        if(waitingWriters.isEmpty())
	            return;
	
	        while(!waitingWriters.isEmpty()) {
	            WriteRunnable writer = waitingWriters.peek();
	            int size = writer.getBufferSize();
	            boolean finished = writer.runDelayedAction();
	            if(!finished) {
	                if(log.isTraceEnabled())
	                    log.trace(this+"Did not write all of id="+writer);
	                int left = writer.getBufferSize();
	                int writtenOut = size - left;
	                waitingBytesCounter -= writtenOut;
	                break;
	            }
	            
	            //if it finished, remove the item from the queue.  It
	            //does not need to be run again.
	            waitingWriters.poll();
	            //release bytebuffer back to pool
	            pool.releaseBuffer(writer.getBuffer());
	            waitingBytesCounter -= size;
	            finishedPromises.add(writer.getPromise());
	        }
	        
	        if(waitingWriters.isEmpty() && applyingBackpressure) {
	        	listener.releaseBackPressure(this);
	        	applyingBackpressure = false;
	        }
		}
		
        //MAKE SURE to notify clients outside of synchronization block so no deadlocks with their locks
        for(CompletableFuture<Channel> promise : finishedPromises) {
        	promise.complete(this);
        }
        
        if(waitingWriters.isEmpty()) {
        	if(log.isTraceEnabled())
        		log.trace(this+"unregister writes");
            registered = false;
            Helper.unregisterSelectableChannel(this, SelectionKey.OP_WRITE); 
        }
    }

    public void bind(SocketAddress addr) {
        if(!(addr instanceof InetSocketAddress))
            throw new IllegalArgumentException(this+"Can only bind to InetSocketAddress addressses");
        if(apiLog.isTraceEnabled())
        	apiLog.trace(this+"Basic.bind called addr="+addr);
        
        try {
			bindImpl(addr);
		} catch (IOException e) {
			throw new NioException(e);
		}
    }
    
    private void bindImpl(SocketAddress addr) throws IOException {
        try {
            bindImpl2(addr);
        } catch(Error e) {
            //NOTE:  jdk was throwing Error instead of BindException.  We fix
            //this and throw BindException which is the logical choice!!
            //We are crossing our fingers hoping there are not other SocketExceptions
            //from things other than address already in use!!!
            if(e.getCause() instanceof SocketException) {
                BindException exc = new BindException(e.getMessage());
                exc.initCause(e.getCause());
                throw exc;
            }
            throw e;
        }        
    }
    
    /**
     * 
     * @param addr
     * @throws IOException
     */
    protected abstract void bindImpl2(SocketAddress addr) throws IOException;
    
	public void registerForReads() {
		if(listener == null)
			throw new IllegalArgumentException(this+"listener cannot be null");
		else if(!isConnecting && !isConnected()) {
			throw new IllegalStateException(this+"Must call one of the connect methods first(ie. connect THEN register for reads)");
		} else if(isClosed())
			throw new IllegalStateException("Channel is closed");

		if(apiLog.isTraceEnabled())
			apiLog.trace(this+"Basic.registerForReads called");
		
        try {
			getSelectorManager().registerChannelForRead(this, listener);
			isRegisterdForReads = true;
		} catch (IOException e) {
			throw new NioException(e);
		} catch (InterruptedException e) {
			throw new NioException(e);
		}
	}
	
	public void unregisterForReads() {
		if(apiLog.isTraceEnabled())
			apiLog.trace(this+"Basic.unregisterForReads called");		
		try {
			isRegisterdForReads = false;
			getSelectorManager().unregisterChannelForRead(this);
		} catch (IOException e) {
			throw new NioException(e);
		} catch (InterruptedException e) {
			throw new NioException(e);
		}
	}	

	@Override
	public CompletableFuture<Channel> write(ByteBuffer b) {
		if(!getSelectorManager().isRunning())
			throw new IllegalStateException(this+"ChannelManager must be running and is stopped");		
		else if(isClosed) {
			AsynchronousCloseException exc = new AsynchronousCloseException();
			throw new NioException(this+"Client cannot write after the client closed the socket", exc);
		}
		CompletableFuture<Channel> impl = new CompletableFuture<Channel>();
		
		if(apiLog.isTraceEnabled())
			apiLog.trace(this+"Basic.write");
		
		if(doNotAllowWrites) {
			throw new IllegalStateException("This channel is in a failed state.  "
					+ "failure functions were called so look for exceptions from them");
		}
		
		int totalToWriteOut = b.remaining();
		int written = writeImpl(b);
		if(written != totalToWriteOut) {
			if(b.remaining() + written != totalToWriteOut) {
				throw new IllegalStateException("Something went wrong.  b.remaining()="+b.remaining()+" written="+written+" total="+totalToWriteOut);
			}
			WriteRunnable holder = new WriteRunnable(this, b, impl, System.currentTimeMillis());
			queueTheWrite(holder);
	        if(log.isTraceEnabled()) {
	        	log.trace(this+"sent write to queue");
	       	}
		} else {
			pool.releaseBuffer(b);
			impl.complete(this);
			if(log.isTraceEnabled())
				log.trace(this+" wrote bytes on client thread");
		}

        return impl;
	}
           
	protected void setConnecting(boolean b) {
		isConnecting = b;
	}

	protected boolean isConnecting() {
		return isConnecting;
	}

	protected void setClosed(boolean b) {
		isClosed = b;
	}
    
    @Override
    public CompletableFuture<Channel> close() {
        //To prevent the following exception, in the readImpl method, we
        //check if the socket is already closed, and if it is we don't read
        //and just return -1 to indicate socket closed.
    	CompletableFuture<Channel> future = new CompletableFuture<>();
    	try {
    		if(apiLog.isTraceEnabled())
    			apiLog.trace(this+"Basic.close called");
    		
	        if(!getRealChannel().isOpen()) {
	        	future.complete(this);
	        	return future;
	        }
	        
	        setClosed(true);
	        CloseRunnable runnable = new CloseRunnable(this, future);
	        unqueueAndFailWritesThenClose(runnable);
        } catch(Exception e) {
            log.warn(this+"Exception closing channel", e);
            future.completeExceptionally(e);
        }
    	return future;
    }
    
    public void closeOnSelectorThread() throws IOException {
    	setClosed(true);
    	closeImpl();
    }
    protected abstract void closeImpl() throws IOException;

    public ChannelSession getSession() {
    	return session;
    }

	@Override
	public void setWriteTimeoutMs(int timeout) {
		this.writeTimeoutMs = timeout;
	}

	@Override
	public int getWriteTimeoutMs() {
		return writeTimeoutMs;
	}   
    
	@Override
	public void setMaxBytesWriteBackupSize(int maxQueueSize) {
		this.maxBytesWaitingSize = maxQueueSize;
	}
	
	@Override
	public int getMaxBytesBackupSize() {
		return maxBytesWaitingSize;
	}

	public boolean isFailOnNoBackPressure() {
		return failOnNoBackPressure;
	}

	public void setFailOnNoBackPressure(boolean failOnNoBackPressure) {
		this.failOnNoBackPressure = failOnNoBackPressure;
	}

	public boolean isRegisteredForReads() {
		return isRegisterdForReads;
	}
	
}
