/**
 (C) Copyright Nuxeo Corp. (http://nuxeo.com/)

 Licensed under the Apache License, Version 2.0 (the "License");
 you may not use this file except in compliance with the License.
 You may obtain a copy of the License at

 http://www.apache.org/licenses/LICENSE-2.0

 Unless required by applicable law or agreed to in writing, software
 distributed under the License is distributed on an "AS IS" BASIS,
 WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 See the License for the specific language governing permissions and
 limitations under the License.
 */
import { html } from '@polymer/polymer/lib/utils/html-tag.js';
import '@polymer/iron-icon/iron-icon.js';
import '@polymer/iron-icons/iron-icons.js';
import '@nuxeo/nuxeo-elements/nuxeo-connection.js';
import '@nuxeo/nuxeo-elements/nuxeo-element.js';
import '@nuxeo/nuxeo-elements/nuxeo-operation.js';
import '@polymer/paper-button/paper-button.js';
import '@polymer/paper-icon-button/paper-icon-button.js';
import '@polymer/polymer/lib/elements/dom-if.js';
import '@polymer/polymer/lib/elements/dom-repeat.js';
import '@nuxeo/nuxeo-ui-elements/nuxeo-icons.js';
import '@nuxeo/nuxeo-ui-elements/widgets/nuxeo-dialog.js';
import '@nuxeo/nuxeo-ui-elements/widgets/nuxeo-tooltip.js';
import '@nuxeo/nuxeo-ui-elements/actions/nuxeo-action-button-styles.js';

/**
 `nuxeo-delete-contents-from-coldstorage-button`
 @group Nuxeo UI
 @element nuxeo-contents-from-coldstorage-button2
 */
class DeleteFromColdStorage extends mixinBehaviors([FiltersBehavior, FormatBehavior], Nuxeo.Element) {
    static get template() {
        return html`
          <style include="nuxeo-action-button-styles nuxeo-styles</style>
          
      <nuxeo-operation id="d" op="Document.DeleteFromColdStorage" input="[[document.uid]]"></nuxeo-operation>
      <nuxeo-connection id="nxcon" user="{{currentUser}}"></nuxeo-connection>

      <dom-if if="[[_isAvailable(document, currentUser)]]">
        <template>
          <div class="action" on-click="_openDialog">
            <paper-icon-button icon="nuxeo:coldstorage" noink></paper-icon-button>
            <nuxeo-tooltip>[[i18n('deleteContentFromColdStorage.tooltip')]]</nuxeo-tooltip>
          </div>
        </template>
      </dom-if>

      <nuxeo-dialog id="dg" with-backdrop>
        <h2>[[i18n('deleteContentFromColdStorage.ask.confirmation')]]
        <p>[[i18n('deleteContentFromColdStorage.description')]]</p>

        <div class="buttons">
          <paper-button id="cancel" name="cancel" noink dialog-dismiss on-click="_delete">
            [[i18n('deleteContentFromColdStorage.cancel')]]
          <paper-button id="confirm" name="confirm" class="primary" noink dialog-confirm>
            [[i18n('deleteContentFromColdStorage.confirm')]]
          </paper-button>
        </div>
      </nuxeo-dialog>
    `;
    }

    static get is() {
        return 'nuxeo-delete-contents-from-coldstorage-button2';
    }

    static get properties() {
        return {
            /**
             * Input document.
             */
            document: Object,

            /**
             * Current user.
             */
            currentUser: Object,
        };
    }

    _toggleDialog() {
        this.$.dg.toggle();
    }

    _canMoveDocument(doc) {
        return (
            (this.hasAdministrationPermissions(this.currentUser) || this.hasPermission(doc, 'WriteColdStorage')) &&
            !this.hasFacet(doc, 'ColdStorage') &&
            this.hasContent(doc)
        );
    }

    _isAvailable(document, currentUser) {
        return true;
    }

    _toggle() {
        this.$.d
            .execute()
            .then(() => {
                this.dispatchEvent(
                    new CustomEvent('document-deleted-from-coldstorage', {
                        composed: true,
                        bubbles: true,
                    }),

                this.dispatchEvent(
                    new CustomEvent('notify', {
                        composed: true,
                        bubbles: true,
                        detail: { message: this.i18n('deleteContentFromColdStorage.success') },
                    }),
                ));
            });
    }
}

customElements.define(DeleteFromColdStorage.is, DeleteFromColdStorage)
