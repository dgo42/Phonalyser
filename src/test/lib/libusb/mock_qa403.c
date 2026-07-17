/*
 * Phonalyser - precision audio measurement workbench.
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

#include "mock_qa403.h"

#include <math.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

/* Output full-scale ranges in dBV, indexed by REG_OUTPUT_FS code (doc section 4). */
static const double QA403_OUTPUT_DBV[4] = { -12.0, -2.0, 8.0, 18.0 };

/* Duplex sample rates in Hz, indexed by REG_SAMPLE_RATE code; code 3 clamps to
 * 192000 defensively (the Java side never writes it) - doc section 4/5. */
static const int QA403_RATE_HZ[4] = { 48000, 96000, 192000, 192000 };

/* dB -> linear factor 10^(dB/20).  Takes the float32 dB (as stored in the cal
 * page) widened to double, exactly as the Java host does, so the loopback gain
 * inverts the host math (doc section 6). */
static double db_to_linear(float db)
{
    return pow(10.0, (double)db / 20.0);
}

/* Saturating round to a 32-bit sample, clamped to +/-(2^31 - 1) (doc section 6). */
static int32_t saturate_i32(double x)
{
    if (x >= (double)QA403_MAXINT) {
        return QA403_MAXINT;
    }
    if (x <= -(double)QA403_MAXINT) {
        return -QA403_MAXINT;
    }
    return (int32_t)(x >= 0.0 ? x + 0.5 : x - 0.5);
}

/* Writes one (int16 level, float32 dB) little-endian record (doc section 6). */
static void write_cal_record(unsigned char *p, int16_t level, float db)
{
    memcpy(p,     &level, sizeof(level)); /* x64 is little-endian: native bytes are LE */
    memcpy(p + 2, &db,    sizeof(db));
}

/* Deterministic factory calibration page (documented for the integration test):
 * 512 zero bytes except the ADC records at 24 + 12*c (c = 0..7) and the DAC
 * records at 120 + 12*c (c = 0..3); Left at the offset, Right at +6 (doc s.6). */
static void build_cal_page(qa403_device *d)
{
    int c;
    memset(d->cal_page, 0, sizeof(d->cal_page));
    for (c = 0; c < 8; c++) {                       /* ADC, keyed by input FS code */
        unsigned char *left = d->cal_page + 24 + 12 * c;
        write_cal_record(left,     (int16_t)c,  (float)(0.10f + 0.02f * c));
        write_cal_record(left + 6, (int16_t)c, -(float)(0.10f + 0.02f * c));
    }
    for (c = 0; c < 4; c++) {                        /* DAC, keyed by output FS code */
        unsigned char *left = d->cal_page + 120 + 12 * c;
        write_cal_record(left,     (int16_t)c,  (float)(0.05f + 0.02f * c));
        write_cal_record(left + 6, (int16_t)c, -(float)(0.05f + 0.02f * c));
    }
}

/*
 * Recomputes the per-channel loopback gains from the current ranges + cal page:
 *   G_ch = 10^((outFS - inFS + 9)/20) / (calDac_ch * calAdc_ch)   (doc section 6)
 * so the app's ADC math reads back exactly the peak voltage its DAC math asked
 * for.  The cal factors use the SAME float32 dB the cal page carries, so the mock
 * and the Java host agree bit-for-bit.
 */
static void recompute_gains(qa403_device *d)
{
    int in_code  = (int)(d->regs[QA403_REG_INPUT_FS]  & 0x7);
    int out_code = (int)(d->regs[QA403_REG_OUTPUT_FS] & 0x3);
    double in_fs  = in_code * 6.0;
    double out_fs = QA403_OUTPUT_DBV[out_code];

    double cal_adc_l = db_to_linear( (float)(0.10f + 0.02f * in_code));
    double cal_adc_r = db_to_linear(-(float)(0.10f + 0.02f * in_code));
    double cal_dac_l = db_to_linear( (float)(0.05f + 0.02f * out_code));
    double cal_dac_r = db_to_linear(-(float)(0.05f + 0.02f * out_code));

    /* Host semantics (maintainer ruling 2026-07-17): the range label is the RMS
     * full scale on BOTH directions — DAC digital = peak/10^((out+3)/20), ADC
     * volts = digital*10^((in+3)/20).  The +3 terms cancel in the loopback, so
     * the digital gain is plain 10^((out-in)/20); the former +9.0 inverted the
     * dropped -6 dB vendor input term. */
    double base = pow(10.0, (out_fs - in_fs) / 20.0);
    d->gain_left  = base / (cal_dac_l * cal_adc_l);
    d->gain_right = base / (cal_dac_r * cal_adc_r);
}

static int rate_hz(qa403_device *d)
{
    return QA403_RATE_HZ[d->regs[QA403_REG_SAMPLE_RATE] & 0x3];
}

void qa403_logf(const char *fmt, ...)
{
    va_list ap;
    va_start(ap, fmt);
    vprintf(fmt, ap);
    va_end(ap);
    fflush(stdout);   /* the JVM may otherwise buffer the DLL's stdout away */
}

static const char *reg_name(unsigned reg)
{
    switch (reg) {
    case QA403_REG_INPUT_FS:        return "INPUT_FS";
    case QA403_REG_OUTPUT_FS:       return "OUTPUT_FS";
    case QA403_REG_RUN:             return "RUN";
    case QA403_REG_SAMPLE_RATE:     return "SAMPLE_RATE";
    case QA403_REG_CAL_PAGE_SELECT: return "CAL_PAGE_SELECT";
    case QA403_REG_CAL_READ:        return "CAL_READ";
    default:                        return NULL;
    }
}

/* Logs a decoded register write: address, symbolic name, value, meaning. */
static void log_reg_write(unsigned reg, uint32_t value)
{
    switch (reg) {
    case QA403_REG_INPUT_FS:
        qa403_logf("[QA403] reg write 0x%02X INPUT_FS = %u (%+d dBV)\n",
                   reg, value, (int)((value & 0x7) * 6));
        break;
    case QA403_REG_OUTPUT_FS:
        qa403_logf("[QA403] reg write 0x%02X OUTPUT_FS = %u (%+d dBV)\n",
                   reg, value, (int)QA403_OUTPUT_DBV[value & 0x3]);
        break;
    case QA403_REG_RUN:
        qa403_logf("[QA403] reg write 0x%02X RUN = %u (%s)\n", reg, value,
                   value == QA403_RUN_START ? "START"
                   : value == QA403_RUN_STOP ? "STOP" : "?");
        break;
    case QA403_REG_SAMPLE_RATE:
        qa403_logf("[QA403] reg write 0x%02X SAMPLE_RATE = %u (%d Hz)\n",
                   reg, value, QA403_RATE_HZ[value & 0x3]);
        break;
    case QA403_REG_CAL_PAGE_SELECT:
        qa403_logf("[QA403] reg write 0x%02X CAL_PAGE_SELECT = 0x%02X\n", reg, value);
        break;
    default:
        qa403_logf("[QA403] reg write 0x%02X = 0x%08X\n", reg, value);
        break;
    }
}

int qa403_init(qa403_device *d)
{
    d->adc_buf = (int32_t *)malloc((size_t)QA403_ADC_CAP_FRAMES * 2 * sizeof(int32_t));
    if (d->adc_buf == NULL) {
        return -1;
    }
    build_cal_page(d);
    qa403_reset(d);
    return 0;
}

void qa403_free(qa403_device *d)
{
    free(d->adc_buf);
    d->adc_buf = NULL;
}

void qa403_reset(qa403_device *d)
{
    memset(d->regs, 0, sizeof(d->regs));
    d->running = 0;
    d->started = 0;
    d->cal_read_idx = 0;
    d->dac_head = 0;
    d->dac_count = 0;
    d->adc_head = 0;
    d->adc_count = 0;
    d->frames_queued_since_start = 0;
    d->frames_due = 0.0;
    d->reply_head = 0;
    d->reply_count = 0;
    d->rx_accum = 0;
    d->rx_total = 0;
    d->tx_accum = 0;
    d->tx_total = 0;
    recompute_gains(d);
}

void qa403_reg_write(qa403_device *d, unsigned reg, uint32_t value)
{
    log_reg_write(reg, value);
    if (reg >= QA403_REG_COUNT) {
        return;                                     /* out of the modelled window */
    }
    d->regs[reg] = value;
    switch (reg) {
    case QA403_REG_INPUT_FS:
    case QA403_REG_OUTPUT_FS:
        recompute_gains(d);
        break;
    case QA403_REG_RUN:
        if (value == QA403_RUN_START) {             /* clear FIFOs, arm the clock */
            d->dac_head = d->dac_count = 0;
            d->adc_head = d->adc_count = 0;
            d->started = 0;
            d->running = 1;
            d->frames_queued_since_start = 0;
            d->frames_due = 0.0;
            d->rx_accum = d->tx_accum = 0;          /* reset streaming counters */
            d->rx_total = d->tx_total = 0;
        } else if (value == QA403_RUN_STOP) {        /* stop + clear FIFOs */
            d->running = 0;
            d->started = 0;
            d->dac_head = d->dac_count = 0;
            d->adc_head = d->adc_count = 0;
        }
        break;
    case QA403_REG_CAL_PAGE_SELECT:
        d->cal_read_idx = 0;                        /* reset the cal read cursor */
        break;
    default:
        break;                                       /* REG_CAL_READ etc.: store only */
    }
}

uint32_t qa403_reg_read(qa403_device *d, unsigned reg)
{
    if (reg == QA403_REG_CAL_READ) {
        uint32_t v;
        int idx = d->cal_read_idx;
        memcpy(&v, d->cal_page + idx * 4, 4);        /* LE word of the page */
        d->cal_read_idx = (idx + 1) & 127;
        qa403_logf("[QA403] reg read 0x%02X CAL_READ[%d] -> 0x%08X\n",
                   QA403_REG_CAL_READ, idx, v);
        return v;
    }
    if (reg >= QA403_REG_COUNT) {
        qa403_logf("[QA403] reg read 0x%02X -> 0x00000000\n", reg);
        return 0;
    }
    {
        uint32_t v = d->regs[reg];
        const char *name = reg_name(reg);
        if (name != NULL) {
            qa403_logf("[QA403] reg read 0x%02X %s -> 0x%08X\n", reg, name, v);
        } else {
            qa403_logf("[QA403] reg read 0x%02X -> 0x%08X\n", reg, v);
        }
        return v;
    }
}

void qa403_reply_push(qa403_device *d, uint32_t word)
{
    if (d->reply_count >= QA403_REPLY_CAP) {
        return;                                      /* host is lock-step: never full */
    }
    d->reply_buf[(d->reply_head + d->reply_count) % QA403_REPLY_CAP] = word;
    d->reply_count++;
}

int qa403_reply_pop(qa403_device *d, uint32_t *word)
{
    if (d->reply_count == 0) {
        return 0;
    }
    *word = d->reply_buf[d->reply_head];
    d->reply_head = (d->reply_head + 1) % QA403_REPLY_CAP;
    d->reply_count--;
    return 1;
}

int qa403_dac_free_frames(qa403_device *d)
{
    return QA403_DAC_CAP_FRAMES - d->dac_count;
}

void qa403_dac_push(qa403_device *d, int32_t slot0, int32_t slot1)
{
    int idx;
    if (d->dac_count >= QA403_DAC_CAP_FRAMES) {
        return;                                      /* full: caller checks free first */
    }
    idx = (d->dac_head + d->dac_count) % QA403_DAC_CAP_FRAMES;
    d->dac_buf[idx * 2]     = slot0;
    d->dac_buf[idx * 2 + 1] = slot1;
    d->dac_count++;
    d->frames_queued_since_start++;
    if (d->running && !d->started
            && d->frames_queued_since_start >= QA403_START_THRESHOLD) {
        d->started = 1;                              /* the QA403 begins to stream */
        qa403_logf("[QA403] output queue reached %d frames - streaming started\n",
                   QA403_START_THRESHOLD);
    }
}

static void adc_push(qa403_device *d, int32_t left, int32_t right)
{
    int idx;
    if (d->adc_count >= QA403_ADC_CAP_FRAMES) {
        return;                                      /* drop-newest on overflow */
    }
    idx = (d->adc_head + d->adc_count) % QA403_ADC_CAP_FRAMES;
    d->adc_buf[idx * 2]     = left;
    d->adc_buf[idx * 2 + 1] = right;
    d->adc_count++;
}

void qa403_stream(qa403_device *d, double elapsed_sec)
{
    int n, i;
    if (!d->started) {
        return;                                      /* reads wait until priming starts */
    }
    d->frames_due += elapsed_sec * (double)rate_hz(d);
    n = (int)d->frames_due;                          /* integer frames due this tick */
    if (n <= 0) {
        return;
    }
    d->frames_due -= (double)n;
    for (i = 0; i < n; i++) {
        int32_t slot0, slot1, left, right;
        if (d->dac_count > 0) {                      /* pop one DAC frame */
            slot0 = d->dac_buf[d->dac_head * 2];
            slot1 = d->dac_buf[d->dac_head * 2 + 1];
            d->dac_head = (d->dac_head + 1) % QA403_DAC_CAP_FRAMES;
            d->dac_count--;
        } else {
            slot0 = 0;                               /* underrun: silence, no recovery */
            slot1 = 0;
        }
        /* Loopback: the cable un-swaps the DAC L/R swap; ADC is not swapped.
         * wire slot0 = logical R, slot1 = logical L (doc section 5). */
        /* 24-bit precision (maintainer, 2026-07-17): only the 24 MSBs of the
         * int32 carry signal on the wire, the low byte is zero padding, both
         * directions.  The DAC ignores the incoming low byte and the ADC emits
         * a zero low byte — mask on ingestion and on output. */
        slot0 = (int32_t)(slot0 & 0xFFFFFF00);
        slot1 = (int32_t)(slot1 & 0xFFFFFF00);
        left  = (int32_t)(saturate_i32(d->gain_left  * (double)slot1) & 0xFFFFFF00);
        right = (int32_t)(saturate_i32(d->gain_right * (double)slot0) & 0xFFFFFF00);
        adc_push(d, left, right);
    }
}

int qa403_adc_avail_frames(qa403_device *d)
{
    return d->adc_count;
}

void qa403_adc_pop(qa403_device *d, int32_t *left, int32_t *right)
{
    if (d->adc_count == 0) {
        *left = 0;
        *right = 0;
        return;
    }
    *left  = d->adc_buf[d->adc_head * 2];
    *right = d->adc_buf[d->adc_head * 2 + 1];
    d->adc_head = (d->adc_head + 1) % QA403_ADC_CAP_FRAMES;
    d->adc_count--;
}

void qa403_log_rx(qa403_device *d, int frames)
{
    d->rx_accum += frames;
    if (d->rx_accum >= 1024) {
        int mult = (d->rx_accum / 1024) * 1024;      /* one line per crossed 1024 */
        d->rx_accum -= mult;
        d->rx_total += mult;
        qa403_logf("[QA403] audio rx +%d frames (total %lld)\n", mult, d->rx_total);
    }
}

void qa403_log_tx(qa403_device *d, int frames)
{
    d->tx_accum += frames;
    if (d->tx_accum >= 1024) {
        int mult = (d->tx_accum / 1024) * 1024;
        d->tx_accum -= mult;
        d->tx_total += mult;
        qa403_logf("[QA403] audio tx +%d frames (total %lld)\n", mult, d->tx_total);
    }
}
