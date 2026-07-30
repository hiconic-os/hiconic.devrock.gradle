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

import org.gradle.api.Action;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.artifacts.repositories.MavenArtifactRepository;
import org.gradle.api.credentials.HttpHeaderCredentials;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.SourceSetContainer;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.authentication.http.HttpHeaderAuthentication;
import org.gradle.plugins.ide.eclipse.model.AbstractClasspathEntry;
import org.gradle.plugins.ide.eclipse.model.EclipseModel;
import org.gradle.plugins.ide.eclipse.model.Library;

import hiconic.gradle.plugin.artifact.reflection.GenerateArtifactReflection;
import hiconic.gradle.plugin.gm.declaration.GenerateModelDeclaration;

public class HiconicPlugin implements Plugin<Project> {

	@Override
	public void apply(Project project) {
		String archetype = (String) project.findProperty("archetype");

		GenerateArtifactReflection generateArtifactReflection = new GenerateArtifactReflection();

		Task generateArtifactReflectioTask = project.task("generate-artifact-reflection", task -> {
			task.setGroup("hiconic");
			task.doLast(generateArtifactReflection);
		});

		TaskProvider<Task> compileTask = project.getTasks().named("compileJava");
		
		compileTask.configure(task -> {
			task.dependsOn(generateArtifactReflectioTask);
		});

		if ("model".equals(archetype)) {
			GenerateModelDeclaration generateModelDeclaration = new GenerateModelDeclaration();
			
			compileTask.configure(task -> {
				task.doLast(generateModelDeclaration);
			});
		}

        SourceSetContainer sourceSets = project.getExtensions().getByType(SourceSetContainer.class);
        
        // Configure the main source set to include additional resources
        SourceSet mainSourceSet = sourceSets.getByName(SourceSet.MAIN_SOURCE_SET_NAME);
        mainSourceSet.getResources().srcDir("generated/main/java");
		mainSourceSet.setCompileClasspath(mainSourceSet.getCompileClasspath().plus(project.files("generated/main/java")));
		
		configureEclipsePlugin(project);
		
		project.getExtensions().add("mavenHiconicDev", (Action<String>) this::mavenHiconicDev);
		
		// Define a custom action as a lambda and add it as an extra property
        project.getExtensions().getExtraProperties().set("mavenHiconicDev",  (Action<String>)this::mavenHiconicDev);
	}
	
	private Action<MavenArtifactRepository> mavenHiconicDev(String githubToken) {
		return repo -> {
			repo.setUrl("https://maven.pkg.github.com/hiconic-os/maven-repo-dev");
			repo.credentials(c -> {
				c.setUsername("ignored");
				c.setPassword(githubToken);
			});
		};
	}
	
	private void configureEclipsePlugin(Project project) {
		if (!project.getPlugins().hasPlugin("eclipse"))
			return;
		
		// Access the Eclipse model for configuration
        project.getExtensions().configure(EclipseModel.class, eclipseModel -> {
        	eclipseModel.synchronizationTasks("generate-artifact-reflection");
        	eclipseModel.autoBuildTasks("compileJava");
        	
            eclipseModel.getClasspath().getFile().whenMerged((org.gradle.plugins.ide.eclipse.model.Classpath classpath) -> {
            	 // Define the path of the directory or file to remove
                String path = "generated/main/java";
                
                // org.gradle.plugins.ide.eclipse.model.Library
                // remote the automatically established entry which has the wrong kind
                classpath.getEntries().removeIf(e -> {
                	if (e instanceof AbstractClasspathEntry)
                		return ((AbstractClasspathEntry)e).getPath().equals(path);
                	
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
