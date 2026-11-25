/* SPDX-License-Identifier: GPL-3.0-or-later */
#include <inttypes.h>
#include <stdint.h>

#include "audio/format.h"
#include "common/msg.h"
#include "mpv_talloc.h"
#include "options/m_option.h"
#include "options/options.h"
#include "ao.h"
#include "internal.h"
#include "usb_transport.h"

struct priv {
    int fd;
    int depth;
    struct usb_transport *transport;
    int32_t *expanded_pcm;
    bool running;
    bool failure_reported;
    bool hardware_volume_issue_reported;
    unsigned int reported_transfer_errors;
    unsigned int reported_feedback_rejections;
};

static void wakeup(void *context)
{
    ao_wakeup(context);
}

static int init(struct ao *ao)
{
    struct priv *p = ao->priv;
    if (p->fd < 0)
        return -1;

    char error[192] = "No matching USB PCM format";
    struct usb_transport_format format;
    if (!af_fmt_is_pcm(ao->format)) {
        MP_ERR(ao, "USB direct output failed: audio format is not PCM\n");
        return -1;
    }
    struct mp_chmap_sel channels = {.tmp = ao};
    struct mp_chmap mono = MP_CHMAP_INIT_MONO;
    struct mp_chmap stereo = MP_CHMAP_INIT_STEREO;
    mp_chmap_sel_add_map(&channels, &mono);
    mp_chmap_sel_add_map(&channels, &stereo);
    if (!ao_chmap_sel_adjust(ao, &channels, &ao->channels)) {
        MP_ERR(ao, "USB direct output failed: unsupported channel layout\n");
        return -1;
    }

    int source = af_fmt_from_planar(ao->format);
    int precision = ao->source_bits;
    if (precision <= 0)
        precision = source == AF_FORMAT_S32 ? 32 : source == AF_FORMAT_S16 ? 16 : 24;
    int minimum = p->depth ? p->depth : MPMIN(32, MPMAX(16, precision));
    p->transport = usb_transport_open(p->fd, ao->samplerate, ao->channels.num,
                                     minimum, &format, wakeup, ao, error, sizeof(error));
    if (!p->transport && ao->channels.num == 1) {
        p->transport = usb_transport_open(p->fd, ao->samplerate, 2,
                                         minimum, &format, wakeup, ao, error, sizeof(error));
        if (p->transport)
            ao->channels = stereo;
    }
    if (!p->transport) {
        MP_ERR(ao, "USB direct output failed: %s (%d Hz, minimum %d bits)\n",
               error, ao->samplerate, minimum);
        return -1;
    }

    struct usb_transport_state state;
    usb_transport_get_state(p->transport, &state);
    ao->format = p->depth == 16 ? AF_FORMAT_S16 : AF_FORMAT_S32;
    ao->device_buffer = state.free_frames;
    if (p->depth == 16)
        p->expanded_pcm = talloc_array(ao, int32_t, ao->device_buffer * ao->channels.num);
    MP_WARN(ao, "USB PCM: %d Hz, %d ch, source %d bits, %d valid bits in %d bytes; %d transfers x %d packets\n",
            format.rate, format.channels, ao->source_bits, format.valid_bits, format.subslot_bytes,
            format.transfer_count, format.packets_per_transfer);
    return 0;
}

static void uninit(struct ao *ao)
{
    struct priv *p = ao->priv;
    usb_transport_close(p->transport);
}

static void reset(struct ao *ao)
{
    struct priv *p = ao->priv;
    p->running = false;
    usb_transport_reset(p->transport);
}

static void start(struct ao *ao)
{
    struct priv *p = ao->priv;
    p->running = true;
    usb_transport_set_running(p->transport, true);
}

static bool set_pause(struct ao *ao, bool paused)
{
    struct priv *p = ao->priv;
    p->running = !paused;
    usb_transport_set_running(p->transport, !paused);
    return true;
}

static bool write_pcm(struct ao *ao, void **data, int frames)
{
    struct priv *p = ao->priv;
    const int32_t *samples = data[0];
    if (p->expanded_pcm) {
        if (frames > ao->device_buffer)
            return false;
        const int16_t *input = data[0];
        for (int i = 0; i < frames * ao->channels.num; i++)
            p->expanded_pcm[i] = (int32_t)input[i] * 65536;
        samples = p->expanded_pcm;
    }
    return usb_transport_write(p->transport, samples, frames) == frames;
}

static int control(struct ao *ao, enum aocontrol command, void *data)
{
    struct priv *p = ao->priv;
    switch (command) {
    case AOCONTROL_GET_VOLUME: {
        char issue[96];
        bool supported = usb_transport_get_volume(p->transport, data, issue, sizeof(issue));
        if (supported) {
            p->hardware_volume_issue_reported = false;
            return CONTROL_OK;
        }
        if (*issue && !p->hardware_volume_issue_reported) {
            p->hardware_volume_issue_reported = true;
            MP_WARN(ao, "DAC hardware volume unavailable: %s\n", issue);
        }
        return CONTROL_UNKNOWN;
    }
    case AOCONTROL_SET_VOLUME:
        return usb_transport_set_volume(p->transport, *(float *)data) ? CONTROL_OK : CONTROL_UNKNOWN;
    default:
        return CONTROL_UNKNOWN;
    }
}

static void get_state(struct ao *ao, struct mp_pcm_state *state)
{
    struct priv *p = ao->priv;
    struct usb_transport_state usb;
    usb_transport_get_state(p->transport, &usb);
    if (usb.failed && !p->failure_reported) {
        p->failure_reported = true;
        MP_ERR(ao, "USB direct output failed: %s (%d Hz, %d ch, %u transfer errors, %u feedback rejections)\n",
               usb.issue, ao->samplerate, ao->channels.num, usb.transfer_errors, usb.rejected_feedback);
    } else if (!usb.failed &&
               ((!p->reported_transfer_errors && usb.transfer_errors) ||
                usb.transfer_errors - p->reported_transfer_errors >= 64 ||
                (!p->reported_feedback_rejections && usb.rejected_feedback) ||
                usb.rejected_feedback - p->reported_feedback_rejections >= 4096)) {
        MP_WARN(ao, "USB transport continued: %s (%u transfer errors, %u packet errors, %u feedback rejections, %" PRIu64 " padded PCM frames)\n",
                usb.issue, usb.transfer_errors, usb.packet_errors, usb.rejected_feedback, usb.padded_frames);
        p->reported_transfer_errors = usb.transfer_errors;
        p->reported_feedback_rejections = usb.rejected_feedback;
    }
    state->free_samples = usb.failed ? 0 : usb.free_frames;
    state->queued_samples = usb.queued_frames;
    int remaining = usb.queued_frames + usb.in_flight_frames;
    state->delay = remaining / (double)ao->samplerate;
    state->playing = p->running && !usb.failed && remaining > 0;
}

#define OPT_BASE_STRUCT struct priv

const struct ao_driver audio_out_android_usb = {
    .description = "Android direct USB PCM output",
    .name = "android-usb",
    .init = init,
    .uninit = uninit,
    .reset = reset,
    .start = start,
    .set_pause = set_pause,
    .write = write_pcm,
    .control = control,
    .get_state = get_state,
    .priv_size = sizeof(struct priv),
    .priv_defaults = &(const struct priv){.fd = -1},
    .options = (const struct m_option[]) {
        {"fd", OPT_INT(fd), M_RANGE(-1, INT32_MAX)},
        {"depth", OPT_CHOICE(depth, {"auto", 0}, {"32", 32}, {"16", 16}), .flags = UPDATE_AUDIO},
        {0},
    },
    .options_prefix = "android-usb",
};
