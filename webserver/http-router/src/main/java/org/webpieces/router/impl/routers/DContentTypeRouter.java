package org.webpieces.router.impl.routers;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.webpieces.ctx.api.RequestContext;
import org.webpieces.router.api.exceptions.NotFoundException;
import org.webpieces.router.impl.model.MatchResult2;
import org.webpieces.router.impl.proxyout.ProxyStreamHandle;
import org.webpieces.util.futures.FutureHelper;

import com.webpieces.http2engine.api.StreamWriter;

public class DContentTypeRouter {

	private List<AbstractRouter> routers;
	private FutureHelper futureUtil;

	public DContentTypeRouter(FutureHelper futureUtil, List<AbstractRouter> routers) {
		this.futureUtil = futureUtil;
		this.routers = routers;
	}

	public String build(String spacing) {
		String text = "\n";
		
		for(AbstractRouter route: routers) {
			text += spacing+route.getMatchInfo().getLoggableString(" ")+"\n";
		}
		
		text+="\n";
		
		return text;
	}

	public CompletableFuture<StreamWriter> invokeRoute(RequestContext ctx, ProxyStreamHandle handler, String relativePath) {
		for(AbstractRouter router : routers) {
			MatchResult2 result = router.matches(ctx.getRequest(), relativePath);
			if(result.isMatches()) {
				ctx.setPathParams(result.getPathParams());
				
				return router.invoke(ctx, handler);
			}
		}

		return futureUtil.<StreamWriter>failedFuture(new NotFoundException("route not found"));
	}

}