package com.webpieces.http2parser.impl.marshallers;

import java.nio.ByteBuffer;

import org.webpieces.data.api.BufferPool;
import org.webpieces.data.api.DataWrapper;
import org.webpieces.data.api.DataWrapperGenerator;

import com.webpieces.http2parser.api.Http2ParseException;
import com.webpieces.http2parser.api.dto.RstStreamFrame;
import com.webpieces.http2parser.api.dto.lib.AbstractHttp2Frame;
import com.webpieces.http2parser.api.dto.lib.Http2ErrorCode;
import com.webpieces.http2parser.api.dto.lib.Http2Frame;
import com.webpieces.http2parser.impl.FrameHeaderData;
import com.webpieces.http2parser.impl.Http2MementoImpl;

public class RstStreamMarshaller extends AbstractFrameMarshaller implements FrameMarshaller {
	public RstStreamMarshaller(BufferPool bufferPool, DataWrapperGenerator dataGen) {
		super(bufferPool);
	}

	@Override
	public DataWrapper marshal(Http2Frame frame) {
		RstStreamFrame castFrame = (RstStreamFrame) frame;

		ByteBuffer payload = bufferPool.nextBuffer(4);
		payload.putInt(castFrame.getErrorCode().getCode());
		payload.flip();

		DataWrapper dataPayload = dataGen.wrapByteBuffer(payload);
		return super.marshalFrame(frame, (byte) 0, dataPayload);
	}

	@Override
	public AbstractHttp2Frame unmarshal(Http2MementoImpl state, DataWrapper framePayloadData) {
		FrameHeaderData frameHeaderData = state.getFrameHeaderData();
		int streamId = frameHeaderData.getStreamId();
		if(state.getFrameHeaderData().getPayloadLength() != 4)
			throw new Http2ParseException(Http2ErrorCode.FRAME_SIZE_ERROR, streamId, true);
		//TODO: Verify this, previous code looks like connectionlevel = false but shouldn't this be true
		
		RstStreamFrame frame = new RstStreamFrame();
		super.unmarshalFrame(state, frame);

		ByteBuffer payloadByteBuffer = bufferPool.createWithDataWrapper(framePayloadData);
		frame.setErrorCode(Http2ErrorCode.fromInteger(payloadByteBuffer.getInt()));

		bufferPool.releaseBuffer(payloadByteBuffer);

		return frame;
	}

}