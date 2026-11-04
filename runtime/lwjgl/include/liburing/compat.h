/* SPDX-License-Identifier: MIT */
/* GhostCraft: replaces LWJGL's hand-written compat.h. The NDK's kernel headers
   already define __kernel_timespec and open_how, so take them from there.
   build.sh force-includes this file and defines LWJGL's guard to skip theirs. */
#ifndef GHOSTCRAFT_LIBURING_COMPAT_H
#define GHOSTCRAFT_LIBURING_COMPAT_H

#include <inttypes.h>
#include <stdint.h>
#include <sys/stat.h>
#include <linux/time_types.h>
#include <linux/openat2.h>

#endif
