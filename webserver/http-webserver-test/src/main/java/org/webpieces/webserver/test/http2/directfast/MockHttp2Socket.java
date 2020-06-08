package org.webpieces.webserver.test.http2.directfast;

import java.net.InetSocketAddress;
import java.util.concurrent.CompletableFuture;

import org.webpieces.frontend2.api.HttpStream;
import org.webpieces.frontend2.api.StreamListener;
import org.webpieces.http2client.api.Http2Socket;
import org.webpieces.http2client.api.dto.FullRequest;
import org.webpieces.http2client.api.dto.FullResponse;
import org.webpieces.http2client.impl.ResponseCacher;

import com.webpieces.http2.api.streaming.RequestStreamHandle;

public class MockHttp2Socket implements Http2Socket {

	private boolean isHttps;
	private StreamListener streamListener;
	private MockFrontendSocket frontendSocket = new MockFrontendSocket();

	public MockHttp2Socket(StreamListener streamListener, boolean isHttps) {
		this.streamListener = streamListener;
		this.isHttps = isHttps;
	}

	@Override
	public CompletableFuture<Void> connect(InetSocketAddress addr) {
		return CompletableFuture.completedFuture(null); //pretend we connected
	}

	@Override
	public CompletableFuture<FullResponse> send(FullRequest request) {
		return new ResponseCacher(() -> openStream()).run(request);
	}

	@Override
	public RequestStreamHandle openStream() {
		HttpStream stream = streamListener.openStream(frontendSocket);
		return new ProxyRequestStreamHandle(stream, frontendSocket);
	}

	@Override
	public CompletableFuture<Void> close() {
		streamListener.fireIsClosed(frontendSocket);
		return CompletableFuture.completedFuture(null);
	}

	@Override
	public CompletableFuture<Void> sendPing() {
		throw new UnsupportedOperationException("not supported");
	}

}