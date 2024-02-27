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
import java.net.MalformedURLException;
import java.net.URL;

import org.gradle.api.UncheckedIOException;

public class ClasspathTools {
	public static URL toUrl(File f) {
		try {
			return f.toURI().toURL();
		} catch (MalformedURLException e) {
			throw new UncheckedIOException(e);
		}		
	}
}
