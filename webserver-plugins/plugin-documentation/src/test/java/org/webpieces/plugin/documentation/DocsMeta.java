package org.webpieces.plugin.documentation;

import java.util.List;

import org.webpieces.plugin.backend.BackendPlugin;
import org.webpieces.plugin.documentation.DocumentationConfig;
import org.webpieces.plugin.documentation.WebpiecesDocumentationPlugin;
import org.webpieces.plugins.fortesting.EmptyModule;
import org.webpieces.plugins.fortesting.FillerRoutes;
import org.webpieces.router.api.plugins.Plugin;
import org.webpieces.router.api.routes.Routes;
import org.webpieces.router.api.routes.WebAppConfig;
import org.webpieces.router.api.routes.WebAppMeta;

import com.google.common.collect.Lists;
import com.google.inject.Module;

public class DocsMeta implements WebAppMeta {
	private WebAppConfig pluginConfig;

	@Override
	public void initialize(WebAppConfig pluginConfig) {
		this.pluginConfig = pluginConfig;
	}
	
	@Override
    public List<Module> getGuiceModules() {
		return Lists.newArrayList(new EmptyModule());
	}
	
	@Override
    public List<Routes> getRouteModules() {
		return Lists.newArrayList(new FillerRoutes());
	}
	
	@Override
	public List<Plugin> getPlugins() {
		return Lists.newArrayList(
				new BackendPlugin(pluginConfig.getCmdLineArguments()),
				new WebpiecesDocumentationPlugin(new DocumentationConfig())
		);
	}
}