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

import java.io.IOException;
import java.io.InputStream;
import java.lang.classfile.Annotation;
import java.lang.classfile.AnnotationElement;
import java.lang.classfile.AnnotationValue;
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.attribute.RuntimeVisibleAnnotationsAttribute;
import java.util.HashMap;
import java.util.Map;

import org.gradle.api.GradleException;

/**
 * {@link ModelReflection} based on the Class-File API of the JDK (since Java 24) - thus we don't need any external deps.
 * <p>
 * This doesn't load classes, so an incomplete classpath gives a clear message instead of a linkage error, and no class of the analyzed model stays
 * resident in the Gradle daemon.
 */
public class ModelClassFileReflection implements ModelReflection {

	private static final String ENUM_NAME = "java/lang/Enum";
	private static final String GENERIC_ENTITY_NAME = "com/braintribe/model/generic/GenericEntity";
	private static final String FORWARD_ANNOTATION_DESCRIPTOR = "Lcom/braintribe/model/generic/annotation/ForwardDeclaration;";

	private final String projectName;
	private final ClassLoader classLoader;
	private final Map<String, Entity> nameToEntityMap = new HashMap<>();

	public ModelClassFileReflection(String projectName, ClassLoader classLoader) {
		this.projectName = projectName;
		this.classLoader = classLoader;
	}

	public static ModelReflection scan(String projectName, ClassLoader classLoader) {
		return new ModelClassFileReflection(projectName, classLoader);
	}

	@Override
	public Entity load(String className) {
		return acquireEntity(internalName(className));
	}

	private byte[] readClassFile(String internalName) {
		try (InputStream in = classLoader.getResourceAsStream(internalName + ".class")) {
			return in == null ? null : in.readAllBytes();
		} catch (IOException e) {
			throw new GradleException("Cannot read class file of [" + internalName.replace('/', '.') + "] (project: " + projectName + ")", e);
		}
	}

	private Entity read(byte[] bytes) {
		ClassModel classModel = ClassFile.of().parse(bytes);

		Entity entity = new Entity();
		entity.setName(classModel.thisClass().asInternalName());

		// the entity is cached before the super types are analyzed, because that analysis
		// reads further class files and may come back to this one
		nameToEntityMap.put(entity.getName(), entity);

		entity.setIsGenericEntity(anyIsGenericEntity(classModel));
		entity.setIsEnum(isEnum(classModel));
		entity.setForwardDeclaration(forwardDeclarationOf(classModel));

		return entity;
	}

	private boolean anyIsGenericEntity(ClassModel classModel) {
		for (var itf : classModel.interfaces()) {
			String name = itf.asInternalName();
			if (GENERIC_ENTITY_NAME.equals(name) || acquireEntity(name).getIsGenericEntity())
				return true;
		}
		return false;
	}

	private boolean isEnum(ClassModel classModel) {
		if (classModel.superclass().isEmpty())
			return false;

		String superName = classModel.superclass().get().asInternalName();
		if (ENUM_NAME.equals(superName))
			return true;

		return acquireEntity(superName).getIsEnum();
	}

	private String forwardDeclarationOf(ClassModel classModel) {
		RuntimeVisibleAnnotationsAttribute attribute = classModel //
				.findAttribute(Attributes.runtimeVisibleAnnotations()) //
				.orElse(null);
		if (attribute == null)
			return null;

		for (Annotation annotation : attribute.annotations()) {
			if (!FORWARD_ANNOTATION_DESCRIPTOR.equals(annotation.className().stringValue()))
				continue;

			for (AnnotationElement element : annotation.elements())
				if (element.value() instanceof AnnotationValue.OfString s)
					return s.stringValue();
		}

		return null;
	}

	private Entity acquireEntity(String internalName) {
		Entity entity = nameToEntityMap.get(internalName);
		if (entity == null) {
			byte[] bytes = readClassFile(internalName);
			if (bytes != null)
				entity = read(bytes);
		}

		if (entity == null)
			throw new GradleException("Cannot analyze model of project [" + projectName + "], type not found on classpath: ["
					+ internalName.replace('/', '.') + "]. If it is a super type of one of your types, maybe a dependency is missing?");

		return entity;
	}

	private static String internalName(String className) {
		return className.replace('.', '/');
	}
}
