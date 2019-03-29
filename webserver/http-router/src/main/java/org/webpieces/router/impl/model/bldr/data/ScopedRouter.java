package org.webpieces.router.impl.model.bldr.data;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.webpieces.ctx.api.RequestContext;
import org.webpieces.router.api.ResponseStreamer;
import org.webpieces.router.api.exceptions.NotFoundException;
import org.webpieces.router.api.routing.Port;
import org.webpieces.router.impl.Route;
import org.webpieces.router.impl.RouteMeta;
import org.webpieces.router.impl.model.MatchResult2;
import org.webpieces.router.impl.model.RouterInfo;
import org.webpieces.util.logging.Logger;
import org.webpieces.util.logging.LoggerFactory;

public class ScopedRouter {
	private static final Logger log = LoggerFactory.getLogger(ScopedRouter.class);

	protected final RouterInfo routerInfo;
	private final Map<String, ScopedRouter> pathPrefixToNextRouter;
	private final List<RouteMeta> routes;

	public ScopedRouter(RouterInfo routerInfo, Map<String, ScopedRouter> pathPrefixToNextRouter, List<RouteMeta> routes) {
		this.routerInfo = routerInfo;
		this.pathPrefixToNextRouter = pathPrefixToNextRouter;
		this.routes = routes;
	}
	
	public CompletableFuture<Void> invokeRoute(RequestContext ctx, ResponseStreamer responseCb, String subPath) {
		if("".equals(subPath))
			return findAndInvokeRoute(ctx, responseCb, subPath);
		else if(!subPath.startsWith("/"))
			throw new IllegalArgumentException("path must start with /");
		
		String prefix = subPath;
		int index = subPath.indexOf("/", 1);
		if(index == 1) {
			throw new IllegalArgumentException("path cannot start with //");
		} else if(index > 1) {
			prefix = subPath.substring(0, index);
		}

		ScopedRouter routeInfo = getPathPrefixToNextRouter().get(prefix);
		if(routeInfo != null) {
			if(index < 0)
				return routeInfo.invokeRoute(ctx, responseCb, "");
			
			String newRelativePath = subPath.substring(index, subPath.length());
			return routeInfo.invokeRoute(ctx, responseCb, newRelativePath);
		}
		
		return findAndInvokeRoute(ctx, responseCb, subPath);
	}
	
	private CompletableFuture<Void> findAndInvokeRoute(RequestContext ctx, ResponseStreamer responseCb, String subPath) {
		for(RouteMeta meta : routes) {
			MatchResult2 result = meta.matches2(ctx.getRequest(), subPath);
			if(result != null)
				return meta.invoke(ctx, responseCb, result.getPathParams());
		}

		CompletableFuture<Void> future = new CompletableFuture<Void>();
		future.completeExceptionally(new NotFoundException("route not found"));
		return future;
	}

	public Map<String, ScopedRouter> getPathPrefixToNextRouter() {
		return pathPrefixToNextRouter;
	}

	public List<RouteMeta> getRoutes() {
		return routes;
	}
	
	@Override
	public String toString() {
		return build("");
	}

	public void printRoutes(boolean isHttps, String tabSpaces) {
		//This is a pain but dynamically build up the html
		String routeHtml = build(tabSpaces);
		
		//print in warn so it's in red for anyone and to stderr IF they have debug enabled
		//it's kind of weird BUT great for tests
		if(!isHttps)
			log.warn("WARNING: The request is NOT https so perhaps your route is only accessible over https so modify your request" + routeHtml);
		else
			log.warn(routeHtml);
	}

	public String build(String spacing) {
		String text = "\n";
		
		for(Map.Entry<String, ScopedRouter> entry : pathPrefixToNextRouter.entrySet()) {
			ScopedRouter childRouting = entry.getValue();
			text += spacing+ "SCOPE:"+entry.getKey();
			text += childRouting.build(spacing + spacing);
		}
		
		List<RouteMeta> routes = getRoutes();
		for(RouteMeta route: routes) {
			Route rt = route.getRoute();
			String http = rt.getExposedPorts() == Port.HTTPS ? "https" : "http";
			text += spacing+pad(rt.getMethod(), 5)+":"+pad(http, 5)+" : "+rt.getFullPath()+"\n";
		}
		
		text+="\n";
		
		return text;
	}

	private String pad(String msg, int n) {
		int left = n-msg.length();
		if(left < 0)
			left = 0;
		
		for(int i = 0; i < left; i++) {
			msg += " ";
		}
		return msg;
	}	
}
