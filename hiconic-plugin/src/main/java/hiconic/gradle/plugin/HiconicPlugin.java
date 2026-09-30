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

import org.gradle.api.Plugin;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.SourceSetContainer;
import org.gradle.api.tasks.TaskContainer;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.plugins.ide.eclipse.model.AbstractClasspathEntry;
import org.gradle.plugins.ide.eclipse.model.EclipseModel;
import org.gradle.plugins.ide.eclipse.model.Library;

import hiconic.gradle.plugin.artifact.reflection.GenerateArtifactReflection;
import hiconic.gradle.plugin.classpath.resources.IndexClasspathResources;
import hiconic.gradle.plugin.gm.declaration.GenerateModelDeclaration;

public class HiconicPlugin implements Plugin<Project> {

	private static final String GENERATED_FOLDER = "generated/main/java";
	private static final String GENERATED_RESOURCES_FOLDER = "generated/main/resources";

	@Override
	public void apply(Project project) {
		TaskContainer tasks = project.getTasks();

		TaskProvider<Task> generateArtifactReflectionTask = tasks.register("generate-artifact-reflection", task -> task.setGroup("hiconic"));

		TaskProvider<Task> compileTask = tasks.named("compileJava");

		compileTask.configure(task -> task.dependsOn(generateArtifactReflectionTask));

		// the generated index is a resource, so it is copied to the output together with the resources it lists
		TaskProvider<Task> indexClasspathResourcesTask = tasks.register("index-classpath-resources", task -> task.setGroup("hiconic"));

		tasks.named(JavaPlugin.PROCESS_RESOURCES_TASK_NAME).configure(task -> task.dependsOn(indexClasspathResourcesTask));

		// the actions are created after the build script of the project was evaluated, so that
		// group, version and archetype are final, and so that all values they need are read at
		// configuration time. An action must not touch the project while it runs.
		project.afterEvaluate(p -> {
			ProjectInfo projectInfo = ProjectInfo.createFrom(p);

			generateArtifactReflectionTask.configure(task -> task.doLast(new GenerateArtifactReflection(projectInfo)));

			if (projectInfo.isModel())
				compileTask.configure(task -> task.doLast(new GenerateModelDeclaration(projectInfo)));

			indexClasspathResourcesTask.configure(task -> task.doLast(new IndexClasspathResources(projectInfo)));
		});

		SourceSetContainer sourceSets = project.getExtensions().getByType(SourceSetContainer.class);

		// Configure the main source set to include additional resources
		SourceSet mainSourceSet = sourceSets.getByName(SourceSet.MAIN_SOURCE_SET_NAME);
		mainSourceSet.getResources().srcDir(GENERATED_FOLDER);
		mainSourceSet.getResources().srcDir(GENERATED_RESOURCES_FOLDER);

		publishGeneratedClassesToConsumers(project, generateArtifactReflectionTask);

		configureEclipsePlugin(project);
	}

	/**
	 * The generated folder holds class files, but it is neither the class output of the java
	 * compiler nor part of it. Without this, a project that depends on this one does not see the
	 * artifact reflection: a project dependency is resolved to the "classes" variant, which holds
	 * only the class output of the compiler.
	 * <p>
	 * The folder is added as a file dependency of the api configuration, and not as an artifact of
	 * the outgoing variants, because an IDE also understands a file dependency. It is exported to
	 * the consumers of this project, and it is on the compile classpath of this project itself.
	 */
	private void publishGeneratedClassesToConsumers(Project project, TaskProvider<Task> generateTask) {
		ConfigurableFileCollection generatedClasses = project.files(GENERATED_FOLDER).builtBy(generateTask);

		String configuration = project.getConfigurations().findByName(JavaPlugin.API_CONFIGURATION_NAME) != null //
				? JavaPlugin.API_CONFIGURATION_NAME //
				: JavaPlugin.IMPLEMENTATION_CONFIGURATION_NAME;

		project.getDependencies().add(configuration, generatedClasses);
	}

	private void configureEclipsePlugin(Project project) {
		if (!project.getPlugins().hasPlugin("eclipse"))
			return;

		// Access the Eclipse model for configuration
		project.getExtensions().configure(EclipseModel.class, eclipseModel -> {
			eclipseModel.synchronizationTasks("generate-artifact-reflection", "index-classpath-resources");
			eclipseModel.autoBuildTasks("compileJava");

			eclipseModel.getClasspath().getFile().whenMerged((org.gradle.plugins.ide.eclipse.model.Classpath classpath) -> {
				// Define the path of the directory or file to remove
				String path = "generated/main/java";

				// org.gradle.plugins.ide.eclipse.model.Library
				// remote the automatically established entry which has the wrong kind
				classpath.getEntries().removeIf(e -> {
					if (e instanceof AbstractClasspathEntry)
						return ((AbstractClasspathEntry) e).getPath().equals(path);

					return false;
				});

				File file = project.file(path);
				Library libEntry = new Library(classpath.fileReference(file));
				libEntry.setExported(true);

				classpath.getEntries().add(libEntry);
			});
		});
	}

}
