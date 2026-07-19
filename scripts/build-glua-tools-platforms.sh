#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
vscode_output_root="${repo_root}/vscode/extensions/autogo/bin"
idea_resource_root="${repo_root}/jetbrains/extensions/autogo/src/main/resources"
source_root="${GO_LUA_VM_ROOT:-}"
temporary_root=""

if [[ -z "${source_root}" ]]; then
  candidates=(
    "${repo_root}/../go-lua-vm"
    "${repo_root}/../../go-lua-vm"
    "${HOME:-}/Documents/go-lua-vm"
  )
  for candidate in "${candidates[@]}"; do
    if [[ -f "${candidate}/go.mod" && -f "${candidate}/cmd/gluals/main.go" && -f "${candidate}/cmd/gluac/main.go" ]]; then
      source_root="${candidate}"
      break
    fi
  done
fi

if [[ -z "${source_root}" ]]; then
  temporary_root="$(mktemp -d "${TMPDIR:-/tmp}/autogo-go-lua-vm.XXXXXX")"
  trap 'rm -rf "${temporary_root}"' EXIT
  source_root="${temporary_root}/go-lua-vm"
  git clone --filter=blob:none https://github.com/ZingYao/go-lua-vm.git "${source_root}"
  git -C "${source_root}" checkout --detach "${GO_LUA_VM_REF:-7edd7b5a31538e9678b443939f90b12fa7674701}"
fi

if [[ ! -f "${source_root}/cmd/gluals/main.go" || ! -f "${source_root}/cmd/gluac/main.go" ]]; then
  echo "invalid go-lua-vm source: ${source_root}" >&2
  exit 1
fi

targets=(
  "darwin amd64"
  "darwin arm64"
  "windows amd64"
  "windows arm64"
)

for target in "${targets[@]}"; do
  os="${target%% *}"
  arch="${target##* }"
  vscode_output_dir="${vscode_output_root}/${os}-${arch}"
  idea_gluals_dir="${idea_resource_root}/gluals/${os}-${arch}"
  idea_gluac_dir="${idea_resource_root}/gluac/${os}-${arch}"
  suffix=""
  if [[ "${os}" == "windows" ]]; then suffix=".exe"; fi
  mkdir -p "${vscode_output_dir}" "${idea_gluals_dir}" "${idea_gluac_dir}"
  (
    cd "${source_root}"
    CGO_ENABLED=0 GOOS="${os}" GOARCH="${arch}" go build -trimpath -o "${vscode_output_dir}/gluals${suffix}" ./cmd/gluals
    CGO_ENABLED=0 GOOS="${os}" GOARCH="${arch}" go build -trimpath -o "${vscode_output_dir}/gluac${suffix}" ./cmd/gluac
  )
  cp "${vscode_output_dir}/gluals${suffix}" "${idea_gluals_dir}/gluals${suffix}"
  cp "${vscode_output_dir}/gluac${suffix}" "${idea_gluac_dir}/gluac${suffix}"
  echo "built ${vscode_output_dir}/gluals${suffix}"
  echo "built ${vscode_output_dir}/gluac${suffix}"
  echo "bundled IDEA ${idea_gluals_dir}/gluals${suffix}"
  echo "bundled IDEA ${idea_gluac_dir}/gluac${suffix}"
done
