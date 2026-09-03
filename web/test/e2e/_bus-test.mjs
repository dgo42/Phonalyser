// Smoke test for the MessageBus + Events backbone (step 1). Temp (_-prefixed).
import { MessageBus } from '../../js/bus/message-bus.js';
import { Events, PaneId, paneTitleClick } from '../../js/bus/events.js';

let fails = 0;
const ok = (cond, msg) => { if (!cond) { console.log('FAIL: ' + msg); fails++; } };

const bus = new MessageBus();

// publish with no subscribers is a no-op (no throw)
bus.publish(Events.FFT_LENGTH_CHANGED);
bus.publish('nobody.listening', { x: 1 });

// subscribe + publish delivers the payload; multiple subscribers all fire
let a = null, b = null;
bus.subscribe(Events.GENERATOR_FREQ_TRIM, (hz) => { a = hz; });
bus.subscribe(Events.GENERATOR_FREQ_TRIM, (hz) => { b = hz; });
bus.publish(Events.GENERATOR_FREQ_TRIM, 1000.5);
ok(a === 1000.5 && b === 1000.5, 'both subscribers receive payload');

// unsubscribe stops delivery
bus.unsubscribe(Events.GENERATOR_FREQ_TRIM, null); // no-op for unknown handler
const h = () => { throw new Error('should not fire'); };
bus.subscribe(Events.SCOPE_AUTO_SETUP, h);
bus.unsubscribe(Events.SCOPE_AUTO_SETUP, h);
bus.publish(Events.SCOPE_AUTO_SETUP); // h removed -> no throw

// no-arg publish delivers null
let got = 'unset';
bus.subscribe(Events.FFT_RANGE_CHANGED, (p) => { got = p; });
bus.publish(Events.FFT_RANGE_CHANGED);
ok(got === null, 'no-arg publish delivers null');

// request returns the single responder's value; payload passes through
bus.registerResponder(Events.GENERATOR_EMITTED_HZ, () => 'buffer');
ok(bus.request(Events.GENERATOR_EMITTED_HZ) === 'buffer', 'request returns responder value');
bus.registerResponder(Events.GENERATOR_RUNNING, (cause) => cause === 'x');
ok(bus.request(Events.GENERATOR_RUNNING, 'x') === true, 'responder receives payload');

// second register replaces the first
bus.registerResponder(Events.GENERATOR_EMITTED_HZ, () => 'replaced');
ok(bus.request(Events.GENERATOR_EMITTED_HZ) === 'replaced', 'second responder replaces first');

// request with no responder returns null; throwing responder returns null
ok(bus.request('no.responder') === null, 'request with no responder is null');
bus.registerResponder('boom', () => { throw new Error('x'); });
ok(bus.request('boom') === null, 'throwing responder returns null');

// throwing subscriber is isolated (later subscribers still fire)
let reached = false;
bus.subscribe(Events.FILE_PLAY_STOPPED, () => { throw new Error('x'); });
bus.subscribe(Events.FILE_PLAY_STOPPED, () => { reached = true; });
bus.publish(Events.FILE_PLAY_STOPPED);
ok(reached, 'throwing subscriber does not abort the rest');

// shared singleton + helpers
ok(MessageBus.instance() === MessageBus.instance(), 'instance() is a singleton');
ok(paneTitleClick(PaneId.FFT) === 'paneTitle.click.3', 'paneTitleClick builds the name');
ok(Object.isFrozen(Events), 'Events is frozen');

console.log(fails === 0 ? 'bus: ALL PASSED' : `bus: ${fails} FAILED`);
process.exit(fails === 0 ? 0 : 1);
