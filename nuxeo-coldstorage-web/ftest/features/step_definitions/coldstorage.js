import Nuxeo from 'nuxeo';
// eslint-disable-next-line import/no-extraneous-dependencies
import { When, Then, Before } from '@cucumber/cucumber';

Before(() => {
  // We want to delete left over binaries in cold storage states else tests will fail
  const nuxeo = new Nuxeo({
    auth: { method: 'basic', username: 'Administrator', password: 'Administrator' },
    baseURL: process.env.NUXEO_URL,
  });
  nuxeo.request('management/binaries/orphaned').delete();
});

When('I click the Send file to cold storage action button', async function () {
  const browser = await this.ui.browser;
  await browser.clickDocumentActionMenu('nuxeo-move-content-to-coldstorage-button');
});

When('I click the Restore file from cold storage action button', async function () {
  const browser = await this.ui.browser;
  await browser.clickDocumentActionMenu('nuxeo-restore-content-from-coldstorage-button');
});

When('I can see the {string} confirmation dialog', async function (dialogType) {
  let dialog;
  const browser = await this.ui.browser;
  if (dialogType === 'Send') {
    dialog = await browser.el.element('nuxeo-dialog#contentToMoveDialog');
  } else if (dialogType === 'Restore') {
    dialog = await browser.el.element('nuxeo-dialog#contentToRestoreDialog');
  } else {
    dialog = await browser.el.element('nuxeo-dialog#contentFromRetrieveDialog');
  }
  await dialog.waitForVisible();
});

When('I click the {word} button in the {string} confirmation dialog', async function (btn, dialogType) {
  const browser = await this.ui.browser;
  let dialogElement;
  if (dialogType === 'Send') {
    dialogElement = await browser.el.element('nuxeo-dialog#contentToMoveDialog');
  } else if (dialogType === 'Restore') {
    dialogElement = await browser.el.element('nuxeo-dialog#contentToRestoreDialog');
  } else {
    dialogElement = await browser.el.element('nuxeo-dialog#contentFromRetrieveDialog');
  }
  const buttonSelector = `paper-button[name="${btn}"]`;
  const button = await dialogElement.$(buttonSelector);
  await button.click();
  await driver.pause(1000);
});

When('I move the files to cold storage', async function () {
  await driver.pause(3000);
  const browser = await this.ui.browser;
  const selectionToolBar = await this.ui.browser.selectionToolbar;
  await selectionToolBar.waitForVisible();
  const ele = await selectionToolBar.el.element('nuxeo-move-contents-to-coldstorage-button');
  await ele.click();
  const dialog = await browser.el.element('nuxeo-dialog#contentsToMoveDialog');
  await dialog.waitForVisible();
  const confirm = await dialog.element('paper-button[name="confirm"]');
  await confirm.click();
  driver.pause(1000);
});

When('I click the Retrieve file from cold storage button', async function () {
  const browser = await this.ui.browser;
  const page = await browser.documentPage('File');
  const docView = await page.view;
  await docView.waitForVisible();
  const dropdownButton = await docView.el.$('div.actions paper-menu-button#dropdownButton');
  await dropdownButton.click();
  await docView.waitForVisible('nuxeo-retrieve-content-from-coldstorage-button');
  const retrieveBtn = await docView.el.$('nuxeo-retrieve-content-from-coldstorage-button span');
  await retrieveBtn.click();
});

Then('I cannot see the Send file to cold storage button', async function () {
  const browser = await this.ui.browser;
  const menu = await browser.el.element('nuxeo-actions-menu');
  await menu.$('#dropdownButton').click();
  await menu.waitForVisible('[slot="dropdown"] .label');
  const menuVisible = await menu.isVisible('nuxeo-move-content-to-coldstorage-button .action');
  await menuVisible.should.be.equals(false);
});

Then('I can see the file is stored in cold storage', async function () {
  const browser = await this.ui.browser;
  await this.ui.reload();
  const page = await browser.documentPage(this.doc.type);
  const infoBar = await page.infoBar;
  await infoBar.waitForVisible('#coldStorageInfoBar .storedInColdStorage');
});

Then('I can see the file is not stored in cold storage', async function () {
  const browser = await this.ui.browser;
  await this.ui.reload();
  const page = await browser.documentPage(this.doc.type);
  const infoBar = await page.infoBar;
  const infoBarVisible = await infoBar.isVisible('#coldStorageInfoBar .storedInColdStorage');
  await infoBarVisible.should.be.equals(false);
});

Then('I can see the file is retrieved', async function () {
  const browser = await this.ui.browser;
  await this.ui.reload();
  const page = await browser.documentPage(this.doc.type);
  const infoBar = await page.infoBar;
  await infoBar.waitForVisible('#coldStorageInfoBar #retrieved.storedInColdStorage');
});

Then('I can see the file is being retrieved', async function () {
  const browser = await this.ui.browser;
  await this.ui.reload();
  const page = await browser.documentPage(this.doc.type);
  const infoBar = await page.infoBar;
  await infoBar.waitForVisible('#coldStorageInfoBar #beingRetrieved.storedInColdStorage');
});

Then('I can see the Send the selected files to cold storage action button', async function () {
  const browser = await this.ui.browser;
  const toolbar = await browser.selectionToolbar;
  await toolbar.waitForVisible();
  const button = await toolbar.isVisible('nuxeo-move-contents-to-coldstorage-button');
  await button.should.be.equals(true);
});

Then('I cannot see the Send the selected files to cold storage action button', async function () {
  const browser = await this.ui.browser;
  const toolbar = await browser.selectionToolbar;
  await toolbar.waitForVisible();
  const button = await toolbar.isVisible('nuxeo-move-contents-to-coldstorage-button');
  await button.should.be.equals(false);
});

Then('I can see the Remove button', async function () {
  const browser = await this.ui.browser;
  const page = await browser.documentPage('File');
  const docView = await page.view;
  await docView.waitForVisible();
  const deleteButton = await docView.isVisible('nuxeo-delete-blob-button .action');
  await deleteButton.should.be.equals(true);
});

Then('I cannot see the Remove button', async function () {
  const browser = await this.ui.browser;
  const page = await browser.documentPage('File');
  const docView = await page.view;
  await docView.waitForVisible();
  const deleteButton = await docView.isVisible('nuxeo-delete-blob-button .action');
  await deleteButton.should.be.equals(false);
});
