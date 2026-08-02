#!/bin/bash
# setup_aapt2_qemu.sh
# Make the x86_64 Android SDK build-tools binaries run on an aarch64 host via
# QEMU user-mode. This is needed because the installed Android SDK build-tools
# ship x86_64 binaries (aapt, aapt2, zipalign, aidl, ...), but this environment
# (UserLAnd / Android userland container) is aarch64 and cannot run them natively.
#
# Usage: ./scripts/setup_aapt2_qemu.sh [--install]
#   --install   Force re-download of QEMU and x86_64 glibc sysroot.
set -euo pipefail

QEMU_DIR="${HOME}/toolchain/qemu-user-static-aarch64"
SYSROOT="${QEMU_DIR}/sysroot"
QEMU_X86_64="${QEMU_DIR}/qemu-x86_64-static"

UBUNTU_QEMU_VERSION="8.2.2+ds-0ubuntu1.18"
UBUNTU_GLIBC_VERSION="2.39-0ubuntu8.8"
UBUNTU_LIBGCC_VERSION="14.2.0-4ubuntu2~24.04.1"

# Parse the ELF machine field from a binary. Prints nothing for non-ELF or on error.
elf_machine() {
    local path="${1}"
    python3 - "${path}" <<'PYEOF'
import struct, sys
path = sys.argv[1]
try:
    with open(path, 'rb') as f:
        h = f.read(20)
    if len(h) < 20 or h[:4] != b'\x7fELF':
        sys.exit(0)
    machine = struct.unpack_from('<H', h, 18)[0]
    print(machine)
except Exception:
    sys.exit(0)
PYEOF
}

download_and_install() {
    echo "Installing aarch64 qemu-user-static..."
    mkdir -p "${QEMU_DIR}"
    local tmpdir
    tmpdir=$(mktemp -d)
    # Keep temp dir on error for debugging only if requested; otherwise clean up.
    trap 'rm -rf "${tmpdir}"' EXIT

    curl -L -o "${tmpdir}/qemu-user-static.deb" \
        "https://ports.ubuntu.com/ubuntu-ports/pool/universe/q/qemu/qemu-user-static_${UBUNTU_QEMU_VERSION}_arm64.deb"
    dpkg-deb -x "${tmpdir}/qemu-user-static.deb" "${tmpdir}/extracted"
    cp "${tmpdir}/extracted/usr/bin/qemu-x86_64-static" "${QEMU_X86_64}"
    chmod +x "${QEMU_X86_64}"

    echo "Installing x86_64 glibc sysroot..."
    curl -L -o "${tmpdir}/libc6.deb" \
        "http://security.ubuntu.com/ubuntu/pool/main/g/glibc/libc6_${UBUNTU_GLIBC_VERSION}_amd64.deb"
    curl -L -o "${tmpdir}/libgcc.deb" \
        "http://security.ubuntu.com/ubuntu/pool/main/g/gcc-14/libgcc-s1_${UBUNTU_LIBGCC_VERSION}_amd64.deb"
    rm -rf "${SYSROOT}"
    mkdir -p "${SYSROOT}"
    dpkg-deb -x "${tmpdir}/libc6.deb" "${SYSROOT}"
    dpkg-deb -x "${tmpdir}/libgcc.deb" "${SYSROOT}"
    mkdir -p "${SYSROOT}/lib64"
    ln -sf ../usr/lib/x86_64-linux-gnu/ld-linux-x86-64.so.2 "${SYSROOT}/lib64/ld-linux-x86-64.so.2"
    mkdir -p "${SYSROOT}/etc"
    touch "${SYSROOT}/etc/ld.so.preload"

    echo "QEMU + sysroot installed in ${QEMU_DIR}"
}

# Wrap a single x86_64 ELF executable so it runs under QEMU.
wrap_binary() {
    local bin_path="${1}"
    local dir
    dir=$(dirname "${bin_path}")
    local name
    name=$(basename "${bin_path}")
    local real_path="${dir}/${name}.real"

    # If already a wrapper script (starts with #!) and .real exists, leave it.
    if [ -f "${real_path}" ] && [ "$(head -c 2 "${bin_path}")" = "#!" ]; then
        return
    fi

    echo "Wrapping ${name}..."
    mv "${bin_path}" "${real_path}"
    cat > "${bin_path}" <<WRAP
#!/bin/bash
exec "${QEMU_X86_64}" -L "${SYSROOT}" "${real_path}" "\$@"
WRAP
    chmod +x "${bin_path}"
}

wrap_build_tools_dir() {
    local build_tools_dir="${1}"
    if [ ! -d "${build_tools_dir}" ]; then
        return
    fi
    echo "Scanning ${build_tools_dir}..."
    for f in "${build_tools_dir}"/*; do
        [ -f "${f}" ] || continue
        [ -x "${f}" ] || continue
        # Skip wrapper scripts, symlinks, and already-backed-up .real binaries.
        if [ -L "${f}" ] || [ "$(head -c 2 "${f}")" = "#!" ]; then
            continue
        fi
        case "${f}" in
            *.real) continue ;;
        esac
        local machine
        machine=$(elf_machine "${f}")
        # 0x3e = EM_X86_64
        if [ "${machine}" = "62" ]; then
            wrap_binary "${f}"
        fi
    done
}

if [ "${1:-}" = "--install" ]; then
    download_and_install
elif [ ! -f "${QEMU_X86_64}" ] || [ ! -d "${SYSROOT}/usr/lib/x86_64-linux-gnu" ]; then
    download_and_install
fi

SDK_BUILD_TOOLS="${HOME}/toolchain/android-sdk/build-tools"
for bt in "${SDK_BUILD_TOOLS}"/*/; do
    wrap_build_tools_dir "${bt%/}"
done

# AGP also downloads platform-specific aapt2 artifacts into the Gradle cache and
# extracts them under ~/.gradle/caches/<gradle-version>/transforms/... We wrap
# those too so resource processing can use them.
echo "Scanning Gradle cache for extracted aapt2 binaries..."
find "${HOME}/.gradle/caches" -type d -name '*aapt2*linux*' 2>/dev/null | while read -r dir; do
    if [ -f "${dir}/aapt2" ]; then
        wrap_binary "${dir}/aapt2"
    fi
done

# Verify common binaries
echo "Verifying wrapped binaries..."
for bt in "${SDK_BUILD_TOOLS}"/*/; do
    [ -f "${bt%/}/aapt2" ] && "${bt%/}/aapt2" version || true
    [ -f "${bt%/}/zipalign" ] && "${bt%/}/zipalign" -h 2>&1 | head -1 || true
done

# Verify Gradle-cached aapt2
find "${HOME}/.gradle/caches" -type f -name 'aapt2' 2>/dev/null | while read -r f; do
    echo "Verifying ${f}..."
    "${f}" version || true
done

echo "aapt2 / build-tools QEMU wrapper setup complete."
