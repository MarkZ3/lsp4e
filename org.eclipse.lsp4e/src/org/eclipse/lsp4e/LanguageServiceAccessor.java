/*******************************************************************************
 * Copyright (c) 2016, 2017 Red Hat Inc. and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *  Mickael Istria (Red Hat Inc.) - initial implementation
 *******************************************************************************/
package org.eclipse.lsp4e;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.content.IContentType;
import org.eclipse.jdt.annotation.NonNull;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.jface.text.IDocument;
import org.eclipse.lsp4e.LanguageServersRegistry.LanguageServerDefinition;
import org.eclipse.lsp4e.server.StreamConnectionProvider;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.services.LanguageServer;

/**
 * The entry-point to retrieve a Language Server for a given resource/project.
 * Deals with instantiations and caching of underlying {@link ProjectSpecificLanguageServerWrapper}.
 *
 */
public class LanguageServiceAccessor {

	private LanguageServiceAccessor() {
		// this class shouldn't be instantiated
	}

	private static Set<ProjectSpecificLanguageServerWrapper> projectServers = new HashSet<>();
	private static Map<StreamConnectionProvider, LanguageServerDefinition> providersToLSDefinitions = new HashMap<>();

	/**
	 * A bean storing association of a Document/File with a language server.
	 */
	public static class LSPDocumentInfo {

		private final @NonNull URI fileUri;
		private final @NonNull IDocument document;
		private final @NonNull ProjectSpecificLanguageServerWrapper wrapper;
		private final @NonNull LanguageServer server;

		private LSPDocumentInfo(@NonNull URI fileUri, @NonNull IDocument document, @NonNull ProjectSpecificLanguageServerWrapper wrapper, @NonNull LanguageServer server) {
			this.fileUri = fileUri;
			this.document = document;
			this.wrapper = wrapper;
			this.server = server;
		}

		public @NonNull IDocument getDocument() {
			return this.document;
		}

		/**
		 * TODO consider directly returning a {@link TextDocumentIdentifier}
		 * @return
		 */
		public @NonNull URI getFileUri() {
			return this.fileUri;
		}

		public @NonNull LanguageServer getLanguageClient() {
			return this.server;
		}

		public @Nullable ServerCapabilities getCapabilites() {
			return this.wrapper.getServerCapabilities();
		}

		public boolean isActive() {
			return this.wrapper.isActive();
		}
	}

	public static @NonNull Collection<LanguageServer> getLanguageServers(@NonNull IFile file, Predicate<ServerCapabilities> request) throws IOException {
		Collection<ProjectSpecificLanguageServerWrapper> wrappers = getLSWrappers(file, request);
		wrappers.forEach(w -> {
			try {
				w.connect(file.getLocation(), null);
			} catch (IOException e) {
				LanguageServerPlugin.logError(e);
			}
		});
		return wrappers.stream().map(ProjectSpecificLanguageServerWrapper::getServer).collect(Collectors.toList());
	}

	/**
	 * Get the requested language server instance for the given file. Starts the language server if not already started.
	 * @param file
	 * @param serverId
	 * @return a LanguageServer for the given file, which is defined with provided server ID and conforms to specified request
	 */
	public static LanguageServer getLanguageServer(@NonNull IFile file, @NonNull LanguageServerDefinition lsDefinition) throws IOException {
		ProjectSpecificLanguageServerWrapper wrapper = getLSWrapperForConnection(file.getProject(), lsDefinition);
		if (wrapper != null) {
			wrapper.connect(file.getLocation(), null);
			return wrapper.getServer();
		}
		return null;
	}

	/**
	 *
	 * @param file
	 * @param request
	 * @return
	 * @throws IOException
	 * @noreference This method is currently internal and should only be referenced for testing
	 */
	@NonNull public static Collection<ProjectSpecificLanguageServerWrapper> getLSWrappers(@NonNull IFile file, @NonNull Predicate<ServerCapabilities> request) throws IOException {
		LinkedHashSet<ProjectSpecificLanguageServerWrapper> res = new LinkedHashSet<>();
		IProject project = file.getProject();
		if (project == null) {
			return res;
		}

		res.addAll(getMatchingStartedWrappers(file, request));

		// look for running language servers via content-type
		Queue<IContentType> contentTypes = new LinkedList<>();
		Set<IContentType> addedContentTypes = new HashSet<>();
		try (InputStream contents = file.getContents()) {
			contentTypes.addAll(Arrays.asList(Platform.getContentTypeManager().findContentTypesFor(contents, file.getName()))); //TODO consider using document as inputstream
			addedContentTypes.addAll(contentTypes);
		} catch (CoreException e) {
			LanguageServerPlugin.logError(e);
			return res;
		}

		while (!contentTypes.isEmpty()) {
			IContentType contentType = contentTypes.poll();
			if (contentType == null) {
				continue;
			}
			for (ContentTypeToLanguageServerDefinition mapping : LanguageServersRegistry.getInstance().findProviderFor(contentType)) {
				if (mapping != null && mapping.getValue() != null) {
					ProjectSpecificLanguageServerWrapper wrapper = getLSWrapperForConnection(project, mapping.getValue());
					if (request == null
						|| wrapper.getServerCapabilities() == null /* null check is workaround for https://github.com/TypeFox/ls-api/issues/47 */
						|| request.test(wrapper.getServerCapabilities())) {
						res.add(wrapper);
					}
				}
			}
			if (contentType.getBaseType() != null && !addedContentTypes.contains(contentType.getBaseType())) {
				addedContentTypes.add(contentType.getBaseType());
				contentTypes.add(contentType.getBaseType());
			}
		}
		return res;
	}

	/**
	 * Returns whether or not a language server definition is associated with a
	 * project.
	 *
	 * @param project
	 * @param serverDefinition
	 * @return
	 * @throws IOException
	 */
	public static boolean isServerInstanceAssociated(@NonNull IProject project,
			@NonNull LanguageServerDefinition serverDefinition) throws IOException {
		return getLSWrapperForConnection(project, serverDefinition, false) != null;
	}

	/**
	 * Return existing {@link ProjectSpecificLanguageServerWrapper} for the given connection. If not found,
	 * create a new one with the given connection and register it for this project/content-type.
	 * @param project
	 * @param serverDefinition
	 * @return
	 * @throws IOException
	 */
	public static ProjectSpecificLanguageServerWrapper getLSWrapperForConnection(@NonNull IProject project, @NonNull LanguageServerDefinition serverDefinition) throws IOException {
		return getLSWrapperForConnection(project, serverDefinition, true);
	}

	/**
	 * Return existing {@link ProjectSpecificLanguageServerWrapper} for the given connection. If not found,
	 * optionally create a new one with the given connection and register it for this project/content-type.
	 * @param project
	 * @param serverDefinition
	 * @param create whether or not to create a new wrapper if it is not found
	 * @return
	 * @throws IOException
	 */
	private static ProjectSpecificLanguageServerWrapper getLSWrapperForConnection(@NonNull IProject project,
			@NonNull LanguageServerDefinition serverDefinition, boolean create) throws IOException {
		ProjectSpecificLanguageServerWrapper wrapper = null;

		synchronized(projectServers) {
			for (ProjectSpecificLanguageServerWrapper startedWrapper : getStartedLSWrappers(project)) {
				if (startedWrapper.serverDefinition.equals(serverDefinition)) {
					wrapper = startedWrapper;
					break;
				}
			}
			if (create) {
				if (wrapper == null) {
					wrapper = new ProjectSpecificLanguageServerWrapper(project, serverDefinition);
					wrapper.start();
				}

				if (wrapper != null) {
					projectServers.add(wrapper);
				}
			}
		}
		return wrapper;
	}

	private static @NonNull List<ProjectSpecificLanguageServerWrapper> getStartedLSWrappers(@NonNull IProject project) {
		return projectServers.stream().filter(wrapper -> wrapper.project.equals(project)).collect(Collectors.toList());
	}

	private static Collection<ProjectSpecificLanguageServerWrapper> getMatchingStartedWrappers(@NonNull IFile file,
	       @NonNull Predicate<ServerCapabilities> request) {
		final IProject project = file.getProject();

		synchronized(projectServers) {
			return projectServers.stream()
				.filter(wrapper -> wrapper.project.equals(project))
				.filter(wrapper -> wrapper.isConnectedTo(file.getLocation()))
				.filter(wrapper -> wrapper.getServerCapabilities() == null || request.test(wrapper.getServerCapabilities()))
				.collect(Collectors.toList());
		}
	}

	/**
	 * Gets list of LS initialized for given project.
	 *
	 * @param project
	 * @param request
	 * @return list of Language Servers
	 */
	@NonNull public static List<@NonNull LanguageServer> getLanguageServers(@NonNull IProject project, Predicate<ServerCapabilities> request) {
		List<@NonNull LanguageServer> serverInfos = new ArrayList<>();
		for (ProjectSpecificLanguageServerWrapper wrapper : projectServers) {
			@Nullable LanguageServer server = wrapper.getServer();
			if (server == null) {
				continue;
			}
			if ((request == null
			    || wrapper.getServerCapabilities() == null /* null check is workaround for https://github.com/TypeFox/ls-api/issues/47 */
			    || request.test(wrapper.getServerCapabilities()))) {
				serverInfos.add(server);
			}
		}
		return serverInfos;
	}

	protected static LanguageServerDefinition getLSDefinition(@NonNull StreamConnectionProvider provider) {
		return providersToLSDefinitions.get(provider);
	}

	@NonNull public static List<@NonNull LSPDocumentInfo> getLSPDocumentInfosFor(@NonNull IDocument document, @NonNull Predicate<ServerCapabilities> capabilityRequest) {
		final IFile file = LSPEclipseUtils.getFile(document);
		URI fileUri = null;
		if (file != null && file.exists()) {
			fileUri = LSPEclipseUtils.toUri(file);
			List<LSPDocumentInfo> res = new ArrayList<>();
			try {
				for (ProjectSpecificLanguageServerWrapper wrapper : getLSWrappers(file, capabilityRequest)) {
					wrapper.connect(file.getLocation(), document);
					@Nullable
					LanguageServer server = wrapper.getServer();
					if (server != null) {
						res.add(new LSPDocumentInfo(fileUri, document, wrapper, server));
					}
				}
			} catch (final Exception e) {
				LanguageServerPlugin.logError(e);
			}
			return res;
		} else {
			LanguageServerPlugin.logInfo("Non IFiles not supported yet"); //$NON-NLS-1$
			//fileUri = "file://" + location.toFile().getAbsolutePath();
			//TODO handle case of plain file (no IFile)
		}
		return Collections.emptyList();
	}

}
