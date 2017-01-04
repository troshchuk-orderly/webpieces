package com.webpieces.http2parser2.impl;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.webpieces.data.api.BufferPool;
import org.webpieces.data.api.DataWrapper;
import org.webpieces.data.api.DataWrapperGenerator;
import org.webpieces.data.api.DataWrapperGeneratorFactory;

import com.webpieces.http2parser.api.Http2Memento;
import com.webpieces.http2parser.api.Http2Parser2;
import com.webpieces.http2parser.api.ParseException;
import com.webpieces.http2parser.api.dto.SettingsFrame;
import com.webpieces.http2parser.api.dto.UnknownFrame;
import com.webpieces.http2parser.api.dto.lib.AbstractHttp2Frame;
import com.webpieces.http2parser.api.dto.lib.Http2ErrorCode;
import com.webpieces.http2parser.api.dto.lib.Http2Frame;
import com.webpieces.http2parser.api.dto.lib.Http2FrameType;

public class Http2Parser2Impl implements Http2Parser2 {

    private final DataWrapperGenerator dataGen = DataWrapperGeneratorFactory.createDataWrapperGenerator();
    private final Map<Http2FrameType, FrameMarshaller> dtoToMarshaller = new HashMap<>();
	private BufferPool bufferPool;
	private SettingsMarshaller settingsMarshaller;

	public Http2Parser2Impl(BufferPool bufferPool) {
        this.bufferPool = bufferPool;
        
        settingsMarshaller = new SettingsMarshaller(bufferPool, dataGen);
		dtoToMarshaller.put(Http2FrameType.CONTINUATION, new ContinuationMarshaller(bufferPool, dataGen));
        dtoToMarshaller.put(Http2FrameType.DATA, new DataMarshaller(bufferPool, dataGen));
        dtoToMarshaller.put(Http2FrameType.GOAWAY, new GoAwayMarshaller(bufferPool, dataGen));
        dtoToMarshaller.put(Http2FrameType.HEADERS, new HeadersMarshaller(bufferPool, dataGen));
        dtoToMarshaller.put(Http2FrameType.PING, new PingMarshaller(bufferPool, dataGen));
        dtoToMarshaller.put(Http2FrameType.PRIORITY, new PriorityMarshaller(bufferPool, dataGen));
        dtoToMarshaller.put(Http2FrameType.PUSH_PROMISE, new PushPromiseMarshaller(bufferPool, dataGen));
        dtoToMarshaller.put(Http2FrameType.RST_STREAM, new RstStreamMarshaller(bufferPool, dataGen));
        dtoToMarshaller.put(Http2FrameType.SETTINGS, settingsMarshaller);
        dtoToMarshaller.put(Http2FrameType.WINDOW_UPDATE, new WindowUpdateMarshaller(bufferPool, dataGen));
	}

	@Override
	public Http2Memento prepareToParse() {
		return new Http2MementoImpl(dataGen.emptyWrapper());
	}

	@Override
	public Http2Memento parse(Http2Memento memento, DataWrapper newData, long maxFrameSize) {
		Http2MementoImpl state = (Http2MementoImpl) memento;
		state.getParsedMessages().clear();
		
		DataWrapper allData = dataGen.chainDataWrappers(state.getLeftOverData(), newData);
		state.setLeftOverData(allData);
		
		while(true) {
			switch(state.getParsingState()) {
			case NEED_PARSE_FRAME_HEADER:
				if(!parseFrameHeader(state, maxFrameSize))
					return state;
				else
					state.setParsingState(ParsingState.NEED_PARSE_BODY);
				break;
			case NEED_PARSE_BODY:
				if(!parseBody(state))
					return state;
				else
					state.setParsingState(ParsingState.NEED_PARSE_FRAME_HEADER);
		    	break;
			}
		}
		
	}

	private boolean parseBody(Http2MementoImpl state) {
    	DataWrapper allData = state.getLeftOverData();
    	FrameHeaderData headerData = state.getFrameHeaderData();
    	if(headerData == null)
    		throw new IllegalArgumentException("Bug, this should never be null at this point");

    	int payloadLen = headerData.getPayloadLength();
    	if(allData.getReadableSize() < payloadLen)
    		return false;
    	
    	List<? extends DataWrapper> split = dataGen.split(allData, payloadLen);
    	DataWrapper framePayloadData = split.get(0);

    	AbstractHttp2Frame frame;
		Optional<Http2FrameType> optFrameType = Http2FrameType.fromId(headerData.getFrameTypeId());
		if(optFrameType.isPresent()) {
			Http2FrameType frameType = optFrameType.get();
			FrameMarshaller marshaller = dtoToMarshaller.get(frameType);
			if(marshaller == null)
				throw new IllegalArgumentException("bug, our developer forgot to add marshaller and only added the enum="+frameType);
			frame = marshaller.unmarshal(state, framePayloadData);
    	} else {
    		frame = new UnknownFrame(
    				headerData.getFlagsByte(),
    				headerData.getFrameTypeId(),
    				headerData.getStreamId(),
    				framePayloadData);
    	}

    	state.setFrameHeaderData(null); //reset header data
    	state.setLeftOverData(split.get(1)); //set leftover data
    	state.addParsedFrame(frame);

		return true;
	}

	/**
	 * Return true if header was parsed
	 * @param maxFrameSize 
	 */
    private boolean parseFrameHeader(Http2MementoImpl state, long maxFrameSize) {
    	DataWrapper allData = state.getLeftOverData();
        int lengthOfData = allData.getReadableSize();
        if (lengthOfData < 9) {
            // Not even a frame header
        	state.setLeftOverData(allData);
        	return false;
        }
        
        List<? extends DataWrapper> split = dataGen.split(allData, 9);
        DataWrapper frameHeader = split.get(0);
        DataWrapper left = split.get(1);
        int payloadLength =  getLength(frameHeader);
        int streamId = getStreamId(frameHeader);
        byte frameTypeId = frameHeader.readByteAt(3);
        byte flagsByte = frameHeader.readByteAt(4);
        
        if(payloadLength > maxFrameSize) 
        	throw new ParseException(Http2ErrorCode.FRAME_SIZE_ERROR, streamId, "Frame size="+payloadLength+" was greater than max="+maxFrameSize, true);
        
        state.setFrameHeaderData(new FrameHeaderData(payloadLength, streamId, frameTypeId, flagsByte));
		state.setLeftOverData(left);

        return true;
	}

	private int getLength(DataWrapper data) {
        ByteBuffer headerByteBuffer = bufferPool.nextBuffer(9);
        headerByteBuffer.put(data.readBytesAt(0, 9));
        headerByteBuffer.flip();

        // Get 4 bytes and just drop the rightmost one.
        return headerByteBuffer.getInt() >>> 8;
    }

    private int getStreamId(DataWrapper data) {
        ByteBuffer streamIdBuffer = bufferPool.nextBuffer(4);
        streamIdBuffer.put(data.readBytesAt(5, 4));
        streamIdBuffer.flip();

        // Ignore the reserved bit
        return streamIdBuffer.getInt() & 0x7FFFFFFF;
    }
    
	@Override
	public DataWrapper marshal(Http2Frame frame) {
		Http2FrameType frameType = frame.getFrameType();
		FrameMarshaller marshaller = dtoToMarshaller.get(frameType);
		if(marshaller == null)
			throw new IllegalArgumentException("unknown frame bean="+frame);
		return marshaller.marshal(frame);
	}

	@Override
	public SettingsFrame unmarshalSettingsPayload(ByteBuffer settingsPayload) {
		return settingsMarshaller.unmarshalPayload(settingsPayload);
	}

}
