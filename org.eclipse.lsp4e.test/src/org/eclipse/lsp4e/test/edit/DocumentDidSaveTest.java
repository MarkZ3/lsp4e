/*******************************************************************************
 * Copyright (c) 2016 Rogue Wave Software Inc. and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *  Michał Niewrzał (Rogue Wave Software Inc.) - initial implementation
 *******************************************************************************/
package org.eclipse.lsp4e.test.edit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jface.text.ITextViewer;
import org.eclipse.lsp4e.LanguageServiceAccessor;
import org.eclipse.lsp4e.test.TestUtils;
import org.eclipse.lsp4e.tests.mock.MockLanguageSever;
import org.eclipse.lsp4j.DidSaveTextDocumentParams;
import org.eclipse.ui.IEditorPart;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class DocumentDidSaveTest {

	private IProject project;
	
	@Before
	public void setUp() throws CoreException {
		project =  TestUtils.createProject("DocumentDidSaveTest"+System.currentTimeMillis());
	}

	@After
	public void tearDown() throws CoreException {
		project.delete(true, true, new NullProgressMonitor());
		MockLanguageSever.INSTANCE.shutdown();
	}
	
	@Test
	public void testSave() throws Exception {
		IFile testFile = TestUtils.createUniqueTestFile(project, "");
		IEditorPart editor = TestUtils.openEditor(testFile);
		ITextViewer viewer = TestUtils.openTextViewer(testFile, editor);
		
		// make sure that timestamp after save will differ from creation time (no better idea at the moment)
		testFile.setLocalTimeStamp(0);
		
		// Force LS to initialize and open file
		LanguageServiceAccessor.getLanguageServer(testFile, null);
		CompletableFuture<DidSaveTextDocumentParams> didSaveExpectation = new CompletableFuture<DidSaveTextDocumentParams>();
		MockLanguageSever.INSTANCE.setDidSaveCallback(didSaveExpectation);
		
		// simulate change in file
		viewer.getDocument().replace(0, 0, "Hello");
		editor.doSave(new NullProgressMonitor());

		DidSaveTextDocumentParams lastChange = didSaveExpectation.get(1000, TimeUnit.MILLISECONDS);
		assertNotNull(lastChange.getTextDocument());
		assertEquals(testFile.getLocationURI().toString(), lastChange.getTextDocument().getUri());
	}

}
