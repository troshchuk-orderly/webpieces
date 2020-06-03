package org.webpieces.httpclient.mocks;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.webpieces.httpclient11.api.HttpDataWriter;
import org.webpieces.httpclient11.api.HttpResponseListener;
import org.webpieces.httpparser.api.dto.HttpResponse;

public class MockResponseListener implements HttpResponseListener {
	private List<Throwable> failures = new ArrayList<Throwable>();
	private boolean isClosed;

	@Override
	public CompletableFuture<HttpDataWriter> incomingResponse(HttpResponse resp, boolean isComplete) {
		return null;
	}

	@Override
	public void failure(Throwable e) {
		failures.add(e);
	}

	public Throwable getSingleFailure() {
		if(failures.size() != 1)
			throw new IllegalStateException("There was '"+failures.size()+"' not exactly 1 failure found");
		return failures.get(0);
	}

	@Override
	public void socketClosed() {
		isClosed = true;
	}

	public boolean isClosed() {
		return isClosed;
	}

}
