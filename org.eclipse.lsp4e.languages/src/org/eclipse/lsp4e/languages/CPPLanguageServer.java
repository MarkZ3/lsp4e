package org.eclipse.lsp4e.languages;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.io.IOUtils;
import org.eclipse.core.runtime.Platform;
import org.eclipse.lsp4e.server.ProcessStreamConnectionProvider;

public class CPPLanguageServer extends ProcessStreamConnectionProvider {

	private static final String CLANG_LANGUAGE_SERVER = "clang-language-server";

	public CPPLanguageServer() {
		List<String> commands = new ArrayList<>();
		File clangServerLocation = getClangServerLocation();
		commands.add(clangServerLocation.getAbsolutePath());
		setCommands(commands);
		String parent = clangServerLocation.getParent();
		setWorkingDirectory(parent);
	}

	@Override
	public Object getInitializationOptions(String rootPath) {
		Map<String, Object> map = new HashMap<>();
		map.put("css", true);
		map.put("javascript", true);
			
		Map<String, Object> options = new HashMap<>();
		options.put("embeddedLanguages", map);
		options.put("format.enable", true);
		return options;
	}

	@Override
	public String toString() {
		return "C/C++ Language Server: " + super.toString();
	}

	public static File getClangServerLocation() {
		String res = null;
		String[] command = new String[] {"/bin/bash", "-c", "which " + CLANG_LANGUAGE_SERVER};
		if (Platform.getOS().equals(Platform.OS_WIN32)) {
			command = new String[] {"cmd", "/c", "where " + CLANG_LANGUAGE_SERVER};
		}
		BufferedReader reader = null;
		try {
			Process p = Runtime.getRuntime().exec(command);
			reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
			res = reader.readLine();
		} catch (IOException e) {
			e.printStackTrace();
		} finally {
			IOUtils.closeQuietly(reader);
		}

		if (res == null) {
			return null;
		}

		File f = new File(res);
		if (f.canExecute()) {
			return f;
		}

		return null;
	}
}
