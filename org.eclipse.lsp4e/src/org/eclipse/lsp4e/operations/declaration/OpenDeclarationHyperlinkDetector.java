/*******************************************************************************
 * Copyright (c) 2016 Red Hat Inc. and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *  Mickael Istria (Red Hat Inc.) - initial implementation
 *  Michał Niewrzał (Rogue Wave Software Inc.) - hyperlink range detection
 *******************************************************************************/
package org.eclipse.lsp4e.operations.declaration;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IRegion;
import org.eclipse.jface.text.ITextViewer;
import org.eclipse.jface.text.Region;
import org.eclipse.jface.text.hyperlink.AbstractHyperlinkDetector;
import org.eclipse.jface.text.hyperlink.IHyperlink;
import org.eclipse.lsp4e.LSPEclipseUtils;
import org.eclipse.lsp4e.LanguageServerPlugin;
import org.eclipse.lsp4e.LanguageServiceAccessor;
import org.eclipse.lsp4e.LanguageServiceAccessor.LSPDocumentInfo;
import org.eclipse.lsp4e.ui.Messages;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.services.LanguageServer;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.PlatformUI;

public class OpenDeclarationHyperlinkDetector extends AbstractHyperlinkDetector {

	public static class LSBasedHyperlink implements IHyperlink {

		private Location location;
		private IRegion highlightRegion;

		public LSBasedHyperlink(Location response, IRegion highlightRegion) {
			this.location = response;
			this.highlightRegion = highlightRegion;
		}

		@Override
		public IRegion getHyperlinkRegion() {
			return this.highlightRegion;
		}

		@Override
		public String getTypeLabel() {
			return Messages.hyperlinkLabel;
		}

		@Override
		public String getHyperlinkText() {
			return Messages.hyperlinkLabel;
		}

		@Override
		public void open() {
			IWorkbenchPage page = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage();
			LSPEclipseUtils.openInEditor(location, page);
		}

	}

	@Override
	public IHyperlink[] detectHyperlinks(ITextViewer textViewer, IRegion region, boolean canShowMultipleHyperlinks) {
		final LSPDocumentInfo info = LanguageServiceAccessor.getLSPDocumentInfoFor(textViewer, (capabilities) -> Boolean.TRUE.equals(capabilities.getDefinitionProvider()));
		if (info != null) {
			@Nullable
			LanguageServer languageClient = info.getLanguageClient();
			if (languageClient != null) {
				try {
					CompletableFuture<List<? extends Location>> documentHighlight = languageClient.getTextDocumentService()
							.definition(LSPEclipseUtils.toTextDocumentPosistionParams(info.getFileUri(), region.getOffset(), info.getDocument()));
					List<? extends Location> locations = documentHighlight.get(2, TimeUnit.SECONDS);
					if (locations == null || locations.isEmpty()) {
						return null;
					}
					IRegion linkRegion = findWord(textViewer.getDocument(), region.getOffset());
					if (linkRegion == null) {
						linkRegion = region;
					}
					List<IHyperlink> hyperlinks = new ArrayList<IHyperlink>(locations.size());
					for (Location responseLocation : locations) {
						hyperlinks.add(new LSBasedHyperlink(responseLocation, linkRegion));
					}
					return hyperlinks.toArray(new IHyperlink[hyperlinks.size()]);
				} catch (BadLocationException | InterruptedException | ExecutionException | TimeoutException e) {
					LanguageServerPlugin.logError(e);
				}
			}
		}
		return null;
	}

	/**
	 * This method is only a workaround for missing range value (which can be
	 * used to highlight hyperlink) in LSP 'definition' response.
	 *
	 * Should be removed when protocol will be updated
	 * (https://github.com/Microsoft/language-server-protocol/issues/3)
	 *
	 * @param document
	 * @param offset
	 * @return
	 */
	private IRegion findWord(IDocument document, int offset) {
		int start = -2;
		int end = -1;

		try {

			int pos = offset;
			char c;

			while (pos >= 0) {
				c = document.getChar(pos);
				if (!Character.isUnicodeIdentifierPart(c))
					break;
				--pos;
			}

			start = pos;

			pos = offset;
			int length = document.getLength();

			while (pos < length) {
				c = document.getChar(pos);
				if (!Character.isUnicodeIdentifierPart(c))
					break;
				++pos;
			}

			end = pos;

		} catch (BadLocationException x) {
		}

		if (start >= -1 && end > -1) {
			if (start == offset && end == offset)
				return new Region(offset, 0);
			else if (start == offset)
				return new Region(start, end - start);
			else
				return new Region(start + 1, end - start - 1);
		}

		return null;
	}

}
