import { When, Then, Before } from '@cucumber/cucumber';
import Nuxeo from 'nuxeo';

Before(function () {
  // We want to delete left over binaries in cold storage states else tests will fail
  const nuxeo = new Nuxeo({
    auth: { method: 'basic', username: 'Administrator', password: 'Administrator' },
    baseURL: process.env.NUXEO_URL,
  });
  nuxeo.request('management/binaries/orphaned').delete();
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
  } else if (dialogType === 'Restore') {
    dialog = this.ui.browser.el.element('nuxeo-dialog#contentToRestoreDialog');
  } else dialog = this.ui.browser.el.element('nuxeo-dialog#contentFromRetrieveDialog');
  dialog.waitForVisible();
});

When('I click the {word} button in the {string} confirmation dialog', function (btn, dialogType) {
  let dialog;
  if (dialogType === 'Send') {
    dialog = this.ui.browser.el.element('nuxeo-dialog#contentToMoveDialog');
  } else if (dialogType === 'Restore') {
    dialog = this.ui.browser.el.element('nuxeo-dialog#contentToRestoreDialog');
  } else dialog = this.ui.browser.el.element('nuxeo-dialog#contentFromRetrieveDialog');
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

When('I click the Retrieve file from cold storage button', function () {
  const page = this.ui.browser.documentPage('File');
  const docView = page.view;
  docView.waitForVisible();
  docView.click('div.actions paper-menu-button#dropdownButton');
  docView.waitForVisible('nuxeo-retrieve-content-from-coldstorage-button');
  const retrieveBtn = docView.el.element('nuxeo-retrieve-content-from-coldstorage-button span');
  retrieveBtn.click();
});

Then('I cannot see the Send file to cold storage button', function () {
  const menu = this.ui.browser.el.element('nuxeo-actions-menu');
  menu.click('#dropdownButton');
  menu.waitForVisible('[slot="dropdown"] .label');
  menu.isVisible('nuxeo-move-content-to-coldstorage-button .action').should.be.equals(false);
});

Then('I can see the file is stored in cold storage', function () {
  this.ui.reload();
  const page = this.ui.browser.documentPage(this.doc.type);
  page.infoBar.waitForVisible('#coldStorageInfoBar .storedInColdStorage');
});

Then('I can see the file is not stored in cold storage', function () {
  this.ui.reload();
  const page = this.ui.browser.documentPage(this.doc.type);
  page.infoBar.isVisible('#coldStorageInfoBar .storedInColdStorage').should.be.equals(false);
});

Then('I can see the file is retrieved', function () {
  this.ui.reload();
  const page = this.ui.browser.documentPage(this.doc.type);
  page.infoBar.waitForVisible('#coldStorageInfoBar #retrieved.storedInColdStorage');
});

Then('I can see the file is being retrieved', function () {
  this.ui.reload();
  const page = this.ui.browser.documentPage(this.doc.type);
  page.infoBar.waitForVisible('#coldStorageInfoBar #beingRetrieved.storedInColdStorage');
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

Then('I can see the Remove button', function () {
  const page = this.ui.browser.documentPage('File');
  const docView = page.view;
  docView.waitForVisible();
  docView.isVisible('nuxeo-delete-blob-button .action').should.be.equals(true);
});

Then('I cannot see the Remove button', function () {
  const page = this.ui.browser.documentPage('File');
  const docView = page.view;
  docView.waitForVisible();
  docView.isVisible('nuxeo-delete-blob-button .action').should.be.equals(false);
});
