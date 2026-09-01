#!/usr/bin/env bash
#
# gradlize-group.sh - generate Gradle build files for a Hiconic artifact group, so that
# an IDE such as IntelliJ can import the group and run the hiconic Gradle plugin. The
# plugin generates the artifact reflection and the model declaration on each build,
# which the Eclipse plugins do in Eclipse.
#
# The script writes:
#   settings.gradle       at the group root, with one include per code artifact
#   build.gradle          at the group root, with the common configuration
#   <artifact>/build.gradle   with version, archetype and dependencies
#
# The pom.xml files stay the source of truth and are never changed. Run the script
# again after a pom.xml changed.
#
# The script is idempotent - a second run produces the same result.
#
set -euo pipefail

# --------------------------------------------------------------------------------
# repositories written into the generated root build.gradle. Adjust for your group.
# Format: name|url|token-env-var  (an empty token-env-var means no credentials)
# --------------------------------------------------------------------------------
REPOSITORIES=(
	"hiconic-dev|https://maven.pkg.github.com/hiconic-os/maven-repo-dev|GITHUB_READ_PACKAGES_TOKEN"
)

# artifact directories that must not become gradle projects. A dependency on such
# an artifact becomes a normal coordinate dependency, so it is taken from a
# repository instead of from the sources.
EXCLUDED_ARTIFACTS=(
)

# the gradle plugin. Each developer publishes it locally:
#   cd hiconic.devrock.gradle/hiconic-plugin && ./gradlew publishToMavenLocal
PLUGIN_COORDINATES="hiconic.devrock.gradle:hiconic-plugin:1.0.1"

GROUP_DIR="."
DRY_RUN=0

usage() {
	cat <<'USAGE'
Usage: gradlize-group.sh [options] [group-dir]

Options:
  -n, --dry-run   show the changes as a diff, write nothing
  -h, --help      show this help

group-dir defaults to the current directory. It must contain the artifact
directories, each with a pom.xml, including the group's parent artifact.

Only artifacts with a src directory become Gradle projects. Artifacts without
sources (parent, assets, setups, repository views) are reported and skipped, and
so are the directories listed in EXCLUDED_ARTIFACTS at the top of this script.
A dependency on a skipped artifact becomes a coordinate dependency, so it comes
from a repository instead of from the sources.
USAGE
}

while [ $# -gt 0 ]; do
	case "$1" in
		-n|--dry-run) DRY_RUN=1 ;;
		-h|--help) usage; exit 0 ;;
		-*) echo "unknown option: $1" >&2; usage >&2; exit 2 ;;
		*) GROUP_DIR="$1" ;;
	esac
	shift
done

[ -d "$GROUP_DIR" ] || { echo "not a directory: $GROUP_DIR" >&2; exit 1; }
cd "$GROUP_DIR"

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

# --------------------------------------------------------------------------------
# awk helpers
# --------------------------------------------------------------------------------
AWK_LIB="$WORK/lib.awk"
cat > "$AWK_LIB" <<'AWK_EOF'
function tagval(line, tag,    re, s) {
	re = "<" tag ">[^<]*</" tag ">"
	if (match(line, re) == 0)
		return ""
	s = substr(line, RSTART, RLENGTH)
	sub("^<" tag ">", "", s)
	sub("</" tag ">$", "", s)
	gsub(/^[ \t]+|[ \t]+$/, "", s)
	return s
}
AWK_EOF

# one dependency per line: groupId, artifactId, version, scope, classifier, type
DEPS_AWK="$WORK/deps.awk"
cat > "$DEPS_AWK" <<'AWK_EOF'
BEGIN { OFS = "\t" }
# an empty field would collapse when bash reads the line, because a tab is an
# IFS whitespace character, so empty values are written as a dash
function e(v) { return v == "" ? "-" : v }
{
	if ($0 ~ /<exclusions>/)  { ex = 1; exclusions = 1 }
	if ($0 ~ /<\/exclusions>/) { ex = 0 }

	if (inb == 0) {
		if ($0 !~ /<dependency>/)
			next
		inb = 1; gid = ""; aid = ""; ver = ""; scope = ""; cls = ""; typ = ""
	}

	if (ex == 0) {
		if ($0 ~ /<groupId>/)    gid   = tagval($0, "groupId")
		if ($0 ~ /<artifactId>/) aid   = tagval($0, "artifactId")
		if ($0 ~ /<version>/)    ver   = tagval($0, "version")
		if ($0 ~ /<scope>/)      scope = tagval($0, "scope")
		if ($0 ~ /<classifier>/) cls   = tagval($0, "classifier")
		if ($0 ~ /<type>/)       typ   = tagval($0, "type")
	}

	if ($0 ~ /<\/dependency>/) {
		print e(gid), e(aid), e(ver), e(scope), e(cls), e(typ)
		inb = 0
	}
}
END { if (exclusions) print "#exclusions", "-", "-", "-", "-", "-" }
AWK_EOF

# one property per line: name, value
PROPS_AWK="$WORK/props.awk"
cat > "$PROPS_AWK" <<'AWK_EOF'
BEGIN { OFS = "\t" }
/<properties>/  { inp = 1; next }
/<\/properties>/ { inp = 0; next }
inp == 1 {
	if (match($0, /<[A-Za-z0-9._-]+>[^<]*<\/[A-Za-z0-9._-]+>/) == 0)
		next
	s = substr($0, RSTART, RLENGTH)
	name = s
	sub(/^</, "", name)
	sub(/>.*$/, "", name)
	print name, tagval(s, name)
}
AWK_EOF

pom_own_value() { # pom-file tag
	awk -v tag="$2" '
		/<parent>/       { skip = "parent" }
		/<dependencies>/ { skip = "dependencies" }
		/<properties>/   { skip = "properties" }
		/<build>/        { skip = "build" }
		/<profiles>/     { skip = "profiles" }
		skip != "" {
			if ($0 ~ "</" skip ">")
				skip = ""
			next
		}
		found == 0 {
			re = "<" tag ">[^<]*</" tag ">"
			if (match($0, re) > 0) {
				s = substr($0, RSTART, RLENGTH)
				sub("^<" tag ">", "", s)
				sub("</" tag ">$", "", s)
				print s
				found = 1
			}
		}
	' "$1"
}

pom_parent_value() { # pom-file tag
	awk -v tag="$2" '
		/<parent>/ { inp = 1 }
		inp == 1 {
			re = "<" tag ">[^<]*</" tag ">"
			if (match($0, re) > 0 && found == 0) {
				s = substr($0, RSTART, RLENGTH)
				sub("^<" tag ">", "", s)
				sub("</" tag ">$", "", s)
				print s
				found = 1
			}
		}
		/<\/parent>/ { inp = 0 }
	' "$1"
}

# --------------------------------------------------------------------------------
# step 1: collect the artifacts of the group
# --------------------------------------------------------------------------------
MAP="$WORK/artifacts.tsv" # artifactId, version, directory, archetype, state
: > "$MAP"

GROUP_ID=""
PARENT_POM=""

for pom in */pom.xml; do
	[ -f "$pom" ] || continue
	dir="${pom%/pom.xml}"

	aid="$(pom_own_value "$pom" artifactId)"
	ver="$(pom_own_value "$pom" version)"
	pkg="$(pom_own_value "$pom" packaging)"
	gid="$(pom_own_value "$pom" groupId)"
	[ -n "$gid" ] || gid="$(pom_parent_value "$pom" groupId)"
	arch="$(grep -o '<archetype>[^<]*</archetype>' "$pom" | sed 's/.*>\(.*\)<.*/\1/' | head -1 || true)"

	[ -n "$aid" ] && [ -n "$ver" ] || { echo "skipping $pom - no artifactId or version" >&2; continue; }

	# state: yes = becomes a gradle project, no = has no sources,
	# excluded = listed in EXCLUDED_ARTIFACTS
	state=no
	[ -d "$dir/src" ] && state=yes
	for excluded in ${EXCLUDED_ARTIFACTS[@]+"${EXCLUDED_ARTIFACTS[@]}"}; do
		[ "$dir" = "$excluded" ] && state=excluded
	done

	# a dash for a missing archetype: an empty field would collapse when bash
	# reads the line, because a tab is an IFS whitespace character
	[ -n "$arch" ] || arch="-"

	printf '%s\t%s\t%s\t%s\t%s\n' "$aid" "$ver" "$dir" "$arch" "$state" >> "$MAP"

	if [ "$pkg" = "pom" ] && [ -z "$(pom_parent_value "$pom" artifactId)" ]; then
		PARENT_POM="$pom"
		GROUP_ID="$gid"
	fi
done

[ -n "$PARENT_POM" ] || { echo "no group parent pom found" >&2; exit 1; }

PROPS="$WORK/props.tsv"
awk -f "$AWK_LIB" -f "$PROPS_AWK" "$PARENT_POM" > "$PROPS"
JAVA_VERSION="$(awk -F'\t' '$1 == "java.version" { print $2 }' "$PROPS")"
[ -n "$JAVA_VERSION" ] || JAVA_VERSION=21

echo "group      : $GROUP_ID"
echo "java       : $JAVA_VERSION"
echo "artifacts  : $(wc -l < "$MAP")"
echo "as projects: $(awk -F'\t' '$5 == "yes"' "$MAP" | wc -l)"

awk -F'\t' '$5 == "no"       { printf "  no sources, skipped: %s\n", $3 }
              $5 == "excluded" { printf "  excluded by configuration: %s\n", $3 }' "$MAP"

# --------------------------------------------------------------------------------
# helpers
# --------------------------------------------------------------------------------
apply() { # new-content-file target-file
	local new="$1" target="$2"
	if [ -f "$target" ] && cmp -s "$new" "$target"; then
		return 0
	fi
	if [ "$DRY_RUN" = 1 ]; then
		echo "--- would change: $target"
		diff -u "$target" "$new" 2>/dev/null || true
	else
		cp "$new" "$target"
		echo "changed: $target"
	fi
}

is_project() { # artifactId
	awk -F'\t' -v a="$1" '$1 == a && $5 == "yes" { found = 1 } END { exit !found }' "$MAP"
}

group_version_of() { # artifactId
	awk -F'\t' -v a="$1" '$1 == a { print $2; exit }' "$MAP"
}

# the gradle project path is the directory, which is not always the artifactId
dir_of() { # artifactId
	awk -F'\t' -v a="$1" '$1 == a { print $3; exit }' "$MAP"
}

resolve_version() { # raw-version
	local v="$1" name value
	case "$v" in
		'${'*'}')
			name="${v#\$\{}"
			name="${name%\}}"
			value="$(awk -F'\t' -v n="$name" '$1 == n { print $2; exit }' "$PROPS")"
			if [ -n "$value" ]; then
				printf '%s' "$value"
			else
				echo "  WARNING: unresolved property $v" >&2
				printf '%s' "$v"
			fi
			;;
		*) printf '%s' "$v" ;;
	esac
}

configuration_of() { # scope
	case "$1" in
		test) printf 'testImplementation' ;;
		provided|system) printf 'compileOnly' ;;
		runtime) printf 'runtimeOnly' ;;
		# api, not implementation: maven's compile scope is transitive on the
		# compile classpath of a depender, and implementation would hide it
		*) printf 'api' ;;
	esac
}

# --------------------------------------------------------------------------------
# step 2: settings.gradle
# --------------------------------------------------------------------------------
out="$WORK/settings.gradle"
{
	echo "// generated by gradlize-group.sh - IDE support only, pom.xml stays the source of truth"
	echo
	echo "rootProject.name = '$GROUP_ID'"
	echo
	awk -F'\t' '$5 == "yes" { printf "include '"'"'%s'"'"'\n", $3 }' "$MAP" | sort
} > "$out"
apply "$out" "settings.gradle"

# --------------------------------------------------------------------------------
# step 3: root build.gradle with the common configuration
# --------------------------------------------------------------------------------
out="$WORK/build.gradle"
{
	echo "// generated by gradlize-group.sh - IDE support only, pom.xml stays the source of truth"
	echo
	echo "buildscript {"
	echo "	repositories {"
	echo "		// the hiconic plugin is published locally:"
	echo "		//   cd hiconic-plugin && ./gradlew publishToMavenLocal"
	echo "		mavenLocal()"
	echo "		mavenCentral()"
	echo "	}"
	echo "	dependencies {"
	echo "		classpath '$PLUGIN_COORDINATES'"
	echo "	}"
	echo "}"
	echo
	echo "subprojects {"
	echo "	apply plugin: 'java-library'"
	echo
	echo "	group = '$GROUP_ID'"
	echo
	echo "	repositories {"
	echo "		mavenCentral()"
	for repo in "${REPOSITORIES[@]}"; do
		IFS='|' read -r name url token <<< "$repo"
		echo "		maven {"
		echo "			name = '$name'"
		echo "			url = '$url'"
		if [ -n "$token" ]; then
			echo "			credentials {"
			echo "				username = 'ignored'"
			echo "				password = System.getenv('$token')"
			echo "			}"
		fi
		echo "		}"
	done
	echo "	}"
	echo
	echo "	// compile with the JDK that runs gradle, but target the java version of the"
	echo "	// parent pom. A toolchain would demand exactly that JDK on every machine."
	echo "	tasks.withType(JavaCompile).configureEach {"
	echo "		options.release = $JAVA_VERSION"
	echo "	}"
	echo
	echo "	// Hiconic layout: java sources and resources both live in src"
	echo "	sourceSets {"
	echo "		main {"
	echo "			java {"
	echo "				srcDirs = ['src']"
	echo "			}"
	echo "			resources {"
	echo "				srcDirs = ['src']"
	echo "				exclude '**/*.java'"
	echo "			}"
	echo "		}"
	echo "	}"
	echo "}"
} > "$out"
apply "$out" "build.gradle"

# --------------------------------------------------------------------------------
# step 4: one build.gradle per code artifact
# --------------------------------------------------------------------------------
while IFS=$'\t' read -r aid ver dir arch state; do
	[ "$state" = "yes" ] || continue

	out="$WORK/artifact.gradle"
	{
		echo "// generated by gradlize-group.sh from pom.xml - do not edit"
		echo
		echo "version = '$ver'"
		if [ "$arch" != "-" ]; then
			echo
			echo "// the archetype must be set before the plugin is applied"
			echo "ext.archetype = '$arch'"
		fi
		echo
		echo "apply plugin: 'hiconic'"
		echo
		echo "dependencies {"

		awk -f "$AWK_LIB" -f "$DEPS_AWK" "$dir/pom.xml" |
		while IFS=$'\t' read -r dgid daid dver dscope dcls dtyp; do
			if [ "$dgid" = "#exclusions" ]; then
				echo "  WARNING: $dir/pom.xml has <exclusions>, which are not translated" >&2
				continue
			fi
			[ "$dgid" != "-" ] && [ "$daid" != "-" ] || continue

			# devrock asset dependencies are no compile classpath entries
			if [ "$dcls" != "-" ] || { [ "$dtyp" != "-" ] && [ "$dtyp" != "jar" ]; }; then
				echo "  asset dependency skipped: $dir -> $dgid:$daid" >&2
				continue
			fi

			cfg="$(configuration_of "$dscope")"

			if [ "$dgid" = "$GROUP_ID" ] && is_project "$daid"; then
				echo "	$cfg project(':$(dir_of "$daid")')"
			elif [ "$dgid" = "$GROUP_ID" ]; then
				echo "	$cfg '$dgid:$daid:$(group_version_of "$daid")'"
			else
				echo "	$cfg '$dgid:$daid:$(resolve_version "$dver")'"
			fi
		done

		echo "}"
	} > "$out"

	apply "$out" "$dir/build.gradle"
done < "$MAP"

# --------------------------------------------------------------------------------
# step 5: gitignore entries for the gradle output
# --------------------------------------------------------------------------------
for entry in ".gradle" "/build" "generated"; do
	if [ -f .gitignore ] && grep -qx -- "$entry" .gitignore; then
		continue
	fi
	if [ "$DRY_RUN" = 1 ]; then
		echo "--- would add to .gitignore: $entry"
	else
		printf '%s\n' "$entry" >> .gitignore
		echo "added to .gitignore: $entry"
	fi
done

echo "done"
