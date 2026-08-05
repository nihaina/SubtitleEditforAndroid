#!/usr/bin/env bash

subtitleedit_meson_setup() {
    local build_dir="$1"
    if meson setup "$@"; then
        return 0
    fi

    local meson_log="${build_dir}/meson-logs/meson-log.txt"
    if [[ ! -f "${meson_log}" ]] || \
        ! tail -n 80 "${meson_log}" | grep -q 'Clock skew detected'; then
        return 1
    fi

    sleep 2
    meson setup "$@"
}
