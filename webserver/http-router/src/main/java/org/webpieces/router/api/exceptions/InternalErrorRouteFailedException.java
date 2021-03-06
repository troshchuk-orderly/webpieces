package org.webpieces.router.api.exceptions;

import java.util.concurrent.CompletionException;

public class InternalErrorRouteFailedException extends CompletionException {

	private static final long serialVersionUID = 1L;
	private Object failedRoute;

	public InternalErrorRouteFailedException(String message, Throwable cause) {
		super(message, cause);
	}

	public InternalErrorRouteFailedException(String message) {
		super(message);
	}

	public InternalErrorRouteFailedException(Throwable cause, Object failedRoute) {
		super(cause);
		this.failedRoute = failedRoute;
	}

	public Object getFailedRoute() {
		return failedRoute;
	}

}
