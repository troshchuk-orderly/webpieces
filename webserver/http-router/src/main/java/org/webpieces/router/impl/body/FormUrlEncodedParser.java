package org.webpieces.router.impl.body;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.inject.Inject;

import org.webpieces.ctx.api.RouterRequest;
import org.webpieces.data.api.DataWrapper;
import org.webpieces.router.api.RouterConfig;
import org.webpieces.util.urlparse.UrlEncodedParser;

public class FormUrlEncodedParser implements BodyParser {

	private UrlEncodedParser parser;
	private Charset encoding;

	@Inject
	public FormUrlEncodedParser(UrlEncodedParser parser, RouterConfig config) {
		this.parser = parser;
		this.encoding = config.getDefaultFormAcceptEncoding();
	}
	
	@Override
	public void parse(DataWrapper body, RouterRequest routerRequest) {
		String multiPartData = body.createStringFrom(0, body.getReadableSize(), encoding);
		Map<String, List<String>> keyToValues = new HashMap<>();
		parser.parse(multiPartData, (key, val) -> addToMap(keyToValues, key, val));
		routerRequest.multiPartFields = keyToValues;
	}

	private Void addToMap(Map<String, List<String>> keyToValues, String key, String val) {
		List<String> list = keyToValues.get(key);
		if(list == null) {
			list = new ArrayList<>();
			keyToValues.put(key, list);
		}

		list.add(val);
		return null;
	}
	
	

}
