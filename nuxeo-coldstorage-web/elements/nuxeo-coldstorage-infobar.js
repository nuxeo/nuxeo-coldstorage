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
import { mixinBehaviors } from '@polymer/polymer/lib/legacy/class.js';
import '@polymer/iron-icon/iron-icon.js';
import '@polymer/iron-icons/iron-icons.js';
import '@nuxeo/nuxeo-elements/nuxeo-element.js';
import '@nuxeo/nuxeo-ui-elements/actions/nuxeo-download-button.js';
import '@polymer/polymer/lib/elements/dom-if.js';
import { FiltersBehavior } from '@nuxeo/nuxeo-ui-elements/nuxeo-filters-behavior.js';
import { FormatBehavior } from '@nuxeo/nuxeo-ui-elements/nuxeo-format-behavior.js';

/**
`nuxeo-coldstorage-infobar`
@group Nuxeo UI
@element nuxeo-coldstorage-infobar
*/
class ColdStorageInfobar extends mixinBehaviors([FiltersBehavior, FormatBehavior], Nuxeo.Element) {
  static get template() {
    return html`
      <style include="nuxeo-styles iron-flex">
        .bar {
          @apply --layout-horizontal;
          @apply --layout-center;
          @apply --layout-justified;
          padding: 8px;
          margin-bottom: 16px;
          box-shadow: 0 3px 5px rgba(0, 0, 0, 0.04);
          background: black;
          color: white;
        }

        .storedInColdStorage {
          margin-right: 3px;
        }
      </style>
      <dom-if if="[[_contentStoredInColdStorage(document)]]">
        <template>
          <div id="coldStorageInfoBar" class="bar coldStorage">
            <div class="layout horizontal center flex">
              <iron-icon noink icon="coldstorage:default"></iron-icon>
              <span id="[[_documentStatus]]" class="storedInColdStorage"> [[_title]] </span>
              <dom-if if="[[_isDocRetrieved]]">
                <template>
                  <nuxeo-download-button
                    document="[[document]]"
                    xpath="coldstorage:coldContent"
                    show-label="true"
                  ></nuxeo-download-button>
                </template>
              </dom-if>
            </div>
          </div>
        </template>
      </dom-if>
    `;
  }

  static get is() {
    return 'nuxeo-coldstorage-infobar';
  }

  static get properties() {
    return {
      /**
       * Input document.
       */
      document: Object,
      /**
       * Coldstorage status of the file.
       */
      _documentStatus: {
        type: String,
        computed: '_getDocumentStatus(document)',
      },
      _title: {
        type: String,
        computed: '_getTitle(document)',
      },
      _isDocRetrieved: {
        type: Boolean,
        computed: '_computeDocRetrieved(_documentStatus)',
      },
    };
  }

  _contentStoredInColdStorage(doc) {
    return this.hasFacet(doc, 'ColdStorage') && doc.properties && doc.properties['coldstorage:coldContent'];
  }

  _computeDocRetrieved() {
    return this._documentStatus === 'retrieved';
  }

  _getDocumentStatus(document) {
    if (document.properties['coldstorage:beingRetrieved']) {
      return 'beingRetrieved';
    }
    if (document.properties['coldstorage:toBeRestored']) {
      return 'toBeRestored';
    }
    if (
      document.properties['coldstorage:downloadableUntil'] &&
      new Date(document.properties['coldstorage:downloadableUntil']) >= new Date()
    ) {
      return 'retrieved';
    }
    return 'storedInColdStorage';
  }

  _getTitle(doc) {
    return this.i18n(
      `documentContentView.infobar.${this._documentStatus}`,
      this.formatDateTime(doc.properties['coldstorage:downloadableUntil']),
    );
  }
}

customElements.define(ColdStorageInfobar.is, ColdStorageInfobar);
