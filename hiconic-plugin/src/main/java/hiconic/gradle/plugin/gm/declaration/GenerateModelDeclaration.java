// ============================================================================
// This library is free software; you can redistribute it and/or modify it under the terms of the GNU Lesser General Public
// License as published by the Free Software Foundation; either version 3 of the License, or (at your option) any later version.
// 
// This library is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public License for more details.
// 
// You should have received a copy of the GNU Lesser General Public License along with this library; See http://www.gnu.org/licenses/.
// ============================================================================
package hiconic.gradle.plugin.gm.declaration;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;

import javax.xml.parsers.DocumentBuilderFactory;

import org.gradle.api.Action;
import org.gradle.api.Task;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import hiconic.gradle.plugin.ProjectInfo;

public class GenerateModelDeclaration implements Action<Task> {
	private static final String JAVA_CLASS_EXT = ".class";

	private final ProjectInfo projectInfo;

	public GenerateModelDeclaration(ProjectInfo projectInfo) {
		this.projectInfo = projectInfo;
	}

	@Override
	public void execute(Task t) {

		ModelDescriptor modelDescriptor = new ModelDescriptor();

		modelDescriptor.groupId = projectInfo.groupId;
		modelDescriptor.artifactId = projectInfo.artifactId;
		modelDescriptor.version = projectInfo.version;
		modelDescriptor.name = projectInfo.name();
		modelDescriptor.dependencies.addAll(projectInfo.modelDependencies);

		File targetFolder = projectInfo.generatedFolder();

		List<File> buildFolders = new ArrayList<>();
		List<URL> cp = new ArrayList<>();

		projectInfo.classesDirs.forEach(dir -> {
			buildFolders.add(dir);
			cp.add(this.toUrl(dir));
		});

		projectInfo.runtimeClasspath.getFiles().stream().map(this::toUrl).forEach(cp::add);

		Map<String, File> classes = new TreeMap<>();

		for (File scanFolder : buildFolders)
			scanTypeCandidates(null, scanFolder, classes);

		try (URLClassLoader classLoader = new URLClassLoader(cp.toArray(new URL[cp.size()]), null)) {

			filterTypeCandidates(projectInfo.artifactId, classLoader, classes.keySet(), modelDescriptor.declaredTypes, modelDescriptor.forwardTypes);

			Collection<File> sortedClassFiles = createSortedClassFiles(classes);

			File descriptor = new File(projectInfo.projectDir, "build.gradle");

			modelDescriptor.hash = buildHash(Stream.concat(Stream.of(descriptor), sortedClassFiles.stream()));

			Map<String, Set<String>> forwards = scanForwards(classLoader);

			Set<String> forwardTypes = forwards.get(modelDescriptor.name);

			if (forwardTypes != null) {
				modelDescriptor.declaredTypes.addAll(forwardTypes);
			}
		} catch (IOException e) {
			throw new UncheckedIOException("Failed to analyze the classes of project: " + projectInfo.artifactId, e);
		}

		File file = new File(targetFolder, "model-declaration.xml");
		file.getParentFile().mkdirs();
		writeDescriptor(file, modelDescriptor);
	}

	private URL toUrl(File f) {
		try {
			return f.toURI().toURL();
		} catch (MalformedURLException e) {
			throw new UncheckedIOException(e);
		}
	}
	
	private static void writeDescriptor(File file, ModelDescriptor modelDescriptor) {
		try (OutputStreamWriter writer = new OutputStreamWriter(new FileOutputStream(file), "UTF-8")) {
			writeDescriptor(writer, modelDescriptor);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static void writeDescriptor(Writer writer, ModelDescriptor modelDescriptor) throws IOException {
		writer.write("<?xml version=\"1.0\" encoding=\"UTF-8\" ?>\n");
		writer.write(
				"<model-declaration xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" xsi:noNamespaceSchemaLocation=\"model-declaration-1.0.xsd\">\n\n");

		writer.write("  <name>");
		writer.write(modelDescriptor.name);
		writer.write("</name>\n\n");
		writer.write("  <groupId>");
		writer.write(modelDescriptor.groupId);
		writer.write("</groupId>\n");
		writer.write("  <artifactId>");
		writer.write(modelDescriptor.artifactId);
		writer.write("</artifactId>\n");
		writer.write("  <version>");
		writer.write(modelDescriptor.version);
		writer.write("</version>\n");
		if (modelDescriptor.globalId != null) {
			writer.write("  <globalId>");
			writer.write(modelDescriptor.globalId);
			writer.write("</globalId>\n");
		}
		writer.write("  <hash>");
		writer.write(modelDescriptor.hash);
		writer.write("</hash>\n\n");

		writer.write("  <dependencies>\n");
		for (String dependency : modelDescriptor.dependencies) {
			writer.write("    <dependency>");
			writer.write(dependency);
			writer.write("</dependency>\n");
		}
		writer.write("  </dependencies>\n\n");

		writer.write("  <types>\n");
		for (String type : modelDescriptor.declaredTypes) {
			writer.write("    <type>");
			writer.write(type);
			writer.write("</type>\n");
		}
		writer.write("  </types>\n\n");

		writer.write("</model-declaration>");
	}

	private static Collection<File> createSortedClassFiles(Map<String, File> values) {
		SortedMap<String, File> sortedFiles = new TreeMap<>(values);
		return sortedFiles.values();
	}

	private static String buildHash(Stream<File> fileStream) {
		try {
			MessageDigest digest = MessageDigest.getInstance("MD5");

			try (ObjectOutputStream out = new ObjectOutputStream(new DigestOutputStream(NullOutputStream.INSTANCE, digest))) {

				fileStream.forEach(file -> {
					try {
						long lastModified = file.lastModified();
						out.writeLong(lastModified);
					} catch (IOException e) {
						throw new UncheckedIOException(e);
					}
				});
			}

			byte[] hashBytes = digest.digest();

			return toHex(hashBytes).toLowerCase();
		} catch (Exception e) {
			throw new RuntimeException("error while building hash");
		}
	}

	private static class NullOutputStream extends OutputStream {

		public static NullOutputStream INSTANCE = new NullOutputStream();

		@Override
		public void write(int b) throws IOException {
			// empty
		}

		@Override
		public void write(byte[] b) throws IOException {
			// empty
		}

		@Override
		public void write(byte[] b, int off, int len) throws IOException {
			// empty
		}
	}

	private static String toHex(byte[] bytes) {
		StringBuilder hexString = new StringBuilder();
		for (byte b : bytes) {
			String hex = Integer.toHexString(0xFF & b);
			if (hex.length() == 1) {
				// This ensures it uses two digits (adds leading zero if necessary)
				hexString.append('0');
			}
			hexString.append(hex);
		}
		return hexString.toString();
	}

	private static Map<String, Set<String>> scanForwards(URLClassLoader classLoader) {
		try {
			Enumeration<URL> urls = classLoader.getResources("model-forward-declaration.xml");

			Map<String, Set<String>> map = new HashMap<String, Set<String>>();

			while (urls.hasMoreElements()) {
				URL url = urls.nextElement();

				readModelForwardDeclaration(url, map);
			}

			return map;
		} catch (RuntimeException e) {
			throw e;
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	private static void readModelForwardDeclaration(URL url, Map<String, Set<String>> map) throws Exception {
		try (InputStream in = url.openStream()) {
			Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(in);

			Element documentElement = doc.getDocumentElement();

			for (Node node = documentElement.getFirstChild(); node != null; node = node.getNextSibling()) {
				if (node.getNodeType() == Node.ELEMENT_NODE) {
					Element element = (Element) node;
					if (element.getTagName().equals("for-model")) {
						String modelName = element.getAttribute("name");

						Set<String> forwardTypes = map.get(modelName);

						if (forwardTypes == null) {
							forwardTypes = new HashSet<String>();
							map.put(modelName, forwardTypes);
						}

						for (Node subNode = element.getFirstChild(); subNode != null; subNode = subNode.getNextSibling()) {

							if (subNode.getNodeType() == Node.ELEMENT_NODE) {
								Element subElement = (Element) subNode;
								if (subElement.getTagName().equals("type")) {
									String type = subElement.getTextContent();
									forwardTypes.add(type);
								}
							}
						}
					}
				}
			}
		}
	}

	private static void filterTypeCandidates(String projectName, ClassLoader classLoader, Collection<String> classNames, Set<String> declaredTypes,
			Map<String, Set<String>> forwardTypes) {

		ModelReflection tools = ModelClassFileReflection.scan(projectName, classLoader);

		for (String className : classNames) {
			Entity entity = tools.load(className);
			if (entity.getIsEnum() || entity.getIsGenericEntity()) {
				String forwardModel = entity.getForwardDeclaration();
				if (forwardModel == null) {
					declaredTypes.add(className);
				} else {
					Set<String> modelForwardTypes = forwardTypes.get(forwardModel);
					if (modelForwardTypes == null) {
						modelForwardTypes = new TreeSet<String>();
						forwardTypes.put(forwardModel, modelForwardTypes);
					}
					modelForwardTypes.add(className);
				}
			}
		}
	}

	private static void scanTypeCandidates(String path, File folder, Map<String, File> classes) {

		for (File file : folder.listFiles()) {
			String fileName = file.getName();
			if (file.isDirectory()) {
				scanTypeCandidates(concat(path, fileName), file, classes);
			} else if (fileName.endsWith(JAVA_CLASS_EXT)) {
				String plainName = fileName.substring(0, fileName.length() - JAVA_CLASS_EXT.length());
				String className = concat(path, plainName);
				classes.put(className, file);
			}
		}
	}

	private static String concat(String path, String name) {
		return path == null ? name : path + "." + name;
	}

}
