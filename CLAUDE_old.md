# Project rules

## 1. Generation discipline

1. Generate only the functionality that is asked.  No additional code; if you think something extra is helpful, ask first.
2. Always use Lombok (`@Getter`, `@Setter`, `@RequiredArgsConstructor`, `@EqualsAndHashCode`, …) instead of hand-written boilerplate.
3. Always use Log4j (`@Log4j2` + `log.info(...)`) for output; never `System.out.println`.

## 2. Design-driven development

**Before writing any non-trivial change, design first.**  Answer these four questions, then write the code:

1. **Ownership** — Which class owns each piece of state and behaviour?  If a class is reaching outside itself to do its job, that's a smell.  Push the work where the state lives.
2. **Dependency direction** — Which way do the references flow?  Aim for one-way coupling.  When two components both need to influence each other, the back-channel goes through the `MessageBus` as a broadcast — never as a stored `Runnable` callback or `setData("key", thing)` side-channel.
3. **Public surface** — What's the smallest constructor + method list that lets callers do what they need?  Anything that doesn't have a clear caller is dead weight.
4. **Lifecycle** — Who creates this, who disposes it, who unsubscribes from the bus?  Resources tied to a `Shell` register a dispose listener.  Bus subscribers store handler references in fields and unsubscribe symmetrically.

## 3. Architectural patterns this codebase uses

- **MVC-ish panes**: each pane owns its widgets and its corresponding controller / worker if any (e.g. `FftView` owns the analyser worker; `FftPane` is just chrome around it).  Widget creation happens in the component that uses the widget — not in the parent that hosts it.
- **Singletons with explicit registration**: `IconUtils.instance()`, `SharedCapture.instance()`, `MessageBus.instance()`.  Constructors are private; `instance()` is the only entry; shell-bound disposal goes through `registerShell(Shell)` so the singleton's cache clears on teardown.
- **Publish / subscribe via `MessageBus`** (see the `bus` subpackage):
  - Event names live in `Events.java` as constants — never write a string literal at a publish / subscribe call site.
  - Three flavours: notification (`publish(String)` / `subscribe(String, Runnable)`), payload (`publish(String, T)` / `subscribe(String, Consumer<T>)`), request / response (`registerResponder` + `request`).
  - For cross-component notifications, **prefer the bus over constructor-injected callbacks**.  Constructor `Runnable` / `Consumer` parameters for fire-and-forget events are the OLD pattern; convert when you see one.
- **Proper SWT widgets, not static factories**: a "widget" with multiple instances and per-instance state subclasses `Canvas` / `Composite` / etc. and uses a real constructor.  `XxxUtils.install(parent, …)` returning the widget while stashing it via `setData("key", instance)` is an anti-pattern — refuse to write that style.

## 4. Things that are smells (refuse, don't propose)

- Static-factory + `setData` side-channels (see above).
- Stored `Runnable` callback fields for cross-component notification when the `MessageBus` already exists.
- Hand-written `equals` / `hashCode` (use `@EqualsAndHashCode`).
- Inline `/*paramName*/ value` comments at call sites (the IDE shows the hint automatically).
- Single-line wrapper methods that just forward to one other call.
- Fully-qualified class names written inline in code instead of an `import` (the `no-fqcn.ps1` hook in `.claude/hooks/` enforces this on every edit).
- Dead code "for future use" — delete it; git remembers.
- Two-way coupling between a pane and its child view.  Pane → view is direct method calls; view → pane is `MessageBus` broadcasts only.

## 5. Project memory

Read [.claude/memory/MEMORY.md](.claude/memory/MEMORY.md) at the start of every session.  It indexes per-topic notes under `.claude/memory/` covering:

- **Conventions / feedback** — style rules the user has asked me to follow (imports over FQCN, no inline parameter-name comments, architecture-first design, …).  These are non-negotiable; treat each note as "I've already corrected this once; don't make me do it again."
- **Project facts** — non-obvious things about this codebase (ADC channel, audio-backend quirks, performance baselines, current focus).

These notes live in the project tree (not in per-user `~/.claude` storage) so they ride with the codebase and apply consistently across machines and sessions.

Before any non-trivial change, scan `MEMORY.md` for a relevant entry — particularly the `feedback_*` notes about architecture, encapsulation, and the publish / subscribe pattern.
