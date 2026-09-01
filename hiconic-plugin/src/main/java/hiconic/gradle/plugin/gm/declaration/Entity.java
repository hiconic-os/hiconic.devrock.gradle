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

/**
 * common entity for both reflections
 * 
 * @author pit
 */
public class Entity {
	private boolean genericEntity;
	private String forward;
	private boolean isEnum;
	private String name;

	public Entity() {
	}

	public Entity(String name, boolean isGeneric, boolean isEnum, String forward) {
		this.name = name;
		this.genericEntity = isGeneric;
		this.isEnum = isEnum;
		this.forward = forward;
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public String getForwardDeclaration() {
		return forward;
	}
	public void setForwardDeclaration(String forward) {
		this.forward = forward;
	}

	public boolean getIsGenericEntity() {
		return genericEntity;
	}

	public void setIsGenericEntity(boolean genericEntity) {
		this.genericEntity = genericEntity;
	}

	public void setIsEnum(boolean isEnum) {
		this.isEnum = isEnum;
	}
	public boolean getIsEnum() {
		return isEnum;
	}

}
