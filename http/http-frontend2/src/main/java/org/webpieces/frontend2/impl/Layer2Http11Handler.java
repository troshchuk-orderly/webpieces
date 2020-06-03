package org.webpieces.frontend2.impl;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.webpieces.data.api.DataWrapper;
import org.webpieces.data.api.DataWrapperGenerator;
import org.webpieces.data.api.DataWrapperGeneratorFactory;
import org.webpieces.frontend2.api.HttpStream;
import org.webpieces.frontend2.api.StreamListener;
import org.webpieces.http2translations.api.Http11ToHttp2;
import org.webpieces.httpparser.api.HttpParser;
import org.webpieces.httpparser.api.MarshalState;
import org.webpieces.httpparser.api.Memento;
import org.webpieces.httpparser.api.dto.HttpMessageType;
import org.webpieces.httpparser.api.dto.HttpPayload;
import org.webpieces.httpparser.api.dto.HttpRequest;
import org.webpieces.util.futures.FutureHelper;
import org.webpieces.util.locking.PermitQueue;

import com.webpieces.http2.api.dto.highlevel.Http2Request;
import com.webpieces.http2.api.dto.lowlevel.DataFrame;
import com.webpieces.http2.api.dto.lowlevel.lib.Http2Msg;
import com.webpieces.http2.api.streaming.StreamRef;
import com.webpieces.http2.api.streaming.StreamWriter;

public class Layer2Http11Handler {
	private static final Logger log = LoggerFactory.getLogger(Layer2Http11Handler.class);
	private static final DataWrapperGenerator dataGen = DataWrapperGeneratorFactory.createDataWrapperGenerator();
	private HttpParser httpParser;
	private StreamListener httpListener;
	private FutureHelper futureUtil = new FutureHelper();
	private AtomicInteger counter = new AtomicInteger(1);

	public Layer2Http11Handler(HttpParser httpParser, StreamListener httpListener) {
		this.httpParser = httpParser;
		this.httpListener = httpListener;
	}

	public CompletableFuture<InitiationResult> initialData(FrontendSocketImpl socket, Consumer<ProtocolType> function, ByteBuffer buf) {
		return initialDataImpl(socket, function, buf);
	}
	
	public CompletableFuture<InitiationResult> initialDataImpl(FrontendSocketImpl socket, Consumer<ProtocolType> function, ByteBuffer buf) {
		
		Memento state = socket.getHttp11ParseState();
		int newDataSize = buf.remaining();
		state = parse(socket, buf);
		int numBytesRead = state.getNumBytesJustParsed();
		
		//IF we are receiving a preface, there will ONLY be ONE message AND leftover data
		InitiationResult result = checkForPreface(socket, state);
		
		if(result != null) {
			return CompletableFuture.completedFuture(result);
		}

		//TODO: check for EXACTLY ONE http request AND check if it is an h2c header with Http-Settings header!!!!
		//if so, return that initiation result and start using the http2 code
		
		//if we get this far, we now know we are http1.1
		if(state.getParsedMessages().size() > 0) {
			function.accept(ProtocolType.HTTP1_1);
			
			CompletableFuture<Void> fut = processWithBackpressure(socket, newDataSize, numBytesRead);
			
			return fut.thenApply(s -> {
				return new InitiationResult(InitiationStatus.HTTP1_1);				
			});
		}
		
		return CompletableFuture.completedFuture(null);
	}

	private InitiationResult checkForPreface(FrontendSocketImpl socket, Memento state) {
		if(state.getParsedMessages().size() != 1)
			return null;
		if(state.getParsedMessages().get(0).getMessageType() != HttpMessageType.HTTP2_MARKER_MSG)
			return null;

		//release memory associated with 1.1 parser for this socket
		socket.setHttp1_1ParseState(null, null);
		
		return new InitiationResult(state.getLeftOverData(), InitiationStatus.PREFACE);
	}

	public CompletableFuture<Void> incomingData(FrontendSocketImpl socket, ByteBuffer buf) {
		
		Http11StreamImpl currentStream = socket.getCurrentStream();
		if(currentStream != null && currentStream.isForConnectRequeest()) {
			//This is for doing an http proxy that upgrades to an SSL proxy and can't see traffic
			//going through itself.  it just passes traffic through
			DataFrame dataFrame = new DataFrame();
			DataWrapper wrapper = dataGen.wrapByteBuffer(buf);
			dataFrame.setData(wrapper);
			
			CompletableFuture<StreamWriter> writer = currentStream.getStreamRef().getWriter();
			
			//We skip permit queue because this is chunking now in SSL that we can't read;
			return writer.thenCompose(w -> w.processPiece(dataFrame));
		}
		
		Memento state = socket.getHttp11ParseState();
		int newDataSize = buf.remaining();
		state = parse(socket, buf);
		
		return processWithBackpressure(socket, newDataSize, state.getNumBytesJustParsed()).exceptionally(t -> {
			log.error("Exception", t);
			socket.close("Exception so closing http1.1 socket="+t.getMessage());
			return null;
		});
	}
	
	public CompletableFuture<Void> processWithBackpressure(
			FrontendSocketImpl socket, int newDataSize, int numBytesRead) {
		
		Memento state = socket.getHttp11ParseState();
		List<HttpPayload> parsed = state.getParsedMessages();
		
		CompletableFuture<Void> future = CompletableFuture.completedFuture(null);
		for(HttpPayload payload : parsed) {
			CompletableFuture<Void> fut = processCorrectly(socket, payload);
			
			//done afterwards sooo, the loop will SLAM through the payloads gathering up the
			//futures and chaining them.  IF you chain them above, then each processCorrectly call
			//has to wait for previous future to resolve
			future = future.thenCompose(s -> fut);
		}
		
		return future;
	}

	private Memento parse(FrontendSocketImpl socket, ByteBuffer buf) {
		DataWrapper moreData = dataGen.wrapByteBuffer(buf);
		Memento state = socket.getHttp11ParseState();
		state = httpParser.parse(state, moreData);
		return state;
	}
	
	private CompletableFuture<Void> processCorrectly(FrontendSocketImpl socket, HttpPayload payload) {
		Http2Msg msg = Http11ToHttp2.translate(payload, socket.isForServingHttpsPages());

		if(payload instanceof HttpRequest) {
			return processInitialPieceOfRequest(socket, (HttpRequest) payload, (Http2Request)msg);
		} else if(msg instanceof DataFrame) {
			return processData(socket, (DataFrame)msg);
		} else {
			throw new IllegalArgumentException("payload not supported="+payload);
		}
	}

	private CompletableFuture<Void> processData(FrontendSocketImpl socket, DataFrame msg) {
		PermitQueue permitQueue = socket.getPermitQueue();
		return permitQueue.runRequest(() -> {
			
			Http11StreamImpl stream = socket.getCurrentStream();
			
			if(stream == null) {
				//This situation occurs if we respond before the request finishes sending.
				//stream goes null once we respond on the socket
				permitQueue.releasePermit();
				return CompletableFuture.completedFuture(null);
			}
			
			if(msg.isEndOfStream())
				stream.setSentFullRequest(true);

			return futureUtil.finallyBlock(
					() -> processPiece(stream, msg),
					() -> possiblyReleaseeQueue(msg, permitQueue)
			);

		});

	}
	
	public CompletableFuture<Void> processPiece(Http11StreamImpl stream, DataFrame msg) {
		CompletableFuture<StreamWriter> writer = stream.getStreamRef().getWriter();

		return writer.thenCompose(w -> w.processPiece(msg));
	}

	private void possiblyReleaseeQueue(DataFrame msg, PermitQueue permitQueue) {
		//since this is NOT end of stream, release permit for next data to come in
		if(!msg.isEndOfStream())
			permitQueue.releasePermit();
		//else we need to wait for FULL response to be sent back and then release
		//the permit

	}

	private CompletableFuture<Void> processInitialPieceOfRequest(FrontendSocketImpl socket, HttpRequest http1Req, Http2Request headers) {
		int id = counter.getAndAdd(2);
		
		PermitQueue permitQueue = socket.getPermitQueue();
		return permitQueue.runRequest(() -> {
			Http11StreamImpl currentStream = new Http11StreamImpl(id, socket, httpParser, permitQueue, http1Req, headers);

			HttpStream streamHandle = httpListener.openStream(socket);
			currentStream.setStreamHandle(streamHandle);
			socket.setCurrentStream(currentStream);

			if(!headers.isEndOfStream()) {

				//in this case, we are NOT at the end of the request so we must let the next piece of
				//data run right after the request
				//TODO(dhiller): Replace this section with futureUtil.trySuccessFinally
				StreamRef streamRef = streamHandle.incomingRequest(headers, currentStream);
				currentStream.setStreamRef(streamRef);
				return streamRef.getWriter().thenApply( w -> {
					
					//must release the permit so the next data piece(which may be cached) can come in
					permitQueue.releasePermit();
					return null;
				});
				
			} else {
				//in this case, since this is the END of the request, we cannot release the permit in the
				//permit queue as we do not want to let the next request to start until the full response is
				//sent back to the client
				currentStream.setSentFullRequest(true);
	
				StreamRef streamRef = streamHandle.incomingRequest(headers, currentStream);
				currentStream.setStreamRef(streamRef);
				return streamRef.getWriter().thenApply(w -> null);
			}
		});
	}

	public void socketOpened(FrontendSocketImpl socket, boolean isReadyForWrites) {
		Memento parseState = httpParser.prepareToParse();
		MarshalState marshalState = httpParser.prepareToMarshal();
		socket.setHttp1_1ParseState(parseState, marshalState);
		//timeoutListener.connectionOpened(socket, isReadyForWrites);
	}

	public void farEndClosed(FrontendSocketImpl socket) {
		httpListener.fireIsClosed(socket);
		socket.farEndClosed(httpListener);
	}

}
