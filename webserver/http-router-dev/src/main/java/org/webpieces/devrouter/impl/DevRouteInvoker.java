package org.webpieces.devrouter.impl;

import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;

import javax.inject.Inject;

import org.webpieces.ctx.api.FlashSub;
import org.webpieces.ctx.api.RequestContext;
import org.webpieces.ctx.api.RouterRequest;
import org.webpieces.router.api.ResponseStreamer;
import org.webpieces.router.api.RouterConfig;
import org.webpieces.router.api.controller.actions.Action;
import org.webpieces.router.api.exceptions.NotFoundException;
import org.webpieces.router.impl.BaseRouteInfo;
import org.webpieces.router.impl.InvokeInfo;
import org.webpieces.router.impl.InvokerInfo;
import org.webpieces.router.impl.ProdRouteInvoker;
import org.webpieces.router.impl.dto.RouteType;
import org.webpieces.router.impl.loader.BinderAndLoader;
import org.webpieces.router.impl.loader.ControllerLoader;
import org.webpieces.router.impl.loader.LoadedController;
import org.webpieces.router.impl.loader.svc.MethodMeta;
import org.webpieces.router.impl.loader.svc.RouteData;
import org.webpieces.router.impl.loader.svc.RouteInfoForContent;
import org.webpieces.router.impl.loader.svc.RouteInfoForHtml;
import org.webpieces.router.impl.loader.svc.RouteInfoForNotFound;
import org.webpieces.router.impl.loader.svc.ServiceInvoker;
import org.webpieces.router.impl.loader.svc.SvcProxyFixedRoutes;
import org.webpieces.router.impl.model.RouteModuleInfo;
import org.webpieces.router.impl.params.ObjectToParamTranslator;
import org.webpieces.router.impl.routebldr.RouteInfo;
import org.webpieces.router.impl.routers.DynamicInfo;
import org.webpieces.util.filters.Service;
import org.webpieces.util.logging.Logger;
import org.webpieces.util.logging.LoggerFactory;

public class DevRouteInvoker extends ProdRouteInvoker {
	private static final Logger log = LoggerFactory.getLogger(DevRouteInvoker.class);
	private final ServiceInvoker serviceInvoker;

	@Inject
	public DevRouteInvoker(ObjectToParamTranslator reverseTranslator, RouterConfig config, ControllerLoader loader, ServiceInvoker invoker) {
		super(reverseTranslator, config, loader);
		this.serviceInvoker = invoker;
	}

	/**
	 * This one is definitely special
	 */
	@Override
	public CompletableFuture<Void> invokeNotFound(InvokeInfo invokeInfo, LoadedController loadedController, RouteData data) {
		BaseRouteInfo route = invokeInfo.getRoute();
		if(loadedController == null) {
			loadedController = controllerFinder.loadGenericController(route.getInjector(), route.getRouteInfo(), false);
		}
		
		RouteInfoForNotFound notFoundData = (RouteInfoForNotFound) data;
		if(notFoundData.getNotFoundException() == null) {
			throw new IllegalArgumentException("must have not found exception to be here");
		}
		
		return invokeCorrectNotFoundRoute(invokeInfo, loadedController, data);
	}

	@Override
	public CompletableFuture<Void> invokeErrorController(InvokeInfo invokeInfo, DynamicInfo info, RouteData data) {
		DynamicInfo newInfo = info;
		//If we haven't loaded it already, load it now
		if(info.getLoadedController() == null) {
			BaseRouteInfo route = invokeInfo.getRoute();
			LoadedController controllerInst = controllerFinder.loadGenericController(route.getInjector(), route.getRouteInfo(), false);
			Service<MethodMeta, Action> service = controllerFinder.loadFilters(route, false);
			newInfo = new DynamicInfo(controllerInst, service);
		}
		return super.invokeErrorController(invokeInfo, newInfo, data);
	}

	@Override
	public CompletableFuture<Void> invokeHtmlController(InvokeInfo invokeInfo, DynamicInfo info, RouteData data) {
		RouteInfoForHtml htmlRoute = (RouteInfoForHtml) data;
		//If we haven't loaded it already, load it now		
		DynamicInfo newInfo = info;
		if(info.getLoadedController() == null) {
			BaseRouteInfo route = invokeInfo.getRoute();
			LoadedController controller = controllerFinder.loadHtmlController(route.getInjector(), route.getRouteInfo(), false, htmlRoute.isPostOnly());
			Service<MethodMeta, Action> svc = controllerFinder.loadFilters(route, false);
			newInfo = new DynamicInfo(controller, svc);
		}
		return super.invokeHtmlController(invokeInfo, newInfo, data);
	}
	
	@Override
	public CompletableFuture<Void> invokeContentController(InvokeInfo invokeInfo, DynamicInfo info, RouteData data) {
		DynamicInfo newInfo = info;
		//If we haven't loaded it already, load it now
		if(info.getLoadedController() == null) {
			BaseRouteInfo route = invokeInfo.getRoute();
			BinderAndLoader binderAndLoader = controllerFinder.loadContentController(route.getInjector(), route.getRouteInfo(), false);
			Service<MethodMeta, Action> svc = controllerFinder.loadFilters(route, false);
			newInfo = new DynamicInfo(binderAndLoader.getLoadedController(), svc);
			data = new RouteInfoForContent(binderAndLoader.getBinder());
		}

		return super.invokeContentController(invokeInfo, newInfo, data);
	}

	private CompletableFuture<Void> invokeCorrectNotFoundRoute(InvokeInfo invokeInfo, LoadedController loadedController, RouteData data) {
		BaseRouteInfo route = invokeInfo.getRoute();
		RequestContext requestCtx = invokeInfo.getRequestCtx();
		ResponseStreamer responseCb = invokeInfo.getResponseCb();
		RouteInfoForNotFound notFoundData = (RouteInfoForNotFound) data;
		NotFoundException notFoundExc = notFoundData.getNotFoundException();

		RouterRequest req = requestCtx.getRequest();
		//RouteMeta origMeta, NotFoundException e, RouterRequest req) {
		if(req.queryParams.containsKey("webpiecesShowPage")) {
			//This is a callback so render the original webapp developer's not found page into the iframe
			return super.invokeNotFound(invokeInfo, loadedController, data);
		}

		//ok, in dev mode, we hijack the not found page with one with a route list AND an iframe containing the developers original
		//notfound page
		
		log.error("(Development only log message) Route not found!!! Either you(developer) typed the wrong url OR you have a bad route.  Either way,\n"
				+ " something needs a'fixin.  req="+req, notFoundExc);
		
		RouteInfo routeInfo = new RouteInfo(new RouteModuleInfo("", null), "/org/webpieces/devrouter/impl/NotFoundController.notFound");
		BaseRouteInfo webpiecesNotFoundRoute = new BaseRouteInfo(
				route.getInjector(), routeInfo, 
				new SvcProxyFixedRoutes(serviceInvoker),
				new ArrayList<>(), RouteType.NOT_FOUND);
		
		LoadedController newLoadedController = controllerFinder.loadGenericController(route.getInjector(), routeInfo, false);
		
		String reason = "Your route was not found in routes table";
		if(notFoundExc != null)
			reason = notFoundExc.getMessage();
		
		RouterRequest newRequest = new RouterRequest();
		newRequest.putMultipart("webpiecesError", "Exception message="+reason);
		newRequest.putMultipart("url", req.relativePath);
		
		RequestContext overridenCtx = new RequestContext(requestCtx.getValidation(), (FlashSub) requestCtx.getFlash(), requestCtx.getSession(), newRequest);
		InvokeInfo newInvokeInfo = new InvokeInfo(webpiecesNotFoundRoute, overridenCtx, responseCb);
		return super.invokeNotFound(newInvokeInfo, newLoadedController, data);
	}
}