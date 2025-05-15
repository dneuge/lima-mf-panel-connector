#!/bin/bash

set -Eeuo pipefail

if [[ $# -ne 6 ]]; then
    echo "Invalid number of parameters."
    echo
    echo "This script is mainly intended for use in CI."
    echo
    echo "Expected parameters:"
    echo
    echo " 1. path to check dependency out to"
    echo " 2. name of POM property holding the dependency version in the main project"
    echo "    (will be overwritten unless a tag/release is being built)"
    echo " 3. set non-zero if dependencies are released to artifact storage/Maven Central"
    echo "    (build & installation will be skipped in that case, unless a snapshot is to be used)"
    echo " 4. Git URL to clone from"
    echo " 5. Git branch name to use if a snapshot is being referred to"
    echo " 6. name of environment variable specifying a dependency version to be checked out"
    echo "    (the variable may be empty/unset but the name has to be specified)"
    echo
    echo
    echo "What it does:"
    echo
    echo "During development we usually want to build against the latest development head of a dependency,"
    echo "which is why *ANY* snapshot version will cause the dependency's dev branch (5) to be checked out."
    echo "This is detected by checking the dependency version property (2) in this project's POM file."
    echo
    echo "If the dependency is supposed to be a release version (not ending in -SNAPSHOT), then depending on (3)"
    echo " - if it is supposed to be available from artifact storage, nothing needs to be done and we quit early"
    echo " - otherwise the version number is prefixed with a 'v' and attempted to be checked out (assuming a tag)"
    echo
    echo "As part of CI, work on dependencies will often want to check if a change breaks something in"
    echo "dependants. Such a build job would pass the version to be tested to a downstream project running this"
    echo "script which then picks the requested version from the variable specified as (6)."
    echo
    echo "After checking out a dependency in the requested revision, this script extracts the *ACTUAL* version"
    echo "and overrides the property (2) in this project's POM to persist the change for later build stages."
    echo
    echo "However, if we build a tag, which usually means we build a release binary for this project, the original"
    echo "POM will remain unchanged. Additionally, some plausibility checks prevent accidentally incorporating"
    echo "unreleased snapshot versions etc."
    exit 1
fi

function die() {
    echo "$@" >&2
    exit 1
}

original_path="$(pwd)"

dep_path=$1
dep_propname=$2
dep_published=$3
git_url=$4
snapshot_branch=$5
version_envname=$6

building_tag=0
if [[ "${CI_COMMIT_TAG:-}" != "" ]]; then
    building_tag=1
fi

wanted_version=$(eval echo \${${version_envname}:-})

echo
echo "=== Preparing ${dep_path} / ${dep_propname}"

dep_parentpath=$(dirname "$dep_path")
mkdir -p "$dep_parentpath"
git clone "$git_url" "$dep_path"
echo

if [[ "$wanted_version" != "" ]]; then
    [[ $building_tag -eq 0 ]] || die "Building a tag but requesting a version override?! Aborting."

    echo "Revision passed by variable: ${wanted_version}"
    git -C "$dep_path" checkout --detach "$wanted_version"
else
    wanted_version="$(mvn help:evaluate -Dexpression="$dep_propname" -q -DforceStdout)"
    checkout_ref="INVALID"
    echo "Version read from POM: ${wanted_version}"
    if [[ "${wanted_version}" =~ -SNAPSHOT$ ]]; then
        [[ $building_tag -eq 0 ]] || die "Building a tag but relying on a snapshot?! Aborting."
        echo "Snapshot detected, using development head instead (branch: ${snapshot_branch})"
        checkout_ref="$snapshot_branch"
    elif [[ $dep_published -ne 0 ]]; then
        echo "Dependency is published; skipping build."
        exit 0
    else
        checkout_ref="v$wanted_version"
        echo "Not a snapshot, using: ${wanted_version}"
    fi
    
    git -C "$dep_path" checkout --detach "$checkout_ref"
fi
echo

if [[ $building_tag -ne 0 ]]; then
    echo "Building a tag - main project POM will not be manipulated!"
else
    cd "$dep_path"
    wanted_version="$(mvn help:evaluate -Dexpression=project.version -q -DforceStdout)"
    echo "Actual dependency version is ${wanted_version}"
    
    cd "$original_path"
    pom_version="$(mvn help:evaluate -Dexpression="$dep_propname" -q -DforceStdout)"
    if [[ "$pom_version" == "$wanted_version" ]]; then
        echo "Main project POM ${dep_propname} is already set correctly, no need to manipulate."
    else
        echo "Overriding ${dep_propname} in main project POM: ${pom_version} => ${wanted_version}"
        sed_propname="${dep_propname//./\\.}"
        sed -i -e 's#\(.*<'"$sed_propname"'>\).*\(</'"$sed_propname"'>\)#\1'"${wanted_version}"'\2#' pom.xml
        
        pom_version="$(mvn help:evaluate -Dexpression="$dep_propname" -q -DforceStdout)"
        if [[ "$pom_version" != "$wanted_version" ]]; then
            echo "Manipulation failed, POM indicates ${pom_version}"
            exit 1
        fi
    fi
fi
echo

cd "$dep_path"
mvn clean install
cd "$original_path"
echo

