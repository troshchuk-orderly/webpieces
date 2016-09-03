package PACKAGE;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.webpieces.compiler.api.CompileConfig;
import org.webpieces.devrouter.api.DevRouterModule;
import org.webpieces.templating.api.DevTemplateModule;
import org.webpieces.templating.api.TemplateCompileConfig;
import org.webpieces.util.file.VirtualFile;
import org.webpieces.util.file.VirtualFileImpl;

import com.google.inject.Module;
import com.google.inject.util.Modules;

public class CLASSNAMESemiProdServer {

	private static final Logger log = LoggerFactory.getLogger(CLASSNAMEServer.class);
	
	//NOTE: This whole project brings in jars that the main project does not have and should never
	//have like the eclipe compiler, a classloading compilter jar(webpieces' runtimecompile.jar
	//and finally the http-router-dev.jar which has the guice module that overrides certain core
	//webserver classes to put in a place a runtime compiler so we can compiler your code as you
	//develop
	public static void main(String[] args) throws InterruptedException {
		new CLASSNAMESemiProdServer(false).start();
		
		synchronized(CLASSNAMESemiProdServer.class) {
			CLASSNAMESemiProdServer.class.wait();
		}
	}
	
	private CLASSNAMEServer server;

	public CLASSNAMESemiProdServer(boolean usePortZero) {
		String filePath1 = System.getProperty("user.dir");
		log.info("running from dir="+filePath1);
		
        String directory = CLASSNAMEDevServer.modifyForIDE(filePath1);
		
		//list all source paths here as you add them(or just create for loop)
		//These are the list of directories that we detect java file changes under
		List<VirtualFile> srcPaths = new ArrayList<>();
		srcPaths.add(new VirtualFileImpl(directory+"/TEMPLATEAPPNAME-prod/src/main/java"));
		
		VirtualFile metaFile = new VirtualFileImpl(directory + "/TEMPLATEAPPNAME-prod/src/main/resources/appmeta.txt");
		log.info("LOADING from meta file="+metaFile.getCanonicalPath());
		
		//html and json template file encoding...
		TemplateCompileConfig templateConfig = new TemplateCompileConfig(CLASSNAMEServer.ALL_FILE_ENCODINGS);
		//Use overrides from DevTemplateModule which compiles html files on-demand...
		Module platformOverrides = new DevTemplateModule(templateConfig);
		
		ServerConfig config = new ServerConfig();
		if(usePortZero) {
			config.setHttpPort(0);
			config.setHttpsPort(0);
		}
		
		config.setMetaFile(metaFile);
		server = new CLASSNAMEServer(platformOverrides, null, config);
	}
	
	public void start() throws InterruptedException {
		server.start();		
	}

	public void stop() {
		server.stop();
	}
}