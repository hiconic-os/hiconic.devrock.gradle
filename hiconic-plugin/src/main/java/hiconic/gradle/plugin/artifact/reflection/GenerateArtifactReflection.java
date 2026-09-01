// ============================================================================
// This library is free software; you can redistribute it and/or modify it under the terms of the GNU Lesser General Public
// License as published by the Free Software Foundation; either version 3 of the License, or (at your option) any later version.
// 
// This library is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public License for more details.
// 
// You should have received a copy of the GNU Lesser General Public License along with this library; See http://www.gnu.org/licenses/.
// ============================================================================
package hiconic.gradle.plugin.artifact.reflection;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.StringTokenizer;

import org.apache.flink.shaded.asm9.org.objectweb.asm.ClassWriter;
import org.apache.flink.shaded.asm9.org.objectweb.asm.MethodVisitor;
import org.apache.flink.shaded.asm9.org.objectweb.asm.Opcodes;
import hiconic.gradle.plugin.ProjectInfo;
import org.gradle.api.Action;
import org.gradle.api.Task;

/**
 * The GenerateArtifactReflection action can generate artifact reflection for given Gradle artifact.
 * <p>
 * The output is stored in the resources build-output and consists of two files per artifact:
 * <ul>
 * <li>build-dir/META-INF/artifact-descriptor.properties</li>
 * <li>build-dir/group_dir/artifact_name.class</li>
 * </ul>
 * where the former is an ASCII/properties version of the latter.
 * <p>
 * <b>group_dir</b> is derived from the group name by replacing the "." with "/".
 * <p>
 * <b>artifact_name</b> follows the pattern "my-artifact" -&gt; "_MyArtifact_".
 * 
 * The class file contains ArtifactReflection implementation for given artifact.
 * 
 * @author Dirk Scheffler
 */
public class GenerateArtifactReflection implements Action<Task>, Opcodes {

	private final ProjectInfo projectInfo;

	public GenerateArtifactReflection(ProjectInfo projectInfo) {
		this.projectInfo = projectInfo;
	}

	@Override
	public void execute(Task t) {
		new StatefulGenerator().generate();
	}

	/* Internal helper class for properly {@link Reason}ed artifact reflection generation. */
	private class StatefulGenerator {

		private String canonizedGroupdId;
		private String canonizedArtifactId;
		private String className;

		public void generate() {
			byte classData[] = generateClassWithAsm();
			writeArtifactReflection(classData);
			writeMetaInf();
		}

		private byte[] generateClassWithAsm() {
			try {
				ClassWriter classWriter = new ClassWriter(0);

				String className = buildCanonizedClassName();

				String internalName = className.replace('.', '/');
				String superName = "java/lang/Object";
				String artifactReflectionDesc = "Lcom/braintribe/common/artifact/ArtifactReflection;";
				String reflectionFieldName = "reflection";

				// create class with a name based on artifact identification deduction
				classWriter.visit(V1_6, ACC_PUBLIC + ACC_FINAL + ACC_SUPER, internalName, null, superName, null);

				// add public static final field for the reflection instance
				classWriter.visitField(ACC_PUBLIC + ACC_STATIC + ACC_FINAL, reflectionFieldName, artifactReflectionDesc, null, null);

				// build class initializer
				MethodVisitor mv = classWriter.visitMethod(ACC_PUBLIC + ACC_STATIC, "<clinit>", "()V", null, null);

				String name = projectInfo.groupId + ":" + projectInfo.artifactId;
				String versionedName = name + "#" + projectInfo.version;

				fillStaticField(classWriter, internalName, mv, "groupId", projectInfo.groupId);
				fillStaticField(classWriter, internalName, mv, "artifactId", projectInfo.artifactId);
				fillStaticField(classWriter, internalName, mv, "version", projectInfo.version);
				fillStaticField(classWriter, internalName, mv, "name", name);
				fillStaticField(classWriter, internalName, mv, "versionedName", versionedName);

				// instantiate plain StandardArtifactReflection instance which places it on
				// stack
				String implementationName = "com/braintribe/common/artifact/StandardArtifactReflection";
				mv.visitTypeInsn(NEW, implementationName);

				// duplicate instance on stack for field assignment after constructor
				mv.visitInsn(DUP);

				// push constructor arguments on stack: groupId, artifactId, version, archetype
				mv.visitLdcInsn(projectInfo.groupId);
				mv.visitLdcInsn(projectInfo.artifactId);
				mv.visitLdcInsn(projectInfo.version);
				if (projectInfo.archetype != null)
					mv.visitLdcInsn(projectInfo.archetype);
				else
					mv.visitInsn(ACONST_NULL);

				// invoke constructor of StandardArtifactReflection
				mv.visitMethodInsn(INVOKESPECIAL, implementationName, "<init>",
						"(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V", false);

				// assign new StandardArtifactReflection instance of the static reflection field
				mv.visitFieldInsn(PUTSTATIC, internalName, reflectionFieldName, artifactReflectionDesc);

				// return from class initializer
				mv.visitInsn(RETURN);
				mv.visitMaxs(6, 0);

				return classWriter.toByteArray();
			} catch (Exception e) {
				throw new RuntimeException("Error while compiling artifact reflection information to bytecode for project: " + projectInfo.artifactId, e);
			}
		}

		private void fillStaticField(ClassWriter classWriter, String internalName, MethodVisitor mv, String name, String value) {
			// add public static final field for the reflection instance
			String stringDesc = "Ljava/lang/String;";
			classWriter.visitField(ACC_PUBLIC + ACC_STATIC + ACC_FINAL, name, stringDesc, null, null);

			// push String argument for PUT instruction
			mv.visitLdcInsn(value);
			// assign pushed value to field
			mv.visitFieldInsn(PUTSTATIC, internalName, name, stringDesc);
		}

		private String buildCanonizedClassName() {

			canonizedGroupdId = canonizedGroupdId(projectInfo.groupId);
			canonizedArtifactId = canonizedArtifactId(projectInfo.artifactId);
			className = canonizedGroupdId + "." + canonizedArtifactId;
			return className;
		}

		private void writeArtifactReflection(byte[] classBytes) {
			File targetFile = projectInfo.generatedFolder().toPath().resolve(canonizedGroupdId.replace('.', '/')).resolve(canonizedArtifactId + ".class").toFile();

			try {
				targetFile.getParentFile().mkdirs();
				try (OutputStream out = new FileOutputStream(targetFile)) {
					out.write(classBytes);
				}
			} catch (IOException e) {
				throw new UncheckedIOException("Failed write class file:" + targetFile.getAbsolutePath() + " for project: " + projectInfo.artifactId, e);
			}
		}

		private void writeMetaInf() {

			File targetFile = projectInfo.generatedFolder().toPath().resolve("META-INF").resolve("artifact-descriptor.properties").toFile();

			try {
				targetFile.getParentFile().mkdirs();

				HashMap<String, String> properties = new LinkedHashMap<>();
				properties.put("groupId", projectInfo.groupId);
				properties.put("artifactId", projectInfo.artifactId);
				properties.put("version", projectInfo.version);

				if (projectInfo.archetype != null)
					properties.put("archetypes", projectInfo.archetype);

				properties.put("reflection-class", className);

				try (PrintStream ps = new PrintStream(new FileOutputStream(targetFile), false, "UTF-8")) {
					for (Map.Entry<String, String> entry : properties.entrySet()) {
						ps.print(entry.getKey());
						ps.print('=');
						ps.println(entry.getValue());
					}
				}
			} catch (IOException e) {
				throw new UncheckedIOException(
						"Failed write artifact-reflection file:" + targetFile.getAbsolutePath() + " for project: " + projectInfo.artifactId, e);
			}
		}

		// Camel-casing of artifactId: this-artifact will be ThisArtifact
		// Furthermore, this will be "underscore-cased": _ThisArtifact_
		private String canonizedArtifactId(String name) {

			StringTokenizer tokenizer = new StringTokenizer(name, "-");

			StringBuilder builder = new StringBuilder();
			builder.append("_");

			while (tokenizer.hasMoreTokens()) {
				String token = tokenizer.nextToken();

				if (token.isEmpty())
					continue;

				builder.append(Character.toUpperCase(token.charAt(0)));
				builder.append(token, 1, token.length());
			}
			builder.append("_");

			return builder.toString();
		}

		// path-version of groupId: "this.group-v2" will become "this.group_v2".
		// Later, for the file-system also with pushDottedPath will produce "this/group_v2".
		private String canonizedGroupdId(String name) {

			return name.replace('-', '_');
		}
	}

}