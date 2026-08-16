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

package org.edgo.audio.measure.net.client;

import lombok.Getter;

/**
 * Why a {@link NetConnection} ended - a value, not a log line.
 *
 * <p>It is typed because the ends differ in what the operator must be told and
 * in what the modules must do: an orderly {@link #BYE} is the client's own
 * doing and needs no message at all, while a {@link #KEEPALIVE_TIMEOUT} means
 * the bench went silent mid-measurement (spec 4.1: "stop all modules, show the
 * connection error") and everything on the wire is now suspect.  A caller that
 * had to parse a sentence to tell those apart would end up guessing.
 */
public enum NetCloseReason {

    /** The client said goodbye (spec 4.1); the server released everything. */
    BYE("the client closed the session", "net.close.bye"),

    /** Four unanswered pings - 2 s of silence, spec 4.1's death. */
    KEEPALIVE_TIMEOUT("the server stopped answering (keepalive timeout)",
            "net.close.keepaliveTimeout"),

    /** The socket closed: the server hung up, shut down, or the link died. */
    TRANSPORT_CLOSED("the connection to the server was closed",
            "net.close.transportClosed"),

    /** The transport itself failed - an I/O error under the WebSocket. */
    TRANSPORT_ERROR("the connection to the server failed", "net.close.transportError"),

    /** The {@code hello} of spec 1 was refused, so no session ever existed. */
    HANDSHAKE_REFUSED("the server refused the session", "net.close.handshakeRefused"),

    /** The server broke the wire contract: a binary frame this client cannot
     *  read (spec 5), or control text on a capture's data connection, where
     *  spec 4.7 allows nothing after the attach.  Either way its framing - and
     *  with it every stream of the session - is no longer to be trusted. */
    PROTOCOL_ERROR("the server broke the wire contract", "net.close.protocolError");

    /** The sentence written to the LOG.  English on purpose: a log line is read
     *  by whoever debugs the bench, not by the operator - what the operator sees
     *  comes from {@link #messageKey}. */
    @Getter
    private final String detail;
    /** The i18n key of the sentence the operator is shown when a connection
     *  error is surfaced.  A key rather than the text itself because this module
     *  is toolkit- and UI-free: the translation belongs to whichever front end
     *  displays it. */
    @Getter
    private final String messageKey;

    private NetCloseReason(String detail, String messageKey) {
        this.detail = detail;
        this.messageKey = messageKey;
    }
}
