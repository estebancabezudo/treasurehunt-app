#!/usr/bin/env bash

# Bajo ningún concepto este script debe pedir obligatoriamente algún parámetro para ejecutarse. Debe poder correrse desde Files.

set -euo pipefail

script_dir=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
product_root=$(CDPATH= cd -- "${script_dir}/.." && pwd)
documents_root=$(CDPATH= cd -- "${product_root}/.." && pwd)
backend_root="${documents_root}/games/backend"
timestamp=$(date +%Y%m%d-%H%M%S)
output_path="${script_dir}/treasurehunt-sources-review-${timestamp}.zip"

if (( $# > 0 )); then
    [[ $# -eq 2 && $1 == --output ]] || {
        printf 'Usage: %s [--output ZIP]\n' "${BASH_SOURCE[0]}" >&2
        exit 2
    }
    output_path=$2
fi

fail() {
    printf 'Error: %s\n' "$*" >&2
    exit 1
}

for command_name in git zip unzip sha256sum mktemp openssl; do
    command -v "${command_name}" >/dev/null 2>&1 || fail "missing command: ${command_name}"
done
[[ -d ${product_root} ]] || fail "missing Treasure Hunt project: ${product_root}"
git -C "${backend_root}" rev-parse --is-inside-work-tree >/dev/null 2>&1 \
    || fail "not a Git repository: ${backend_root}"
[[ ! -e ${output_path} ]] || fail "refusing to overwrite: ${output_path}"

temporary_directory=$(mktemp -d)
staging_root="${temporary_directory}/staging"
candidate_archive="${temporary_directory}/sources.zip"
trap 'rm -rf -- "${temporary_directory}"' EXIT
mkdir -p -- "${staging_root}"
declare -A staged_paths=()

is_excluded() {
    local path=${1,,}
    case "/${path}/" in
        */.git/*|*/.gradle/*|*/build/*|*/target/*|*/node_modules/*|*/dist/*|*/coverage/*|*/.idea/*|*/.kotlin/*|*/.local-secrets/*)
            return 0
            ;;
    esac
    case "${path}" in
        local.properties|*/local.properties|.env|.env.*|*/.env|*/.env.*|*.apk|*.aab|*.zip|*.log|*.tmp|*.temp|*.bak|*.swp|*.swo|*~|*.db|*.sqlite|*.sqlite3)
            return 0
            ;;
    esac
    return 1
}

is_sensitive_path() {
    local path=${1,,}
    case "/${path}/" in
        */secrets/*|*/credentials/*|*/private-keys/*|*/private_keys/*|*/.ssh/*|*/.gnupg/*|*/.aws/*|*/.codex/*|*/.agents/*)
            return 0
            ;;
    esac
    case "${path}" in
        *.key|*.p12|*.pfx|*.jks|*.keystore|*private-key*|*private_key*)
            return 0
            ;;
    esac
    return 1
}

contains_secret() {
    openssl pkey -in "$1" -inform PEM -noout >/dev/null 2>&1 && return 0
    openssl pkey -in "$1" -inform DER -noout >/dev/null 2>&1 && return 0
    LC_ALL=C grep -aEq -- \
        '^[[:space:]]*-----BEGIN ([A-Z0-9 ]+ )?PRIVATE KEY-----[[:space:]]*$|(^|[^A-Za-z0-9])(sk-proj-|sk-)[A-Za-z0-9_-]{20,}|(^|[^A-Z0-9])AKIA[A-Z0-9]{16}([^A-Z0-9]|$)' \
        "$1"
}

stage_file() {
    local source_file=$1
    local archive_path=$2
    [[ -f ${source_file} && ! -L ${source_file} ]] || return 0
    is_excluded "${archive_path}" && return 0
    is_sensitive_path "${archive_path}" && fail "sensitive path selected: ${archive_path}"
    contains_secret "${source_file}" && fail "possible secret found in: ${archive_path}"
    [[ -z ${staged_paths["${archive_path}"]+x} ]] || return 0
    staged_paths["${archive_path}"]=1
    mkdir -p -- "${staging_root}/$(dirname -- "${archive_path}")"
    cp -p -- "${source_file}" "${staging_root}/${archive_path}"
}

stage_git_selection() {
    local repository=$1
    local archive_prefix=$2
    shift 2
    local relative_path
    while IFS= read -r -d '' relative_path; do
        stage_file "${repository}/${relative_path}" "${archive_prefix}/${relative_path}"
    done < <(git -C "${repository}" ls-files -z --cached --others --exclude-standard -- "$@")
}

stage_filesystem_tree() {
    local source_root=$1
    local archive_prefix=$2
    local source_file
    while IFS= read -r -d '' source_file; do
        stage_file "${source_file}" "${archive_prefix}/${source_file#"${source_root}/"}"
    done < <(find -P "${source_root}" \
        -type d \( -name .git -o -name .gradle -o -name build -o -name target \
            -o -name node_modules -o -name dist -o -name coverage -o -name .idea \
            -o -name .kotlin -o -name .local-secrets \) -prune \
        -o -type f -print0)
}

if git -C "${product_root}" rev-parse --is-inside-work-tree >/dev/null 2>&1; then
    stage_git_selection "${product_root}" 'treasurehunt' '.'
else
    stage_filesystem_tree "${product_root}" 'treasurehunt'
fi

stage_git_selection "${backend_root}" 'games/backend' '.'

file_count=$(find -P "${staging_root}" -type f -printf . | wc -c)
(( file_count > 0 )) || fail 'no source files selected'
(cd "${staging_root}" && zip -q -r "${candidate_archive}" .)
unzip -tq "${candidate_archive}" >/dev/null || fail 'unzip validation failed'
mkdir -p -- "$(dirname -- "${output_path}")"
mv -- "${candidate_archive}" "${output_path}"

archive_size=$(stat -c '%s' "${output_path}")
archive_sha256=$(sha256sum "${output_path}" | awk '{print $1}')
printf 'ZIP: %s\nFiles: %s\nSize: %s bytes\nSHA-256: %s\nunzip -t: OK\n' \
    "${output_path}" "${file_count}" "${archive_size}" "${archive_sha256}"
