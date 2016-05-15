package org.webpieces.httpproxy.api;

import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.List;

import org.webpieces.asyncserver.api.AsyncServer;
import org.webpieces.asyncserver.api.AsyncServerManager;
import org.webpieces.nio.api.SSLEngineFactory;
import org.webpieces.nio.api.handlers.DataListener;

public class MockAsyncServerManager implements AsyncServerManager {

	private List<DataListener> serverListeners = new ArrayList<>();


	public List<DataListener> getServerListeners() {
		return serverListeners;
	}

	@Override
	public AsyncServer createTcpServer(String id, SocketAddress addr, DataListener listener) {
		serverListeners.add(listener);
		return null;
	}

	@Override
	public AsyncServer createTcpServer(String id, SocketAddress addr, DataListener listener,
			SSLEngineFactory sslFactory) {
		serverListeners.add(listener);
		return null;
	}

}