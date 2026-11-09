// SPDX-License-Identifier: MIT
// Copyright © 2018-2023 WireGuard LLC. All Rights Reserved.

#include <sys/types.h>
#include "key.h"
#include "x25519.h"

/* From <sys/kern_control.h>. Имена с префиксом wg_, чтобы не конфликтовать с такими же
   структурами из Tun2SocksKitC (прокси-туннель собран в том же расширении). */
#ifndef CTLIOCGINFO
#define CTLIOCGINFO 0xc0644e03UL
#endif
struct wg_ctl_info {
    u_int32_t   ctl_id;
    char        ctl_name[96];
};
struct wg_sockaddr_ctl {
    u_char      sc_len;
    u_char      sc_family;
    u_int16_t   ss_sysaddr;
    u_int32_t   sc_id;
    u_int32_t   sc_unit;
    u_int32_t   sc_reserved[5];
};
