/*
 * Phonalyser web - JSON Schema for the device-profile store.
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 *
 * Describes the document DeviceProfileStore.configDocument() writes: the two version
 * markers, the card profiles and the device-to-card bindings. The JSON configuration
 * editor hands this schema to CodeMirror, so every `description` below is the hover help
 * an operator reads while editing - it explains what the key means and what its values
 * do, not merely what it is called. Derived from the store's own reader and writer
 * (_readProfile / _writeProfile) and from the account of the store in the help page
 * Preferences ▸ Audio ▸ Card.
 */

/** JSON Schema (draft 2020-12) for the device-profile store document. */
export const DEVICES_SCHEMA = {
  $schema: 'https://json-schema.org/draft/2020-12/schema',
  title: 'Phonalyser device store',
  description:
    "Every full-scale calibration the application knows, held as one JSON document: a card "
    + "profile per physical sound card or analyser, and the device names each card answers to. "
    + "Full scale belongs to the card rather than to the back end or to whatever name a driver "
    + "reports, so one calibration follows the card however the device is spelled. The document "
    + "is read once at start-up and rewritten whenever the application saves; unknown keys and "
    + "unusable entries are skipped with a console warning rather than failing the load.",
  type: 'object',
  additionalProperties: true,
  properties: {
    formatVersion: {
      type: 'integer',
      description:
        "Marker for the layout of this document, not for its contents. It changes only if the "
        + "schema itself changes, and the application supplies it from the bundled catalogue; "
        + "leave it as written.",
    },
    contentVersion: {
      type: 'integer',
      description:
        "Generation of the bundled device catalogue this store has already absorbed. When a "
        + "release ships a higher number the seed merge runs once - adding cards the release "
        + "introduced and refreshing uncalibrated rows to its nominal values - then records the "
        + "new number so it never repeats. Your calibrated rows, your own cards and your "
        + "selections are never touched by it.",
    },
    audioDevices: {
      type: 'array',
      description:
        "The card profiles, in the order they are held. Each entry is one physical card: its "
        + "logical name, the device names it recognises, and a range table with a measured full "
        + "scale per channel for each direction it is calibrated for.",
      items: {
        title: 'Card profile',
        description:
          "One physical card. It carries the calibration that the dBV axis, the oscilloscope "
          + "voltage readouts and the generator output level are all computed from, so a card "
          + "reached under two different back ends is measured once and used twice.",
        type: 'object',
        additionalProperties: true,
        required: ['name'],
        properties: {
          name: {
            type: 'string',
            description:
              "The logical name of the card, as the Card combo shows it and as a `bindings` "
              + "entry refers to it. It must be non-empty and unique; an entry without a usable "
              + "name is skipped when the store is read.",
          },
          match: {
            type: 'array',
            description:
              "The device names this card recognises. Each entry is matched as a "
              + "case-insensitive substring of the device name shown in the device list, and "
              + "when several cards could match, the longest matching entry wins. This is the "
              + "guess used when nobody has said which card a device is; an explicit `bindings` "
              + "entry always beats it. Omitted when the list is empty.",
            items: {
              type: 'string',
              description:
                "A device name or a fragment of one - a fragment such as Cosmos ADC catches "
                + "both the bare name and the wrapped form the browser reports.",
            },
          },
          input: {
            title: 'Input endpoint',
            description:
              "The recording direction of this card: how its capture channels are calibrated, "
              + "which switchable positions they have and which position is selected. Left out "
              + "entirely for a card that provides no input, or whose input has no range rows.",
            type: 'object',
            properties: {
              channels: {
                type: 'string',
                enum: ['MONO', 'LINKED', 'INDEPENDENT'],
                description:
                  "How the two channels of this direction are calibrated. MONO is a single "
                  + "physical channel whose full scale serves both sides; LINKED is a stereo "
                  + "pair that switches range together but keeps its own full scale per "
                  + "channel; INDEPENDENT lets each channel sit on its own range with its own "
                  + "full scale. Taken as LINKED when the key is absent or unrecognised.",
              },
              calibrationFromDevice: {
                type: 'boolean',
                description:
                  "Set true when the instrument itself reports its full scale (a QA40x-class "
                  + "device). Its ranges stay switchable, but the application never writes a "
                  + "calibration into them and a catalogue upgrade never supplies their values - "
                  + "the numbers come from the device. Omit it for an ordinary card you "
                  + "calibrate yourself; it is written out only when true.",
              },
              ranges: {
                type: 'array',
                description:
                  "One row per switchable front-end position - attenuator step, input-gain step "
                  + "or DIP setting - each with the full scale it reaches. A direction with no "
                  + "rows is not written at all.",
                items: {
                  title: 'Range row',
                  description:
                    "One front-end position and the voltage it calibrates to. Rows a release "
                    + "adds appear here at the next upgrade; rows you add by hand are kept.",
                  type: 'object',
                  required: ['label'],
                  properties: {
                    label: {
                      type: 'string',
                      description:
                        "The key of the row, and the text the ranges table and the range radios "
                        + "show - the switch position as the hardware names it, such as 1.7V or "
                        + "0 dBV. `activeRange` selects a row by this exact string, so renaming "
                        + "one here means renaming it there too. A row without a label is "
                        + "skipped when the store is read.",
                    },
                    fsVrms: {
                      type: ['object', 'number'],
                      description:
                        "The full scale of this row in volts RMS - the input voltage that reads "
                        + "as digital full scale (±1.0), and therefore what fixes the absolute "
                        + "dBV axis, the voltage readouts and the generator output level. The "
                        + "application always writes the { left, right } pair, since no two "
                        + "physical channels calibrate exactly alike.",
                      oneOf: [
                        {
                          title: 'Per-channel pair',
                          description:
                            "The form the application writes: one measured full scale for each "
                            + "physical channel.",
                          type: 'object',
                          properties: {
                            left: {
                              type: 'number',
                              description:
                                'Full scale of the left channel, in volts RMS.',
                            },
                            right: {
                              type: 'number',
                              description:
                                'Full scale of the right channel, in volts RMS.',
                            },
                          },
                        },
                        {
                          title: 'Shorthand',
                          description:
                            "A single full scale in volts RMS applied to both channels - "
                            + "accepted when typed by hand, never written by the application.",
                          type: 'number',
                        },
                      ],
                    },
                    calibrated: {
                      type: 'boolean',
                      description:
                        "True on a row that was actually measured through a Calibrate flow. The "
                        + "once-per-release seed merge refreshes an uncalibrated row to the "
                        + "nominal the release ships, but never overwrites a calibrated one. A "
                        + "row without the flag is still the seeded nominal; the flag is written "
                        + "out only when true.",
                    },
                    displayLabel: {
                      type: 'string',
                      description:
                        "Verbose display text for the ranges table when it differs from the "
                        + "`label` key - a QA40x input row spells out its dBFS and dBV "
                        + "translation while the key stays plain. READ but never written by this "
                        + "application: it arrives with the rows of a card on a bench across the "
                        + "network, so a locally saved store never carries it and typing one "
                        + "here does not survive the next save.",
                    },
                  },
                },
              },
              activeRange: {
                type: ['string', 'object'],
                description:
                  "Which row is currently selected. LINKED and MONO name a single row, because "
                  + "both channels switch together; INDEPENDENT gives each channel its own row. "
                  + "A name that matches no row leaves the selection unresolved, so it must be "
                  + "one of the `label` values above.",
                oneOf: [
                  {
                    title: 'Single selection (LINKED / MONO)',
                    description:
                      "The `label` of the one selected row, used by both channels.",
                    type: 'string',
                  },
                  {
                    title: 'Per-channel selection (INDEPENDENT)',
                    description:
                      "The selected row of each channel, when the two channels switch "
                      + "separately.",
                    type: 'object',
                    properties: {
                      left: {
                        type: 'string',
                        description:
                          "The `label` of the row the left channel sits on.",
                      },
                      right: {
                        type: 'string',
                        description:
                          "The `label` of the row the right channel sits on; when it is missing, "
                          + "the left channel row is used for both.",
                      },
                    },
                  },
                ],
              },
            },
          },
          output: {
            title: 'Output endpoint',
            description:
              "The playback direction of this card - the same shape as `input`, describing how "
              + "its generator channels are calibrated and which range they drive. Left out "
              + "entirely for a card that provides no output, or whose output has no range rows.",
            type: 'object',
            properties: {
              channels: {
                type: 'string',
                enum: ['MONO', 'LINKED', 'INDEPENDENT'],
                description:
                  "How the two channels of this direction are calibrated. MONO is a single "
                  + "physical channel whose full scale serves both sides; LINKED is a stereo "
                  + "pair that switches range together but keeps its own full scale per "
                  + "channel; INDEPENDENT lets each channel sit on its own range with its own "
                  + "full scale. Taken as LINKED when the key is absent or unrecognised.",
              },
              calibrationFromDevice: {
                type: 'boolean',
                description:
                  "Set true when the instrument itself reports its full scale (a QA40x-class "
                  + "device). Its ranges stay switchable, but the application never writes a "
                  + "calibration into them and a catalogue upgrade never supplies their values - "
                  + "the numbers come from the device. Omit it for an ordinary card you "
                  + "calibrate yourself; it is written out only when true.",
              },
              ranges: {
                type: 'array',
                description:
                  "One row per switchable output position - attenuator or level step - each "
                  + "with the full scale it reaches. A direction with no rows is not written at "
                  + "all.",
                items: {
                  title: 'Range row',
                  description:
                    "One output position and the voltage it calibrates to. Rows a release adds "
                    + "appear here at the next upgrade; rows you add by hand are kept.",
                  type: 'object',
                  required: ['label'],
                  properties: {
                    label: {
                      type: 'string',
                      description:
                        "The key of the row, and the text the ranges table and the range radios "
                        + "show - the switch position as the hardware names it. `activeRange` "
                        + "selects a row by this exact string, so renaming one here means "
                        + "renaming it there too. A row without a label is skipped when the "
                        + "store is read.",
                    },
                    fsVrms: {
                      type: ['object', 'number'],
                      description:
                        "The full scale of this row in volts RMS - the voltage the card puts "
                        + "out at digital full scale (±1.0), which is what turns a requested "
                        + "generator level into a sample amplitude. The application always "
                        + "writes the { left, right } pair, since no two physical channels "
                        + "calibrate exactly alike.",
                      oneOf: [
                        {
                          title: 'Per-channel pair',
                          description:
                            "The form the application writes: one measured full scale for each "
                            + "physical channel.",
                          type: 'object',
                          properties: {
                            left: {
                              type: 'number',
                              description:
                                'Full scale of the left channel, in volts RMS.',
                            },
                            right: {
                              type: 'number',
                              description:
                                'Full scale of the right channel, in volts RMS.',
                            },
                          },
                        },
                        {
                          title: 'Shorthand',
                          description:
                            "A single full scale in volts RMS applied to both channels - "
                            + "accepted when typed by hand, never written by the application.",
                          type: 'number',
                        },
                      ],
                    },
                    calibrated: {
                      type: 'boolean',
                      description:
                        "True on a row that was actually measured through a Calibrate flow. The "
                        + "once-per-release seed merge refreshes an uncalibrated row to the "
                        + "nominal the release ships, but never overwrites a calibrated one. A "
                        + "row without the flag is still the seeded nominal; the flag is written "
                        + "out only when true.",
                    },
                    displayLabel: {
                      type: 'string',
                      description:
                        "Verbose display text for the ranges table when it differs from the "
                        + "`label` key. READ but never written by this application: it arrives "
                        + "with the rows of a card on a bench across the network, so a locally "
                        + "saved store never carries it and typing one here does not survive the "
                        + "next save.",
                    },
                  },
                },
              },
              activeRange: {
                type: ['string', 'object'],
                description:
                  "Which row is currently selected. LINKED and MONO name a single row, because "
                  + "both channels switch together; INDEPENDENT gives each channel its own row. "
                  + "A name that matches no row leaves the selection unresolved, so it must be "
                  + "one of the `label` values above.",
                oneOf: [
                  {
                    title: 'Single selection (LINKED / MONO)',
                    description:
                      "The `label` of the one selected row, used by both channels.",
                    type: 'string',
                  },
                  {
                    title: 'Per-channel selection (INDEPENDENT)',
                    description:
                      "The selected row of each channel, when the two channels switch "
                      + "separately.",
                    type: 'object',
                    properties: {
                      left: {
                        type: 'string',
                        description:
                          "The `label` of the row the left channel sits on.",
                      },
                      right: {
                        type: 'string',
                        description:
                          "The `label` of the row the right channel sits on; when it is missing, "
                          + "the left channel row is used for both.",
                      },
                    },
                  },
                ],
              },
            },
          },
        },
      },
    },
    bindings: {
      type: 'object',
      description:
        "The device-to-card choices actually made in the dialog: device name to card name. A "
        + "binding is consulted BEFORE `match`, so a card that merely recognises part of a "
        + "device name can never overrule the card that was chosen - which is what settled two "
        + "interfaces whose names share a word. A binding naming a card that has since been "
        + "deleted or renamed is not an error: the device falls through to ordinary matching, so "
        + "a stale entry can never leave a device uncalibrated. The block is left out of the "
        + "document entirely while nothing is bound.",
      propertyNames: {
        type: 'string',
        description:
          "The device name exactly as the device list shows it. A device on a bench across the "
          + "network is keyed as the bench identifier, a slash and the device name, so two "
          + "benches with identically named analysers stay apart.",
      },
      additionalProperties: {
        type: 'string',
        description:
          "The `name` of the card profile this device resolves to, whatever any `match` entry "
          + "would otherwise say.",
      },
    },
  },
};
