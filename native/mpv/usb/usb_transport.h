/* SPDX-License-Identifier: GPL-3.0-or-later */
#ifndef SUBTITLEEDIT_USB_TRANSPORT_H
#define SUBTITLEEDIT_USB_TRANSPORT_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

struct usb_transport;

struct usb_transport_format {
    int rate;
    int channels;
    int valid_bits;
    int subslot_bytes;
    int packets_per_transfer;
    int transfer_count;
};

struct usb_transport_state {
    int queued_frames;
    int in_flight_frames;
    int free_frames;
    bool failed;
    unsigned int transfer_errors;
    unsigned int packet_errors;
    unsigned int rejected_feedback;
    uint64_t padded_frames;
    char issue[192];
};

/*
 * The caller must keep fd open for this call. A successful session holds its
 * own duplicated fd until close. rate and channels are negotiated exactly;
 * valid bits can be widened. error may be NULL. wakeup runs on the USB
 * worker and may be NULL. It must not synchronously stop the session.
 */
struct usb_transport *usb_transport_open(
    int fd, int rate, int channels, int min_valid_bits,
    struct usb_transport_format *format, void (*wakeup)(void *),
    void *wakeup_data, char *error, size_t error_size);

/* PCM input uses the full-scale, interleaved signed 32-bit representation. */
int usb_transport_write(struct usb_transport *session,
                        const int32_t *samples, int frames);
void usb_transport_get_state(struct usb_transport *session,
                             struct usb_transport_state *state);
/* USB Audio Class Feature Unit level, normalized to the DAC's usable range. */
bool usb_transport_get_volume(struct usb_transport *session, float *percent,
                              char *issue, size_t issue_size);
bool usb_transport_set_volume(struct usb_transport *session, float percent);
void usb_transport_set_running(struct usb_transport *session, bool running);
/* Discard queued audio, including already submitted USB transfers. */
void usb_transport_reset(struct usb_transport *session);
void usb_transport_close(struct usb_transport *session);

#ifdef __cplusplus
}
#endif

#endif
