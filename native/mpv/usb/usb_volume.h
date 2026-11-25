/* SPDX-License-Identifier: GPL-3.0-or-later */
#ifndef SUBTITLEEDIT_USB_VOLUME_H
#define SUBTITLEEDIT_USB_VOLUME_H

#include <stdbool.h>
#include <stdint.h>
#include <libusb.h>

enum { USB_VOLUME_MAX_CHANNELS = 2, USB_VOLUME_MAX_RANGES = 16 };

struct usb_volume_io {
    void *context;
    int (*transfer)(void *context, uint8_t type, uint8_t request, uint16_t value,
                    uint16_t index, uint8_t *data, uint16_t length, unsigned int timeout);
};

struct usb_volume_range {
    int16_t min, max, step;
};

struct usb_volume_channel {
    uint8_t number;
    unsigned int range_count;
    struct usb_volume_range ranges[USB_VOLUME_MAX_RANGES];
};

struct usb_hardware_volume {
    uint8_t interface_number, feature_unit;
    bool uac2;
    bool ranges_loaded;
    unsigned int channel_count;
    int min, max;
    char issue[96];
    struct usb_volume_channel channels[USB_VOLUME_MAX_CHANNELS];
};

/* Parse only the Feature Unit for the selected playback path. Does not perform I/O. */
void usb_volume_select(struct usb_hardware_volume *volume,
                       const struct libusb_interface_descriptor *control,
                       bool uac2, uint8_t terminal, int channels);
bool usb_volume_get(struct usb_hardware_volume *volume,
                    const struct usb_volume_io *io, float *percent);
bool usb_volume_set(struct usb_hardware_volume *volume,
                    const struct usb_volume_io *io, float percent);

#endif
