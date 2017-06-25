package org.webpieces.webserver.scopes;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.webpieces.httpclient11.api.HttpFullRequest;
import org.webpieces.httpparser.api.common.Header;
import org.webpieces.httpparser.api.common.KnownHeaderName;
import org.webpieces.httpparser.api.dto.HttpRequest;
import org.webpieces.httpparser.api.dto.KnownHttpMethod;
import org.webpieces.httpparser.api.dto.KnownStatusCode;
import org.webpieces.util.file.VirtualFileClasspath;
import org.webpieces.webserver.Requests;
import org.webpieces.webserver.ResponseExtract;
import org.webpieces.webserver.WebserverForTest;
import org.webpieces.webserver.test.AbstractWebpiecesTest;
import org.webpieces.webserver.test.FullResponse;
import org.webpieces.webserver.test.Http11Socket;


public class TestScopes extends AbstractWebpiecesTest {

	private Http11Socket http11Socket;
	
	@Before
	public void setUp() throws InterruptedException, ExecutionException, TimeoutException {
		VirtualFileClasspath metaFile = new VirtualFileClasspath("scopesMeta.txt", WebserverForTest.class.getClassLoader());
		WebserverForTest webserver = new WebserverForTest(platformOverrides, null, false, metaFile);
		webserver.start();
		http11Socket = http11Simulator.createHttpSocket(webserver.getUnderlyingHttpChannel().getLocalAddress());
	}

	@Test
	public void testSessionScope() {
		HttpRequest req = Requests.createRequest(KnownHttpMethod.GET, "/home");
		
		http11Socket.send(req);
		
		FullResponse response = ResponseExtract.assertSingleResponse(http11Socket);

		response.assertStatusCode(KnownStatusCode.HTTP_200_OK);
		response.assertContains("age=30");
		response.assertContains("result=true");
		response.assertContains("name=Dean");
	}

	@Test
	public void testSessionScopeModificationByClient() {
		HttpRequest req = Requests.createRequest(KnownHttpMethod.GET, "/home");
		
		http11Socket.send(req);
		
		FullResponse response = ResponseExtract.assertSingleResponse(http11Socket);

		response.assertStatusCode(KnownStatusCode.HTTP_200_OK);
		response.assertContains("age=30");
		
		Header cookie = response.getResponse().getHeaderLookupStruct().getHeader(KnownHeaderName.SET_COOKIE);
		String value = cookie.getValue();
		value = value.replace("age=30", "age=60");
		value = value.replace("; path=/; HttpOnly", "");
		
		//modify cookie and rerequest...
		HttpRequest req2 = Requests.createRequest(KnownHttpMethod.GET, "/displaySession");
		req2.addHeader(new Header(KnownHeaderName.COOKIE, value));
		
		http11Socket.send(req2);
		
        response = ResponseExtract.assertSingleResponse(http11Socket);
		response.assertStatusCode(KnownStatusCode.HTTP_303_SEEOTHER);
		
		Header cookie2 = response.getResponse().getHeaderLookupStruct().getHeader(KnownHeaderName.SET_COOKIE);
		String deleteCookie = cookie2.getValue();
		Assert.assertEquals("webSession=; Max-Age=0; path=/; HttpOnly", deleteCookie);
		Header header = response.getResponse().getHeaderLookupStruct().getHeader(KnownHeaderName.LOCATION);
		String url = header.getValue();
		Assert.assertEquals("http://myhost.com/displaySession", url);
	}

	@Test
    public void testFlashMessage() {
        HttpRequest req = Requests.createRequest(KnownHttpMethod.GET, "/flashmessage");
        http11Socket.send(req);

        FullResponse response = ResponseExtract.assertSingleResponse(http11Socket);

        
        response.assertStatusCode(KnownStatusCode.HTTP_200_OK);
        response.assertContains("Msg: it worked");
    }

	@Test
    public void testGetStaticFileDoesNotClearFlashMessage() {
        HttpRequest req = Requests.createRequest(KnownHttpMethod.GET, "/flashmessage");
        http11Socket.send(req);

        FullResponse response = ResponseExtract.assertSingleResponse(http11Socket);

        response.assertStatusCode(KnownStatusCode.HTTP_200_OK);
        
		Header header = response.createCookieRequestHeader();
        HttpRequest req2 = Requests.createRequest(KnownHttpMethod.GET, "/public/fonts.css");
        req2.addHeader(header);
        http11Socket.send(req2);
        
        FullResponse response2 = ResponseExtract.assertSingleResponse(http11Socket);
        response2.assertStatusCode(KnownStatusCode.HTTP_200_OK);
        Header cookie = response2.getResponse().getHeaderLookupStruct().getHeader(KnownHeaderName.SET_COOKIE);
        Assert.assertNull("static routes should not be clearing cookies or things go south", cookie);

    }
	
	//A basic POST form with invalid field, redirect to error page and load AND THEN
	//POST form with valid data and expect success redirect
	//This tests out the Validation scoped cookie
	@Test
	public void testValidationErrorDoesNotPersist() {
		//POST first resulting in redirect with errors
		//RENDER page WITH errors, verify errors are there
		//POST again with valid data and verify POST succeeds
		
		FullResponse response1 = runInvalidPost();
		FullResponse response2 = runGetUserFormWithErrors(response1);
		
		Header header = response2.createCookieRequestHeader();
		HttpFullRequest req = Requests.createPostRequest("/user/post", 
				"user.id", "",
				"user.firstName", "Dean", //valid firstname
				"user.lastName", "Hiller",
				"user.fullName", "Dean Hiller",
				"user.address.zipCode", "555",
				"user.address.street", "Coolness Dr.");		
		req.addHeader(header);

		http11Socket.send(req);
		
		FullResponse response = ResponseExtract.assertSingleResponse(http11Socket);

		response.assertStatusCode(KnownStatusCode.HTTP_303_SEEOTHER);
		Assert.assertEquals("http://myhost.com/user/list", response.getRedirectUrl());
	}

	private FullResponse runInvalidPost() {
		HttpFullRequest req = Requests.createPostRequest("/user/post", 
				"user.firstName", "D", //invalid first name
				"user.lastName", "Hiller",
				"user.fullName", "Dean Hiller",
				"user.address.zipCode", "555",
				"user.address.street", "Coolness Dr.");
		
		http11Socket.send(req);
		
		FullResponse response = ResponseExtract.assertSingleResponse(http11Socket);
		response.assertStatusCode(KnownStatusCode.HTTP_303_SEEOTHER);
		return response;
	}
	
	private FullResponse runGetUserFormWithErrors(FullResponse response1) {
		Assert.assertEquals("http://myhost.com/user/new", response1.getRedirectUrl());
        HttpRequest req = Requests.createRequest(KnownHttpMethod.GET, "/user/new");
        Header cookieHeader = response1.createCookieRequestHeader();
        req.addHeader(cookieHeader);
        
        http11Socket.send(req);

        FullResponse response = ResponseExtract.assertSingleResponse(http11Socket);

        response.assertStatusCode(KnownStatusCode.HTTP_200_OK);
        response.assertContains("First name must be more than 2 characters");
		return response;
	}
}
