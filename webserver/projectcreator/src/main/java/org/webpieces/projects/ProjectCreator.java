package org.webpieces.projects;

import java.io.File;
import java.io.IOException;
import java.util.Scanner;

public class ProjectCreator {

	private String version;

	public ProjectCreator(String version) {
		this.version = version;
	}

	public static void main(String[] args) throws IOException {
		String version = System.getProperty("webpieces.version");
		if(version == null)
			throw new IllegalArgumentException("We must have the version on project creation");
		System.out.println("Starting up VERSION="+version+" args.length="+args.length);

		ProjectCreator creator = new ProjectCreator(version);
		if(args.length > 0)
			creator.createProject(args);
		else
			creator.start();
	}

	private void createProject(String[] args) throws IOException {
		if(args.length != 3)
			throw new IllegalArgumentException("./createProject {className} {package} {Directory} is the format");
		
		String className = args[0];
		String packageStr = args[1];
		String dir = args[2];
		createProject(className, packageStr, dir);
	}

	private void start() throws IOException {
		try (Scanner scanner = new Scanner(System.in)) {
		    //  prompt for the user's name
		    System.out.print("Enter your camel case app name(used in class file names): ");
	
		    // get their input as a String
		    String appClassName = scanner.next();
		    String appDirectoryNameTmp = appClassName.toLowerCase()+"-all";

		    
		    System.out.println("Enter your package with . separating each package(ie. org.webpieces.myapp): ");
		    String packageStr = scanner.next();
		    
		    System.out.println("\n\n\n");
		    String currentDir = System.getProperty("user.dir");
		    System.out.println("your current directory is '"+currentDir+"'");
		    System.out.println("Enter the path relative to the above directory or use an absolute directory for where");
		    System.out.println("we will create a directory called="+appDirectoryNameTmp+" OR will re-use an existing directory called "+ appDirectoryNameTmp+" to fill it in");
		    String directory = scanner.next();
		    
		    createProject(appClassName, packageStr, directory);
		}
	}

	private void createProject(String appClassName, String packageStr, String directory) throws IOException {
		String justAppName = appClassName.toLowerCase();
		String appDirectoryName = justAppName+"-all";
		
		//we only allow execution from the jar file right now due to this...(so running this in the IDE probably won't work)
		String path = ProjectCreator.class.getProtectionDomain().getCodeSource().getLocation().getPath();
		File jarFile = new File(path);
		System.out.println("Running from jar file="+jarFile);

		File webpiecesDir = jarFile.getParentFile().getParentFile().getParentFile().getParentFile().getParentFile();
		System.out.println("Base Directory="+webpiecesDir);

		if(packageStr.contains("/") || packageStr.contains("\\"))
			throw new IllegalArgumentException("package must contain '.' character and no '/' nor '\\' characters");
		
		File dirTheUserTypedIn = new File(directory);
		setupDirectory(dirTheUserTypedIn);

		File appDir = new File(dirTheUserTypedIn, appDirectoryName);
		setupDirectory(appDir);
		
		new FileCopy(webpiecesDir, appClassName, justAppName, packageStr, appDir, version).createProject();
	}

	private void setupDirectory(File dirTheUserTypedIn) throws IOException {
		if(!dirTheUserTypedIn.exists()) {
			System.out.println("Directory not exist="+dirTheUserTypedIn.getCanonicalPath()+" so we are creating it");
			dirTheUserTypedIn.mkdirs();
		} else if(!dirTheUserTypedIn.isDirectory()) {
			throw new IllegalArgumentException("directory="+dirTheUserTypedIn.getAbsolutePath()+" already exists BUT is not a directory and needs to be");
		} else
			System.out.println("Directory already exists so we are filling it in="+dirTheUserTypedIn.getCanonicalPath());
	}

}
