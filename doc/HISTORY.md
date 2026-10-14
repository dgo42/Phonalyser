# How Phonalyser came to be

Phonalyser began in April 2026 as a private tool for one question: how
much distortion does an audio converter really add, measured below the
level where commercial analysers stop being honest? Six months later it is
a measurement workbench with an FFT analyser, an oscilloscope, a frequency
response module, a signal generator, a notch-tuning aid, a network server
and a browser port. Its own digital predistortion pushes a DAC's harmonic
distortion below one part in a hundred million. This essay tells how it
got there. It follows the main road and the turns that mattered, and it
names the dead ends, because they shaped the result as much as the ideas
that survived.

## The first question

It began with a measurement bench that was not good enough. Its own THD
was about 0.000046 %, and its measuring capability of about 0.000080 %
fell short of the goal: to measure THD with confidence down to one
millionth of a percent.

The first idea was a well-known one, the statistical calibration of an
ADC. The method is normally applied to converters of 12 to 16 bits, far
below the resolution of an audio ADC, but it was worth a try. Behind it
stood a chicken-and-egg problem that has never quite gone away: how to
tell apart the nonlinearity of the DAC, of its output amplifier, of the
ADC's input amplifier and of the ADC itself. With no clear road ahead,
the work started where it could start, with measuring the ADC's integral
and differential nonlinearity, INL and DNL.

The first tool was a statistics mode for the ADC. A sine generated in
REW (Room EQ Wizard) was recorded to a WAV file and analysed, and the
result was an INL histogram. To test it, a second tool turned the raw
recording into a corrected one, and REW measured its spectrum. The
correction worked, but only a little: THD fell from 0.000046 % to
0.000041 %, a gain of about 11 %. That was not enough, and every attempt
took many slow manual steps.

A sawtooth looked like a better test signal than a sine. The first one
came from another application, and then the project got its own DDS
generator with several waveforms and a Taylor-series correction for the
sine. The work was now a chain of separate runs: generate the signal in
one process, capture it in another, analyse the saved file, calculate
the correction table, then generate again and capture with the table
applied.

REW's spectrum soon showed that this was not enough either. The project
needed its own FFT, one that could apply the correction table and
resolve harmonics down to 1e-8 %. As the manual routine grew tiresome
again, the pieces were combined: the generator ran in one process, and
the other captured with the table applied and fed a coherent FFT at
once, saving pictures and CSV files. Still, every result meant waiting
until the capture had finished.

That was the moment the next step became clear. The work needed a
graphical application in which several modules run in parallel in one
process, everything can be changed on the fly, and the FFT average can
be watched as it grows.

## A tool becomes a program

The first weeks happened outside version control. When the code was
imported into git on 19 May it was already a working analyser, and the
first days went into making it a program rather than an experiment:
three rounds of refactoring, a DAC calibration, a continuous-integration
build for every platform, and a frequency response tab. The commits of
those weeks are milestones with numbers instead of descriptions. Ideas
came faster than they could be named. Coherent averaging, dual-tone and
multitone measurement, a reworked capture path and a frequency-locked
loop all arrived in that rush. The loop, the FLL, trims the generator so
that its tone sits exactly on an FFT bin.

In June the question changed from "does it measure?" to "can it be
trusted and maintained?". The preferences were rebuilt on two-way
binding. A large rework then gave every module its own controller, which
owns that module's engines and state, and moved collaborators to
constructor injection. Concurrency and performance reviews followed. One
of them made the analyser about twice as fast on a million-point FFT. The
generator got a 64-bit phase accumulator, so long runs no longer drift by
rounding. In the middle of the month the first DAC predistortion wizard
appeared. It is a closed loop that measures the converter's harmonics and
feeds anti-phase copies back into the generator. A macOS port followed
days later, and on 19 June the first public release candidate shipped.

## The instrument grows up

The summer releases turned a capable analyser into an instrument. The
oscilloscope moved to the GPU and gained digital-phosphor persistence, a
precise pan and zoom model, rubber-band zoom and a glitch trigger. A
residual view subtracts the best-fit tone, so that whatever hides under
the fundamental becomes visible. A notch-tuning wizard arrived for passive
twin-T filters, the analog notch that removes the fundamental before the
ADC so the harmonics can be measured with the converter's full range. It
uses a looping log sweep whose period is a power of two, so it needs no
trigger. An earlier plan to tune the notch with full Farina sweeps was
dropped as overkill. Version 1.0.3 also brought the first browser port.

Calibration was the deeper change of July. Full scale had been an
application setting. It became a property of the physical card: each card
carries a list of device names to recognise it on any backend, and a
range table with one row per attenuator position. Soon each stereo
channel had its own full scale. A backend for the QuantAsylum QA402 and
QA403 analysers arrived, driven directly over USB. The generator's dither
became a quantity entered in dBV that can be read straight off the FFT
noise floor. Two corrections of measurement theory came from checking
numbers against references: noise integrals had to be divided by the
window's equivalent noise bandwidth, and weighted readouts had to follow
IEC 61672. Version 1.1.0 closed this phase on 21 July.

## Measuring from elsewhere

In late July the desktop application was split into Maven modules, and on
that base the largest feature so far was built: a headless Phonalyser
server. It serves the audio hardware of the machine wired to the
measurement bench to other installations on the network. It handles
several clients with per-device locks, finds clients by multicast, and
runs the generator on the server, so that a frequency response sweep is
deconvolved against exactly what was played. The desktop and the browser
both learned to measure through such a server, and version 1.2.0 shipped
on 7 August.

The two weeks after the release were about truthfulness. Every platform
had to report what its devices really accept: real format lists from the
macOS audio layer and from Linux hardware parameters, exact bit depths,
hardware volume pinned to the device's own 0 dB while a stream is open.
The question of who owns a calibration was settled in favour of the
server: a remote device's card and calibration live there, and clients
read them. A digital loopback backend gave both applications a reference
with no instrument in the path. Its dither floor matched the formula to
hundredths of a dB. Version 1.2.1 followed on 16 August.

The dead ends of this phase were mostly attempts to be clever. Sending
calibrated volts over the wire would have been a round trip of multiply
and divide, and it did not fit the desktop's integer sample lane, so
normalised samples plus the server's calibration values stayed. Rescuing
lost WASAPI events under a virtual machine raced the DMA buffer and was
rolled back. Advertising every sample rate on the Mac, and forcing its
volume to unity, both gave wrong answers and were replaced by asking the
hardware. One long glitch hunt on a Mac ended outside the code entirely:
the card tears its captures inside macOS itself, and Apple's own
recorder shows the same tears.

## Chasing parts per billion

From late August the project's centre of gravity moved to the bench: a
high-quality DAC, a twin-T notch, a buffer and a 384 kHz ADC, averaged
over millions of FFT points. The aim was to see what the DAC's
predistortion could really achieve, and every improvement exposed the
next weakest link.

The first campaign tried to model the bench. The wizard measured the
"plant", the phase and gain the chain applies to each harmonic, before
correcting. The probe produced a phase rotation per harmonic that later
proved to be an artefact of how it was constructed, and the plant
measurement was removed within days. What stayed was a real fix found
along the way: dual-tone corrections had been applied at half strength.
A bank of Goertzel filters, tried as an alternative readout, was dropped
too. Its mathematics was right, but integrating it into the live analysis
was not, and the project settled on one estimator per quantity.

Other work of this phase lasted. A random overlap, a hop between analysis
windows that cycles through a lattice, removed the periodic texture a
fixed hop leaves on a deeply averaged noise floor. The coherent average,
the vector average of many FFT frames, learned to rotate each tone's
region by that tone's own phase. SINAD, THD+N and ENOB were brought to
the standard converter definitions. The browser port was rebuilt to
mirror the desktop's structure, with one event bus and no stored
callbacks. In September the generator gained an amplitude per channel,
calibration moved fully into the device cards, and a fundamental phase
alignment arrived. It reads the tone a second time through a reference
channel, because behind a drifting notch the fundamental's phase wanders
while its harmonics hold still. An FLL that follows that wander spreads it
onto each harmonic in proportion to its order, and a long average then
buries the harmonics instead of lifting them out of the noise.

October brought the hardest problem: the residual second harmonic jumped
between levels from one collection to the next. Many suspects were
examined and cleared. Temperature explained the slow drift but not the
steps. The buffer amplifier was cleared by arithmetic, by biasing it into
class A, and finally by removing it. Dither, the size of the notch
residual and cold connector contacts were each checked and dropped. A
pilot-tone alignment was built, and then turned out never to have rotated
the average at all, because a different mechanism had been doing that
work. That discovery led to one rule for both alignment modes and then to
a rebuild of the whole averaging code into a single accumulator. An
overnight run with a plain, uncorrected sine settled the question of
blame: the software reproduced its corrections to within a fraction of a
percent, while the DAC's own second harmonic wandered by several percent
over hours.

The last big change was to the predistortion itself. Its rounds, global
cycles of averaging and correcting, could not be sized well: every rule
for ending a round made rounds either far too long or far too short.
Simulations showed why. The weakest harmonic decides how long a run
takes, so global rounds waste averages on lines that are already
resolved. The design that replaced them is one endless run. Each
harmonic or intermodulation line is corrected the moment it stands 5 dB
above its local noise band, and only that line's part of the average
restarts. A Kalman filter for pooling corrections was simulated as well
and dropped, because it gained nothing under these rules. The first bench
run of the new engine reached about 0.7e-6 % THD, roughly -163 dB, after
4000 averages.

## How the work was done

The method changed as much as the code. In May, commits were milestones
and the work happened in long single sessions. By June the work ran
through structured reviews, and by August through orchestration. A
written contract went to a persistent implementer agent, an adversarial
verifier agent checked the result against the code, and only then did a
change count as done. Each failure of that process left a rule behind.
An agent that reported a test it had never written led to the rule that
every claim is verified in the code. An agent that wiped another's edits
with a git checkout led to a ban on destructive git commands for agents.
Single sessions that kept making the same design mistakes led to a design
gate before any Java is written: name the owner of the state, inject
collaborators, avoid static methods, and search for existing code first.
Hooks now enforce what discipline could not, from ASCII-only text to the
ban on shell file access.

The bench taught a parallel lesson about evidence. Several promising
explanations of the October measurements, from DAC temperature to
short-term wander, were withdrawn once a cleaner measurement contradicted
them. Reading the code that fixes an outcome replaced running a test
whose result that code already determined. A finding from a log, a
summary or an agent became a lead to be checked, not a fact.

## Where it stands

Phonalyser is at version 1.2.2 in preparation. The predistortion engine
with per-line restarts works and is verified, and on the bench it
reaches below one part in a hundred million. The limit now lies in the
DAC: its second harmonic depends on temperature, and a thermostat for the
converter is being designed. On the software side, the noise readouts
still take their noise from the coherent average, which makes SNR, THD+N
and ENOB look far better than they are. A hidden power average beside the
coherent one is planned to fix that. Remote predistortion, the remaining
browser sync and the release itself are next.
