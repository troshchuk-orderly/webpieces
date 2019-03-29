package org.webpieces.plugins.backend.login;

import org.webpieces.ctx.api.HttpMethod;
import org.webpieces.router.api.routing.Port;
import org.webpieces.router.api.routing.RouteId;
import org.webpieces.router.impl.model.bldr.RouteBuilder;
import org.webpieces.router.impl.model.bldr.ScopedRouteBuilder;
import org.webpieces.webserver.api.login.AbstractLoginRoutes;

public class BackendLoginRoutes extends AbstractLoginRoutes {

	/**
	 * @param controller
	 * @param basePath The 'unsecure' path that has a login page so you can get to the secure path
	 * @param securePath The path for the secure filter that ensures everyone under that path is secure
	 */
	public BackendLoginRoutes(String controller, String basePath, String securePath) {
		super(controller, basePath, securePath, "password");
	}
	
	@Override
	protected RouteId getPostLoginRoute() {
		return BackendLoginRouteId.POST_BACKEND_LOGIN;
	}

	@Override
	protected RouteId getRenderLoginRoute() {
		return BackendLoginRouteId.BACKEND_LOGIN;
	}

	@Override
	protected RouteId getRenderLogoutRoute() {
		return BackendLoginRouteId.BACKEND_LOGOUT;
	}

	@Override
	protected String getSessionToken() {
		return BackendLoginController.TOKEN;
	}

	@Override
	protected void addLoggedInHome(RouteBuilder baseRouter, ScopedRouteBuilder scope1Router) {
		ScopedRouteBuilder scopedRouter = scope1Router.getScopedRouteBuilder("/secure");
		scopedRouter.addRoute(Port.HTTPS, HttpMethod.GET ,   "/loggedinhome",        "org.webpieces.plugins.backend.BackendController.home", BackendLoginRouteId.BACKEND_LOGGED_IN_HOME);
		
		ScopedRouteBuilder scoped2 = baseRouter.getScopedRouteBuilder(basePath);
		scoped2.addRoute(Port.BOTH, HttpMethod.GET,  "", "org.webpieces.plugins.backend.BackendController.redirectToLogin", BackendLoginRouteId.LOGGED_OUT_LANDING);
	}

}
