package org.webpieces.devrouter.impl;

import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;

import javax.inject.Inject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.webpieces.ctx.api.ApplicationContext;
import org.webpieces.ctx.api.FlashSub;
import org.webpieces.ctx.api.RequestContext;
import org.webpieces.ctx.api.RouterRequest;
import org.webpieces.router.api.controller.actions.Action;
import org.webpieces.router.api.exceptions.NotFoundException;
import org.webpieces.router.api.routes.MethodMeta;
import org.webpieces.router.impl.WebInjector;
import org.webpieces.router.impl.body.BodyParsers;
import org.webpieces.router.impl.dto.RouteType;
import org.webpieces.router.impl.loader.BinderAndLoader;
import org.webpieces.router.impl.loader.ControllerLoader;
import org.webpieces.router.impl.loader.LoadedController;
import org.webpieces.router.impl.loader.MethodMetaAndController;
import org.webpieces.router.impl.model.RouteModuleInfo;
import org.webpieces.router.impl.proxyout.ProxyStreamHandle;
import org.webpieces.router.impl.routebldr.BaseRouteInfo;
import org.webpieces.router.impl.routebldr.RouteInfo;
import org.webpieces.router.impl.routeinvoker.AbstractRouteInvoker;
import org.webpieces.router.impl.routeinvoker.InvokeInfo;
import org.webpieces.router.impl.routeinvoker.ProdRouteInvoker;
import org.webpieces.router.impl.routeinvoker.RouteInvokerStatic;
import org.webpieces.router.impl.routeinvoker.RouterStreamRef;
import org.webpieces.router.impl.routers.DynamicInfo;
import org.webpieces.router.impl.services.RouteData;
import org.webpieces.router.impl.services.RouteInfoForContent;
import org.webpieces.router.impl.services.RouteInfoForHtml;
import org.webpieces.router.impl.services.RouteInfoForNotFound;
import org.webpieces.router.impl.services.RouteInfoForStatic;
import org.webpieces.router.impl.services.RouteInfoForStream;
import org.webpieces.router.impl.services.ServiceInvoker;
import org.webpieces.router.impl.services.SvcProxyFixedRoutes;
import org.webpieces.util.filters.Service;
import org.webpieces.util.futures.FutureHelper;

import com.google.inject.Injector;
import com.webpieces.http2.api.streaming.StreamWriter;

public class DevRouteInvoker extends AbstractRouteInvoker {
	private static final Logger log = LoggerFactory.getLogger(DevRouteInvoker.class);

	private final ServiceInvoker serviceInvoker;
	private WebInjector webInjector;

	@Inject
	public DevRouteInvoker(
			WebInjector webInjector, 
			ControllerLoader loader, 
			ServiceInvoker invoker,
			FutureHelper futureUtil,
			RouteInvokerStatic staticInvoker,
			BodyParsers bodyParsers
	) {
		super(loader, futureUtil, staticInvoker, bodyParsers);
		this.webInjector = webInjector;
		this.serviceInvoker = invoker;
	}
	
	@Override
	public RouterStreamRef invokeStatic(RequestContext ctx, ProxyStreamHandle handle,
			RouteInfoForStatic data) {
		//RESET the encodings to known so we don't try to go the compressed cache which doesn't
		//exist in dev server since we want the latest files always
		ctx.getRequest().encodings = new ArrayList<>();
		return super.invokeStatic(ctx, handle, data);
	}

	/**
	 * This one is definitely special
	 */
	@Override
	public CompletableFuture<StreamWriter> invokeNotFound(InvokeInfo invokeInfo, DynamicInfo info, RouteData data) {

		return invokeCorrectNotFoundRoute(invokeInfo, info, data);
	}

	@Override
	public CompletableFuture<StreamWriter> invokeErrorController(InvokeInfo invokeInfo, DynamicInfo info, RouteData data) {
		DynamicInfo newInfo = info;
		//If we haven't loaded it already, load it now
		if(info.getLoadedController() == null) {
			BaseRouteInfo route = invokeInfo.getRoute();
			Injector injector = route.getFilterChainCreationInfo().getInjector();
			LoadedController controllerInst = controllerFinder.loadErrorController(injector, route.getRouteInfo(), false);
			Service<MethodMeta, Action> service = controllerFinder.loadFilters(route.getFilterChainCreationInfo(), false);
			newInfo = new DynamicInfo(controllerInst, service);
		}
		return super.invokeErrorController(invokeInfo, newInfo, data);
	}

	@Override
	public RouterStreamRef invokeHtmlController(InvokeInfo invokeInfo, DynamicInfo info, RouteData data) {
		RouteInfoForHtml htmlRoute = (RouteInfoForHtml) data;
		//If we haven't loaded it already, load it now		
		DynamicInfo newInfo = info;
		if(info.getLoadedController() == null) {
			BaseRouteInfo route = invokeInfo.getRoute();
			Injector injector = route.getFilterChainCreationInfo().getInjector();
			MethodMetaAndController controller = controllerFinder.loadHtmlController(injector, route.getRouteInfo(), false, htmlRoute.isPostOnly());
			Service<MethodMeta, Action> svc = controllerFinder.loadFilters(route.getFilterChainCreationInfo(), false);
			newInfo = new DynamicInfo(controller.getLoadedController(), svc);
		}
		return super.invokeHtmlController(invokeInfo, newInfo, data);
	}
	
	@Override
	public RouterStreamRef invokeContentController(InvokeInfo invokeInfo, DynamicInfo info, RouteData data) {
		DynamicInfo newInfo = info;
		//If we haven't loaded it already, load it now
		if(info.getLoadedController() == null) {
			BaseRouteInfo route = invokeInfo.getRoute();
			Injector injector = route.getFilterChainCreationInfo().getInjector();
			BinderAndLoader binderAndLoader = controllerFinder.loadContentController(injector, route.getRouteInfo(), false);
			Service<MethodMeta, Action> svc = controllerFinder.loadFilters(route.getFilterChainCreationInfo(), false);
			LoadedController loadedController = binderAndLoader.getMetaAndController().getLoadedController();
			newInfo = new DynamicInfo(loadedController, svc);
			data = new RouteInfoForContent(binderAndLoader.getBinder());
		}
		return super.invokeContentController(invokeInfo, newInfo, data);
	}

	@Override
	public RouterStreamRef invokeStreamingController(InvokeInfo invokeInfo, DynamicInfo info, RouteData data) {
		DynamicInfo newInfo = info;
		//If we haven't loaded it already, load it now
		if(info.getLoadedController() == null) {
			BaseRouteInfo route = invokeInfo.getRoute();
			Injector injector = route.getFilterChainCreationInfo().getInjector();
			MethodMetaAndController metaAndController = controllerFinder.loadGenericController(injector, route.getRouteInfo(), false);
			//no filters on streaming yet AND they would be different filters anyways
			//Service<MethodMeta, Action> svc = controllerFinder.loadFilters(route, false);
			LoadedController loadedController = metaAndController.getLoadedController();
			newInfo = new DynamicInfo(loadedController, null);
			data = new RouteInfoForStream();
		}		
		return super.invokeStreamingController(invokeInfo, newInfo, data);
	}

	private CompletableFuture<StreamWriter> invokeCorrectNotFoundRoute(InvokeInfo invokeInfo, DynamicInfo info, RouteData data) {
		BaseRouteInfo route = invokeInfo.getRoute();
		RequestContext requestCtx = invokeInfo.getRequestCtx();
		ProxyStreamHandle handler = invokeInfo.getHandler();
		RouteInfoForNotFound notFoundData = (RouteInfoForNotFound) data;
		NotFoundException notFoundExc = notFoundData.getNotFoundException();

		RouterRequest req = requestCtx.getRequest();
		if(notFoundData.getNotFoundException() == null) {
			throw new IllegalArgumentException("must have not found exception to be here");
		} else if(req.queryParams.containsKey("webpiecesShowPage")) {
			//This is a callback so render the original webapp developer's not found page into the iframe
			return invokeRealNotFouuud(invokeInfo, info, data);
		}
		
		//ok, in dev mode, we hijack the not found page with one with a route list AND an iframe containing the developers original
		//notfound page
		
		log.error("(Development only log message) Route not found!!! Either you(developer) typed the wrong url OR you have a bad route.  Either way,\n"
				+ " something needs a'fixin.  req="+req, notFoundExc);

		Injector injector = route.getFilterChainCreationInfo().getInjector();

		RouteInfo routeInfo = new RouteInfo(new RouteModuleInfo("", null), "/org/webpieces/devrouter/impl/NotFoundController.notFound");
		BaseRouteInfo webpiecesNotFoundRoute = new BaseRouteInfo(
				injector, routeInfo, 
				new SvcProxyFixedRoutes(serviceInvoker, futureUtil),
				new ArrayList<>(), RouteType.NOT_FOUND);
		
		SvcProxyFixedRoutes svcProxy = new SvcProxyFixedRoutes(serviceInvoker, futureUtil);
		LoadedController newLoadedController = controllerFinder.loadGenericController(injector, routeInfo, false).getLoadedController();
		DynamicInfo newInfo = new DynamicInfo(newLoadedController, svcProxy);
		
		String reason = "Your route was not found in routes table";
		if(notFoundExc != null)
			reason = notFoundExc.getMessage();
		
		RouterRequest newRequest = new RouterRequest();
		newRequest.putMultipart("webpiecesError", "Exception message="+reason);
		newRequest.putMultipart("url", req.relativePath);
		newRequest.isHttps = req.isHttps;
		newRequest.isBackendRequest = req.isBackendRequest;
		newRequest.originalRequest = req.originalRequest;
		newRequest.requestState.put(NotFoundController.ORIGINAL_REQUEST, req);
		
		ApplicationContext ctx = webInjector.getAppContext();
		RequestContext overridenCtx = new RequestContext(requestCtx.getValidation(), (FlashSub) requestCtx.getFlash(), requestCtx.getSession(), newRequest, ctx);
		InvokeInfo newInvokeInfo = new InvokeInfo(webpiecesNotFoundRoute, overridenCtx, handler, false);
		return super.invokeNotFound(newInvokeInfo, newInfo, data);
	}

	private CompletableFuture<StreamWriter> invokeRealNotFouuud(InvokeInfo invokeInfo, DynamicInfo info,
			RouteData data) {
		
		DynamicInfo newInfo = info;
		//If we haven't loaded it already, load it now
		if(info.getLoadedController() == null) {
			BaseRouteInfo route = invokeInfo.getRoute();
			Injector injector = route.getFilterChainCreationInfo().getInjector();
			LoadedController controllerInst = controllerFinder.loadNotFoundController(injector, route.getRouteInfo(), false);
			Service<MethodMeta, Action> service = controllerFinder.loadFilters(route.getFilterChainCreationInfo(), false);
			newInfo = new DynamicInfo(controllerInst, service);
		}
		
		return super.invokeNotFound(invokeInfo, newInfo, data);
	}
}
