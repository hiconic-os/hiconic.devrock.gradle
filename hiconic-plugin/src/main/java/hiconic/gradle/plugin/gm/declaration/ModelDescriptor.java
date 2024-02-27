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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

public class ModelDescriptor {
	public String groupId;
	public String artifactId;
	public String version;
	public String globalId;
	public String name;
	public String hash;
	public Set<String> declaredTypes = new TreeSet<String>();
	public List<String> dependencies = new ArrayList<String>();
	public Map<String, Set<String>> forwardTypes = new TreeMap<String, Set<String>>();
}