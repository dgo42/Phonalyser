/*
 * Phonalyser web - finding the servers a browser cannot hear.
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 *
 * Faithful port of org.edgo.audio.measure.net.client.ServerProber's HTTP half, extended with
 * the peer table of spec 2.2 - which is the web's ONLY discovery.
 *
 * WHY. The desktop hears the multicast beacon of spec 2.1; a browser has no socket that can,
 * and BeaconListener is therefore not portable at all. Spec 2.2 was written for exactly this
 * case: every server also LISTENS to the beacon group and keeps a peer table, so one typed
 * address is enough to see every server on the LAN - `GET /servers` returns the table, self
 * included. Discovery on the web is therefore: type one address once, probe it, remember what
 * it names.
 *
 * `GET /info` is the identity endpoint the Java prober polls for liveness: cheap, lock-free,
 * and the answer says WHICH server answered - a row that answers as a DIFFERENT serverId is a
 * reinstallation, and vouching for the old id would mark a ghost live.
 */
import { NetFields, NetProto } from './net-proto.js';
import { makeServerEntry } from './net-preferences.js';

/** Per-request budget. Short on purpose: this is a liveness poll, and the worst case runs once
 *  per row per round (Java ServerProber.TIMEOUT_MS). */
const TIMEOUT_MS = 600;

/** `GET /info`: identity and port, no hardware, no lock - the cheapest thing a server answers. */
const INFO_PATH = '/info';
/** `GET /servers`: the peer table of spec 2.2. */
const SERVERS_PATH = '/servers';

/**
 * Splits an operator-typed "host" or "host:port" into its two halves, defaulting the port to
 * the one number a server serves everything on. Bracketed IPv6 ("[::1]:8377") is honoured, and
 * a bare IPv6 literal keeps its colons - a colon is only a port separator when it is the LAST
 * one and what follows is a number.
 *
 * @param {string} typed @returns {?{host: string, port: number}} null when nothing was typed
 */
export function parseAddress(typed) {
  const text = (typed || '').trim();
  if (text === '') return null;
  if (text.startsWith('[')) {
    const end = text.indexOf(']');
    if (end < 0) return null;
    const host = text.substring(1, end);
    const rest = text.substring(end + 1);
    const port = rest.startsWith(':') ? Number(rest.substring(1)) : NetProto.DEFAULT_PORT;
    return host === '' || !(port > 0) ? null : { host, port };
  }
  // More than one colon and no brackets: a bare IPv6 literal. A port can only be appended to
  // a BRACKETED one, so "fe80::1" is an address, never host "fe80:" on port 1.
  if (text.indexOf(':') !== text.lastIndexOf(':')) return { host: text, port: NetProto.DEFAULT_PORT };
  const colon = text.lastIndexOf(':');
  if (colon > 0 && /^\d+$/.test(text.substring(colon + 1))) {
    const host = text.substring(0, colon);
    const port = Number(text.substring(colon + 1));
    return host === '' || !(port > 0) ? null : { host, port };
  }
  return { host: text, port: NetProto.DEFAULT_PORT };
}

/**
 * Is `host:port` the address this very page was served from?
 *
 * It decides the one case where the session may be encrypted: a bench reached at its own plain
 * `host:port` speaks `ws://`, but the server that SERVED an `https://` page is demonstrably
 * behind TLS, so its control channel is `wss://` on that same address.
 *
 * @param {string} host @param {number|string} port
 * @returns {boolean} false whenever there is no document to compare against
 */
export function isPageOrigin(host, port) {
  if (typeof location === 'undefined' || !location.hostname) return false;
  const pagePort = Number(location.port) || (location.protocol === 'https:' ? 443 : 80);
  return String(host) === location.hostname && Number(port) === pagePort;
}

/**
 * The URL a server's control channel is dialled on - spec 4: the upgrade lives at `/`, on the
 * same one port.
 *
 * `wss://` ONLY for the server that served an https page ({@link isPageOrigin}). A bench typed
 * in by address is a plain LAN host with no certificate, so `ws://` stays right for it - and an
 * https page cannot open that anyway, which is what the mixed-content explanation is for.
 *
 * @param {string} host @param {number} port
 * @returns {string}
 */
export function wsUrlOf(host, port) {
  const secure = typeof location !== 'undefined'
    && location.protocol === 'https:' && isPageOrigin(host, port);
  return `${secure ? 'wss' : 'ws'}://${host.includes(':') ? `[${host}]` : host}:${port}/`;
}

export class ServerProber {

  /**
   * @param {Object} [deps]
   * @param {(url: string, init: Object) => Promise<Response>} [deps.fetch] the HTTP seam,
   *        injected so a test answers without a socket (Java's prober takes its codec and a
   *        callback for the same reason). Default: the page's own fetch.
   */
  constructor({ fetch: fetchImpl } = {}) {
    this._fetch = fetchImpl || ((url, init) => fetch(url, init));
  }

  /**
   * One `GET /info` round trip - the server's identity, or null when the host does not answer,
   * answers something that is not a Phonalyser server, or answers past the budget.
   *
   * @param {string} host @param {number} port
   * @returns {Promise<?{serverId: string, name: string, app: string, proto: number, port: number}>}
   */
  async probeInfo(host, port) {
    return this._identity(
      await this._getJson(`http://${hostPart(host)}:${port}${INFO_PATH}`), host, port);
  }

  /**
   * The same question asked of THE PAGE'S OWN ADDRESS - is the place this app was served from a
   * Phonalyser server?
   *
   * It resolves `info` against the document base, so it inherits the page's SCHEME, host, port
   * and BASE PATH: `https://bench.example/app/` asks `https://bench.example/app/info`. That is
   * the whole point. The old code asked `probeInfo(location.hostname, port)`, which hardcodes
   * `http://` and a bare `/info` - so a server behind a TLS reverse proxy was asked
   * `http://host:443/info`, which is not where it lives, and the page concluded it had been
   * served by nobody and offered the local backends instead.
   *
   * @param {string} baseUri normally `document.baseURI`
   * @returns {Promise<?{serverId: string, name: string, app: string, proto: number, port: number}>}
   */
  async probeOrigin(baseUri) {
    let url;
    try {
      url = new URL('info', baseUri);
    } catch (e) {
      return null;                       // no usable base (file://, a test without a document)
    }
    const fallbackPort = Number(url.port) || (url.protocol === 'https:' ? 443 : 80);
    return this._identity(await this._getJson(url.href), url.hostname, fallbackPort);
  }

  /** The identity fields of an `/info` answer, or null when it is not a Phonalyser server's.
   *  Shared so the two probes above can never disagree about what a server IS. */
  _identity(info, host, port) {
    if (info == null) return null;
    const serverId = info[NetFields.SERVER_ID];
    if (typeof serverId !== 'string' || serverId === '') return null;   // not a Phonalyser server
    return {
      serverId,
      name: typeof info[NetFields.NAME] === 'string' ? info[NetFields.NAME] : host,
      app: info[NetFields.APP] || '',
      proto: Number(info[NetFields.PROTO]) || 0,
      // The server's OWN port answer wins over the one it was reached at: a reverse proxy can
      // put it on another number, and the peer table it serves carries the real one.
      port: Number(info[NetFields.PORT]) > 0 ? Number(info[NetFields.PORT]) : port,
    };
  }

  /**
   * The peer table of spec 2.2 as remembered-server entries - every server the probed one has
   * HEARD, self included. This is the whole of the web's LAN discovery: one reachable address
   * yields the rest.
   *
   * Entries are marked `manual: false` - they were discovered, not typed; the row the operator
   * typed is added by the caller as a manual one, so removing a discovered peer is not a
   * decision that has to survive.
   *
   * @param {string} host @param {number} port
   * @returns {Promise<Object[]>} the entries (empty when the host does not answer)
   */
  async probePeers(host, port) {
    const body = await this._getJson(`http://${hostPart(host)}:${port}${SERVERS_PATH}`);
    const rows = body && body[NetFields.SERVERS];
    if (!Array.isArray(rows)) return [];
    const out = [];
    for (const row of rows) {
      if (row == null || typeof row !== 'object') continue;
      const serverId = row[NetFields.SERVER_ID];
      const rowPort = Number(row[NetFields.PORT]);
      // The table's own `host` is the address the datagram came FROM (spec 2.2) - except for
      // the answering server's own row, which has no datagram: reach it back where we reached
      // it, not at whatever it believes its address is (a multi-homed host lies).
      const rowHost = row[NetFields.SELF] === true ? host : row[NetFields.HOST];
      if (typeof serverId !== 'string' || serverId === '') continue;
      if (typeof rowHost !== 'string' || rowHost === '' || !(rowPort > 0)) continue;
      out.push(makeServerEntry(serverId,
        typeof row[NetFields.NAME] === 'string' ? row[NetFields.NAME] : rowHost,
        rowHost, rowPort, false));
    }
    return out;
  }

  /** One bounded GET returning parsed JSON, or null for anything that is not a 2xx JSON answer
   *  - a refusal, a timeout, a cross-origin block, a body that will not parse. Never throws:
   *  a host that does not answer is an ordinary outcome here, not an error to handle. */
  async _getJson(url) {
    const abort = typeof AbortController === 'function' ? new AbortController() : null;
    const timer = abort ? setTimeout(() => abort.abort(), TIMEOUT_MS) : null;
    if (timer && typeof timer.unref === 'function') timer.unref();
    try {
      const response = await this._fetch(url, abort ? { signal: abort.signal } : {});
      if (!response || !response.ok) return null;
      return await response.json();
    } catch (e) {
      console.debug(`net discovery: ${url} did not answer: ${e && e.message}`);
      return null;
    } finally {
      if (timer) clearTimeout(timer);
    }
  }
}

/** An IPv6 literal needs its brackets back in a URL. */
function hostPart(host) {
  return host.includes(':') ? `[${host}]` : host;
}
