/* SPDX-License-Identifier: GPL-3.0-or-later */
/* USB Audio Class 1/2 Feature Unit volume. Protocol reference: Mornye's
 * direct USB volume implementation in rust_backend/crates/mobile/src/usb_audio/volume.rs. */
#include "usb_volume.h"

#include <math.h>
#include <stddef.h>
#include <stdio.h>
#include <string.h>

enum {
    CS_INTERFACE = 0x24,
    INPUT_TERMINAL = 2,
    OUTPUT_TERMINAL = 3,
    MIXER_UNIT = 4,
    SELECTOR_UNIT = 5,
    FEATURE_UNIT = 6,
    USB_STREAMING_TERMINAL = 0x0101,
    VOLUME_CONTROL = 0x0200,
    REQUEST_CUR = 1,
    REQUEST_RANGE = 2,
    UAC1_GET_CUR = 0x81,
    UAC1_GET_MIN = 0x82,
    VOLUME_CONTROL_TIMEOUT_MS = 120,
};

struct entity {
    const uint8_t *descriptor;
    const uint8_t *sources;
    unsigned int source_count;
};

static uint16_t read_u16(const uint8_t *data)
{
    return (uint16_t)(data[0] | data[1] << 8);
}

static bool parse_topology(struct entity entities[256], const uint8_t *data,
                           int size, bool uac2)
{
    while (size > 0) {
        int length = data[0];
        if (length < 2 || length > size)
            return false;
        if (data[1] == CS_INTERFACE) {
            if (length < 3)
                return false;
            unsigned int subtype = data[2];
            if (subtype != 1 && !(uac2 && subtype >= 10 && subtype <= 12)) {
                if (length < 4 || data[3] == 0 || entities[data[3]].descriptor)
                    return false;
                struct entity *entity = &entities[data[3]];
                entity->descriptor = data;
                int source_start = 0;
                int count_index = 0;
                switch (subtype) {
                case INPUT_TERMINAL:
                    if (length < 6)
                        return false;
                    break;
                case OUTPUT_TERMINAL:
                    source_start = 7;
                    break;
                case FEATURE_UNIT:
                    source_start = 4;
                    break;
                case MIXER_UNIT:
                case SELECTOR_UNIT:
                    count_index = 4;
                    source_start = 5;
                    break;
                case 7:
                    if (uac2) {
                        source_start = 6; // Effect unit.
                    } else {
                        count_index = 6; // Processing unit.
                        source_start = 7;
                    }
                    break;
                case 8:
                case 9:
                    if (!uac2 && subtype == 9)
                        return false;
                    count_index = 6; // Processing or extension unit.
                    source_start = 7;
                    break;
                case 13:
                    if (!uac2)
                        return false;
                    source_start = 4; // Sample rate converter unit.
                    break;
                default:
                    return false;
                }
                if (source_start) {
                    if (length <= source_start)
                        return false;
                    entity->source_count = count_index ? data[count_index] : 1;
                    if (!entity->source_count || entity->source_count > (unsigned int)(length - source_start))
                        return false;
                    entity->sources = data + source_start;
                }
            }
        }
        data += length;
        size -= length;
    }
    return true;
}

static bool can_write(const uint8_t *descriptor, bool uac2, int channel)
{
    int first = uac2 ? 5 : 6;
    if (descriptor[0] <= first)
        return false;
    int width = uac2 ? 4 : descriptor[5];
    if (width <= 0 || first + (channel + 1) * width >= descriptor[0])
        return false;
    uint8_t flags = descriptor[first + channel * width];
    return uac2 ? ((flags >> 2) & 3) == 3 : (flags & 2) != 0;
}

static bool leads_to(const struct entity entities[256], unsigned int node,
                     unsigned int target, bool seen[256])
{
    if (node == target)
        return true;
    if (seen[node])
        return false;
    seen[node] = true;
    const struct entity *entity = &entities[node];
    for (unsigned int i = 0; i < entity->source_count; i++)
        if (leads_to(entities, entity->sources[i], target, seen))
            return true;
    return false;
}

void usb_volume_select(struct usb_hardware_volume *volume,
                       const struct libusb_interface_descriptor *control,
                       bool uac2, uint8_t terminal, int channels)
{
    memset(volume, 0, sizeof(*volume));
    if (!control || !control->extra || channels < 1 || channels > USB_VOLUME_MAX_CHANNELS)
        return;
    struct entity entities[256] = {0};
    if (!parse_topology(entities, control->extra, control->extra_length, uac2))
        return;
    const uint8_t *input = entities[terminal].descriptor;
    if (!input || input[2] != INPUT_TERMINAL || read_u16(input + 4) != USB_STREAMING_TERMINAL)
        return;

    unsigned int feature = 0;
    for (unsigned int output = 1; output < 256; output++) {
        const uint8_t *descriptor = entities[output].descriptor;
        bool seen[256] = {0};
        if (!descriptor || descriptor[2] != OUTPUT_TERMINAL ||
            !leads_to(entities, output, terminal, seen))
            continue;
        if ((read_u16(descriptor + 4) & 0xff00) == 0x0100)
            return;
        unsigned int chosen = 0;
        unsigned int id = entities[output].sources[0];
        // Choose only a Feature Unit on a direct, unambiguous playback chain.
        while (id != terminal) {
            const uint8_t *node = entities[id].descriptor;
            if (!node || node[2] != FEATURE_UNIT)
                return;
            if (!chosen && (can_write(node, uac2, 0) ||
                (can_write(node, uac2, 1) && (channels == 1 || can_write(node, uac2, 2)))))
                chosen = id;
            id = entities[id].sources[0];
        }
        if (!chosen || (feature && chosen != feature))
            return;
        feature = chosen;
    }
    if (!feature)
        return;

    for (unsigned int id = 1; id < 256; id++) {
        const uint8_t *node = entities[id].descriptor;
        bool seen[256] = {0};
        if (node && node[2] == OUTPUT_TERMINAL && (read_u16(node + 4) & 0xff00) == 0x0100 &&
            leads_to(entities, id, feature, seen))
            return;
    }
    volume->interface_number = control->bInterfaceNumber;
    volume->feature_unit = (uint8_t)feature;
    volume->uac2 = uac2;
    if (can_write(entities[feature].descriptor, uac2, 0)) {
        volume->channel_count = 1;
    } else {
        volume->channel_count = (unsigned int)channels;
        for (int ch = 0; ch < channels; ch++)
            volume->channels[ch].number = (uint8_t)(ch + 1);
    }
}

static int transfer(const struct usb_hardware_volume *volume, const struct usb_volume_io *io,
                    bool reading, uint8_t request, uint8_t channel, uint8_t *data, uint16_t length)
{
    const uint8_t direction = reading ? LIBUSB_ENDPOINT_IN : LIBUSB_ENDPOINT_OUT;
    const uint16_t index = (uint16_t)(volume->feature_unit << 8 | volume->interface_number);
    const uint16_t control = (uint16_t)(VOLUME_CONTROL | channel);
    return io->transfer(io->context, direction | LIBUSB_REQUEST_TYPE_CLASS | LIBUSB_RECIPIENT_INTERFACE,
                                   request, control, index, data, length, VOLUME_CONTROL_TIMEOUT_MS);
}

static bool read_uac2_ranges(struct usb_hardware_volume *volume, const struct usb_volume_io *io,
                             uint8_t channel, uint8_t *data, unsigned int *count)
{
    // Some firmware answers a single RANGE only when wLength is exactly 8.
    // Others require the count to be queried before reading the whole range.
    int length = transfer(volume, io, true, REQUEST_RANGE, channel, data, 8);
    if (length == 8 && read_u16(data) == 1) {
        *count = 1;
        return true;
    }
    if (length < 2)
        length = transfer(volume, io, true, REQUEST_RANGE, channel, data, 2);
    if (length >= 2) {
        *count = read_u16(data);
        if (*count && *count <= USB_VOLUME_MAX_RANGES) {
            uint16_t expected = 2 + 6 * *count;
            length = transfer(volume, io, true, REQUEST_RANGE, channel, data, expected);
            if (length == expected && read_u16(data) == *count)
                return true;
        }
    }
    // Keep the larger buffered read as a fallback for devices that return
    // a full payload only in response to a sufficiently large request.
    length = transfer(volume, io, true, REQUEST_RANGE, channel, data, 2 + 6 * USB_VOLUME_MAX_RANGES);
    if (length >= 2) {
        *count = read_u16(data);
        if (*count && *count <= USB_VOLUME_MAX_RANGES && length == 2 + 6 * (int)*count)
            return true;
    }
    snprintf(volume->issue, sizeof(volume->issue), "Invalid GET_RANGE response on channel %u (%d bytes)",
             channel, length);
    return false;
}

static bool read_ranges(struct usb_hardware_volume *volume, const struct usb_volume_io *io,
                       struct usb_volume_channel *channel)
{
    uint8_t data[2 + 6 * USB_VOLUME_MAX_RANGES];
    unsigned int count = 1;
    if (volume->uac2) {
        if (!read_uac2_ranges(volume, io, channel->number, data, &count))
            return false;
    } else {
        for (int i = 0; i < 3; i++) {
            int length = transfer(volume, io, true, UAC1_GET_MIN + i, channel->number, data + 2 + 2 * i, 2);
            if (length != 2) {
                snprintf(volume->issue, sizeof(volume->issue), "GET_MIN/MAX/RES 0x%02x failed on channel %u (%d)",
                         UAC1_GET_MIN + i, channel->number, length);
                return false;
            }
        }
    }
    for (unsigned int i = 0; i < count; i++) {
        const uint8_t *p = data + 2 + 6 * i;
        struct usb_volume_range range = {
            (int16_t)read_u16(p), (int16_t)read_u16(p + 2), (int16_t)read_u16(p + 4),
        };
        if (range.min == INT16_MIN || range.min > range.max || range.step < 0) {
            snprintf(volume->issue, sizeof(volume->issue), "Invalid volume range on channel %u (%d to %d, step %d)",
                     channel->number, range.min, range.max, range.step);
            return false;
        }
        channel->ranges[i] = range;
    }
    channel->range_count = count;
    return true;
}

static int floor_level(const struct usb_volume_channel *channel, int target)
{
    int best = INT16_MIN;
    for (unsigned int i = 0; i < channel->range_count; i++) {
        const struct usb_volume_range *range = &channel->ranges[i];
        if (target < range->min)
            continue;
        int value = target < range->max ? target : range->max;
        int step = range->step > 0 ? range->step : 1;
        value = range->min + (value - range->min) / step * step;
        if (value > best)
            best = value;
    }
    return best;
}

static bool ensure_ranges(struct usb_hardware_volume *volume, const struct usb_volume_io *io)
{
    if (!volume->channel_count)
        return false;
    if (volume->ranges_loaded)
        return true;
    int min = INT16_MIN;
    int max = 0;
    for (unsigned int i = 0; i < volume->channel_count; i++) {
        struct usb_volume_channel *channel = &volume->channels[i];
        if (!read_ranges(volume, io, channel))
            return false;
        int low = INT16_MAX;
        for (unsigned int n = 0; n < channel->range_count; n++)
            if (channel->ranges[n].min < low)
                low = channel->ranges[n].min;
        int high = floor_level(channel, 0);
        if (low > min)
            min = low;
        if (high < max)
            max = high;
    }
    if (min >= max) {
        snprintf(volume->issue, sizeof(volume->issue), "No shared volume range at or below 0 dB");
        return false;
    }
    volume->min = min;
    volume->max = max;
    volume->ranges_loaded = true;
    return true;
}

static bool read_level(struct usb_hardware_volume *volume, const struct usb_volume_io *io,
                       uint8_t channel, int *level)
{
    uint8_t data[2];
    int length = transfer(volume, io, true, volume->uac2 ? REQUEST_CUR : UAC1_GET_CUR,
                          channel, data, sizeof(data));
    if (length != sizeof(data)) {
        snprintf(volume->issue, sizeof(volume->issue), "GET_CUR failed on channel %u (%d)", channel, length);
        return false;
    }
    *level = (int16_t)read_u16(data);
    return true;
}

bool usb_volume_get(struct usb_hardware_volume *volume, const struct usb_volume_io *io, float *percent)
{
    if (!percent || !volume->channel_count)
        return false;
    int loudest = INT16_MIN;
    for (unsigned int i = 0; i < volume->channel_count; i++) {
        int level;
        if (!read_level(volume, io, volume->channels[i].number, &level))
            return false;
        if (level > loudest)
            loudest = level;
    }
    if (!ensure_ranges(volume, io))
        return false;
    int level = loudest < volume->min ? volume->min : loudest > volume->max ? volume->max : loudest;
    *percent = 100.0f * (level - volume->min) / (volume->max - volume->min);
    return true;
}

bool usb_volume_set(struct usb_hardware_volume *volume, const struct usb_volume_io *io, float percent)
{
    if (!isfinite(percent) || percent < 0 || percent > 100 || !ensure_ranges(volume, io))
        return false;
    int target = volume->min + (int)((volume->max - volume->min) * percent / 100.0f);
    int values[USB_VOLUME_MAX_CHANNELS];
    for (unsigned int i = 0; i < volume->channel_count; i++) {
        values[i] = floor_level(&volume->channels[i], target);
        if (values[i] == INT16_MIN)
            return false;
    }
    for (unsigned int i = 0; i < volume->channel_count; i++) {
        uint8_t number = volume->channels[i].number;
        uint16_t value = (uint16_t)values[i];
        uint8_t data[] = {(uint8_t)value, (uint8_t)(value >> 8)};
        int readback;
        int length = transfer(volume, io, false, REQUEST_CUR, number, data, sizeof(data));
        if (length != sizeof(data)) {
            snprintf(volume->issue, sizeof(volume->issue), "SET_CUR failed on channel %u (%d)", number, length);
            return false;
        }
        if (!read_level(volume, io, number, &readback))
            return false;
        if (readback != values[i]) {
            snprintf(volume->issue, sizeof(volume->issue), "SET_CUR readback differs on channel %u (%d vs %d)",
                     number, readback, values[i]);
            return false;
        }
    }
    return true;
}
