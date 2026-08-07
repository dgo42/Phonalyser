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

/**
 * The headless net server: it owns the local audio backends and the signal
 * generator and serves them to Phonalyser GUI clients over the wire format in
 * {@link org.edgo.audio.measure.net.proto} (specified by doc/NET-PROTOCOL.md).
 *
 * <p>In place: the entry point with its parsed command line, the WebSocket
 * control front, one session per connection (handshake, dispatch, keepalive,
 * teardown) and the device-lock registry they contend on.  The device catalog,
 * the capture streamer, the generator session and the HTTP/beacon front arrive
 * with the remaining server phases.
 *
 * <p>Nothing here may touch SWT or any GUI package - the server runs on a
 * machine with no display.
 */
package org.edgo.audio.measure.net.server;
