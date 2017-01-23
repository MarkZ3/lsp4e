package org.eclipse.lsp4e.languages;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.lsp4e.ProcessStreamConnectionProvider;

public class CPPLanguageServer extends ProcessStreamConnectionProvider {

	public CPPLanguageServer() {
		List<String> commands = new ArrayList<>();
//		commands.add(InitializeLaunchConfigurations.getNodeJsLocation());
		commands.add("bash");
		commands.add("-c");
		commands.add("tee log.txt | /Users/mark/.vscode/extensions/ms-vscode.cpptools-0.9.3/bin/Microsoft.VSCode.CPP.Extension.darwin --stdio");
//		commands.add("/Users/mark/.vscode/extensions/ms-vscode.cpptools-0.9.3/bin/Microsoft.VSCode.CPP.Extension.darwin");
//		commands.add("--stdio");
		String workingDir = "/Users/mark/temp";
		setCommands(commands);
		setWorkingDirectory(workingDir);
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
}
