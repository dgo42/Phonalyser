/*
 * Phonalyser web - why a net session ended.
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 *
 * Faithful port of org.edgo.audio.measure.net.client.NetCloseReason - a value, not a log line.
 *
 * It is typed because the ends differ in what the operator must be told and in what the
 * modules must do: an orderly BYE is the client's own doing and needs no message at all,
 * while a KEEPALIVE_TIMEOUT means the bench went silent mid-measurement (spec 4.1: "stop all
 * modules, show the connection error") and everything on the wire is now suspect.
 *
 * `detail` is the LOG sentence - English on purpose, read by whoever debugs the bench;
 * `messageKey` is the i18n key of what the OPERATOR is shown, resolved by the UI layer,
 * never here.
 */

/** @enum {{name: string, detail: string, messageKey: string}} */
export const NetCloseReason = Object.freeze({
  /** The client said goodbye (spec 4.1); the server released everything. */
  BYE: Object.freeze({
    name: 'BYE',
    detail: 'the client closed the session',
    messageKey: 'net.close.bye',
  }),
  /** Four unanswered pings - 2 s of silence, spec 4.1's death. */
  KEEPALIVE_TIMEOUT: Object.freeze({
    name: 'KEEPALIVE_TIMEOUT',
    detail: 'the server stopped answering (keepalive timeout)',
    messageKey: 'net.close.keepaliveTimeout',
  }),
  /** The socket closed: the server hung up, shut down, or the link died. */
  TRANSPORT_CLOSED: Object.freeze({
    name: 'TRANSPORT_CLOSED',
    detail: 'the connection to the server was closed',
    messageKey: 'net.close.transportClosed',
  }),
  /** The transport itself failed - an error under the WebSocket. */
  TRANSPORT_ERROR: Object.freeze({
    name: 'TRANSPORT_ERROR',
    detail: 'the connection to the server failed',
    messageKey: 'net.close.transportError',
  }),
  /** The `hello` of spec 1 was refused, so no session ever existed. */
  HANDSHAKE_REFUSED: Object.freeze({
    name: 'HANDSHAKE_REFUSED',
    detail: 'the server refused the session',
    messageKey: 'net.close.handshakeRefused',
  }),
  /** The server sent a binary frame this client cannot read (spec 5), so its framing - and
   *  with it every stream on the socket - is no longer to be trusted. */
  PROTOCOL_ERROR: Object.freeze({
    name: 'PROTOCOL_ERROR',
    detail: 'the server sent audio data this client cannot read',
    messageKey: 'net.close.protocolError',
  }),
});
