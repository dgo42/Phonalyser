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

/*
 * mock_qa403 - the emulated QA403 device model.
 *
 * A PURE device model: register file, factory calibration page, the DAC and ADC
 * sample FIFOs, the loopback (ADC = inverse of the host raw<->volts math applied
 * to the DAC), and the sample clock.  It knows nothing about libusb, threads or
 * locks - every function assumes the ONE global lock (owned by mock_libusb) is
 * already held by the caller.  mock_libusb bridges libusb transfers into/out of
 * these FIFOs; the loopback DSP lives entirely here.
 *
 * See doc/QA40X-PROTOCOL.md sections 4/5/6 for the wire contract this emulates.
 */
#ifndef MOCK_QA403_H
#define MOCK_QA403_H

#include <stdint.h>

/* Registers 0x00..0x1F (doc section 4). */
#define QA403_REG_COUNT            0x20

/* Factory calibration page size in bytes (doc section 6). */
#define QA403_CAL_PAGE_BYTES       512
/* Calibration words: 512 / 4 (doc section 6). */
#define QA403_CAL_WORDS            128

/* Hardware DAC queue depth (doc section 5): the QA403 starts once it fills. */
#define QA403_DAC_CAP_FRAMES       1024
/* ADC ring capacity in frames (1 << 20) - drop-newest on overflow. */
#define QA403_ADC_CAP_FRAMES       (1 << 20)
/* Output frames queued since RUN_START before the device begins streaming. */
#define QA403_START_THRESHOLD      1024

/* Full-scale sample magnitude: 2^31 - 1 (doc section 6). */
#define QA403_MAXINT               2147483647

/* Register addresses (doc section 4). */
#define QA403_REG_INPUT_FS         0x05
#define QA403_REG_OUTPUT_FS        0x06
#define QA403_REG_RUN              0x08
#define QA403_REG_SAMPLE_RATE      0x09
#define QA403_REG_CAL_PAGE_SELECT  0x0D
#define QA403_REG_CAL_READ         0x19

/* REG_RUN values (doc section 5). */
#define QA403_RUN_START            0x05
#define QA403_RUN_STOP             0x00

/* Small register-read reply ring (host reads are strictly write-then-read). */
#define QA403_REPLY_CAP            16

/*
 * The emulated device.  All fields are guarded by mock_libusb's global lock.
 */
typedef struct qa403_device {
    uint32_t regs[QA403_REG_COUNT];         /* register file */
    int      running;                        /* REG_RUN == RUN_START */
    int      started;                        /* start threshold crossed */

    unsigned char cal_page[QA403_CAL_PAGE_BYTES];
    int      cal_read_idx;                   /* 0..127, advanced by REG_CAL_READ */

    int32_t  dac_buf[QA403_DAC_CAP_FRAMES * 2]; /* interleaved slot0,slot1 */
    int      dac_head;                       /* frame index */
    int      dac_count;                      /* frames queued */

    int32_t *adc_buf;                        /* malloc'd interleaved L,R ring */
    int      adc_head;                       /* frame index */
    int      adc_count;                      /* frames queued */

    long long frames_queued_since_start;     /* for the start threshold */
    double   frames_due;                     /* fractional sample-clock accumulator */

    double   gain_left;                      /* loopback gain, logical L */
    double   gain_right;                     /* loopback gain, logical R */

    int       rx_accum;                      /* DAC frames accepted since last rx log */
    long long rx_total;                      /* logged rx frames since RUN_START */
    int       tx_accum;                      /* ADC frames delivered since last tx log */
    long long tx_total;                      /* logged tx frames since RUN_START */

    uint32_t reply_buf[QA403_REPLY_CAP];     /* pending register-read replies */
    int      reply_head;
    int      reply_count;
} qa403_device;

/* Lifecycle. */
int  qa403_init(qa403_device *d);            /* allocates the ADC ring, builds cal, resets; 0 on success */
void qa403_free(qa403_device *d);
void qa403_reset(qa403_device *d);           /* full device reset (cal page persists) */

/* Register access (doc section 4) - caller holds the lock. */
void     qa403_reg_write(qa403_device *d, unsigned reg, uint32_t value);
uint32_t qa403_reg_read(qa403_device *d, unsigned reg);  /* advances cal idx for REG_CAL_READ */

/* Register-read reply ring (EP 0x01 read-request pushes; EP 0x81 pops). */
void qa403_reply_push(qa403_device *d, uint32_t word);
int  qa403_reply_pop(qa403_device *d, uint32_t *word);   /* 1 if popped, 0 if empty */

/* DAC feed (mock_libusb moves OUT-transfer bytes in). */
int  qa403_dac_free_frames(qa403_device *d);
void qa403_dac_push(qa403_device *d, int32_t slot0, int32_t slot1);

/* Sample clock + loopback: advance by elapsed_sec, DAC -> ADC (doc section 5/6). */
void qa403_stream(qa403_device *d, double elapsed_sec);

/* ADC deliver (mock_libusb moves frames out into IN transfers). */
int  qa403_adc_avail_frames(qa403_device *d);
void qa403_adc_pop(qa403_device *d, int32_t *left, int32_t *right);

#undef DEBUG_LOG
/* Console protocol log (stdout, "[QA403] ..." lines, flushed each call). */
void qa403_logf(const char *fmt, ...);

/* Streaming per-direction frame logging: one "+N frames (total M)" line per
 * crossed multiple of 1024 (doc/plan "Console logging").  Counters reset at
 * RUN_START; call once per feed/deliver batch, never per sample. */
void qa403_log_rx(qa403_device *d, int frames);   /* host -> DAC accepted */
void qa403_log_tx(qa403_device *d, int frames);   /* ADC -> host delivered */

#endif /* MOCK_QA403_H */
