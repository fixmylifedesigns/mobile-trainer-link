// Native "Pico" for testing the Android bridge: links the firmware's real gbridge.c /
// socket_impl.c / io_buffer.c and talks USB packets over stdin/stdout
// (each packet = 1 length byte + payload). handle_input_data() is copied from main.c.
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>
#include <mobile.h>
#include "io_buffer.h"
#include "upkeep_callback.h"
#include "socket_impl.h"

#define MAX_TRANSFER_BYTES 0x40
#define DEBUG_TRANSFER_FLAG 0x80

uint64_t fake_time_us(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (uint64_t)ts.tv_sec * 1000000ull + ts.tv_nsec / 1000;
}

static int read_full(int fd, uint8_t *b, int n) {
    int got = 0;
    while (got < n) {
        int r = read(fd, b + got, n - got);
        if (r <= 0) exit(3);
        got += r;
    }
    return got;
}

static void send_pkt(const uint8_t *b, uint8_t n) {
    uint8_t hdr = n;
    if (write(1, &hdr, 1) != 1 || write(1, b, n) != n) exit(4);
}

static void handle_input_data(uint8_t *buf_in, uint32_t count) {
    for (int i = count; i < (MAX_TRANSFER_BYTES * 2); i++) buf_in[i] = 0;
    if (count > 1) {
        uint32_t reported_num = buf_in[0];
        if ((reported_num & 0xC0) == 0xC0) {
            fprintf(stderr, "[pico] debug cmd %u (%u bytes)\n", buf_in[1], reported_num & 0x3F);
        } else {
            if (reported_num > (count - 1)) reported_num = count - 1;
            set_data_in(buf_in + 1, reported_num);
        }
    }
    uint8_t buf_out[MAX_TRANSFER_BYTES * 2] = {0};
    uint8_t count_out = 1;
    bool success;
    for (int i = 1; i < MAX_TRANSFER_BYTES; i++) {
        buf_out[i] = get_data_out(&success);
        if (!success) break;
        buf_out[0]++;
        count_out++;
    }
    if (!buf_out[0]) {
        for (int i = 1; i < MAX_TRANSFER_BYTES; i++) {
            buf_out[i] = get_data_out_debug(&success);
            if (!success) break;
            buf_out[0]++;
            count_out++;
        }
        if (buf_out[0]) buf_out[0] |= DEBUG_TRANSFER_FLAG;
    }
    send_pkt(buf_out, count_out);
}

// One host packet in, one packet out: lock-step like the USB loop on the phone.
static void upkeep(bool in_loop) {
    (void)in_loop;
    uint8_t n;
    uint8_t buf[MAX_TRANSFER_BYTES * 2];
    read_full(0, &n, 1);
    if (n > sizeof(buf)) exit(5);
    read_full(0, buf, n);
    handle_input_data(buf, n);
}

#define CHECK(cond, msg) do { if (!(cond)) { fprintf(stderr, "FAIL: %s\n", msg); exit(1); } \
    else fprintf(stderr, "ok: %s\n", msg); } while (0)

static int build_dns_query(uint8_t *q, const char *host) {
    int p = 0;
    q[p++] = 0x12; q[p++] = 0x34; q[p++] = 0x01; q[p++] = 0x00;
    q[p++] = 0; q[p++] = 1; q[p++] = 0; q[p++] = 0; q[p++] = 0; q[p++] = 0; q[p++] = 0; q[p++] = 0;
    const char *s = host;
    while (*s) {
        const char *dot = strchr(s, '.');
        int l = dot ? (int)(dot - s) : (int)strlen(s);
        q[p++] = l;
        memcpy(q + p, s, l);
        p += l;
        s += l;
        if (*s == '.') s++;
    }
    q[p++] = 0;
    q[p++] = 0; q[p++] = 1; q[p++] = 0; q[p++] = 1;
    return p;
}

int main(int argc, char **argv) {
    int echo_port = argc > 1 ? atoi(argv[1]) : 0;
    set_upkeep_callback(upkeep);

    // ---- DNS through the phone's built-in responder
    CHECK(impl_sock_open(NULL, 0, MOBILE_SOCKTYPE_UDP, MOBILE_ADDRTYPE_IPV4, 0), "open UDP");
    uint8_t q[512];
    int ql = build_dns_query(q, "gameboy.datacenter.ne.jp");
    struct mobile_addr4 dion = {.type = MOBILE_ADDRTYPE_IPV4, .port = 53, .host = {210, 196, 3, 183}};
    CHECK(impl_sock_send(NULL, 0, q, ql, (struct mobile_addr *)&dion) == ql, "send DNS query");
    uint8_t resp[512];
    struct mobile_addr from = {0};
    int r = 0;
    for (int i = 0; i < 50 && r == 0; i++) r = impl_sock_recv(NULL, 0, resp, sizeof(resp), &from);
    CHECK(r == ql + 16, "DNS reply size");
    CHECK(resp[3] == 0x80 && resp[7] == 1, "DNS reply has one answer");
    CHECK(memcmp(resp + r - 4, (uint8_t[]){10, 0, 0, 7}, 4) == 0, "datacenter host -> game server IP");
    struct mobile_addr4 *f4 = (struct mobile_addr4 *)&from;
    CHECK(from.type == MOBILE_ADDRTYPE_IPV4 && f4->port == 53 && memcmp(f4->host, dion.host, 4) == 0,
          "reply comes from the DNS address the game asked");
    ql = build_dns_query(q, "nosuchhost.dion.ne.jp.invalid");
    impl_sock_send(NULL, 0, q, ql, (struct mobile_addr *)&dion);
    r = 0;
    for (int i = 0; i < 50 && r == 0; i++) r = impl_sock_recv(NULL, 0, resp, sizeof(resp), &from);
    CHECK(r > 0 && (resp[3] & 0x0F) == 3, "unknown host -> NXDOMAIN");
    impl_sock_close(NULL, 0);
    fprintf(stderr, "ok: close UDP\n");

    // ---- TCP echo server run by the Java harness
    CHECK(impl_sock_open(NULL, 1, MOBILE_SOCKTYPE_TCP, MOBILE_ADDRTYPE_IPV4, 0), "open TCP");
    struct mobile_addr4 srv = {.type = MOBILE_ADDRTYPE_IPV4, .port = echo_port, .host = {127, 0, 0, 1}};
    int c = 0;
    for (int i = 0; i < 200 && c == 0; i++) c = impl_sock_connect(NULL, 1, (struct mobile_addr *)&srv);
    CHECK(c == 1, "TCP connect");
    CHECK(impl_sock_recv(NULL, 1, NULL, 0, NULL) == 0, "alive check on idle socket");
    uint8_t msg[200];
    for (int i = 0; i < 200; i++) msg[i] = (uint8_t)(i * 7 + 3);
    CHECK(impl_sock_send(NULL, 1, msg, sizeof(msg), NULL) == sizeof(msg), "TCP send 200 bytes");
    uint8_t got[400];
    int total = 0;
    for (int i = 0; i < 400 && total < 200; i++) {
        int n = impl_sock_recv(NULL, 1, got + total, 254, NULL);
        if (n < 0) { fprintf(stderr, "recv error %d\n", n); break; }
        total += n;
    }
    CHECK(total == 200 && memcmp(got, msg, 200) == 0, "TCP echo matches");
    // The harness closes the connection after echoing; we must see -2.
    int closed = 0;
    for (int i = 0; i < 400 && closed == 0; i++) closed = impl_sock_recv(NULL, 1, got, 254, NULL);
    CHECK(closed == -2, "remote close reported as -2");
    impl_sock_close(NULL, 1);

    // ---- connection refused
    CHECK(impl_sock_open(NULL, 1, MOBILE_SOCKTYPE_TCP, MOBILE_ADDRTYPE_IPV4, 0), "reopen TCP");
    struct mobile_addr4 dead = {.type = MOBILE_ADDRTYPE_IPV4, .port = 1, .host = {127, 0, 0, 1}};
    c = 0;
    for (int i = 0; i < 200 && c == 0; i++) c = impl_sock_connect(NULL, 1, (struct mobile_addr *)&dead);
    CHECK(c == -1, "refused connect -> -1");
    impl_sock_close(NULL, 1);

    fprintf(stderr, "ALL OK\n");
    return 0;
}
