# The Gradle Hiconic Plugin

The Hiconic Plugin for gradle helps to create and build generic models for the [hiconic platform](https://github.com/hiconic-os) which supports building applications ranging from microservices (reflex) up to large integrated platforms (cortex) based on models for data, api and configuration.

Generic Models are interface based POJO/Bean models which are automatically implemented to offer additional value for generic algorithms and cross cutting concerns (e.g. efficient and elegant reflection, property access interceptors).

## Plugin Features

### Generation of Artifact Reflection

`ArtifactReflection` is a way to automatically propagate a library's artifact coordinates from its build file onto its classpath. The `ArtifactReflection` is prepared to make it accessible with referential integrity for the support of type-safety and code completion.

The propagated coordinates are:

|artifact coordinate|gradle source property|example value|
|---|---|---|
|`groupId`|group|my.group|
|`artifactId`|name|my-model|
|`version`|version|1.0|
|`archetypes`|ext.archetype|model|

The plugin will generate a class who's internal implementation looks like that:

```java
package your.group;

import com.braintribe.common.artifact.ArtifactReflection;
static import java.util.Collections.singleton;

public class _MyModel_ {
    public static final String groupId = "my.group";
    public static final String artifactId = "my-model";
    public static final String version = "1.0";
    public static final String name = groupId + ":" + artifactId;
    public static final String versionedName = name + "#" + version;

    public static final ArtifactReflection reflection = new ArtifactReflection() {
        public String groupId() { return groupId; }
        public String artifactId() { return artifactId; }
        public String version() { return version; }
	    public Set<String> archetypes() { return singleton("model"); }
        public String name() { return name; }
        public String versionedName() { return versionedName; }
    }
}
```

The plugin will also generate a properties file `META-INF/artifact-reflection.properties` that contains the same information:

```properties
groupId=my.group
artifactId=my-model
version=1.0
archetypes=model
reflection-class=my.group._MyModel_
```

All the generated information can be accessed via the classpath:

```java
public class AccessExample {
    public static main(String args[]) {
        // find all artifact reflection properties on the classpath
        Enumeration<URL> resources = AccessExample.class //
            .getClassLoader() //
            .getResources("META-INF/artifact-reflection.properties");

        // access a specific well known artifact reflection via static fields
        System.out.println("groupId: " + my.group._MyModel_.groupId);
        System.out.println("artifactId: " + my.group._MyModel_.artifactId);
        System.out.println("version: " + my.group._MyModel_.version);
        System.out.println("name: " + my.group._MyModel_.name);
        System.out.println("versionedName: " + my.group._MyModel_.versionedName);

        // access a specific well known artifact reflection via interface
        ArtifactReflection ar = my.group._MyModel_.reflection;
        System.out.println("reflection: " + ar);
    }
}
```

### Generation of Generic Model Declaration

Each model can declare a number of entity types and enums types.

The hiconic plugin will generate a model descriptor that reflects that model, its dependencies and types in a classpath resource named `generic-model-declaration.xml`

given the following source:

```java
package example.model;

import com.braintribe.model.generic.base.EnumBase;
import com.braintribe.model.generic.reflection.EnumType;
import com.braintribe.model.generic.reflection.EnumTypes;

/* Enums derive from EnumBase to mark them as model enums and equip them with type support */
public enum Color implements EnumBase {
    // enum constants
	red, green, blue;
	
    // Type literal for reflection
	public static final EnumType T = EnumTypes.T(Color.class);
	
    // Instance level access to the type
	@Override
	public EnumType type() { return T; }
}
```

```java
package example.model;

import com.braintribe.model.generic.GenericEntity;
import com.braintribe.model.generic.reflection.EntityType;
import com.braintribe.model.generic.reflection.EntityTypes;

/* 
If a type has no other super type from a model it derives at least from GenericEntity.
*/
public interface Person extends GenericEntity {
    // Type literal for construction and reflection
	EntityType<Person> T = EntityTypes.T(Person.class);
	
    String getName();
    void setName(String name);

    // a property
	Color getEyeColor();
	void setEyeColor(Color eyeColor);
}
```

### Eclipse support

The hiconic plugin for grade automatically detects the presence of the optional eclipse plugin for gradle to properly configure eclipse classpath and auto building.

## Gradle Build Script for Model Projects

Example for `build.gradle`:
```groovy
plugins {
    // Apply the java-library plugin
    id 'java-library'
    
    // Optionally apply eclipse support 
    // id 'eclipse'

    // Apply hiconic plugin
    id 'hiconic'
}

ext {
    // enable model building features of hiconic plugin
	archetype = 'model'
}

group = 'your.group.id'
version = '1.0'

repositories {
    // Use Maven Central for third party dependencies.
    mavenCentral()
    
    // Use public hiconic-os github repo for hiconic dependencies
    maven {
        url 'https://maven.pkg.github.com/hiconic-os/maven-repo-dev'

        // public github repos require authenthication with github token
        credentials {
			username 'ignored'

            // 1. Login to github with you github user
            // 2. Create a github token with scope "read packages"
            // 3. Set environment var GITHUB_READ_PACKAGES_TOKEN to token
	        password System.getenv('GITHUB_READ_PACKAGES_TOKEN')
	    }
    }
}

dependencies {
    // dependency to root model 
    implementation "com.braintribe.gm:root-model:[2.0,2.1)"
}
```

