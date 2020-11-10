/* eslint import/no-extraneous-dependencies: ["error", {"devDependencies": true}] */
import { createSauceLabsLauncher } from '@web/test-runner-saucelabs';
import { legacyPlugin } from '@web/dev-server-legacy';
import { playwrightLauncher } from '@web/test-runner-playwright';

const baseConfig = {
  files: 'test/**/*.test.js',
  browsers: [
    playwrightLauncher({ product: 'chromium' }),
    playwrightLauncher({ product: 'webkit' }),
    playwrightLauncher({ product: 'firefox' }),
  ],
  coverage: true,
  nodeResolve: true,
  testFramework: {
    config: {
      ui: 'tdd',
    },
  },
};

const isSauceLabsRun = process.env.SAUCE_USERNAME && process.env.SAUCE_ACCESS_KEY;
if (isSauceLabsRun) {
  const sharedCapabilities = {
    'sauce:options': {
      name: 'Nuxeo Cold Storage',
      build: `Nuxeo Cold Storage ${process.env.GITHUB_REF || 'local'} build ${process.env.GITHUB_RUN_NUMBER || ''}`,
    },
  };

  const sauceLabsLauncher = createSauceLabsLauncher({
    user: process.env.SAUCE_USERNAME,
    key: process.env.SAUCE_ACCESS_KEY,
  });

  const sauceBrowsers = [
    sauceLabsLauncher({
      ...sharedCapabilities,
      browserName: 'chrome',
      browserVersion: 'latest',
      platformName: 'Windows 10',
    }),
    sauceLabsLauncher({
      ...sharedCapabilities,
      browserName: 'firefox',
      browserVersion: 'latest',
      platformName: 'Windows 10',
    }),
    sauceLabsLauncher({
      ...sharedCapabilities,
      browserName: 'safari',
      browserVersion: 'latest',
      platformName: 'macOS 10.15',
    }),
    sauceLabsLauncher({
      ...sharedCapabilities,
      browserName: 'MicrosoftEdge',
      browserVersion: 'latest',
      platformName: 'Windows 10',
    }),
  ];
  baseConfig.browsers = baseConfig.browsers.concat(sauceBrowsers);
  baseConfig.browserStartTimeout = 1000 * 30 * 5;
  baseConfig.sessionStartTimeout = 1000 * 30 * 5;
  baseConfig.sessionFinishTimeout = 1000 * 30 * 5;
  baseConfig.plugins = [legacyPlugin()];
  baseConfig.testFramework.config.timeout = '10000';
}

export default baseConfig;
