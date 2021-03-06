package org.webpieces.router.impl.routebldr;

import java.util.regex.Pattern;

import org.webpieces.router.api.routes.FilterPortType;
import org.webpieces.router.api.routes.RouteFilter;

public class FilterInfo<T> {

	private String path;
	private Class<? extends RouteFilter<T>> filter;
	private T initialConfig;
	private Pattern patternToMatch;
	private FilterPortType portType;
	private int filterApplyLevel;
	private boolean applyToPackage;

	public FilterInfo(String regExPathSrc, Class<? extends RouteFilter<T>> filter, T initialConfig, FilterPortType type, int filterApplyLevel) {
		this(regExPathSrc, false, filter, initialConfig, type, filterApplyLevel);
	}
	
	public FilterInfo(String regExPathSrc, boolean applyToPackage, Class<? extends RouteFilter<T>> filter, T initialConfig, FilterPortType type, int filterApplyLevel) {
		this.path = regExPathSrc;
		this.applyToPackage = applyToPackage;
		this.filterApplyLevel = filterApplyLevel;
		this.patternToMatch = Pattern.compile(regExPathSrc);
		this.filter = filter;
		this.initialConfig = initialConfig;
		this.portType = type;
	}

	public String getPath() {
		return path;
	}

	public Class<? extends RouteFilter<T>> getFilter() {
		return filter;
	}

	public T getInitialConfig() {
		return initialConfig;
	}

	public Pattern getPatternToMatch() {
		return patternToMatch;
	}

	public FilterPortType getPortType() {
		return portType;
	}

	public boolean securityMatch(boolean isHttps) {
		if(portType == FilterPortType.ALL_FILTER)
			return true;
		else if(isHttps && portType == FilterPortType.HTTPS_FILTER)
			return true;
		else if(!isHttps && portType == FilterPortType.HTTP_FILTER)
			return true;
		
		return false;
	}

	@Override
	public String toString() {
		return "FilterInfo [path=" + path + ", filter=" + filter + ", portType=" + portType + ", level="+filterApplyLevel+"]";
	}

	public int getFilterApplyLevel() {
		return filterApplyLevel;
	}

	public boolean isApplyToPackage() {
		return applyToPackage;
	}
	
}
