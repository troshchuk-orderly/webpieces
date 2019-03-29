package org.webpieces.webserver.domains.app;

import static org.webpieces.ctx.api.HttpMethod.GET;
import static org.webpieces.router.api.routing.Port.BOTH;

import org.webpieces.router.api.routing.BasicRoutes;
import org.webpieces.router.impl.model.bldr.RouteBuilder;

public class Domain1Routes implements BasicRoutes {

	@Override
	public void configure(RouteBuilder bldr) {
		bldr.addRoute(BOTH, GET ,     "/domain1",             "DomainsController.domain1", DomainsRouteId.DOMAIN1);
		
		bldr.setPageNotFoundRoute("DomainsController.notFoundDomain1");
		bldr.setInternalErrorRoute("/org/webpieces/webserver/basic/app/biz/BasicController.internalError");
	}

}
