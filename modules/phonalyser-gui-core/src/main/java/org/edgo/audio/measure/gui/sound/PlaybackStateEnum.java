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

package org.edgo.audio.measure.gui.sound;

/**
 * How a playback-lane start ended - the machine-readable answer a start
 * attempt returns instead of a localized sentence, since returning localized
 * error text from this depth is wrong practice.
 *
 * <p>Two things hang off the value.  The UI maps it to its i18n message
 * ({@link #i18nKey()}) at the boundary where the operator is addressed, and
 * nowhere deeper.  And {@link #retryable()} is what a start loop consults:
 * a driver race is worth a second attempt, a sound card that is not there is
 * not - retrying it only doubles the time until the operator learns the truth.
 */
public enum PlaybackStateEnum {

    /** No start has been attempted yet - the state a fresh lane answers
     *  before its first start.  Not a message and not a failure. */
    PARKED(null, false),

    /** The lane is up and emitting. */
    STARTED(null, false),

    /** No output device is configured at all - the operator has not picked
     *  one; retrying changes nothing. */
    NO_DEVICE("generator.error.noDevice", false),

    /** The configured output device is not present right now - unplugged or
     *  serving another host.  Not retryable: what is not there now is not
     *  there half a second later. */
    DEVICE_UNAVAILABLE("generator.error.deviceUnavailable", false),

    /** The remote bench refused (or failed) the start - its own words are in
     *  the log; the wire timeouts already bounded the wait.  Not retryable
     *  from here: the bench said no.
     *
     *  <p>Its message carries WHY, like the local open's: the bench classified
     *  the failure with the backend that owns the driver and sent the reason
     *  over the wire (spec 4.2), so the operator reads it in the UI language.
     *  It has its own key rather than the shared {@code net.servers.error.title}
     *  - that one is a dialog TITLE in eight other places and must not grow a
     *  parameter. */
    REMOTE_REFUSED("generator.error.remoteRefused", false),

    /** The compensated form has no predistortion file loaded - the operator
     *  must load one; retrying changes nothing. */
    NEED_PREDISTORTION("generator.error.needPredistortion", false),

    /** A predistortion file IS configured and is not on disk - moved, renamed,
     *  or on a drive that is not mounted.  Its own value rather than a build
     *  failure, because it is the one configuration fault the operator can fix
     *  the moment they are told which path is missing; the message names it. */
    PREDISTORTION_FILE_MISSING("generator.error.predistortionFileMissing", false),

    /** Building the signal source itself failed (an unreadable predistortion
     *  file, an impossible parameter set) - a configuration problem, not a
     *  device one; retrying changes nothing. */
    BUILD_FAILED("generator.error.buildFailed", false),

    /** The output device would not open - the card is missing, held
     *  exclusively, or refused the format.  Not retryable: what is not there
     *  now is not there half a second later. */
    OPEN_FAILED("generator.error.openDeviceFailed", false),

    /** The device opened but the stream never signalled ready in time - the
     *  in-process WASAPI-exclusive start race is the known case, and exactly
     *  the one a single retry wins. */
    NO_STREAM_START("generator.error.noStreamStart", true),

    /** The starting thread was interrupted - the application is on its way
     *  somewhere else; nothing to retry. */
    INTERRUPTED("generator.error.startInterrupted", false);

    /** The i18n key the UI renders this value with - null for
     *  {@link #STARTED}, which is not a message.  The key's arguments stay
     *  the CALLER's business (a device name, a timeout) - technical details
     *  from below belong to the log, never to the operator. */
    private final String i18nKey;
    private final boolean retryable;

    private PlaybackStateEnum(String i18nKey, boolean retryable) {
        this.i18nKey = i18nKey;
        this.retryable = retryable;
    }

    public String i18nKey() {
        return i18nKey;
    }

    public boolean retryable() {
        return retryable;
    }
}
