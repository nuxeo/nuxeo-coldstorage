import { Given, When, Then } from 'cucumber';

Given('I have the following documents in the platform', function (table) {
  driver.pause(1000);
  const tasks = table.hashes().map((row) => () => {
    const { doctype, title, creator, nature, subjects, coverage, path, collections, tag, file } = row;

    const doc = fixtures.documents.init(doctype, title);

    // assign basic dc properties (unprefixed)
    Object.assign(doc.properties, {
      'dc:title': title,
      'dc:creator': creator,
      'dc:nature': nature,
      'dc:subjects': Array.isArray(subjects) ? subjects : [subjects],
      'dc:coverage': coverage,
    });

    // fill in dummy note content
    if (doctype === 'Note') {
      doc.properties['note:note'] = 'Lorem Ipsum';
    }

    // fill in any other properties (prefixed)
    Object.keys(row)
      .filter((k) => k.indexOf(':') !== -1)
      .forEach((k) => {
        doc.properties[k] = row[k];
      });

    // create the document
    return (
      fixtures.documents
        .create(path, doc)
        // add to collection
        .then((d) => (collections && collections.length > 0 ? fixtures.collections.addToCollection(d, collections) : d))
        // add tag
        .then((d) => (tag && tag.length > 0 ? fixtures.documents.addTag(d, tag) : d))
        // attach files
        .then((d) => (file && file.length > 0 ? fixtures.documents.attach(d, fixtures.blobs.get(file)) : d))
    );
  });
  return tasks.reduce((current, next) => current.then(next), Promise.resolve([]));
});

When('I click the Send file to cold storage action button', function () {
  this.ui.browser.clickDocumentActionMenu('nuxeo-move-content-to-coldstorage-button');
});

When('I click the Restore file from cold storage action button', function () {
  this.ui.browser.clickDocumentActionMenu('nuxeo-restore-content-from-coldstorage-button');
});

When('I can see the {string} confirmation dialog', function (dialogType) {
  let dialog;
  if (dialogType === 'Send') {
    dialog = this.ui.browser.el.element('nuxeo-dialog#contentToMoveDialog');
  } else dialog = this.ui.browser.el.element('nuxeo-dialog#contentToRestoreDialog');
  dialog.waitForVisible();
});

When('I click the {word} button in the {string} confirmation dialog', function (btn, dialogType) {
  let dialog;
  if (dialogType === 'Send') {
    dialog = this.ui.browser.el.element('nuxeo-dialog#contentToMoveDialog');
  } else dialog = this.ui.browser.el.element('nuxeo-dialog#contentToRestoreDialog');
  dialog.click(`paper-button[name="${btn}"]`);
  driver.pause(1000);
});

When('I move the files to cold storage', function () {
  this.ui.browser.selectionToolbar.waitForVisible();
  this.ui.browser.selectionToolbar.click('nuxeo-move-contents-to-coldstorage-button');
  const dialog = this.ui.browser.el.element('nuxeo-dialog#contentsToMoveDialog');
  dialog.waitForVisible();
  dialog.click('paper-button[name="confirm"]');
  driver.pause(1000);
});

Then('I cannot see the Send file to cold storage button', function () {
  const menu = this.ui.browser.el.element('nuxeo-actions-menu');
  menu.click('#dropdownButton');
  menu.waitForVisible('[slot="dropdown"] .label');
  menu.isVisible('nuxeo-move-content-to-coldstorage-button .action').should.be.equals(false);
});

Then('I can see the file is stored in cold storage', function () {
  driver.waitUntil(() => {
    driver.refresh();
    const page = this.ui.browser.documentPage(this.doc.type);
    page.infoBar.waitForVisible('#coldStorageInfoBar .storedInColdStorage');
    return true;
  });
});

Then('I can see the file is not stored in cold storage', function () {
  driver.refresh();
  const page = this.ui.browser.documentPage(this.doc.type);
  page.infoBar.isVisible('#coldStorageInfoBar .storedInColdStorage').should.be.equals(false);
});

Then('I can see the Send the selected files to cold storage action button', function () {
  const toolbar = this.ui.browser.selectionToolbar;
  toolbar.waitForVisible();
  toolbar.isVisible('nuxeo-move-contents-to-coldstorage-button').should.be.equals(true);
});

Then('I cannot see the Send the selected files to cold storage action button', function () {
  const toolbar = this.ui.browser.selectionToolbar;
  toolbar.waitForVisible();
  toolbar.isVisible('nuxeo-move-contents-to-coldstorage-button').should.be.equals(false);
});
