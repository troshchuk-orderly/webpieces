package org.webpieces.router.impl.model.bldr;

public interface DomainRouteBuilder {

	/**
	 * Gets the RouteBuilder you can add routes to that will match on all domains not specified when
	 * creating a DomainScopedRouteBuilder.  If you create a DomainScopedRouteBuilder, those domains
	 * are then excluded and no routes built with this builder will match when requests come from the
	 * DomainScopedRouteBuilder domains.
	 * 
	 * @return
	 */
	RouteBuilder getAllDomainsRouteBuilder();

	/**
	 * Only used if you host multiple domains(like me)!!!!!  All paths refer to all domains EXCEPT the ones defined
	 * in a DomainScopedRouter.  Only domains matching the pattern of domainRegEx will see these pages and
	 * the rest are served a not found (or are served the page defined in another module rather than the
	 * one for the specific domains)
	 *  
	 * @param path
	 * @param isSecure true if only available over https otherwise available over http and https
	 * @return
	 */
	RouteBuilder getDomainScopedRouteBuilder(String domainRegEx);
	
}
