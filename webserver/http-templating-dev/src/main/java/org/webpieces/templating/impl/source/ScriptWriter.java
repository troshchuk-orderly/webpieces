package org.webpieces.templating.impl.source;

import java.util.List;
import java.util.Stack;
import java.util.regex.Pattern;

import javax.inject.Inject;

import org.webpieces.templating.api.AbstractTag;
import org.webpieces.templating.api.HtmlTag;
import org.webpieces.templating.api.HtmlTagLookup;
import org.webpieces.templating.api.Tag;

public class ScriptWriter {

	//Some compilers can't deal with long lines so let's max at 40k
    protected static final int maxLineLength = 30000;
    
	private Pattern pattern = Pattern.compile("\"");

	private ThreadLocal<Stack<String>> tagStack = new ThreadLocal<>();
	
	private TagLookup tagLookup;

	private HtmlTagLookup htmlTagLookup;

	@Inject
	public ScriptWriter(HtmlTagLookup htmlTagLookup, TagLookup lookup) {
		this.htmlTagLookup = htmlTagLookup;
		tagLookup = lookup;
	}
	
	public void printHead(ScriptOutputImpl sourceCode, String packageStr, String className) {
		tagStack.set(new Stack<>());

		if(packageStr != null && !"".equals(packageStr.trim())) {
			sourceCode.println("package "+packageStr);
		}
		
        sourceCode.print("class ");
        //This generated classname is parsed when creating cleanStackTrace.
        //The part after "Template_" is used as key when
        //looking up the file on disk this template-class is generated from.
        //cleanStackTrace is looking in TemplateLoader.templates

        sourceCode.print(className);
        sourceCode.println(" extends org.webpieces.templating.impl.GroovyTemplateSuperclass {");
        sourceCode.println("  public Object run() {");
        sourceCode.println("    use(org.webpieces.templating.impl.source.GroovyExtensions) {");
//        for (String n : extensionsClassnames) {
//            println("use(_('" + n + "')) {");
//        }
	}

	public void printEnd(ScriptOutputImpl sourceCode) {
		sourceCode.println("    }");
		sourceCode.println("  }");
		sourceCode.println("}");
		
		tagStack.set(null);
	}

	public void printPlain(TokenImpl token, ScriptOutputImpl sourceCode) {
		String srcText = token.getValue();
		if(srcText.length() < maxLineLength) {
			String text = addEscapesToSrc(srcText);
			sourceCode.println("      __out.print(\""+text+"\");");
			return;
		}

		//while our max line lenght is 40k, the addEscapes lengthens the text for each new line and each
		//'/' character BUT someone would have to double the size so just throw in that one case to notify
		//the user before groovy breaks(this should not happen, but who knows....fail fast)
		while(srcText.length() > 0) {
			int cutpoint = Math.min(srcText.length(), maxLineLength);
			String prefix = srcText.substring(0, cutpoint);
			srcText = srcText.substring(cutpoint);
			String text = addEscapesToSrc(prefix);
			sourceCode.println("      __out.print(\""+text+"\");");
		}
	}

	private String addEscapesToSrc(String srcText) {
        String text = srcText.replace("\\", "\\\\");
        text = pattern.matcher(text).replaceAll("\\\\\"");
        text = text.replaceAll("\n", "\\\\n");
        return text;
	}

	public void printScript() {
		
	}

	public void printExpression(TokenImpl token, ScriptOutputImpl sourceCode) {
		String expr = token.getValue().trim();
        sourceCode.print("      __out.print(useFormatter("+expr+"));");
        sourceCode.appendTokenComment(token);
        sourceCode.println();
	}

	public void printMessage() {
		
	}

	public void printAction(boolean b) {
		
	}

	/**
	 * This is for tags with no body(or ones where the body is optional and #{../}# was used.
	 * 
	 * @param token
	 * @param sourceCode
	 */
	public void printStartEndTag(TokenImpl token, ScriptOutputImpl sourceCode) {
		String expr = token.getCleanValue();
		int indexOfSpace = expr.indexOf(" ");
		String tagName = expr;
		if(indexOfSpace > 0) {
			//we have arguments to parse
			throw new UnsupportedOperationException("not done yet...need to implement arguments");
		}
		
		Tag tag = tagLookup.lookup(tagName, token);
		HtmlTag htmltag = htmlTagLookup.lookup(tagName);
		if(tag != null) {
			tag.generateStartAndEnd(sourceCode, token);
		} else if(htmltag != null) {
			throw new IllegalArgumentException("Unknown tag="+tagName+" location="+token.getSourceLocation());
		}
	}

	public void printStartTag(TokenImpl token, TokenImpl previousToken, ScriptOutputImpl sourceCode) {
		String expr = token.getCleanValue();
		int indexOfSpace = expr.indexOf(" ");
		String tagName = expr;
		if(indexOfSpace > 0) {
			tagName = expr.substring(0, indexOfSpace);
		}		

		tagStack.get().push(tagName);
		
		Tag tag = tagLookup.lookup(tagName, token);
		HtmlTag htmltag = htmlTagLookup.lookup(tagName);
		if(tag != null) {
			if(tag instanceof AbstractTag) {
				AbstractTag abstractTag = (AbstractTag) tag;
				//Things like #{else}# tag are given chance to validate that it is only after an #{if}# tag
				abstractTag.validatePreviousSibling(token, previousToken);
			}
			tag.generateStart(sourceCode, token);
		} else if(htmltag != null) {
			throw new IllegalArgumentException("Unknown tag="+tagName+" location="+token.getSourceLocation());
		}

//        sourceCode.print("      __out.print(escapeHtml("+expr+"));");
//        sourceCode.appendTokenComment(token);
//        sourceCode.println();
	}

	public void printEndTag(TokenImpl token, ScriptOutputImpl sourceCode) {
		String expr = token.getCleanValue();
		if(tagStack.get().size() == 0)
			throw new IllegalArgumentException("Unmatched end tag #{/"+expr+"}# as the begin tag was not found..only the end tag. location="+token.getSourceLocation());
		String startTagName = tagStack.get().pop();
		if(!expr.equals(startTagName))
			throw new IllegalArgumentException("Unmatched end tag #{/"+expr+"}# as the begin tag appears to be #{"+startTagName
			+"}# which does not match.  end tag location="+token.getSourceLocation());

		Tag tag = tagLookup.lookup(expr, token);
		if(tag != null)
			tag.generateEnd(sourceCode, token);
	}

	public void unprintUpToLastNewLine() {
		
	}

	public void verifyTagIntegrity(List<TokenImpl> tokens) {
		
	}

}
