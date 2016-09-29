package com.webpieces.http2parser.api.dto;

import org.webpieces.data.api.DataWrapper;

import java.nio.ByteBuffer;

public class Http2RstStream extends Http2Frame {
    public Http2FrameType getFrameType() {
        return Http2FrameType.RST_STREAM;
    }

    /* flags */
    public void unmarshalFlags(byte flags) {
    }

    /* payload */
    private Http2ErrorCode errorCode; //32 bits

    public Http2ErrorCode getErrorCode() {
        return errorCode;
    }

    public void setErrorCode(Http2ErrorCode errorCode) {
        this.errorCode = errorCode;
    }

    public void unmarshalPayload(DataWrapper payload) {
        ByteBuffer payloadByteBuffer = ByteBuffer.wrap(payload.createByteArray());
        errorCode = Http2ErrorCode.fromInteger(payloadByteBuffer.getInt());
    }

}
