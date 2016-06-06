package org.webpieces.compiler;

import java.io.File;

import org.junit.After;
import org.junit.Assert;
import org.junit.Test;


public class AddFileTest extends AbstractCompileTest {

	//modify ONE child class file
	//ADD a file AND use the file from controller (different from before)
	//REMOVE a file AND use the file from controller
	
	@Override
	protected String getPackageFilter() {
		return "org.webpieces.compiler.addfile";
	}
	
	@After
	public void tearDown() {
		super.tearDown();
		
		//must manually remove the added file ourselves
		removeAddedFile();
	}
	
	@SuppressWarnings("rawtypes")
	@Test
	public void testAddingFileAndModifyingControllerToUseIt() {
		log.info("loading class AddFileController");
		String controller = getPackageFilter()+".AddFileController";
		Class c = compiler.loadClass(controller);

		log.info("loaded");
		int retVal = invokeMethod(c, "someMethod");
		
		Assert.assertEquals(1, retVal);
		
		cacheAndMoveFiles();
		
		Class c2 = compiler.loadClass(controller);
		
		int retVal2 = invokeMethod(c2, "someMethod");
		
		Assert.assertEquals(2, retVal2);
	}

	private void removeAddedFile() {
		String packageFilter = getPackageFilter();
		String path = packageFilter.replace('.', '/');

		File existingDir = new File(myCodePath, path);
		
		File javaFile = new File(existingDir, "MyAddedClass.java");
		//javaFile must exist for test to be run...
		Assert.assertTrue(javaFile.exists());
		Assert.assertTrue(javaFile.delete());
	}

}