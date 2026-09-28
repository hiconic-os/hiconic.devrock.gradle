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

import static java.lang.classfile.ClassFile.JAVA_6_VERSION;
import static java.lang.constant.ConstantDescs.CD_String;
import static java.lang.constant.ConstantDescs.CD_void;
import static java.lang.constant.ConstantDescs.CLASS_INIT_NAME;
import static java.lang.constant.ConstantDescs.INIT_NAME;
import static java.lang.constant.ConstantDescs.MTD_void;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.CodeBuilder;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.StringTokenizer;

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
public class GenerateArtifactReflection implements Action<Task> {

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
			byte classData[] = generateClass();
			writeArtifactReflection(classData);
			writeMetaInf();
		}

		private byte[] generateClass() {
			try {
				String className = buildCanonizedClassName();

				ClassDesc thisClass = ClassDesc.of(className);
				ClassDesc artifactReflection = ClassDesc.of("com.braintribe.common.artifact.ArtifactReflection");
				ClassDesc standardArtifactReflection = ClassDesc.of("com.braintribe.common.artifact.StandardArtifactReflection");

				String name = projectInfo.name();
				String versionedName = name + "#" + projectInfo.version;

				int publicStaticFinal = ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC | ClassFile.ACC_FINAL;

				return ClassFile.of().build(thisClass, cb -> {
					cb.withVersion(JAVA_6_VERSION, 0);
					cb.withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_FINAL | ClassFile.ACC_SUPER);

					cb.withField("reflection", artifactReflection, publicStaticFinal);
					cb.withField("groupId", CD_String, publicStaticFinal);
					cb.withField("artifactId", CD_String, publicStaticFinal);
					cb.withField("version", CD_String, publicStaticFinal);
					cb.withField("name", CD_String, publicStaticFinal);
					cb.withField("versionedName", CD_String, publicStaticFinal);

					// the class initializer fills the static fields
					cb.withMethodBody(CLASS_INIT_NAME, MTD_void, ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC, cob -> {
						fillStaticField(cob, thisClass, "groupId", projectInfo.groupId);
						fillStaticField(cob, thisClass, "artifactId", projectInfo.artifactId);
						fillStaticField(cob, thisClass, "version", projectInfo.version);
						fillStaticField(cob, thisClass, "name", name);
						fillStaticField(cob, thisClass, "versionedName", versionedName);

						// new StandardArtifactReflection(groupId, artifactId, version, archetype)
						cob.new_(standardArtifactReflection) //
								.dup() //
								.loadConstant(projectInfo.groupId) //
								.loadConstant(projectInfo.artifactId) //
								.loadConstant(projectInfo.version);

						if (projectInfo.archetype != null)
							cob.loadConstant(projectInfo.archetype);
						else
							cob.aconst_null();

						cob.invokespecial(standardArtifactReflection, INIT_NAME,
								MethodTypeDesc.of(CD_void, CD_String, CD_String, CD_String, CD_String)) //
								.putstatic(thisClass, "reflection", artifactReflection) //
								.return_();
					});
				});
			} catch (Exception e) {
				throw new RuntimeException("Error while compiling artifact reflection information to bytecode for project: " + projectInfo.artifactId, e);
			}
		}

		private void fillStaticField(CodeBuilder cob, ClassDesc thisClass, String name, String value) {
			cob.loadConstant(value) //
					.putstatic(thisClass, name, CD_String);
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