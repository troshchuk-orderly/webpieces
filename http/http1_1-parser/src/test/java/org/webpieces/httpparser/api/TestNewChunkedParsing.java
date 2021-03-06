package org.webpieces.httpparser.api;

import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Assert;
import org.junit.Test;
import org.webpieces.data.api.TwoPools;
import org.webpieces.data.api.DataWrapper;
import org.webpieces.data.api.DataWrapperGenerator;
import org.webpieces.data.api.DataWrapperGeneratorFactory;
import org.webpieces.httpparser.api.common.Header;
import org.webpieces.httpparser.api.common.KnownHeaderName;
import org.webpieces.httpparser.api.dto.HttpChunk;
import org.webpieces.httpparser.api.dto.HttpChunkExtension;
import org.webpieces.httpparser.api.dto.HttpData;
import org.webpieces.httpparser.api.dto.HttpLastChunk;
import org.webpieces.httpparser.api.dto.HttpPayload;
import org.webpieces.httpparser.api.dto.HttpResponse;
import org.webpieces.httpparser.api.dto.HttpResponseStatus;
import org.webpieces.httpparser.api.dto.HttpResponseStatusLine;
import org.webpieces.httpparser.api.dto.KnownStatusCode;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

public class TestNewChunkedParsing {
	
	private HttpParser parser = HttpParserFactory.createParser("", new SimpleMeterRegistry(), new TwoPools("pl", new SimpleMeterRegistry()));
	private DataWrapperGenerator dataGen = DataWrapperGeneratorFactory.createDataWrapperGenerator();
	private MarshalState state = parser.prepareToMarshal();
	
	@Test
	public void testEqualsHashChunkExtension() {
		//a dumb test on code to increase code coverage only as this test doesn't test much
		HttpChunkExtension c = new HttpChunkExtension("hi", "there");
		HttpChunkExtension c2 = new HttpChunkExtension("hi", "there");
		HttpChunkExtension c3 = new HttpChunkExtension("hi", "not");

		Map<HttpChunkExtension, String> keyVal = new HashMap<>();
		keyVal.put(c, "result");
		String value = keyVal.get(c2);
		Assert.assertEquals("result", value);
		
		Assert.assertFalse(c.equals(c3));
	}
	
	@Test
	public void testHex() {
		for(int i = 0; i < 50; i++) {
			String string = Integer.toHexString(i);
			int val = Integer.parseInt(string, 16);
			Assert.assertEquals(i, val);
		}
	}
	
	private byte[] unwrap(ByteBuffer buffer) {
		byte[] data = new byte[buffer.remaining()];
		buffer.get(data);
		return data;
	}
	
	@Test
	public void testBasic() {
		String chunkedData = "4\r\nWiki\r\n5\r\npedia\r\nE\r\n in\r\n\r\nchunks.\r\n0\r\n\r\n";
		
		HttpResponse resp = TestResponseParsing.createOkResponse();
		resp.addHeader(new Header(KnownHeaderName.TRANSFER_ENCODING, "chunked"));
		
		byte[] bytes = unwrap(parser.marshalToByteBuffer(state, resp));
		byte[] chunked = chunkedData.getBytes();
		
		byte[] all = new byte[bytes.length+chunked.length];
		System.arraycopy(bytes, 0, all, 0, bytes.length);
		System.arraycopy(chunked, 0, all, bytes.length, chunked.length);
		
		DataWrapper wrapper = dataGen.wrapByteArray(all);
		Memento memento = parser.prepareToParse();
		memento = parser.parse(memento, wrapper);
		
		List<HttpPayload> msgs = memento.getParsedMessages();
		Assert.assertEquals(5, msgs.size());
		
		HttpPayload msg = msgs.get(0).getHttpResponse();
		Assert.assertEquals(resp, msg);
		
		HttpData chunk1 = msgs.get(1).getHttpData();
		String first = chunk1.getBody().createStringFrom(0, chunk1.getBody().getReadableSize(), Charset.defaultCharset());
		Assert.assertEquals("Wiki", first);
		
		HttpData chunk3 = msgs.get(3).getHttpData();
		String third = chunk3.getBody().createStringFrom(0, chunk3.getBody().getReadableSize(), Charset.defaultCharset());
		Assert.assertEquals(" in\r\n\r\nchunks.", third);
	}

	@Test
	public void testResponseAfterChunked() {
		String chunkedData = "4\r\nWiki\r\n5\r\npedia\r\nE\r\n in\r\n\r\nchunks.\r\n0\r\n\r\n";
		
		HttpResponse resp = TestResponseParsing.createOkResponse();
		resp.addHeader(new Header(KnownHeaderName.TRANSFER_ENCODING, "chunked"));
		
		byte[] bytes = unwrap(parser.marshalToByteBuffer(state, resp));
		byte[] chunked = chunkedData.getBytes();
		HttpResponse resp400 = create400Response();
		byte[] tail = unwrap(parser.marshalToByteBuffer(state, resp400));
		
		byte[] all = new byte[bytes.length+chunked.length+tail.length];
		System.arraycopy(bytes, 0, all, 0, bytes.length);
		System.arraycopy(chunked, 0, all, bytes.length, chunked.length);
		System.arraycopy(tail, 0, all, bytes.length+chunked.length, tail.length);
		
		DataWrapper wrapper = dataGen.wrapByteArray(all);
		Memento memento = parser.prepareToParse();
		memento = parser.parse(memento, wrapper);
		
		List<HttpPayload> msgs = memento.getParsedMessages();
		Assert.assertEquals(6, msgs.size());
		
		HttpPayload msg = msgs.get(0).getHttpResponse();
		Assert.assertEquals(resp, msg);
		
		HttpData chunk1 = msgs.get(1).getHttpData();
		String first = chunk1.getBody().createStringFrom(0, chunk1.getBody().getReadableSize(), Charset.defaultCharset());
		Assert.assertEquals("Wiki", first);
		
		HttpData chunk3 = msgs.get(3).getHttpData();
		String third = chunk3.getBody().createStringFrom(0, chunk3.getBody().getReadableSize(), Charset.defaultCharset());
		Assert.assertEquals(" in\r\n\r\nchunks.", third);
		
		HttpPayload tailMsg = msgs.get(5);
		Assert.assertEquals(resp400, tailMsg);
	}
	
	@Test
	public void testSplitChunkBodyAndResponseAfter() {
		String chunkedData = "1E\r\n012345678901234567890123456789\r\n7\r\nchunks.\r\n0\r\n\r\n";
		
		HttpResponse resp = TestResponseParsing.createOkResponse();
		resp.addHeader(new Header(KnownHeaderName.TRANSFER_ENCODING, "chunked"));
		
		byte[] bytes = unwrap(parser.marshalToByteBuffer(state, resp));
		byte[] chunked = chunkedData.getBytes();
		HttpResponse resp400 = create400Response();
		byte[] tail = unwrap(parser.marshalToByteBuffer(state, resp400));
		
		int lengthOfChunked1stHalf = 15;
		int lengthOfChunked2ndHalf = chunked.length - 15;
		byte[] firstPiece = new byte[bytes.length+lengthOfChunked1stHalf];
		byte[] secondPiece = new byte[chunked.length-lengthOfChunked1stHalf+tail.length];
		
		System.arraycopy(bytes, 0, firstPiece, 0, bytes.length);
		System.arraycopy(chunked, 0, firstPiece, bytes.length, lengthOfChunked1stHalf);
		
		System.arraycopy(chunked, lengthOfChunked1stHalf, secondPiece, 0, lengthOfChunked2ndHalf);
		System.arraycopy(tail, 0, secondPiece, lengthOfChunked2ndHalf, tail.length);
		
		DataWrapper first = dataGen.wrapByteArray(firstPiece);
		DataWrapper second = dataGen.wrapByteArray(secondPiece);
		
		Memento memento = parser.prepareToParse();
		memento = parser.parse(memento, first);
		
		Assert.assertEquals(ParsingState.CHUNK, memento.getUnParsedState().getCurrentlyParsing());
		//The new implementation consumes AS MUCH AS POSSIBLE
		Assert.assertEquals(0, memento.getUnParsedState().getCurrentUnparsedSize());
		
		List<HttpPayload> msgs = memento.getParsedMessages();
		Assert.assertEquals(2, msgs.size());
		Assert.assertEquals(resp, msgs.get(0));
		
		HttpData data = (HttpData) msgs.get(1);
		Assert.assertTrue(data.isStartOfChunk());
		Assert.assertFalse(data.isEndOfChunk());
		Assert.assertFalse(data.isEndOfData());
		
		memento = parser.parse(memento, second);
		List<HttpPayload> parsedMessages = memento.getParsedMessages();
		Assert.assertEquals(4, parsedMessages.size());

		Assert.assertEquals(resp400, parsedMessages.get(3));
	}

	@Test
	public void testMarshalOut() {
		DataWrapper payload1 = dataGen.wrapByteArray("0123456789".getBytes());
		
		HttpChunk chunk = new HttpChunk();
		chunk.addExtension(new HttpChunkExtension("asdf", "value"));
		chunk.addExtension(new HttpChunkExtension("something"));
		chunk.setBody(payload1);
		
		byte[] payload = unwrap(parser.marshalToByteBuffer(state, chunk));
		String str = new String(payload);

		Assert.assertEquals("a;asdf=value;something\r\n0123456789\r\n", str);
		
		HttpLastChunk lastChunk = new HttpLastChunk();
		lastChunk.addExtension(new HttpChunkExtension("this", "that"));
		lastChunk.addHeader(new Header("customer", "value"));
		String lastPayload = parser.marshalToString(lastChunk);
		
		Assert.assertEquals("0;this=that\r\ncustomer: value\r\n\r\n", lastPayload);
		
		byte[] lastBytes = unwrap(parser.marshalToByteBuffer(state, lastChunk));
		String lastPayloadFromBytes = new String(lastBytes, HttpParserFactory.ISO8859_1);
		
		Assert.assertEquals("0;this=that\r\ncustomer: value\r\n\r\n", lastPayloadFromBytes);
	}
	
	@Test
	public void testSplitChunkExtensionsAndResponseAfter() {
		
	}
	
	@Test
	public void testMultipleExtensions() {
		
	}
	
	//http://stackoverflow.com/questions/5590791/http-chunked-encoding-need-an-example-of-trailer-mentioned-in-spec
	@Test
	public void testLastChunkContainsTrailingHeaders() {
		
	}
	
	static HttpResponse create400Response() {
		Header header1 = new Header();
		header1.setName(KnownHeaderName.AGE);
		header1.setValue("CooolValue");
		
		HttpResponseStatus status = new HttpResponseStatus();
		status.setKnownStatus(KnownStatusCode.HTTP_400_BADREQUEST);
		HttpResponseStatusLine statusLine = new HttpResponseStatusLine();
		statusLine.setStatus(status);
		
		HttpResponse resp = new HttpResponse();
		resp.setStatusLine(statusLine);
		resp.addHeader(header1);
		return resp;
	}
}
