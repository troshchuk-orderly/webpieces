package org.webpieces.templatingdev.api;

public abstract class AbstractGroovyGen implements GroovyGen {

	@Override
	public void generateStartAndEnd(ScriptOutput sourceCode, Token token, int uniqueId) {
		String name = getName();
		throw new IllegalArgumentException(name+" tag can only be used with a body so"
				+ " #{"+name+"/} is not usable. "+token.getSourceLocation(true));
	}
	
	/**
	 * Only needed in special cases like the #{else}# MUST have an #{/if} before it
	 */
	public void validatePreviousSibling(Token current, Token prevous) {
	}
}
