package org.webpieces.router.impl;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import javax.inject.Inject;

import org.webpieces.ctx.api.Current;
import org.webpieces.ctx.api.Messages;
import org.webpieces.ctx.api.RequestContext;
import org.webpieces.ctx.api.RouterRequest;
import org.webpieces.router.api.PortConfig;
import org.webpieces.router.api.ResponseStreamer;
import org.webpieces.router.api.RouterConfig;
import org.webpieces.router.api.controller.actions.Action;
import org.webpieces.router.api.controller.actions.RenderContent;
import org.webpieces.router.api.exceptions.NotFoundException;
import org.webpieces.router.impl.actions.AjaxRedirectImpl;
import org.webpieces.router.impl.actions.RawRedirect;
import org.webpieces.router.impl.actions.RedirectImpl;
import org.webpieces.router.impl.actions.RenderImpl;
import org.webpieces.router.impl.ctx.ProcessorInfo;
import org.webpieces.router.impl.ctx.RequestLocalCtx;
import org.webpieces.router.impl.ctx.ResponseProcessor;
import org.webpieces.router.impl.ctx.ResponseStaticProcessor;
import org.webpieces.router.impl.dto.RenderStaticResponse;
import org.webpieces.router.impl.loader.ControllerLoader;
import org.webpieces.router.impl.loader.LoadedController;
import org.webpieces.router.impl.loader.svc.LoadedController2;
import org.webpieces.router.impl.loader.svc.MethodMeta;
import org.webpieces.router.impl.loader.svc.RouteData;
import org.webpieces.router.impl.loader.svc.RouteInfoForNotFound;
import org.webpieces.router.impl.params.ObjectToParamTranslator;
import org.webpieces.router.impl.routers.DynamicInfo;
import org.webpieces.util.file.VirtualFile;
import org.webpieces.util.filters.Service;

public class ProdRouteInvoker implements RouteInvoker {

	private ReverseRoutes reverseRoutes;

	private final ObjectToParamTranslator reverseTranslator;
	protected final RouterConfig config;
	protected final ControllerLoader controllerFinder;

	private volatile PortConfig portConfig;

	@Inject
	public ProdRouteInvoker(
		ObjectToParamTranslator reverseTranslator,
		RouterConfig config,
		ControllerLoader controllerFinder
	) {
		this.reverseTranslator = reverseTranslator;
		this.config = config;
		this.controllerFinder = controllerFinder;
	}
	
	@Override
	public CompletableFuture<Void> invokeStatic(StaticRoute route, Map<String, String> pathParams, RequestContext ctx, ResponseStreamer responseCb) {

		boolean isOnClassPath = route.getIsOnClassPath();

		RenderStaticResponse resp = new RenderStaticResponse(route.getTargetCacheLocation(), isOnClassPath);
		if(route.isFile()) {
			resp.setFilePath(route.getFileSystemPath());
		} else {
			String relativeUrl = pathParams.get("resource");
			VirtualFile fullPath = route.getFileSystemPath().child(relativeUrl);
			resp.setFileAndRelativePath(fullPath, relativeUrl);
		}

		ResponseStaticProcessor processor = new ResponseStaticProcessor(ctx, responseCb);

		return processor.renderStaticResponse(resp);
	}

	private CompletableFuture<Void> invokeImpl(InvokeInfo invokeInfo, DynamicInfo dynamicInfo, RouteData data) {
		Service<MethodMeta, Action> service = dynamicInfo.getService();
		LoadedController loadedController = dynamicInfo.getLoadedController();
		return invoke(invokeInfo, loadedController, service, data);
	}
	
	@Override
	public CompletableFuture<Void> invokeErrorController(InvokeInfo invokeInfo, DynamicInfo dynamicInfo, RouteData data) {
		return invokeImpl(invokeInfo, dynamicInfo, data);
	}
	
	@Override
	public CompletableFuture<Void> invokeHtmlController(InvokeInfo invokeInfo, DynamicInfo dynamicInfo, RouteData data) {
		return invokeImpl(invokeInfo, dynamicInfo, data);
	}
	
	@Override
	public CompletableFuture<Void> invokeContentController(InvokeInfo invokeInfo, DynamicInfo dynamicInfo, RouteData data) {
		return invokeImpl(invokeInfo, dynamicInfo, data);
	}
	
	@Override
	public CompletableFuture<Void> invokeNotFound(InvokeInfo invokeInfo, LoadedController loadedController, RouteData data) {
		BaseRouteInfo route = invokeInfo.getRoute();
		RequestContext requestCtx = invokeInfo.getRequestCtx();
		Service<MethodMeta, Action> service = createNotFoundService(route, requestCtx.getRequest());
		return invoke(invokeInfo, loadedController, service, data);
	}
	


	private CompletableFuture<Void> invoke(InvokeInfo invokeInfo, LoadedController loadedController, Service<MethodMeta, Action> service,
			RouteData data) {
		BaseRouteInfo route = invokeInfo.getRoute();
		RequestContext requestCtx = invokeInfo.getRequestCtx();
		ResponseStreamer responseCb = invokeInfo.getResponseCb();
		
		if(portConfig == null)
			portConfig = config.getPortConfigCallback().fetchPortConfig();
		
		Object obj = loadedController.getControllerInstance();
		Method method = loadedController.getControllerMethod();
		if(obj == null)
			throw new IllegalStateException("Someone screwed up, as controllerInstance should not be null at this point, bug");
		else if(service == null)
			throw new IllegalStateException("Bug, service should never be null at this point");
		ProcessorInfo info = new ProcessorInfo(route.getRouteType(), obj, method);
		ResponseProcessor processor = new ResponseProcessor(requestCtx, reverseRoutes, reverseTranslator, info, responseCb, portConfig);
		
		Messages messages = new Messages(route.getRouteModuleInfo().i18nBundleName, "webpieces");
		requestCtx.setMessages(messages);

		RequestLocalCtx.set(processor);
		Current.setContext(requestCtx);
		
		MethodMeta methodMeta = new MethodMeta(new LoadedController2(obj, method), Current.getContext(), data);
		CompletableFuture<Action> response;
		try {
			response = service.invoke(methodMeta);
		} finally {
			RequestLocalCtx.set(null);
			Current.setContext(null);
		}
		
		CompletableFuture<Void> future = response.thenCompose(resp -> continueProcessing(processor, resp, responseCb));
		return future;
	}
	
	public  Service<MethodMeta, Action> createNotFoundService(BaseRouteInfo route, RouterRequest req) {
		List<FilterInfo<?>> filterInfos = findNotFoundFilters(route.getFilters(), req.relativePath, req.isHttps);
		return controllerFinder.loadFilters(route, filterInfos, false);
	}

	public List<FilterInfo<?>> findNotFoundFilters(List<FilterInfo<?>> notFoundFilters, String path, boolean isHttps) {
		List<FilterInfo<?>> matchingFilters = new ArrayList<>();
		for(FilterInfo<?> info : notFoundFilters) {
			if(!info.securityMatch(isHttps))
				continue; //skip this filter
			
			matchingFilters.add(0, info);
		}
		return matchingFilters;
	}
	
	public CompletableFuture<Void> continueProcessing(ResponseProcessor processor, Action controllerResponse, ResponseStreamer responseCb) {
		if(controllerResponse instanceof RedirectImpl) {
			return processor.createFullRedirect((RedirectImpl)controllerResponse);
		} else if(controllerResponse instanceof AjaxRedirectImpl) {
			return processor.createAjaxRedirect((AjaxRedirectImpl)controllerResponse);
		} else if(controllerResponse instanceof RenderImpl) {
			return processor.createRenderResponse((RenderImpl)controllerResponse);
		} else if(controllerResponse instanceof RawRedirect) {
			//redirects to a raw straight up url
			return processor.createRawRedirect((RawRedirect)controllerResponse);
		} else if(controllerResponse instanceof RenderContent) {
			//TODO: MOVE THIS out of here into ContentProcessor!!!
			//Rendering stuff like json/protobuf/thrift or basically BodyContentBinder generated content
			return processor.createContentResponse((RenderContent)controllerResponse);
		} else {
			throw new UnsupportedOperationException("Bug, a webpieces developer must have missed some code to write");
		}
	}
	
	@Override
	public void init(ReverseRoutes reverseRoutes) {
		this.reverseRoutes = reverseRoutes;
	}

}