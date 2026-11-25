/* SPDX-License-Identifier: GPL-3.0-or-later */
/* USB Audio Class 1/2 PCM transport. Reference: Mornye's Android direct USB
 * transport, https://github.com/mornyeapp/Mornye/tree/af9a4d6422759541ec57a9ed696b3b47928a1bff/rust_backend/crates/mobile/src/usb_audio
 */
#define _POSIX_C_SOURCE 200809L

#include "usb_transport.h"
#include "usb_volume.h"

// Private diagnostic supplied by the bundled Android libusb backend.
int subtitleedit_usbfs_iso_status(struct libusb_transfer *transfer);

#include <fcntl.h>
#include <math.h>
#include <libusb.h>
#include <pthread.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <sys/resource.h>
#include <unistd.h>

enum {
    USB_AUDIO_CONTROL = 1,
    USB_AUDIO_STREAMING = 2,
    UAC2_PROTOCOL = 0x20,
    CS_INTERFACE = 0x24,
    CS_ENDPOINT = 0x25,
    AS_GENERAL = 1,
    FORMAT_TYPE = 2,
    FORMAT_TYPE_I = 1,
    INPUT_TERMINAL = 2,
    CLOCK_SOURCE = 0x0a,
    CLOCK_SELECTOR = 0x0b,
    CLOCK_MULTIPLIER = 0x0c,
    CONTROL_TIMEOUT_MS = 500,
    DEFAULT_OUTPUT_TRANSFERS = 16,
    MAX_OUTPUT_TRANSFERS = 64,
    OUTPUT_TRANSFER_US = 4000,
    UA2_PLUS_TRANSFER_US = 1000,
    UA2_PLUS_VID = 0x20b1,
    UA2_PLUS_PID = 0x3028,
    MAX_CANDIDATES = 64,
    MAX_CONSECUTIVE_USB_ERRORS = 8,
};

struct stream_candidate {
    const struct libusb_interface_descriptor *control;
    const struct libusb_interface_descriptor *stream;
    unsigned char terminal;
    unsigned char endpoint;
    unsigned char feedback_endpoint;
    unsigned int max_packet;
    unsigned int feedback_size;
    unsigned int interval_us;
    int subslot_bytes;
    int valid_bits;
    bool uac2;
    bool rate_control;
    bool high_speed;
};

struct usb_transfer {
    struct usb_transport *session;
    struct libusb_transfer *transfer;
    unsigned char *data;
    int frames;
    bool submitted;
    bool feedback;
};

struct usb_transport {
    libusb_context *context;
    libusb_device_handle *handle;
    int fd;
    int claimed_control;
    int claimed_stream;

    struct usb_transport_format format;
    struct stream_candidate output;
    struct usb_hardware_volume volume;
    pthread_mutex_t volume_mutex;
    pthread_cond_t volume_condition;
    pthread_t volume_thread;
    bool volume_worker_started, volume_stop, volume_pending, volume_supported;
    bool volume_transfer_pending;
    float volume_percent, volume_target;
    char volume_issue[sizeof(((struct usb_hardware_volume *)0)->issue)];
    struct usb_transfer slots[MAX_OUTPUT_TRANSFERS];
    struct usb_transfer feedback;
    int output_transfer_count;
    int packets_per_transfer;
    double nominal_frames;
    double feedback_frames;
    double remainder;
    unsigned int max_packet_frames;

    pthread_t thread;
    pthread_mutex_t mutex;
    pthread_cond_t condition;
    void (*wakeup)(void *);
    void *wakeup_data;
    bool running;
    bool resetting;
    bool closing;
    bool failed;
    unsigned int output_error_streak;
    unsigned int submit_error_streak;
    unsigned int transfer_errors;
    unsigned int packet_errors;
    unsigned int rejected_feedback;
    uint64_t padded_frames;
    char issue[sizeof(((struct usb_transport_state *)0)->issue)];
    int32_t *queue;
    int capacity;
    int head;
    int queued;
    int in_flight;
};

static uint32_t read_le(const unsigned char *p, int length)
{
    uint32_t value = 0;
    for (int i = 0; i < length; i++)
        value |= (uint32_t)p[i] << (i * 8);
    return value;
}

static void write_le(unsigned char *p, uint32_t value, int length)
{
    for (int i = 0; i < length; i++)
        p[i] = (unsigned char)(value >> (i * 8));
}

/* Find class-specific descriptors without trusting malformed length fields. */
static const unsigned char *find_descriptor(const unsigned char *extra, int size,
                                             int type, int subtype, int entity)
{
    for (; size >= 3 && extra[0] >= 3 && extra[0] <= size;
         size -= extra[0], extra += extra[0]) {
        if (extra[1] == type && extra[2] == subtype &&
            (entity < 0 || (extra[0] >= 4 && extra[3] == entity)))
            return extra;
    }
    return NULL;
}

static const struct libusb_interface_descriptor *find_control(
    const struct libusb_config_descriptor *config,
    const struct libusb_interface_descriptor *stream, int terminal, bool uac2)
{
    const struct libusb_interface_descriptor *match = NULL;
    for (int i = 0; i < config->bNumInterfaces; i++) {
        const struct libusb_interface *group = &config->interface[i];
        for (int j = 0; j < group->num_altsetting; j++) {
            const struct libusb_interface_descriptor *control = &group->altsetting[j];
            if (control->bInterfaceClass != LIBUSB_CLASS_AUDIO ||
                control->bInterfaceSubClass != USB_AUDIO_CONTROL ||
                control->bInterfaceProtocol != stream->bInterfaceProtocol ||
                control->bAlternateSetting != 0)
                continue;

            if (!find_descriptor(control->extra, control->extra_length,
                                 CS_INTERFACE, INPUT_TERMINAL, terminal))
                continue;

            if (!uac2) {
                const unsigned char *header = find_descriptor(
                    control->extra, control->extra_length, CS_INTERFACE, 1, -1);
                if (!header || header[0] < 8 || header[0] - 8 < header[7] ||
                    !memchr(header + 8, stream->bInterfaceNumber, header[7]))
                    continue;
            }
            // Ambiguous terminal IDs across UAC2 functions must be resolved
            // before sending requests to an interface.
            if (match)
                return NULL;
            match = control;
        }
    }
    return match;
}

static bool uac1_supports_rate(const unsigned char *format, int rate)
{
    if (format[0] < 8)
        return false;

    int count = format[7];
    if (!count) {
        return format[0] >= 14 && (uint32_t)rate >= read_le(format + 8, 3) &&
               (uint32_t)rate <= read_le(format + 11, 3);
    }
    if (count > (format[0] - 8) / 3)
        return false;
    for (int i = 0; i < count; i++) {
        if (read_le(format + 8 + 3 * i, 3) == (uint32_t)rate)
            return true;
    }
    return false;
}

static unsigned int max_packet(libusb_context *context,
                               const struct libusb_endpoint_descriptor *endpoint,
                               int speed)
{
    if (speed >= LIBUSB_SPEED_SUPER) {
        struct libusb_ss_endpoint_companion_descriptor *companion = NULL;
        if (libusb_get_ss_endpoint_companion_descriptor(context, endpoint, &companion) < 0)
            return 0;
        unsigned int bytes = companion->wBytesPerInterval;
        libusb_free_ss_endpoint_companion_descriptor(companion);
        return bytes;
    }
    unsigned int packet = endpoint->wMaxPacketSize;
    unsigned int extra_transactions = (packet >> 11) & 3;
    if (extra_transactions > 2 || (extra_transactions && speed == LIBUSB_SPEED_FULL))
        return 0;
    return (packet & 0x7ff) * (extra_transactions + 1);
}

static bool resolve_endpoints(libusb_context *context, int speed, int rate, int channels,
                              struct stream_candidate *candidate)
{
    const struct libusb_interface_descriptor *stream = candidate->stream;
    const struct libusb_endpoint_descriptor *output = NULL;
    for (int i = 0; i < stream->bNumEndpoints; i++) {
        const struct libusb_endpoint_descriptor *ep = &stream->endpoint[i];
        if ((ep->bmAttributes & LIBUSB_TRANSFER_TYPE_MASK) == LIBUSB_TRANSFER_TYPE_ISOCHRONOUS &&
            !(ep->bEndpointAddress & LIBUSB_ENDPOINT_IN)) {
            if (output)
                return false;
            output = ep;
        }
    }
    if (!output || output->bInterval == 0 || output->bInterval > 4)
        return false;

    candidate->high_speed = speed >= LIBUSB_SPEED_HIGH;
    candidate->interval_us = (candidate->high_speed ? 125u : 1000u)
                             << (output->bInterval - 1);
    candidate->endpoint = output->bEndpointAddress;
    candidate->max_packet = max_packet(context, output, speed);
    unsigned int frame_bytes = (unsigned int)channels * candidate->subslot_bytes;
    uint64_t nominal = (uint64_t)rate * candidate->interval_us;
    if (candidate->max_packet < ((nominal + 999999) / 1000000) * frame_bytes)
        return false;

    // An asynchronous OUT endpoint needs explicit feedback. Adaptive and
    // synchronous endpoints pace the DAC from USB frames.
    if ((output->bmAttributes & LIBUSB_ISO_SYNC_TYPE_MASK) == (LIBUSB_ISO_SYNC_TYPE_ASYNC << 2)) {
        for (int i = 0; i < stream->bNumEndpoints; i++) {
            const struct libusb_endpoint_descriptor *ep = &stream->endpoint[i];
            if (!(ep->bEndpointAddress & LIBUSB_ENDPOINT_IN) ||
                (ep->bmAttributes & LIBUSB_TRANSFER_TYPE_MASK) != LIBUSB_TRANSFER_TYPE_ISOCHRONOUS ||
                ((ep->bmAttributes & LIBUSB_ISO_USAGE_TYPE_MASK) != (LIBUSB_ISO_USAGE_TYPE_FEEDBACK << 4) &&
                 (!output->bSynchAddress || output->bSynchAddress != ep->bEndpointAddress)))
                continue;
            if ((ep->bmAttributes & LIBUSB_ISO_USAGE_TYPE_MASK) ==
                (LIBUSB_ISO_USAGE_TYPE_IMPLICIT << 4))
                return false;
            unsigned int bytes = max_packet(context, ep, speed);
            if (bytes < 3 || bytes > 64 || candidate->feedback_endpoint)
                return false;
            candidate->feedback_endpoint = ep->bEndpointAddress;
            candidate->feedback_size = bytes;
        }
        if (!candidate->feedback_endpoint)
            return false;
    }

    const unsigned char *endpoint_class = find_descriptor(
        output->extra, output->extra_length, CS_ENDPOINT, 1, -1);
    candidate->rate_control = !candidate->uac2 && endpoint_class && endpoint_class[0] >= 4 &&
                              (endpoint_class[3] & 1);
    return true;
}

static bool parse_candidate(libusb_context *context, int speed,
                             const struct libusb_config_descriptor *config,
                             const struct libusb_interface_descriptor *stream,
                             int rate, int channels, int min_bits,
                             struct stream_candidate *candidate)
{
    if (stream->bInterfaceClass != LIBUSB_CLASS_AUDIO ||
        stream->bInterfaceSubClass != USB_AUDIO_STREAMING ||
        !stream->bAlternateSetting ||
        (stream->bInterfaceProtocol != 0 && stream->bInterfaceProtocol != UAC2_PROTOCOL))
        return false;

    const unsigned char *general = find_descriptor(stream->extra, stream->extra_length,
                                                   CS_INTERFACE, AS_GENERAL, -1);
    const unsigned char *format = find_descriptor(stream->extra, stream->extra_length,
                                                   CS_INTERFACE, FORMAT_TYPE, -1);
    bool uac2 = stream->bInterfaceProtocol == UAC2_PROTOCOL;
    if (!general || !format || format[0] < 6 || format[3] != FORMAT_TYPE_I ||
        general[0] < (uac2 ? 16 : 7))
        return false;
    if (uac2 ? (general[5] != 1 || !(read_le(general + 6, 4) & 1))
             : (read_le(general + 5, 2) != 1 || !uac1_supports_rate(format, rate)))
        return false;

    int source_channels = uac2 ? general[10] : format[4];
    int subslot = format[uac2 ? 4 : 5];
    int bits = format[uac2 ? 5 : 6];
    if (source_channels != channels || subslot < 2 || subslot > 4 ||
        bits < min_bits || bits < 16 || bits > subslot * 8)
        return false;

    *candidate = (struct stream_candidate){
        .control = find_control(config, stream, general[3], uac2),
        .stream = stream,
        .terminal = general[3],
        .subslot_bytes = subslot,
        .valid_bits = bits,
        .uac2 = uac2,
    };
    if (!candidate->control || !resolve_endpoints(context, speed, rate, channels, candidate))
        return false;

    // With no endpoint frequency control, the device must advertise only
    // the exact requested sample rate for this alternate.
    return uac2 || candidate->rate_control ||
           (format[7] == 1) ||
           (format[7] == 0 && read_le(format + 8, 3) == read_le(format + 11, 3));
}

static int compare_candidates(const void *a, const void *b)
{
    const struct stream_candidate *first = a, *second = b;
    int result = first->valid_bits - second->valid_bits;
    if (result)
        return result;
    return first->subslot_bytes - second->subslot_bytes;
}

static int frequency_request(struct usb_transport *session, bool input,
                              bool endpoint, int request, unsigned int index,
                              unsigned char *data, int length)
{
    int type = LIBUSB_REQUEST_TYPE_CLASS |
               (input ? LIBUSB_ENDPOINT_IN : LIBUSB_ENDPOINT_OUT) |
               (endpoint ? LIBUSB_RECIPIENT_ENDPOINT : LIBUSB_RECIPIENT_INTERFACE);
    int result = libusb_control_transfer(session->handle, type, request, 0x0100,
                                         index, data, length, CONTROL_TIMEOUT_MS);
    return result == length ? 0 : result < 0 ? result : LIBUSB_ERROR_IO;
}

static int resolve_clock(struct usb_transport *session, const struct stream_candidate *candidate)
{
    const struct libusb_interface_descriptor *control = candidate->control;
    const unsigned char *terminal = find_descriptor(control->extra, control->extra_length,
                                                     CS_INTERFACE, INPUT_TERMINAL,
                                                     candidate->terminal);
    if (!terminal || terminal[0] < 8)
        return LIBUSB_ERROR_NOT_SUPPORTED;
    unsigned char id = terminal[7];
    for (int depth = 0; id != 0 && depth < 8; depth++) {
        if (find_descriptor(control->extra, control->extra_length,
                            CS_INTERFACE, CLOCK_SOURCE, id))
            return id;
        if (find_descriptor(control->extra, control->extra_length,
                            CS_INTERFACE, CLOCK_MULTIPLIER, id))
            return LIBUSB_ERROR_NOT_SUPPORTED;
        const unsigned char *selector = find_descriptor(control->extra, control->extra_length,
                                                         CS_INTERFACE, CLOCK_SELECTOR, id);
        if (!selector || selector[0] < 8 || selector[4] < 1 || selector[4] > selector[0] - 7)
            return LIBUSB_ERROR_NOT_SUPPORTED;

        unsigned char input = 1;
        if (selector[4] > 1) {
            int status = frequency_request(session, true, false, 1,
                                          (id << 8) | control->bInterfaceNumber, &input, 1);
            if (status < 0)
                return status;
            if (input == 0 || input > selector[4])
                return LIBUSB_ERROR_IO;
        }
        id = selector[4 + input];
    }
    return LIBUSB_ERROR_NOT_SUPPORTED;
}

static int configure_rate(struct usb_transport *session, bool uac2,
                          unsigned int index, int rate)
{
    unsigned char value[4];
    const int length = uac2 ? 4 : 3;
    int read_request = uac2 ? 1 : 0x81;
    int result = frequency_request(session, true, !uac2, read_request, index, value, length);
    if (result == 0 && read_le(value, length) == (uint32_t)rate)
        return 0;

    // UAC2 RANGE reports accepted rates. Check before SET_CUR because some
    // devices accept out-of-range requests but never start the clock.
    if (uac2) {
        unsigned char ranges[2 + 12 * 64];
        result = frequency_request(session, true, false, 2, index, ranges, 2);
        if (result < 0)
            return result;
        unsigned int count = read_le(ranges, 2);
        if (!count || count > 64)
            return LIBUSB_ERROR_NOT_SUPPORTED;
        result = frequency_request(session, true, false, 2, index, ranges, 2 + 12 * count);
        if (result < 0)
            return result;
        if (read_le(ranges, 2) != count)
            return LIBUSB_ERROR_IO;
        bool supported = false;
        for (unsigned int n = 0; n < count; n++) {
            const unsigned char *range = ranges + 2 + 12 * n;
            uint32_t min = read_le(range, 4), max = read_le(range + 4, 4);
            uint32_t resolution = read_le(range + 8, 4);
            if ((uint32_t)rate >= min && (uint32_t)rate <= max &&
                (!resolution || ((uint32_t)rate - min) % resolution == 0)) {
                supported = true;
                break;
            }
        }
        if (!supported)
            return LIBUSB_ERROR_NOT_SUPPORTED;
    }

    write_le(value, rate, length);
    result = frequency_request(session, false, !uac2, 1, index, value, length);
    if (result < 0)
        return result;
    result = frequency_request(session, true, !uac2, read_request, index, value, length);
    if (result < 0)
        return result;
    return read_le(value, length) == (uint32_t)rate ? 0 : LIBUSB_ERROR_IO;
}

static void release_interfaces(struct usb_transport *session)
{
    if (session->claimed_stream >= 0) {
        libusb_set_interface_alt_setting(session->handle, session->claimed_stream, 0);
        libusb_release_interface(session->handle, session->claimed_stream);
    }
    if (session->claimed_control >= 0 && session->claimed_control != session->claimed_stream)
        libusb_release_interface(session->handle, session->claimed_control);
    session->claimed_stream = session->claimed_control = -1;
}

static int claim_stream(struct usb_transport *session, const struct stream_candidate *candidate,
                         int rate)
{
    int control = candidate->control->bInterfaceNumber;
    int stream = candidate->stream->bInterfaceNumber;
    int result = libusb_claim_interface(session->handle, control);
    if (result < 0)
        return result;
    session->claimed_control = control;
    if (stream != control) {
        result = libusb_claim_interface(session->handle, stream);
        if (result < 0)
            return result;
    }
    session->claimed_stream = stream;
    result = libusb_set_interface_alt_setting(session->handle, stream, 0);
    if (result < 0)
        return result;
    if (candidate->uac2) {
        int clock = resolve_clock(session, candidate);
        if (clock < 0)
            return clock;
        result = configure_rate(session, true, (clock << 8) | control, rate);
        if (result < 0)
            return result;
    }
    result = libusb_set_interface_alt_setting(session->handle, stream,
                                               candidate->stream->bAlternateSetting);
    if (result < 0)
        return result;
    if (candidate->rate_control)
        result = configure_rate(session, false, candidate->endpoint, rate);
    return result;
}

static int negotiate(struct usb_transport *session, int rate, int channels, int bits)
{
    struct libusb_config_descriptor *config = NULL;
    libusb_device *device = libusb_get_device(session->handle);
    int speed = libusb_get_device_speed(device);
    if (speed < LIBUSB_SPEED_FULL)
        return LIBUSB_ERROR_NOT_SUPPORTED;
    int result = libusb_get_active_config_descriptor(device, &config);
    if (result < 0)
        return result;
    struct stream_candidate candidates[MAX_CANDIDATES];
    int count = 0;
    for (int i = 0; i < config->bNumInterfaces && count < MAX_CANDIDATES; i++) {
        const struct libusb_interface *group = &config->interface[i];
        for (int j = 0; j < group->num_altsetting && count < MAX_CANDIDATES; j++) {
            if (parse_candidate(session->context, speed, config, &group->altsetting[j],
                                rate, channels, bits, &candidates[count]))
                count++;
        }
    }
    qsort(candidates, count, sizeof(candidates[0]), compare_candidates);
    result = LIBUSB_ERROR_NOT_SUPPORTED;
    for (int i = 0; i < count; i++) {
        result = claim_stream(session, &candidates[i], rate);
        if (result == 0) {
            const struct stream_candidate *selected = &candidates[i];
            usb_volume_select(&session->volume, selected->control, selected->uac2,
                              selected->terminal, channels);
            session->output = candidates[i];
            session->output.stream = NULL;
            session->output.control = NULL;
            break;
        }
        release_interfaces(session);
        if (result == LIBUSB_ERROR_NO_DEVICE)
            break;
    }
    libusb_free_config_descriptor(config);
    return result;
}

/* Caller holds the queue lock. A terminal failure keeps its first reason. */
static void set_issue(struct usb_transport *session, bool failed, const char *format, ...)
{
    if (session->failed)
        return;
    va_list args;
    va_start(args, format);
    vsnprintf(session->issue, sizeof(session->issue), format, args);
    va_end(args);
    session->failed = failed;
}

static const char *transfer_status_name(enum libusb_transfer_status status)
{
    switch (status) {
    case LIBUSB_TRANSFER_COMPLETED: return "completed";
    case LIBUSB_TRANSFER_ERROR: return "I/O error";
    case LIBUSB_TRANSFER_TIMED_OUT: return "timed out";
    case LIBUSB_TRANSFER_CANCELLED: return "cancelled";
    case LIBUSB_TRANSFER_STALL: return "stalled";
    case LIBUSB_TRANSFER_NO_DEVICE: return "device disconnected";
    case LIBUSB_TRANSFER_OVERFLOW: return "overflow";
    }
    return "unknown";
}

static void handle_feedback(struct usb_transport *session, struct usb_transfer *slot)
{
    const struct libusb_iso_packet_descriptor *packet = &slot->transfer->iso_packet_desc[0];
    if (slot->transfer->status != LIBUSB_TRANSFER_COMPLETED ||
        packet->status != LIBUSB_TRANSFER_COMPLETED)
        return;

    int length = packet->actual_length;
    double frames;
    if (length == 3 && !session->output.high_speed)
        frames = read_le(slot->data, 3) / 16384.0;
    else if (length == 4)
        frames = read_le(slot->data, 4) / 65536.0;
    else
        return;

    frames *= session->output.interval_us / (session->output.high_speed ? 125.0 : 1000.0);
    double nominal = session->nominal_frames;
    // At integer rates, a small positive correction can still require one
    // additional frame. An endpoint without that headroom must use the last
    // valid rate (nominal initially) instead of stopping a working stream.
    if (frames >= nominal * 0.98 && frames <= nominal * 1.02 &&
        frames <= session->max_packet_frames) {
        session->feedback_frames = frames;
    } else if (frames > 0) {
        session->rejected_feedback++;
        set_issue(session, false, "Feedback %.4f frames/packet outside range (nominal %.4f, max %u)",
                  frames, nominal, session->max_packet_frames);
    }
}

static void LIBUSB_CALL transfer_finished(struct libusb_transfer *transfer)
{
    struct usb_transfer *slot = transfer->user_data;
    struct usb_transport *session = slot->session;
    enum libusb_transfer_status status = transfer->status;
    pthread_mutex_lock(&session->mutex);
    slot->submitted = false;
    session->in_flight -= slot->frames;
    slot->frames = 0;
    bool active = !session->closing && !session->resetting && !session->failed;
    if (active) {
        if (status == LIBUSB_TRANSFER_NO_DEVICE) {
            set_issue(session, true, "Endpoint 0x%02x: %s", transfer->endpoint,
                      transfer_status_name(status));
        } else if (slot->feedback) {
            handle_feedback(session, slot);
        } else {
            int bad_packet = -1;
            bool packet_fatal = false;
            for (int i = 0; i < transfer->num_iso_packets; i++) {
                const struct libusb_iso_packet_descriptor *packet = &transfer->iso_packet_desc[i];
                if (packet->status != LIBUSB_TRANSFER_COMPLETED || packet->actual_length != packet->length) {
                    if (bad_packet < 0)
                        bad_packet = i;
                    session->packet_errors++;
                    packet_fatal |= packet->status == LIBUSB_TRANSFER_NO_DEVICE ||
                                    packet->status == LIBUSB_TRANSFER_STALL;
                }
            }
            if (status == LIBUSB_TRANSFER_COMPLETED && bad_packet < 0) {
                session->output_error_streak = 0;
            } else {
                session->transfer_errors++;
                const struct libusb_iso_packet_descriptor *packet = bad_packet >= 0
                    ? &transfer->iso_packet_desc[bad_packet] : NULL;
                bool fatal = ++session->output_error_streak >= MAX_CONSECUTIVE_USB_ERRORS ||
                    status == LIBUSB_TRANSFER_STALL || packet_fatal;
                int pending = 0;
                for (int i = 0; i < session->output_transfer_count; i++)
                    pending += session->slots[i].submitted;
                set_issue(session, fatal,
                          "OUT 0x%02x: %s, packet %d: %s, bytes %u/%u, usbfs %d, pending %d/%d, consecutive %u",
                          transfer->endpoint, transfer_status_name(status), bad_packet,
                          packet ? transfer_status_name(packet->status) : "n/a",
                          packet ? packet->actual_length : 0, packet ? packet->length : 0,
                          subtitleedit_usbfs_iso_status(transfer), pending, session->output_transfer_count,
                          session->output_error_streak);
            }
        }
    }
    bool wakeup = !slot->feedback || session->failed;
    pthread_mutex_unlock(&session->mutex);

    if (wakeup && session->wakeup)
        session->wakeup(session->wakeup_data);
}

static int allocate_transfer(struct usb_transport *session, struct usb_transfer *slot,
                             int packets, unsigned int bytes, bool feedback)
{
    slot->session = session;
    slot->feedback = feedback;
    slot->transfer = libusb_alloc_transfer(packets);
    slot->data = malloc((size_t)packets * bytes);
    if (!slot->transfer || !slot->data)
        return LIBUSB_ERROR_NO_MEM;

    libusb_fill_iso_transfer(slot->transfer, session->handle,
                             feedback ? session->output.feedback_endpoint : session->output.endpoint,
                             slot->data, packets * bytes, packets,
                             transfer_finished, slot, 1000);
    if (feedback)
        slot->transfer->iso_packet_desc[0].length = bytes;
    return 0;
}

/* Caller holds the queue lock. Only this worker submits and pumps USB. */
static int submit_output(struct usb_transport *session, struct usb_transfer *slot)
{
    const int channels = session->format.channels;
    const int subslot = session->format.subslot_bytes;
    const int frame_bytes = channels * subslot;
    double frames_per_packet = session->feedback_frames > 0
                               ? session->feedback_frames : session->nominal_frames;
    double remainder = session->remainder;
    unsigned int bytes = 0;
    for (int n = 0; n < session->packets_per_transfer; n++) {
        remainder += frames_per_packet;
        unsigned int count = (unsigned int)remainder;
        remainder -= count;
        unsigned int packet_bytes = count * frame_bytes;
        if (packet_bytes > session->output.max_packet)
            return LIBUSB_ERROR_OVERFLOW;
        slot->transfer->iso_packet_desc[n].length = packet_bytes;
        bytes += packet_bytes;
    }

    int frames = (int)(bytes / frame_bytes);
    int filled = frames < session->queued ? frames : session->queued;
    unsigned char *out = slot->data;
    unsigned int valid_mask = UINT32_MAX << (32 - session->format.valid_bits);
    int index = session->head;
    for (int i = 0; i < filled; i++) {
        const int32_t *frame = session->queue + (size_t)index * channels;
        for (int ch = 0; ch < channels; ch++) {
            uint32_t sample = (uint32_t)frame[ch] & valid_mask;
            write_le(out, sample >> (8 * (4 - subslot)), subslot);
            out += subslot;
        }
        if (++index == session->capacity)
            index = 0;
    }
    memset(out, 0, (frames - filled) * frame_bytes);
    slot->transfer->length = bytes;
    int result = libusb_submit_transfer(slot->transfer);
    if (result < 0)
        return result;

    slot->frames = filled;
    slot->submitted = true;
    session->remainder = remainder;
    session->head = index;
    session->queued -= filled;
    session->in_flight += filled;
    session->padded_frames += frames - filled;
    return 0;
}

static bool has_pending(const struct usb_transport *session)
{
    if (session->feedback.submitted)
        return true;
    for (int i = 0; i < session->output_transfer_count; i++) {
        if (session->slots[i].submitted)
            return true;
    }
    return false;
}

static void cancel_pending(struct usb_transport *session)
{
    if (session->feedback.submitted)
        libusb_cancel_transfer(session->feedback.transfer);
    for (int i = 0; i < session->output_transfer_count; i++) {
        if (session->slots[i].submitted)
            libusb_cancel_transfer(session->slots[i].transfer);
    }
}

static int submit_available(struct usb_transport *session, unsigned char *failed_endpoint)
{
    if (!session->queued)
        return 0;
    struct usb_transfer *feedback = &session->feedback;
    if (feedback->transfer && !feedback->submitted) {
        int status = libusb_submit_transfer(feedback->transfer);
        if (status < 0) {
            *failed_endpoint = session->output.feedback_endpoint;
            return status;
        }
        feedback->submitted = true;
    }

    for (int i = 0; i < session->output_transfer_count && session->queued; i++) {
        if (!session->slots[i].submitted) {
            int status = submit_output(session, &session->slots[i]);
            if (status < 0) {
                *failed_endpoint = session->output.endpoint;
                return status;
            }
        }
    }
    return 0;
}

static void *stream_worker(void *argument)
{
#ifdef __ANDROID__
    // Match Android's audio priority when allowed. Linux applies nice values per thread.
    if (setpriority(PRIO_PROCESS, 0, -16) < 0)
        (void)setpriority(PRIO_PROCESS, 0, -4);
#endif
    struct usb_transport *session = argument;
    bool cancelling = false;
    pthread_mutex_lock(&session->mutex);
    for (;;) {
        bool discard = session->closing || session->resetting || session->failed;
        if (discard && !cancelling) {
            cancel_pending(session);
            cancelling = true;
        }
        if (!has_pending(session) && discard) {
            session->head = 0;
            session->queued = 0;
            session->remainder = 0;
            session->feedback_frames = 0;
            session->output_error_streak = 0;
            session->submit_error_streak = 0;
            session->resetting = false;
            pthread_cond_broadcast(&session->condition);
            if (session->closing)
                break;
            cancelling = false;
        }

        bool was_failed = session->failed;
        bool retry = false;
        if (session->running && !discard) {
            unsigned char endpoint = 0;
            int status = submit_available(session, &endpoint);
            if (status < 0) {
                session->transfer_errors++;
                retry = ++session->submit_error_streak < MAX_CONSECUTIVE_USB_ERRORS &&
                    (status == LIBUSB_ERROR_INTERRUPTED || status == LIBUSB_ERROR_BUSY);
                set_issue(session, !retry,
                          "Submit endpoint 0x%02x: %s (%d), consecutive errors %u",
                          endpoint, libusb_error_name(status), status, session->submit_error_streak);
            } else {
                session->submit_error_streak = 0;
            }
        }

        bool notify = session->failed != was_failed;
        if (has_pending(session) || session->volume_transfer_pending || notify || retry) {
            pthread_mutex_unlock(&session->mutex);
            if (notify && session->wakeup)
                session->wakeup(session->wakeup_data);
            struct timeval timeout = {.tv_sec = 0, .tv_usec = 5000};
            int status = libusb_handle_events_timeout(session->context, &timeout);
            pthread_mutex_lock(&session->mutex);
            if (status < 0 && status != LIBUSB_ERROR_INTERRUPTED) {
                bool notify_failure = !session->failed && session->wakeup;
                set_issue(session, true, "Poll USB events: %s (%d)", libusb_error_name(status), status);
                if (notify_failure) {
                    pthread_mutex_unlock(&session->mutex);
                    session->wakeup(session->wakeup_data);
                    pthread_mutex_lock(&session->mutex);
                }
            }
        } else if (!session->resetting && !session->closing &&
                   (!session->running || !session->queued || session->failed)) {
            pthread_cond_wait(&session->condition, &session->mutex);
        }
    }
    pthread_mutex_unlock(&session->mutex);
    return NULL;
}

struct volume_transfer {
    struct usb_transport *session;
    int result;
};

static void LIBUSB_CALL finish_volume_transfer(struct libusb_transfer *transfer)
{
    struct volume_transfer *request = transfer->user_data;
    struct usb_transport *session = request->session;
    pthread_mutex_lock(&session->mutex);
    switch (transfer->status) {
    case LIBUSB_TRANSFER_COMPLETED: request->result = transfer->actual_length; break;
    case LIBUSB_TRANSFER_TIMED_OUT: request->result = LIBUSB_ERROR_TIMEOUT; break;
    case LIBUSB_TRANSFER_STALL: request->result = LIBUSB_ERROR_PIPE; break;
    case LIBUSB_TRANSFER_NO_DEVICE: request->result = LIBUSB_ERROR_NO_DEVICE; break;
    case LIBUSB_TRANSFER_OVERFLOW: request->result = LIBUSB_ERROR_OVERFLOW; break;
    default: request->result = LIBUSB_ERROR_IO; break;
    }
    session->volume_transfer_pending = false;
    pthread_cond_broadcast(&session->condition);
    pthread_mutex_unlock(&session->mutex);
}

// The volume worker waits for completion without competing with the audio
// worker for libusb's event lock. The audio worker dispatches all completions.
static int submit_volume_transfer(void *context, uint8_t type, uint8_t request,
                                  uint16_t value, uint16_t index, uint8_t *data,
                                  uint16_t length, unsigned int timeout)
{
    struct usb_transport *session = context;
    uint8_t buffer[LIBUSB_CONTROL_SETUP_SIZE + 2 + 6 * USB_VOLUME_MAX_RANGES];
    if (length > sizeof(buffer) - LIBUSB_CONTROL_SETUP_SIZE)
        return LIBUSB_ERROR_INVALID_PARAM;
    struct libusb_transfer *transfer = libusb_alloc_transfer(0);
    if (!transfer)
        return LIBUSB_ERROR_NO_MEM;
    struct volume_transfer pending = {.session = session};
    libusb_fill_control_setup(buffer, type, request, value, index, length);
    if (!(type & LIBUSB_ENDPOINT_IN))
        memcpy(buffer + LIBUSB_CONTROL_SETUP_SIZE, data, length);
    libusb_fill_control_transfer(transfer, session->handle, buffer,
                                 finish_volume_transfer, &pending, timeout);

    pthread_mutex_lock(&session->mutex);
    int status = session->closing || session->failed
        ? LIBUSB_ERROR_NO_DEVICE : libusb_submit_transfer(transfer);
    if (status == 0) {
        session->volume_transfer_pending = true;
        pthread_cond_broadcast(&session->condition);
        while (session->volume_transfer_pending)
            pthread_cond_wait(&session->condition, &session->mutex);
        status = pending.result;
    }
    pthread_mutex_unlock(&session->mutex);

    if (status > 0 && status <= length && (type & LIBUSB_ENDPOINT_IN))
        memcpy(data, buffer + LIBUSB_CONTROL_SETUP_SIZE, status);
    libusb_free_transfer(transfer);
    return status;
}

static void *volume_worker(void *argument)
{
    struct usb_transport *session = argument;
    const struct usb_volume_io io = {.context = session, .transfer = submit_volume_transfer};
    float actual = 0;
    bool supported = false;
    for (int attempt = 0; attempt < 3; attempt++) {
        if (usb_volume_get(&session->volume, &io, &actual)) {
            supported = true;
            break;
        }
        if (attempt < 2) {
            const struct timespec delay = {.tv_nsec = 50000000};
            nanosleep(&delay, NULL);
        }
        pthread_mutex_lock(&session->volume_mutex);
        bool stopped = session->volume_stop;
        pthread_mutex_unlock(&session->volume_mutex);
        if (stopped)
            return NULL;
    }
    pthread_mutex_lock(&session->volume_mutex);
    while (!session->volume_stop) {
        session->volume_supported = supported;
        session->volume_percent = actual;
        snprintf(session->volume_issue, sizeof(session->volume_issue), "%s",
                 supported ? "" : session->volume.issue);
        while (!session->volume_stop && !session->volume_pending)
            pthread_cond_wait(&session->volume_condition, &session->volume_mutex);
        if (session->volume_stop)
            break;
        float target = session->volume_target;
        session->volume_pending = false;
        pthread_mutex_unlock(&session->volume_mutex);
        supported = usb_volume_set(&session->volume, &io, target) &&
                     usb_volume_get(&session->volume, &io, &actual);
        pthread_mutex_lock(&session->volume_mutex);
    }
    pthread_mutex_unlock(&session->volume_mutex);
    return NULL;
}

static void start_volume_worker(struct usb_transport *session)
{
    if (!session->volume.channel_count || pthread_mutex_init(&session->volume_mutex, NULL) != 0)
        return;
    if (pthread_cond_init(&session->volume_condition, NULL) == 0) {
        if (pthread_create(&session->volume_thread, NULL, volume_worker, session) == 0) {
            session->volume_worker_started = true;
            return;
        }
        pthread_cond_destroy(&session->volume_condition);
    }
    pthread_mutex_destroy(&session->volume_mutex);
}

/* Only use after the event thread has returned all outstanding transfers. */
static void free_session(struct usb_transport *session)
{
    for (int i = 0; i < session->output_transfer_count; i++) {
        libusb_free_transfer(session->slots[i].transfer);
        free(session->slots[i].data);
    }
    libusb_free_transfer(session->feedback.transfer);
    free(session->feedback.data);
    free(session->queue);
    if (session->handle) {
        release_interfaces(session);
        libusb_close(session->handle);
    }
    if (session->context)
        libusb_exit(session->context);
    if (session->fd >= 0)
        close(session->fd);
    free(session);
}

static void write_error(char *error, size_t size, const char *stage, int status)
{
    if (error && size)
        snprintf(error, size, "%s: %s", stage, libusb_error_name(status));
}

struct usb_transport *usb_transport_open(
    int fd, int rate, int channels, int min_valid_bits,
    struct usb_transport_format *format, void (*wakeup)(void *),
    void *wakeup_data, char *error, size_t error_size)
{
    if (fd < 0 || rate < 8000 || rate > 768000 || channels < 1 || channels > 2 ||
        min_valid_bits < 1 || min_valid_bits > 32 || !format) {
        write_error(error, error_size, "Unsupported USB audio request", LIBUSB_ERROR_INVALID_PARAM);
        return NULL;
    }
    struct usb_transport *session = calloc(1, sizeof(*session));
    if (!session) {
        write_error(error, error_size, "Allocate USB session", LIBUSB_ERROR_NO_MEM);
        return NULL;
    }
    session->claimed_control = session->claimed_stream = -1;
    session->fd = fcntl(fd, F_DUPFD_CLOEXEC, 0);
    if (session->fd < 0) {
        write_error(error, error_size, "Duplicate Android USB descriptor", LIBUSB_ERROR_IO);
        free(session);
        return NULL;
    }

    const struct libusb_init_option option = {.option = LIBUSB_OPTION_NO_DEVICE_DISCOVERY};
    int status = libusb_init_context(&session->context, &option, 1);
    if (!status)
        status = libusb_wrap_sys_device(session->context, (intptr_t)session->fd, &session->handle);
    if (!status)
        status = libusb_set_auto_detach_kernel_driver(session->handle, 1);
    if (status < 0) {
        write_error(error, error_size, "Open Android USB audio device", status);
        free_session(session);
        return NULL;
    }

    status = negotiate(session, rate, channels, min_valid_bits);
    if (status < 0) {
        write_error(error, error_size, "Claim USB PCM interfaces and set the source rate", status);
        free_session(session);
        return NULL;
    }
    session->wakeup = wakeup;
    session->wakeup_data = wakeup_data;
    session->capacity = rate / 10;
    session->queue = calloc((size_t)session->capacity * channels, sizeof(*session->queue));
    struct libusb_device_descriptor device = {0};
    libusb_get_device_descriptor(libusb_get_device(session->handle), &device);
    // Match the UA2 Plus's 1 ms explicit-feedback interval, as ALSA does
    // when sizing playback URBs, without reducing the prequeued time.
    bool short_transfers = session->output.high_speed && device.idVendor == UA2_PLUS_VID &&
                           device.idProduct == UA2_PLUS_PID &&
                           session->output.interval_us <= UA2_PLUS_TRANSFER_US;
    session->output_transfer_count = short_transfers ? MAX_OUTPUT_TRANSFERS : DEFAULT_OUTPUT_TRANSFERS;
    unsigned int transfer_us = short_transfers ? UA2_PLUS_TRANSFER_US : OUTPUT_TRANSFER_US;
    session->packets_per_transfer = transfer_us / session->output.interval_us;
    if (session->packets_per_transfer == 0)
        session->packets_per_transfer = 1;
    session->format = (struct usb_transport_format){
        .rate = rate,
        .channels = channels,
        .valid_bits = session->output.valid_bits,
        .subslot_bytes = session->output.subslot_bytes,
        .packets_per_transfer = session->packets_per_transfer,
        .transfer_count = session->output_transfer_count,
    };
    session->nominal_frames = rate * (double)session->output.interval_us / 1000000.0;
    session->max_packet_frames = session->output.max_packet /
        (channels * session->format.subslot_bytes);

    status = session->queue ? 0 : LIBUSB_ERROR_NO_MEM;
    for (int i = 0; i < session->output_transfer_count && !status; i++) {
        status = allocate_transfer(session, &session->slots[i], session->packets_per_transfer,
                                   session->output.max_packet, false);
    }
    if (!status && session->output.feedback_endpoint)
        status = allocate_transfer(session, &session->feedback, 1, session->output.feedback_size, true);
    if (status < 0) {
        write_error(error, error_size, "Allocate USB audio transfers", status);
        free_session(session);
        return NULL;
    }

    if (pthread_mutex_init(&session->mutex, NULL) != 0) {
        write_error(error, error_size, "Initialize USB audio queue", LIBUSB_ERROR_OTHER);
        free_session(session);
        return NULL;
    }
    if (pthread_cond_init(&session->condition, NULL) != 0) {
        write_error(error, error_size, "Initialize USB audio queue", LIBUSB_ERROR_OTHER);
        pthread_mutex_destroy(&session->mutex);
        free_session(session);
        return NULL;
    }
    if (pthread_create(&session->thread, NULL, stream_worker, session) != 0) {
        write_error(error, error_size, "Start USB audio worker", LIBUSB_ERROR_OTHER);
        pthread_cond_destroy(&session->condition);
        pthread_mutex_destroy(&session->mutex);
        free_session(session);
        return NULL;
    }
    start_volume_worker(session);
    *format = session->format;
    return session;
}

int usb_transport_write(struct usb_transport *session, const int32_t *samples, int frames)
{
    if (!samples || frames <= 0)
        return 0;
    pthread_mutex_lock(&session->mutex);
    int available = session->capacity - session->queued;
    if (session->failed || session->resetting)
        available = 0;
    int accepted = frames < available ? frames : available;
    int tail = (session->head + session->queued) % session->capacity;
    int first = accepted < session->capacity - tail ? accepted : session->capacity - tail;
    size_t stride = (size_t)session->format.channels * sizeof(*samples);
    memcpy(session->queue + (size_t)tail * session->format.channels, samples, (size_t)first * stride);
    memcpy(session->queue, samples + (size_t)first * session->format.channels,
           (size_t)(accepted - first) * stride);
    session->queued += accepted;
    pthread_cond_signal(&session->condition);
    pthread_mutex_unlock(&session->mutex);
    return accepted;
}

void usb_transport_get_state(struct usb_transport *session, struct usb_transport_state *state)
{
    pthread_mutex_lock(&session->mutex);
    *state = (struct usb_transport_state){
        .queued_frames = session->queued,
        .in_flight_frames = session->in_flight,
        .free_frames = session->failed || session->resetting ? 0 : session->capacity - session->queued,
        .failed = session->failed,
        .transfer_errors = session->transfer_errors,
        .packet_errors = session->packet_errors,
        .rejected_feedback = session->rejected_feedback,
        .padded_frames = session->padded_frames,
    };
    if (session->failed || session->transfer_errors || session->rejected_feedback)
        memcpy(state->issue, session->issue, sizeof(state->issue));
    pthread_mutex_unlock(&session->mutex);
}

bool usb_transport_get_volume(struct usb_transport *session, float *percent,
                              char *issue, size_t issue_size)
{
    if (!session->volume_worker_started) {
        snprintf(issue, issue_size, "%s", session->volume.channel_count
                 ? "Could not start USB volume worker" : "No writable Feature Unit on the selected playback path");
        return false;
    }
    pthread_mutex_lock(&session->volume_mutex);
    bool result = session->volume_supported;
    *percent = session->volume_percent;
    snprintf(issue, issue_size, "%s", session->volume_issue);
    pthread_mutex_unlock(&session->volume_mutex);
    return result;
}

bool usb_transport_set_volume(struct usb_transport *session, float percent)
{
    if (!session->volume_worker_started || !isfinite(percent) || percent < 0 || percent > 100)
        return false;
    pthread_mutex_lock(&session->volume_mutex);
    bool result = session->volume_supported;
    if (result) {
        session->volume_target = percent;
        session->volume_pending = true;
        pthread_cond_signal(&session->volume_condition);
    }
    pthread_mutex_unlock(&session->volume_mutex);
    return result;
}

void usb_transport_set_running(struct usb_transport *session, bool running)
{
    pthread_mutex_lock(&session->mutex);
    session->running = running;
    pthread_cond_signal(&session->condition);
    pthread_mutex_unlock(&session->mutex);
}

void usb_transport_reset(struct usb_transport *session)
{
    pthread_mutex_lock(&session->mutex);
    session->running = false;
    session->resetting = true;
    pthread_cond_signal(&session->condition);
    while (session->resetting)
        pthread_cond_wait(&session->condition, &session->mutex);
    pthread_mutex_unlock(&session->mutex);
}

void usb_transport_close(struct usb_transport *session)
{
    if (!session)
        return;
    if (session->volume_worker_started) {
        pthread_mutex_lock(&session->volume_mutex);
        session->volume_stop = true;
        pthread_cond_signal(&session->volume_condition);
        pthread_mutex_unlock(&session->volume_mutex);
        pthread_join(session->volume_thread, NULL);
        pthread_cond_destroy(&session->volume_condition);
        pthread_mutex_destroy(&session->volume_mutex);
    }
    pthread_mutex_lock(&session->mutex);
    session->closing = true;
    pthread_cond_signal(&session->condition);
    pthread_mutex_unlock(&session->mutex);
    pthread_join(session->thread, NULL);
    pthread_cond_destroy(&session->condition);
    pthread_mutex_destroy(&session->mutex);
    free_session(session);
}
