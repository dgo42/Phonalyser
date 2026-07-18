/*
 * Phonalyser web — the card create / edit dialog (Java CardEditorDialog).
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 *
 * The DOM half of gui/preferences/CardEditorDialog: name, direction (input / output / both),
 * card-level Mono / Stereo, the one match list, and per-direction Linked / Independent coupling.
 * Every decision (capability derivation, name validation, drop-calibrated detection, match
 * parsing, the OK profile assembly) is delegated to store/card-editor-logic.js so it stays
 * testable; this module owns only the widgets + i18n + the modal lifecycle. open() resolves the
 * assembled profile on OK, or null on Cancel / close (CardEditorDialog.open).
 */
import { t } from '../i18n/i18n.js';
import { DeviceChannelMode } from '../store/device-enums.js';
import {
  Capability, seedIsMono, nameTaken, droppingCalibrated, parseMatch, assembleProfile,
} from '../store/card-editor-logic.js';

export class CardEditorDialog {
  /**
   * @param deps {showConfirm} — the shared confirm modal (title, message) => Promise<boolean>,
   *   used for the drop-calibrated-ranges warning on a direction reduction.
   */
  constructor({ showConfirm }) {
    this._showConfirm = showConfirm;
  }

  _modal() {
    return window.bootstrap.Modal.getOrCreateInstance(document.getElementById('cardEditorModal'));
  }

  _couplingOf(sel) {
    return $(sel).val() === 'INDEPENDENT' ? DeviceChannelMode.INDEPENDENT : DeviceChannelMode.LINKED;
  }

  /** Enables each direction's detail group only when that direction is on; the coupling combo is
   *  further gated on Stereo (CardEditorDialog.updateEnablement). */
  _updateEnablement() {
    const stereo = $('#cardChStereo').is(':checked');
    const inputOn = !$('#cardDirOutput').is(':checked');
    const outputOn = !$('#cardDirInput').is(':checked');
    $('#cardInputDetail').prop('disabled', !inputOn);
    $('#cardOutputDetail').prop('disabled', !outputOn);
    $('#cardInputCoupling').prop('disabled', !(inputOn && stereo));
    $('#cardOutputCoupling').prop('disabled', !(outputOn && stereo));
  }

  /**
   * Opens the dialog on {@code seed}, never mutating it. Resolves the assembled profile on OK,
   * or null on Cancel / close.
   * @param {import('../store/device-profiles.js').AudioDeviceProfile} seed
   * @param {string} initial the direction radios' initial selection (Capability token).
   * @param {string[]} existingNames every current card's name (uniqueness check).
   * @param {?string} originalName the name being edited (null for a create).
   * @param {number} inputSeedFs full-scale (V RMS) seeded into a newly enabled input range.
   * @param {number} outputSeedFs full-scale (V RMS) seeded into a newly enabled output range.
   * @returns {Promise<?import('../store/device-profiles.js').AudioDeviceProfile>}
   */
  open(seed, initial, existingNames, originalName, inputSeedFs, outputSeedFs) {
    return new Promise((resolve) => {
      const editing = originalName != null;
      $('#cardEditorTitle').text(t(editing ? 'preferences.audio.card.edit' : 'preferences.audio.card.new'));
      $('#cardName').val(seed.name != null ? seed.name : '');
      $('#cardDirInput').prop('checked', initial === Capability.INPUT_ONLY);
      $('#cardDirOutput').prop('checked', initial === Capability.OUTPUT_ONLY);
      $('#cardDirBoth').prop('checked', initial === Capability.BOTH);
      const mono = seedIsMono(seed);
      $('#cardChMono').prop('checked', mono);
      $('#cardChStereo').prop('checked', !mono);
      $('#cardMatch').val(seed.match.join('\n'));
      $('#cardInputCoupling').val(seed.input.channels === DeviceChannelMode.INDEPENDENT ? 'INDEPENDENT' : 'LINKED');
      $('#cardOutputCoupling').val(seed.output.channels === DeviceChannelMode.INDEPENDENT ? 'INDEPENDENT' : 'LINKED');
      $('#cardEditorError').addClass('d-none');
      this._updateEnablement();

      const el = document.getElementById('cardEditorModal');
      let result = null;
      const showErr = (key) => $('#cardEditorError').text(t(key)).removeClass('d-none');
      const onEnable = () => this._updateEnablement();
      const radios = '#cardDirInput, #cardDirOutput, #cardDirBoth, #cardChMono, #cardChStereo';

      const onOk = async () => {
        const name = String($('#cardName').val()).trim();
        if (name === '') { showErr('preferences.audio.card.error.empty'); return; }
        if (nameTaken(name, existingNames, originalName)) { showErr('preferences.audio.card.error.duplicate'); return; }
        const wantInput = !$('#cardDirOutput').is(':checked');
        const wantOutput = !$('#cardDirInput').is(':checked');
        if (droppingCalibrated(seed.input, wantInput) || droppingCalibrated(seed.output, wantOutput)) {
          const yes = await this._showConfirm(
            t('preferences.audio.card.dropCalibrated.title'),
            t('preferences.audio.card.dropCalibrated.message'));
          if (!yes) return;
        }
        result = assembleProfile(seed, {
          name, match: parseMatch($('#cardMatch').val()),
          wantInput, wantOutput, mono: $('#cardChMono').is(':checked'),
          inputCoupling: this._couplingOf('#cardInputCoupling'),
          outputCoupling: this._couplingOf('#cardOutputCoupling'),
          inputSeedFs, outputSeedFs, defaultRangeLabel: t('preferences.audio.range.default'),
        });
        this._modal().hide();
      };
      const onHidden = () => {
        $('#cardEditorOk').off('click', onOk);
        $(radios).off('change', onEnable);
        el.removeEventListener('hidden.bs.modal', onHidden);
        resolve(result);
      };
      $('#cardEditorOk').on('click', onOk);
      $(radios).on('change', onEnable);
      el.addEventListener('hidden.bs.modal', onHidden);
      this._modal().show();
    });
  }
}
