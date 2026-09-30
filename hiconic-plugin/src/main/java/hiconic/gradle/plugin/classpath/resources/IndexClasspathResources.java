// ============================================================================
// This library is free software; you can redistribute it and/or modify it under the terms of the GNU Lesser General Public
// License as published by the Free Software Foundation; either version 3 of the License, or (at your option) any later version.
//
// This library is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public License for more details.
//
// You should have received a copy of the GNU Lesser General Public License along with this library; See http://www.gnu.org/licenses/.
// ============================================================================
package hiconic.gradle.plugin.classpath.resources;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

import org.gradle.api.Action;
import org.gradle.api.GradleException;
import org.gradle.api.Task;
import org.gradle.api.file.FileTree;

import hiconic.gradle.plugin.ProjectInfo;

/**
 * The IndexClasspathResources action expands the declaration of the classpath resources of an artifact into its index. It is the counterpart of the
 * index-classpath-resources target of common-ant-script.
 * <p>
 * An artifact declares the files it publishes as classpath resources in META-INF/classpath-resources.txt, one top level file or folder per line,
 * relative to its resources. The action writes META-INF/classpath-index.txt, with one line per declared file - needed for loading the resources by
 * a ClassLoader.
 * <p>
 * An artifact whose sources hold nothing besides the declaration and the declared resources is marked as a pure classpath resource artifact with
 * META-INF/classpath-resource-only.
 * <p>
 * Both files are written to the generated resources folder, which is a resource folder of the main source set, so they are copied to the output
 * next to the resources they describe.
 */
public class IndexClasspathResources implements Action<Task> {

	private static final String DECLARATION = "META-INF/classpath-resources.txt";
	private static final String INDEX = "META-INF/classpath-index.txt";
	private static final String MARKER = "META-INF/classpath-resource-only";

	private static final Pattern INVALID_ENTRY = Pattern.compile("(^[/\\\\])|(\\.\\.)|(^[A-Za-z]:)");

	private final ProjectInfo projectInfo;

	public IndexClasspathResources(ProjectInfo projectInfo) {
		this.projectInfo = projectInfo;
	}

	@Override
	public void execute(Task task) {
		File outputFolder = projectInfo.generatedResourcesFolder();
		File index = new File(outputFolder, INDEX);
		File marker = new File(outputFolder, MARKER);

		// the outputs of a previous run must not survive a removed declaration
		index.delete();
		marker.delete();
		outputFolder.mkdirs();

		Map<String, File> resources = relativePaths(projectInfo.resources);

		File declaration = resources.get(DECLARATION);
		if (declaration == null)
			return;

		List<String> entries = readDeclaration(declaration);
		List<File> resourceDirs = projectInfo.resourceDirs.stream().filter(dir -> !isGenerated(dir)).toList();

		for (String entry : entries) {
			if (INVALID_ENTRY.matcher(entry).find())
				throw new GradleException(
						"Invalid entry [" + entry + "] in " + declaration + ". An entry must be a relative path inside the resource folder.");

			if (resourceDirs.stream().noneMatch(dir -> new File(dir, entry).exists()))
				throw new GradleException("Declared classpath resource [" + entry + "] does not exist in " + resourceDirs
						+ ". Listed in " + declaration + ".");
		}

		Set<String> javaSources = relativePaths(projectInfo.javaSources).keySet();
		if (javaSources.stream().anyMatch(path -> isDeclared(path, entries)))
			throw new GradleException("A declared classpath resource folder contains Java sources. They would be compiled into the class output"
					+ " folder, but never indexed. Move them out of the declared entries.");

		Set<String> indexed = new TreeSet<>();
		boolean pure = javaSources.isEmpty();

		for (String path : resources.keySet()) {
			if (isDeclared(path, entries))
				indexed.add(path);
			else if (!path.equals(DECLARATION))
				pure = false;
		}

		write(index, String.join("\n", indexed) + "\n");

		if (pure) {
			task.getLogger().lifecycle("Pure classpath resource artifact. Its jar may be pruned from an assembled image.");
			write(marker, "formatVersion=1\n");
		}
	}

	/** The path of each file of the tree relative to its source folder, without the generated files. */
	private Map<String, File> relativePaths(FileTree tree) {
		Map<String, File> result = new LinkedHashMap<>();
		tree.visit(details -> {
			File file = details.getFile();
			if (details.isDirectory() || isGenerated(file))
				return;

			result.putIfAbsent(details.getRelativePath().getPathString(), file);
		});

		return result;
	}

	private boolean isGenerated(File file) {
		return file.toPath().startsWith(projectInfo.generatedFolder().toPath()) //
				|| file.toPath().startsWith(projectInfo.generatedResourcesFolder().toPath());
	}

	private static boolean isDeclared(String path, List<String> entries) {
		for (String entry : entries)
			if (path.equals(entry) || path.startsWith(entry + "/"))
				return true;

		return false;
	}

	/** One entry per line, without blank lines and lines starting with #, and without a trailing slash. */
	private static List<String> readDeclaration(File declaration) {
		List<String> result = new ArrayList<>();

		for (String line : readLines(declaration)) {
			line = line.trim().replace('\\', '/');
			if (line.isEmpty() || line.startsWith("#"))
				continue;

			while (line.endsWith("/"))
				line = line.substring(0, line.length() - 1);

			result.add(line);
		}

		return result;
	}

	private static List<String> readLines(File file) {
		try {
			return Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new UncheckedIOException("Failed to read classpath resources declaration: " + file.getAbsolutePath(), e);
		}
	}

	private static void write(File file, String content) {
		try {
			file.getParentFile().mkdirs();
			Files.writeString(file.toPath(), content, StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new UncheckedIOException("Failed to write file: " + file.getAbsolutePath(), e);
		}
	}

}
