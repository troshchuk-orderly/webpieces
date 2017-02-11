package com.webpieces.http2engine.impl.client;

import java.util.concurrent.CompletableFuture;

import javax.xml.bind.DatatypeConverter;

import org.webpieces.data.api.DataWrapper;
import org.webpieces.data.api.DataWrapperGenerator;
import org.webpieces.data.api.DataWrapperGeneratorFactory;
import org.webpieces.util.logging.Logger;
import org.webpieces.util.logging.LoggerFactory;

import com.webpieces.hpack.api.HpackParser;
import com.webpieces.hpack.api.dto.Http2Headers;
import com.webpieces.http2engine.api.StreamWriter;
import com.webpieces.http2engine.api.client.ClientEngineListener;
import com.webpieces.http2engine.api.client.Http2ClientEngine;
import com.webpieces.http2engine.api.client.Http2Config;
import com.webpieces.http2engine.api.client.Http2ResponseListener;
import com.webpieces.http2engine.impl.shared.HeaderSettings;
import com.webpieces.http2engine.impl.shared.Level2ParsingAndRemoteSettings;
import com.webpieces.http2engine.impl.shared.Level5LocalFlowControl;
import com.webpieces.http2engine.impl.shared.Level5RemoteFlowControl;
import com.webpieces.http2engine.impl.shared.Level6MarshalAndPing;
import com.webpieces.http2engine.impl.shared.StreamState;

public class Level1ClientEngine implements Http2ClientEngine {
	
	private static final Logger log = LoggerFactory.getLogger(Level1ClientEngine.class);
	private static final DataWrapperGenerator dataGen = DataWrapperGeneratorFactory.createDataWrapperGenerator();
	private static final byte[] preface = DatatypeConverter.parseHexBinary("505249202a20485454502f322e300d0a0d0a534d0d0a0d0a");

	private Level2ParsingAndRemoteSettings parsing;
	private Level3ClientStreams streamInit;
	private Level7NotifyListeners finalLayer;
	private Level6MarshalAndPing marshalLayer;

	public Level1ClientEngine(Http2Config config, HpackParser parser, ClientEngineListener clientEngineListener) {
		HeaderSettings remoteSettings = new HeaderSettings();
		HeaderSettings localSettings = config.getLocalSettings();

		//all state(memory) we need to clean up is in here or is the engine itself.  To release RAM,
		//we have to release items in the map inside this or release the engine
		StreamState streamState = new StreamState();
		
		finalLayer = new Level7NotifyListeners(clientEngineListener);
		marshalLayer = new Level6MarshalAndPing(parser, remoteSettings, finalLayer);
		Level5RemoteFlowControl remoteFlowCtrl = new Level5RemoteFlowControl(streamState, marshalLayer, remoteSettings);
		Level5LocalFlowControl localFlowCtrl = new Level5LocalFlowControl(marshalLayer, finalLayer, localSettings);
		Level4ClientStateMachine clientSm = new Level4ClientStateMachine(config.getId(), remoteFlowCtrl, localFlowCtrl);
		streamInit = new Level3ClientStreams(streamState, clientSm, remoteFlowCtrl, config, remoteSettings);
		parsing = new Level2ParsingAndRemoteSettings(streamInit, remoteFlowCtrl, marshalLayer, parser, config, remoteSettings);
	}

	@Override
	public CompletableFuture<Void> sendInitializationToSocket() {
		log.info("sending preface");
		DataWrapper prefaceData = dataGen.wrapByteArray(preface);
		finalLayer.sendPreface(prefaceData);

		return parsing.sendSettings();
	}
	
	@Override
	public CompletableFuture<Void> sendPing() {
		return marshalLayer.sendPing();
	}
	
	@Override
	public CompletableFuture<StreamWriter> sendFrameToSocket(Http2Headers headers, Http2ResponseListener responseListener) {
		int streamId = headers.getStreamId();
		if(streamId <= 0)
			throw new IllegalArgumentException("frames for requests must have a streamId > 0");
		else if(streamId % 2 == 0)
			throw new IllegalArgumentException("Client cannot send frames with even stream ids to server per http/2 spec");
		
		return streamInit.createStreamAndSend(headers, responseListener);
	}

	@Override
	public void parse(DataWrapper newData) {
		parsing.parse(newData);
	}

	@Override
	public void farEndClosed() {
		marshalLayer.farEndClosed();
	}

	@Override
	public void initiateClose(String reason) {
		
	}
}