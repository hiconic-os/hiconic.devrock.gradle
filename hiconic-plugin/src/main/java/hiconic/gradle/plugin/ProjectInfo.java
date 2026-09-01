// ============================================================================
// This library is free software; you can redistribute it and/or modify it under the terms of the GNU Lesser General Public
// License as published by the Free Software Foundation; either version 3 of the License, or (at your option) any later version.
//
// This library is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public License for more details.
//
// You should have received a copy of the GNU Lesser General Public License along with this library; See http://www.gnu.org/licenses/.
// ============================================================================
package hiconic.gradle.plugin;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import org.gradle.api.Project;
import org.gradle.api.file.FileCollection;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.SourceSetContainer;

/**
 * Everything the generators need from the gradle {@link Project}, read once, at configuration time.
 * <p>
 * A task action must not touch the project while it runs, because that is incompatible with the
 * configuration cache. A {@link FileCollection} may be held and resolves lazily, a
 * {@code Configuration} may not be held.
 */
public class ProjectInfo {

	public final String groupId;
	public final String artifactId;
	public final String version;
	public final String archetype;
	public final File projectDir;

	/** dependencies of this artifact that are models, as "groupId:artifactId" */
	public final List<String> modelDependencies;

	/** the class output folders of the main source set */
	public final FileCollection classesDirs;

	public final FileCollection runtimeClasspath;

	public ProjectInfo(String groupId, String artifactId, String version, String archetype, File projectDir, List<String> modelDependencies,
			FileCollection classesDirs, FileCollection runtimeClasspath) {
		this.groupId = groupId;
		this.artifactId = artifactId;
		this.version = version;
		this.archetype = archetype;
		this.projectDir = projectDir;
		this.modelDependencies = modelDependencies;
		this.classesDirs = classesDirs;
		this.runtimeClasspath = runtimeClasspath;
	}

	public static ProjectInfo createFrom(Project project) {
		SourceSetContainer sourceSets = project.getExtensions().getByType(SourceSetContainer.class);
		SourceSet mainSourceSet = sourceSets.getByName(SourceSet.MAIN_SOURCE_SET_NAME);

		List<String> modelDependencies = new ArrayList<>();
		project.getConfigurations().getByName("implementation").getIncoming().getDependencies().forEach(dependency -> {
			String name = dependency.getGroup() + ":" + dependency.getName();
			if (name.endsWith("-model"))
				modelDependencies.add(name);
		});

		return new ProjectInfo( //
				project.getGroup().toString(), //
				project.getName(), //
				project.getVersion().toString(), //
				(String) project.findProperty("archetype"), //
				project.getProjectDir(), //
				modelDependencies, //
				mainSourceSet.getOutput().getClassesDirs(), //
				project.getConfigurations().getByName("runtimeClasspath"));
	}

	/** the folder for the generated artifact reflection, model declaration and properties */
	public File generatedFolder() {
		return new File(projectDir, "generated/main/java");
	}

	public boolean isModel() {
		return "model".equals(archetype);
	}

	/** "groupId:artifactId" */
	public String name() {
		return groupId + ":" + artifactId;
	}
}
