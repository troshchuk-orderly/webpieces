package org.webpieces.nio.impl.cm.basic.udp;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.PortUnreachableException;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectableChannel;
import java.util.Calendar;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.webpieces.nio.api.channels.Channel;
import org.webpieces.nio.api.channels.UDPChannel;
import org.webpieces.nio.api.exceptions.FailureInfo;
import org.webpieces.nio.api.exceptions.NioException;
import org.webpieces.nio.impl.cm.basic.BasChannelImpl;
import org.webpieces.nio.impl.cm.basic.IdObject;
import org.webpieces.nio.impl.cm.basic.SelectorManager2;
import org.webpieces.util.futures.Future;
import org.webpieces.util.futures.PromiseImpl;


public class UDPChannelImpl extends BasChannelImpl implements UDPChannel {

	private static final Logger log = Logger.getLogger(UDPChannel.class.getName());
	private static final Logger apiLog = Logger.getLogger(UDPChannel.class.getName());
	private DatagramChannel channel;
	private boolean isConnected = false;
    private Calendar expires;
    
	public UDPChannelImpl(IdObject id, SelectorManager2 selMgr) {
		super(id, selMgr);
		try {
			channel = DatagramChannel.open();
			channel.configureBlocking(false);
	        channel.socket().setReuseAddress(true);
		} catch(IOException e) {
			throw new NioException(e);
		}
	}

	public void bindImpl2(SocketAddress addr) throws IOException {
        channel.socket().bind(addr);
	}

	@Override
	public Future<Channel, FailureInfo> connect(SocketAddress addr) {
		return connectImpl(addr);
	}
	
	private synchronized Future<Channel, FailureInfo> connectImpl(SocketAddress addr) {
		PromiseImpl<Channel, FailureInfo> promise = new PromiseImpl<>();
		
		try {
			if(apiLog.isLoggable(Level.FINE))
				apiLog.fine(this+"Basic.connect called-addr="+addr);
			
			channel.connect(addr);
			
	        isConnected = true;
	        promise.setResult(this);
		} catch(Exception e) {
			promise.setFailure(new FailureInfo(this, e));
		}
		
        return promise;
	}
    
    public synchronized void disconnect() {
		if(apiLog.isLoggable(Level.FINE))
			apiLog.fine(this+"Basic.disconnect called");
		
		try {
			isConnected = false;        
			channel.disconnect();
		} catch(IOException e) {
			throw new NioException(e);
		}
    }

    public void setReuseAddress(boolean b) {
		throw new UnsupportedOperationException("not implemented yet");
	}

	public boolean isBlocking() {
		return channel.isBlocking();
	}

	public void closeImpl() throws IOException {
		channel.close();
	}

	public boolean isClosed() {
		return channel.socket().isClosed();
	}

	public boolean isBound() {
		return channel.socket().isBound();
	}

	public InetSocketAddress getLocalAddress() {
		InetAddress addr = channel.socket().getLocalAddress();
		int port = channel.socket().getLocalPort();      
		return new InetSocketAddress(addr, port);
	}

	public InetSocketAddress getRemoteAddress() {
		return (InetSocketAddress)channel.socket().getRemoteSocketAddress();
	}
    
	public boolean isConnected() {
		return channel.isConnected();
	}

	@Override
	public SelectableChannel getRealChannel() {
		return channel;
	}
	
	@Override
	public int readImpl(ByteBuffer b) throws IOException {
		if(b == null)
			throw new IllegalArgumentException("Cannot use a null ByteBuffer");
		else if(!isConnected)
			throw new IllegalStateException("Currently not connected");
		try {
			return channel.read(b);
		} catch(PortUnreachableException e) {
			if(expires != null) {
				//ignore the event if we are not at expires yet
				if(Calendar.getInstance().before(expires)) {
					return 0;
				}
			}

			expires = Calendar.getInstance();
			expires.add(Calendar.SECOND, 10);
			log.warning("PortUnreachable.  NOTICE NOTICE:  We will ignore this exc again on this channel for 10 seconds");
			throw e;
		}
	}

	@Override
	protected int writeImpl(ByteBuffer b) throws IOException {
		return channel.write(b);
	}

}
