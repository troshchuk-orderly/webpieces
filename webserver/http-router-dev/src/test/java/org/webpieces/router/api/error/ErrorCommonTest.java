package org.webpieces.router.api.error;

import java.io.File;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.webpieces.compiler.api.CompileConfig;
import org.webpieces.devrouter.api.DevRouterFactory;
import org.webpieces.router.api.RouterSvcFactory;
import org.webpieces.router.api.RoutingService;
import org.webpieces.router.api.dto.HttpMethod;
import org.webpieces.router.api.dto.RenderResponse;
import org.webpieces.router.api.dto.RouterRequest;
import org.webpieces.router.api.error.dev.CommonRoutesModules;
import org.webpieces.router.api.mocks.MockResponseStream;
import org.webpieces.router.api.mocks.VirtualFileInputStream;
import org.webpieces.util.file.VirtualFile;
import org.webpieces.util.file.VirtualFileImpl;

@RunWith(Parameterized.class)
public class ErrorCommonTest {

	private static final Logger log = LoggerFactory.getLogger(ErrorCommonTest.class);
	private boolean isProdTest;
	
	@SuppressWarnings("rawtypes")
	@Parameterized.Parameters
	public static Collection bothServers() {
		return Arrays.asList(new Object[][] {
	         { true, true },
	         { false, true }
	      });
	}
	
	public ErrorCommonTest(boolean isProdTest, boolean expected) {
		this.isProdTest = isProdTest;
		log.info("constructing test suite for server prod="+isProdTest);
	}
	
	@Test
	public void testRedirectRouteNotEnoughArguments() {
		//say method is something(int arg, String this)
		//we verify redirects MUST match type and number of method arguments every time
		//then when we form url, we put the stuff in the path OR put it as query params so it works on the way back in again too
		String moduleFileContents = CommonRoutesModules.class.getName();
		RoutingService server = createServer(isProdTest, moduleFileContents);
		
		server.start();
		
		RouterRequest req = RequestCreation.createHttpRequest(HttpMethod.GET, "/user/5553");
		MockResponseStream mockResponseStream = new MockResponseStream();

		server.processHttpRequests(req, mockResponseStream);
			
		Exception e = mockResponseStream.getOnlyException();
		Assert.assertEquals(IllegalStateException.class, e.getClass());
	}
	
//	@Test
//	public void testArgsTypeMismatch() {
//		log.info("starting");
//		String moduleFileContents = CommonRoutesModules.class.getName();
//		RoutingService server = createServer(isProdTest, moduleFileContents);
//		
//		server.start();
//		
//		RouterRequest req = RequestCreation.createHttpRequest(HttpMethod.GET, "/something");
//		MockResponseStream mockResponseStream = new MockResponseStream();
//		
//		server.processHttpRequests(req, mockResponseStream);
//
//		verifyNotFoundRendered(mockResponseStream);
//	}

	private void verifyNotFoundRendered(MockResponseStream mockResponseStream) {
		List<RenderResponse> responses = mockResponseStream.getSendRenderHtmlList();
		Assert.assertEquals(1, responses.size());
		Assert.assertEquals("notFound.xhtml", responses.get(0).getView());
	}
	
//	@Test
//	public void testGetNotMatchPostRoute() {
//		log.info("starting");
//		String moduleFileContents = CommonRoutesModules.class.getName();
//		RoutingService server = createServer(isProdTest, moduleFileContents);
//		
//		server.start();
//		
//		RouterRequest req = RequestCreation.createHttpRequest(HttpMethod.GET, "/postroute");
//		MockResponseStream mockResponseStream = new MockResponseStream();
//		
//		server.processHttpRequests(req, mockResponseStream);
//
//		verifyNotFoundRendered(mockResponseStream);
//	}
	
	@Test
	public void testHTMLPostAndTryingToRenderInsteadOfRedirectFails() {
		log.info("starting");
		String moduleFileContents = CommonRoutesModules.class.getName();
		RoutingService server = createServer(isProdTest, moduleFileContents);
		
		server.start();
		
		RouterRequest req = RequestCreation.createHttpRequest(HttpMethod.POST, "/postroute");
		MockResponseStream mockResponseStream = new MockResponseStream();
		
		server.processHttpRequests(req, mockResponseStream);

		Exception e = mockResponseStream.getOnlyException();
		Assert.assertEquals(IllegalStateException.class, e.getClass());
	}
	
	/** 
	 * Need to live test with browser to see if PRG is better or just returning 404 is better!!!
	 * Current behavior is to return a 404
	 */
	//TODO: Test this with browser and then fix for best user experience
//	@Test
//	public void testNotFoundPostRouteResultsInRedirectToNotFoundCatchAllController() {
//		log.info("starting");
//		String moduleFileContents = CommonRoutesModules.class.getName();
//		RoutingService server = createServer(isProdTest, moduleFileContents);
//		
//		server.start();
//		
//		RouterRequest req = RequestCreation.createHttpRequest(HttpMethod.POST, "/notexistpostroute");
//		MockResponseStream mockResponseStream = new MockResponseStream();
//		
//		server.processHttpRequests(req, mockResponseStream);
//
//		verifyNotFoundRendered(mockResponseStream);
//	}
	
	public static RoutingService createServer(boolean isProdTest, String moduleFileContents) {
		VirtualFile f = new VirtualFileInputStream(moduleFileContents.getBytes(), "testAppModules");		
		
		if(isProdTest)
			return RouterSvcFactory.create(f);
		
		//otherwise create the development server
		String filePath = System.getProperty("user.dir");
		File myCodePath = new File(filePath + "/src/test/java");
		CompileConfig compileConfig = new CompileConfig(new VirtualFileImpl(myCodePath));		
		log.info("bytecode dir="+compileConfig.getByteCodeCacheDir());
		RoutingService server = DevRouterFactory.create(f, compileConfig);
		return server;
	}
}