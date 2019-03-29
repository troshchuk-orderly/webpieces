package org.webpieces.router.impl;

import org.webpieces.router.api.routing.ReverseRouteLookup;
import org.webpieces.router.impl.model.bldr.data.DomainRouter;

public class RoutingHolder {

	private ReverseRouteLookup reverseRouteLookup;
	private DomainRouter domainRouter;

	public void setReverseRouteLookup(ReverseRouteLookup reverseRouteLookup) {
		this.reverseRouteLookup = reverseRouteLookup;
	}

	
	public ReverseRouteLookup getReverseRouteLookup() {
		return reverseRouteLookup;
	}
	
	public DomainRouter getDomainRouter() {
		return domainRouter;
	}


	public void setDomainRouter(DomainRouter domainRouter) {
		this.domainRouter = domainRouter;
	}

}
