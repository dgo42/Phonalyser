/*
 * Phonalyser web — in-process publish/subscribe + request/response hub.
 *
 * Faithful port of org.edgo.audio.measure.gui.bus.MessageBus. Event names are the symbolic
 * constants in events.js — never a string literal at the call site, so renaming an event is a
 * single-file change and a publisher typo can't silently bypass every subscriber.
 *
 * Two flavours:
 *   • Pub/sub — every subscriber is a function. publish(name) delivers null; publish(name, payload)
 *     delivers the payload. One payload shape per event name.
 *   • Request/response — request(name[, payload]) returns a value from the single registered
 *     responder (registerResponder). Exactly one responder per name; a second registration
 *     replaces the first. Returns null when none is registered.
 *
 * Threading: JS is single-threaded and publish() dispatches synchronously on the caller — there is
 * no SWT Display.asyncExec analogue to worry about; workers already marshal via postMessage.
 * GNU Affero General Public License v3 or later.
 */
import { debug } from '../util/debug.js';

export class MessageBus {
  constructor() {
    /** @type {Map<string, Function[]>} handler list per event name. */
    this._subscribers = new Map();
    /** @type {Map<string, Function>} single responder per event name. */
    this._responders = new Map();
  }

  /** The process-wide shared bus (mirror MessageBus.instance()). */
  static instance() {
    if (!MessageBus._instance) MessageBus._instance = new MessageBus();
    return MessageBus._instance;
  }

  // ---- Pub / sub --------------------------------------------------------

  /** @param {string} eventName @param {Function} handler */
  subscribe(eventName, handler) { this._listFor(eventName).push(handler); }

  /** @param {string} eventName @param {Function} handler */
  unsubscribe(eventName, handler) {
    const list = this._subscribers.get(eventName);
    if (list) { const i = list.indexOf(handler); if (i >= 0) list.splice(i, 1); }
  }

  /** Dispatches synchronously to every subscriber (null payload for the no-arg form). A throwing
   *  subscriber is logged, never aborts the rest. Iterates a snapshot so a handler may
   *  subscribe/unsubscribe during dispatch (mirror of the Java CopyOnWriteArrayList). */
  publish(eventName, payload = null) {
    const list = this._subscribers.get(eventName);
    if (!list || list.length === 0) return;
    for (const handler of list.slice()) {
      try { handler(payload); }
      catch (ex) { debug(`bus: subscriber threw on ${eventName}:`, ex); }
    }
  }

  // ---- Request / response -----------------------------------------------

  /** Registers the single responder producing a value for every future request() on eventName,
   *  replacing any previous registration (with a warning) — registration is exclusive.
   *  @param {string} eventName @param {Function} responder */
  registerResponder(eventName, responder) {
    if (this._responders.has(eventName)) {
      debug(`bus: responder for ${eventName} was replaced; previous registration overridden`);
    }
    this._responders.set(eventName, responder);
  }

  /** @param {string} eventName */
  unregisterResponder(eventName) { this._responders.delete(eventName); }

  /** Invokes the registered responder (if any) and returns its result; null when no responder is
   *  registered or the responder threw (logged). @param {string} eventName */
  request(eventName, payload = null) {
    const responder = this._responders.get(eventName);
    if (!responder) return null;
    try { return responder(payload); }
    catch (ex) { debug(`bus: responder for ${eventName} threw:`, ex); return null; }
  }

  // ---- Internal ---------------------------------------------------------

  _listFor(eventName) {
    let list = this._subscribers.get(eventName);
    if (!list) { list = []; this._subscribers.set(eventName, list); }
    return list;
  }
}
