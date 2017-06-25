package org.webpieces.throughput.server;

import java.net.InetSocketAddress;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import org.webpieces.asyncserver.api.AsyncServerManager;
import org.webpieces.asyncserver.api.AsyncServerMgrFactory;
import org.webpieces.data.api.BufferCreationPool;
import org.webpieces.frontend2.api.FrontendMgrConfig;
import org.webpieces.frontend2.api.HttpFrontendFactory;
import org.webpieces.frontend2.api.HttpFrontendManager;
import org.webpieces.frontend2.api.HttpServer;
import org.webpieces.frontend2.api.HttpSvrConfig;
import org.webpieces.nio.api.ChannelManager;
import org.webpieces.nio.api.ChannelManagerFactory;
import org.webpieces.throughput.AsyncConfig;
import org.webpieces.util.logging.Logger;
import org.webpieces.util.logging.LoggerFactory;

import com.webpieces.http2engine.api.client.Http2Config;

public class ServerAsync {
	private static final Logger log = LoggerFactory.getLogger(ServerAsync.class);
	private AsyncConfig config;
	private Http2Config http2Config;

	public ServerAsync(AsyncConfig config) {
		this.config = config;
		http2Config = new Http2Config();
		long max = config.getClientMaxConcurrentRequests();
		http2Config.getLocalSettings().setMaxConcurrentStreams(max);
	}

	public CompletableFuture<InetSocketAddress> start() {
    	log.error("running ASYNC HTTP1.1 AND HTTP2 SERVER");

		HttpServer server = createFrontend();
		CompletableFuture<Void> future = server.start();
		return future.thenApply(v -> server.getUnderlyingChannel().getLocalAddress());
	}

	private HttpServer createFrontend() {
		if(config.getServerThreadCount() != null) {
			return createFrontendMultiThreaded();
		}
		
		log.info("Creating single threaded server");
		BufferCreationPool pool = new BufferCreationPool();
		
		ChannelManagerFactory factory = ChannelManagerFactory.createFactory();
		ChannelManager chanMgr = factory.createSingleThreadedChanMgr("svrCmLoop", pool, config.getBackpressureConfig());

		AsyncServerManager svrMgr = AsyncServerMgrFactory.createAsyncServer(chanMgr);


		HttpFrontendManager mgr = HttpFrontendFactory.createFrontEnd(svrMgr, pool, http2Config);
		return mgr.createHttpServer(new HttpSvrConfig("asyncsvr"), new EchoListener());
	}
	
	private HttpServer createFrontendMultiThreaded() {
		ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
		
		
		FrontendMgrConfig frontendConfig = new FrontendMgrConfig();
		frontendConfig.setHttp2Config(http2Config);
		frontendConfig.setBackpressureConfig(config.getBackpressureConfig());
		frontendConfig.setThreadPoolSize(config.getServerThreadCount());
		
		log.info("Creating multithreaded server. threads="+frontendConfig.getThreadPoolSize());

		HttpFrontendManager mgr = HttpFrontendFactory.createFrontEnd(
				"deansvr", timer, new BufferCreationPool(), frontendConfig);
		
		return mgr.createHttpServer(new HttpSvrConfig("asyncsvr"), new EchoListener());
	}
}