import { terser } from 'rollup-plugin-terser';
import copy from 'rollup-plugin-copy';
import minifyHTML from 'rollup-plugin-minify-html-literals';
import resolve from '@rollup/plugin-node-resolve';
import path from 'path';

// Replace imports by an export of existing globals in Web UI
// https://github.com/nuxeo/nuxeo-web-ui/blob/master/index.js#L7
const GLOBALS = {
  '@polymer/polymer/lib/legacy/class.js': 'const { mixinBehaviors } = Polymer; export { mixinBehaviors };',
  '@polymer/polymer/lib/legacy/polymer.dom.js':
    'const { dom, html, matchesSelector } = Polymer; export { dom, html, matchesSelector };',
  '@polymer/polymer/polymer-element.js': 'const { PolymerElement } = window; export { PolymerElement };',
  '@polymer/polymer/lib/elements/dom-module.js': 'const { DomModule } = window; export { DomModule };',
  '@polymer/polymer/lib/utils/html-tag.js': 'const { html } = Polymer; export { html };',
  '@polymer/polymer/lib/utils/debounce.js':
    'const { enqueueDebouncer, flushDebouncers, Debouncer } = Polymer; ' +
    'export { enqueueDebouncer, flushDebouncers, Debouncer };',
  '@nuxeo/nuxeo-ui-elements/nuxeo-i18n-behavior.js': 'const { I18nBehavior } = Nuxeo; export { I18nBehavior };',
  '@nuxeo/nuxeo-ui-elements/nuxeo-filters-behavior.js':
    'const { FiltersBehavior } = Nuxeo; export { FiltersBehavior };',
  '@nuxeo/nuxeo-ui-elements/nuxeo-format-behavior.js': 'const { FormatBehavior } = Nuxeo; export { FormatBehavior };',
};

// Ignore these imports since they should just be all about custom element definitions which are done already by Web UI
const IGNORES = [/^@(nuxeo|polymer)\//];

// Keep these imports
const KEEP = ['@nuxeo/nuxeo-ui-elements/import-href.js'];

const TARGET = 'target/classes/web/nuxeo.war/ui';

export default {
  input: 'index.js',
  output: {
    file: `${TARGET}/nuxeo-coldstorage.bundle.js`,
    format: 'es',
  },
  plugins: [
    copy({
      targets: [{ src: 'i18n', dest: TARGET }],
    }),
    resolve(),
    {
      transform(code, id) {
        // HTML imports
        if (path.extname(id) === '.html') {
          return `export default ${JSON.stringify(code)}`;
        }

        const dep = path.relative('./node_modules', id);

        // Rewrite imports
        if (GLOBALS[dep]) {
          return GLOBALS[dep];
        }

        // Ignore bundled imports
        if (!KEEP.includes(dep) && IGNORES.some((r) => r.test(dep))) {
          return 'export default undefined;';
        }

        return code;
      },
    },
    ...(process.env.NODE_ENV === 'production' ? [minifyHTML(), terser()] : []),
  ],
};
