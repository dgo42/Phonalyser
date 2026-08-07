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
 * The net wire format, shared by both ends of the bridge: the JSON message
 * envelope and its codec, the binary audio frames, the discovery beacon, and
 * the protocol's fixed numbers ({@link NetProto}) and field names
 * ({@link NetFields}) - all of it a literal quotation of doc/NET-PROTOCOL.md.
 *
 * <p>Depends on JSON only: no audio, no backend, no GUI, so the headless
 * server and every client can share it without dragging the other side in.
 */
package org.edgo.audio.measure.net.proto;
