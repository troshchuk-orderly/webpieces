package org.webpieces.router.impl.routebldr;

import static org.webpieces.ctx.api.HttpMethod.GET;
import static org.webpieces.ctx.api.HttpMethod.POST;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.webpieces.ctx.api.HttpMethod;
import org.webpieces.router.api.controller.actions.Action;
import org.webpieces.router.api.routebldr.ScopedRouteBuilder;
import org.webpieces.router.api.routes.CrudRouteIds;
import org.webpieces.router.api.routes.Port;
import org.webpieces.router.api.routes.RouteId;
import org.webpieces.router.impl.AbstractRouteMeta;
import org.webpieces.router.impl.BaseRouteInfo;
import org.webpieces.router.impl.FilterInfo;
import org.webpieces.router.impl.ResettingLogic;
import org.webpieces.router.impl.StaticRoute;
import org.webpieces.router.impl.StaticRouteMeta;
import org.webpieces.router.impl.UrlPath;
import org.webpieces.router.impl.dto.RouteType;
import org.webpieces.router.impl.loader.BinderAndLoader;
import org.webpieces.router.impl.loader.LoadedController;
import org.webpieces.router.impl.loader.svc.MethodMeta;
import org.webpieces.router.impl.loader.svc.SvcProxyForContent;
import org.webpieces.router.impl.loader.svc.SvcProxyForHtml;
import org.webpieces.router.impl.model.RouteBuilderLogic;
import org.webpieces.router.impl.model.RouterInfo;
import org.webpieces.router.impl.routers.AbstractRouter;
import org.webpieces.router.impl.routers.ContentRouter;
import org.webpieces.router.impl.routers.DynamicInfo;
import org.webpieces.router.impl.routers.HtmlRouter;
import org.webpieces.router.impl.routers.MatchInfo;
import org.webpieces.router.impl.routers.ScopedRouter;
import org.webpieces.util.file.VirtualFile;
import org.webpieces.util.file.VirtualFileClasspath;
import org.webpieces.util.file.VirtualFileFactory;
import org.webpieces.util.filters.Service;
import org.webpieces.util.logging.Logger;
import org.webpieces.util.logging.LoggerFactory;

public class ScopedRouteBuilderImpl implements ScopedRouteBuilder {

	private static final Logger log = LoggerFactory.getLogger(ScopedRouteBuilderImpl.class);

	protected final ResettingLogic resettingLogic;
	protected final RouteBuilderLogic holder;
	protected final RouterInfo routerInfo;
	private final Map<String, ScopedRouteBuilderImpl> pathToBuilder = new HashMap<>();

	private final List<RouterAndInfo> newDynamicRoutes = new ArrayList<>();
	private final List<StaticRouteMeta> staticRoutes = new ArrayList<>();

	private List<StaticRoute> allStaticRoutes;
	
	public ScopedRouteBuilderImpl(RouterInfo routerInfo, List<StaticRoute> allStaticRoutes, RouteBuilderLogic holder, ResettingLogic resettingLogic) {
		this.routerInfo = routerInfo;
		this.allStaticRoutes = allStaticRoutes;
		this.holder = holder;
		this.resettingLogic = resettingLogic;
	}
	
	@Override
	public void addContentRoute(Port port, HttpMethod method, String path, String controllerMethod) {
		UrlPath p = new UrlPath(routerInfo, path);
		RouteInfo routeInfo = new RouteInfo(CurrentPackage.get(), controllerMethod);
		//MUST DO loadControllerIntoMeta HERE so stack trace has customer's line in it so he knows EXACTLY what 
		//he did wrong when reading the exception!!
		BinderAndLoader container = holder.getFinder().loadContentController(resettingLogic.getInjector(), routeInfo, true);
		
		MatchInfo matchInfo = new MatchInfo(p, port, method, holder.getUrlEncoding());
		ContentRouter router = new ContentRouter(holder.getRouteInvoker2(), matchInfo, container.getBinder());
		SvcProxyForContent svc = new SvcProxyForContent(holder.getSvcProxyLogic(), container.getBinder());
		RouterAndInfo routerAndInfo = new RouterAndInfo(router, routeInfo, RouteType.HTML, container.getLoadedController(), svc);
		
		newDynamicRoutes.add(routerAndInfo);
		log.info("scope:'"+routerInfo+"' added content route="+matchInfo+" method="+routeInfo.getControllerMethodString());
	}
	
	@Override
	public void addRoute(Port port, HttpMethod method, String path, String controllerMethod, RouteId routeId) {
		boolean checkSecureToken = false;
		if(method == HttpMethod.POST)
			checkSecureToken = true;
		addRoute(port, method, path, controllerMethod, routeId, checkSecureToken);
	}

	@Override
	public void addRoute(Port port, HttpMethod method, String path, String controllerMethod, RouteId routeId,
			boolean checkToken) {
		UrlPath p = new UrlPath(routerInfo, path);

		boolean isPostOnly = method == HttpMethod.POST;
		RouteInfo routeInfo = new RouteInfo(CurrentPackage.get(), controllerMethod);

		//MUST DO loadControllerIntoMetat HERE so stack trace has customer's line in it so he knows EXACTLY what 
		//he did wrong when reading the exception!!
		LoadedController loadedController = holder.getFinder().loadHtmlController(resettingLogic.getInjector(), routeInfo, true, isPostOnly);
		
		MatchInfo matchInfo = new MatchInfo(p, port, method, holder.getUrlEncoding());
		HtmlRouter router = new HtmlRouter(holder.getRouteInvoker2(), matchInfo, checkToken);	
		SvcProxyForHtml svc = new SvcProxyForHtml(holder.getSvcProxyLogic());
		RouterAndInfo routerAndInfo = new RouterAndInfo(router, routeInfo, RouteType.HTML, loadedController, svc);
		
		newDynamicRoutes.add(routerAndInfo);
		resettingLogic.getReverseRoutes().addRoute(routeId, router);
		log.info("scope:'"+routerInfo+"' added route="+router.getMatchInfo()+" method="+routeInfo.getControllerMethodString());
	}

	/*
	 * Adds routes like the following all in one method
	 * 
	 * 	//addRoute(GET ,   "/user/list",        "crud/CrudUserController.userList", listRoute);
	 *	//addRoute(GET ,   "/user/new",         "crud/CrudUserController.userAddEdit", addRoute);
	 *	//addRoute(GET ,   "/user/edit/{id}",   "crud/CrudUserController.userAddEdit", editRoute);
	 *	//addRoute(POST,   "/user/post",        "crud/CrudUserController.postSaveUser", saveRoute);
	 *	//addRoute(GET,    "/user/delete/{id}", "crud/CrudUserController.postDeleteUser", deleteRoute);
	 */
	@Override
	public void addCrud(Port port, String entity, String controller, CrudRouteIds routeIds) {
		RouteId listRoute = routeIds.getListRoute();
		RouteId addRoute = routeIds.getAddRoute();
		RouteId editRoute = routeIds.getEditRoute(); 
		RouteId postSaveRoute = routeIds.getPostSaveRoute();
		RouteId confirmDelete = routeIds.getConfirmDelete();
		RouteId postDeleteRoute = routeIds.getPostDeleteRoute();
		
		String entityWithCapital = entity.substring(0, 1).toUpperCase() + entity.substring(1);
		addRoute(port, GET , "/"+entity+"/list",        controller+"."+entity+"List", listRoute);
		addRoute(port, GET , "/"+entity+"/new",         controller+"."+entity+"AddEdit", addRoute);
		addRoute(port, GET , "/"+entity+"/edit/{id}",   controller+"."+entity+"AddEdit", editRoute);
		addRoute(port, POST, "/"+entity+"/post",        controller+".postSave"+entityWithCapital, postSaveRoute);

		//get the confirm delete page
		addRoute(port, GET,  "/"+entity+"/confirmdelete/{id}", controller+".confirmDelete"+entityWithCapital, confirmDelete);
		//NOTE: Browsers don't support DELETE.  POST might make more sense here for delete but GET is way way less html
		//code(ok, 1 line instead of 3).  There are hacks with javascript to support DELETE but seriously, we should just
		//KISS and YAGNI (google that if you don't know).  
		//HOWEVER, If you don't like this, copy and paste this method and modify to be a POST OR DELETE and add the 
		//javascript for next time
		addRoute(port, POST, "/"+entity+"/delete/{id}", controller+".postDelete"+entityWithCapital, postDeleteRoute);
	}
	
	@Override
	public void addStaticDir(Port port, String urlPath, String fileSystemPath, boolean isOnClassPath) {
		if(!urlPath.endsWith("/"))
			throw new IllegalArgumentException("Static directory so urlPath must end with a /");
		addStaticRoute(port, urlPath, fileSystemPath, isOnClassPath);
	}

	@Override
	public void addStaticFile(Port port, String urlPath, String fileSystemPath, boolean isOnClassPath) {
		if(urlPath.endsWith("/"))
			throw new IllegalArgumentException("Static file so urlPath must NOT end with a /");
		addStaticRoute(port, urlPath, fileSystemPath, isOnClassPath);
	}

	private void addStaticRoute(Port port, String urlPath, String fileSystemPath, boolean isOnClassPath) {
		if(isOnClassPath)
			addStaticClasspathFile(port, urlPath, fileSystemPath);
		else
			addStaticLocalFile(port, urlPath, fileSystemPath);
	}
	
	private void addStaticClasspathFile(Port port, String urlPath, String fileSystemPath) {
		if(!fileSystemPath.startsWith("/"))
			throw new IllegalArgumentException("Classpath resources must start with a / and be absolute on the classpath");
		
		boolean isDirectory = fileSystemPath.endsWith("/");
		VirtualFile file = new VirtualFileClasspath(fileSystemPath, getClass(), isDirectory);
		
		StaticRoute route = new StaticRoute(holder.getRouteInvoker2(), port, new UrlPath(routerInfo, urlPath), file, true, holder.getCachedCompressedDirectory());
		allStaticRoutes.add(route);
		log.info("scope:'"+routerInfo+"' adding static route="+route.getFullPath()+" fileSystemPath="+route.getFileSystemPath());
		StaticRouteMeta meta = new StaticRouteMeta(route, holder.getUrlEncoding());
		staticRoutes.add(meta);
	}
	
	private void addStaticLocalFile(Port port, String urlPath, String fileSystemPath) {
		if(fileSystemPath.startsWith("/"))
			throw new IllegalArgumentException("Absolute file system path is not supported as it is not portable across OS when done wrong.  Override the modules working directory instead");
		
		File workingDir = holder.getConfig().getWorkingDirectory();
		VirtualFile file = VirtualFileFactory.newFile(workingDir, fileSystemPath);
		
		StaticRoute route = new StaticRoute(holder.getRouteInvoker2(), port, new UrlPath(routerInfo, urlPath), file, false, holder.getCachedCompressedDirectory());
		allStaticRoutes.add(route);
		log.info("scope:'"+routerInfo+"' adding static route="+route.getFullPath()+" fileSystemPath="+route.getFileSystemPath());
		StaticRouteMeta meta = new StaticRouteMeta(route, holder.getUrlEncoding());
		staticRoutes.add(meta);
	}
	
	/**
	 * Note, if someone passes in /car/civic, we have to create a ScopedRouteBuilder and
	 * then call getScopedRouteBuilder("/civic") on that so that lookups work when they
	 * do getScopedRouteBuilder("/car") in another module.
	 */
	@Override
	public ScopedRouteBuilder getScopedRouteBuilder(String fullPath) {
		if(fullPath == null)
			throw new IllegalArgumentException("path cannot be null");
		else if(!fullPath.startsWith("/"))
			throw new IllegalArgumentException("path must start with /");
		else if(fullPath.endsWith("/"))
			throw new IllegalArgumentException("path must not end with /");
		else if(fullPath.length() <= 1)
			throw new IllegalArgumentException("path size must be greater than 1");
		
		String[] split = splitInTwo(fullPath);
		String path = fullPath;
		if(split != null)
			path = split[0];
		
		ScopedRouteBuilderImpl r = pathToBuilder.get(path);
		if(r == null) {
			r = new ScopedRouteBuilderImpl(new RouterInfo(routerInfo.getDomain(), routerInfo.getPath()+fullPath), allStaticRoutes, holder, resettingLogic);
			pathToBuilder.put(path, r);
		}
		
		if(split == null)
			return r;
		else
			return r.getScopedRouteBuilder(split[1]);
	}
	
	private String[] splitInTwo(String fullPath) {
		if(!fullPath.startsWith("/"))
			throw new IllegalArgumentException("fullPath should start with a / but did not");
		
		int indexOf = fullPath.indexOf("/", 1);
		if(indexOf < 0)
			return null;
		
		String path = fullPath.substring(0, indexOf);
		String leftover = fullPath.substring(indexOf);
		return new String[] {path, leftover};
	}
	
	public ScopedRouter build(List<FilterInfo<?>> routeFilters) {
		List<AbstractRouteMeta> routes = buildRoutes(routeFilters);
		List<AbstractRouter> routers = buildRoutes2(routeFilters);
		
		Map<String, ScopedRouter> pathToRouter = buildScopedRouters(routeFilters);
		
		return new ScopedRouter(routerInfo, pathToRouter, routes, routers);
	}

	protected Map<String, ScopedRouter> buildScopedRouters(List<FilterInfo<?>> routeFilters) {
		Map<String, ScopedRouter> pathToRouter = new HashMap<>();
		for(Entry<String, ScopedRouteBuilderImpl> entry : pathToBuilder.entrySet()) {
			ScopedRouter router2 = entry.getValue().build(routeFilters);
			pathToRouter.put(entry.getKey(), router2);
		}
		return pathToRouter;
	}

	protected List<AbstractRouter> buildRoutes2(List<FilterInfo<?>> routeFilters) {
		List<AbstractRouter> routers = new ArrayList<>();
		for(RouterAndInfo routerAndInfo : newDynamicRoutes) {
			AbstractRouter router = routerAndInfo.getRouter();
			MatchInfo matchInfo = router.getMatchInfo();
			String path = matchInfo.getFullPath();
			Port port = matchInfo.getExposedPorts();
			List<FilterInfo<?>> filters = findMatchingFilters(path, port, routeFilters);
			
			BaseRouteInfo baseRouteInfo = new BaseRouteInfo(
					resettingLogic.getInjector(), routerAndInfo.getRouteInfo(), routerAndInfo.getSvcProxy(), filters, routerAndInfo.getRouteType());
			Service<MethodMeta, Action> service = holder.getFinder().loadFilters(baseRouteInfo, true);

			router.setBaseRouteInfo(baseRouteInfo);
			router.setDynamicInfo(new DynamicInfo(routerAndInfo.getLoadedController(), service));
			routers.add(router);
		}
		
		return routers;
	}
	
	protected List<AbstractRouteMeta> buildRoutes(List<FilterInfo<?>> routeFilters) {
		List<AbstractRouteMeta> routes = new ArrayList<>();
		routes.addAll(staticRoutes);
		return routes;
	}

	public List<FilterInfo<?>> findMatchingFilters(String path, Port exposedPorts, List<FilterInfo<?>> routeFilters) {
		boolean isHttpsOnly = exposedPorts == Port.HTTPS;
		List<FilterInfo<?>> matchingFilters = new ArrayList<>();
		for(FilterInfo<?> info : routeFilters) {
			if(!info.securityMatch(isHttpsOnly))
				continue; //skip this filter
			
			Pattern patternToMatch = info.getPatternToMatch();
			Matcher matcher = patternToMatch.matcher(path);
			if(matcher.matches()) {
				log.debug(() -> "Adding filter="+info.getFilter()+" to path="+path);
				matchingFilters.add(0, info);
			}
		}
		return matchingFilters;
	}
}