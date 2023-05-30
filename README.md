[![Build Status](https://jenkins.platform.dev.nuxeo.com/buildStatus/icon?job=coldstorage%2Fnuxeo-coldstorage%2Flts-2021)](https://jenkins.platform.dev.nuxeo.com/job/coldstorage/job/nuxeo-coldstorage/job/lts-2021/)

# Nuxeo Cold Storage

The Nuxeo Cold Storage addon allows the storage of the document main content in a cold storage. This can be needed for archiving, compliance, etc.

For more details around functionalities, requirements, installation and usage please consider this addon [official documentation](https://doc.nuxeo.com/nxdoc/nuxeo-coldstorage/).

## Context
Nuxeo Cold Storage is an addon that can be plugged to Nuxeo.

It is bundled as a marketplace package that includes all the backend and frontend contributions needed for [Nuxeo Platform](https://github.com/nuxeo/nuxeo) and [Nuxeo Web UI](https://github.com/nuxeo/nuxeo-web-ui).

## Sub Modules Organization

- **ci**: CI/CD files and configurations responsible to generate preview environments and running Cold Storage pipeline
- **nuxeo-coldstorage**: Backend contribution for Nuxeo Platform
- **nuxeo-coldstorage-package**: Builder for [nuxeo-coldstorage](https://connect.nuxeo.com/nuxeo/site/marketplace/package/nuxeo-coldstorage) marketplace package. This package will install all the necessary mechanisms to integrate Cold Storage capabilities into Nuxeo
- **nuxeo-coldstorage-web**: Frontend contribution for Nuxeo Web UI

## Build

Nuxeo's ecosystem is Java based and uses Maven. This addon is not an exception and can be built by simply performing:

```shell script
mvn clean install
```

This will build all the modules except _ci_ and generate the correspondent artifacts: _`.jar`_ files for the contributions, and a _`.zip_ file for the package.

## DB configuration

Create the following db indexes for an optimal functioning of the addon:
 - `coldstorage:beingRetrieved`
 - `coldstorage:coldContent/digest`
 - `file:content/digest`
 - `ecm:mixinTypes`

 Typically on MongoDB:
 ```
 db.default.createIndex(
    { "coldstorage:beingRetrieved": 1 },
    { partialFilterExpression: { "coldstorage:beingRetrieved": true } }
 );

 db.default.createIndex(
    { "content.digest": 1 }
 );

 db.default.createIndex(
    { "coldstorage:coldContent.digest": 1 }
 );

 db.default.createIndex(
   { "ecm:mixinTypes": 1 }
);
 ```

## Configuration properties

 - `nuxeo.coldstorage.check.retrieve.state.cronExpression` :  cron expression to define the frequency of the execution of the process to check if a document has been retrieved. Default value is `0 7 * ? * * *` i.e. every hour at the 7th minute.
 - `nuxeo.bulk.action.checkColdStorageAvailability.scroller` : scroller implementation to be used to query documents being retrieved. `elastic` value can be set to relieve the regular back-end.
 - `nuxeo.coldstorage.numberOfDaysOfAvailability.value.default` : number of days a document remains available once it has been retrieved. Default value is `1`.
 - `nuxeo.coldstorage.thumbnailPreviewRequired` : is a thumbnail required to be used as a place holder to send a document to Cold Storage. Default value is `true`.

### Frontend Contribution

`nuxeo-coldstorage-web` module is also generating a _`.jar`_ file containing all the artifacts needed for an integration with Nuxeo's ecosystem.
Nevertheless this contribution is basically generating an ES Module ready for being integrated with Nuxeo Web UI.

It is possible to isolate this part of the build by running the following command:

```shell script
npm run build
```

It is using [rollup.js](https://rollupjs.org/guide/en/) to build, optimize and minify the code, making it ready for deployment.

## Test

In a similar way to what was written above about the building process, it is possible to run tests against each one of the modules.

Here, despite being under the same ecosystem, the contributions use different approaches.

### Backend Contribution

#### Unit Tests

```shell script
mvn test
```

A couple of unit test classes are designed to run with a blob provider using a real s3 bucket. In order to run them locally, you must define the following system properties:
 - `nuxeo.s3storage.awsid` : your AWS_ACCESS_KEY_ID
 - `nuxeo.s3storage.awssecret` : your AWS_SECRET_ACCESS_KEY
 - `nuxeo.test.s3storage.awstoken` : optional depending on your aws credentials type
 - `nuxeo.test.s3storage.region`: your AWS_REGION
 - `nuxeo.s3storage.bucket` : the name of the S3 bucket

### Frontend Contribution

#### Unit Tests

```shell script
npm run test
```

[Web Test Runner](https://modern-web.dev/docs/test-runner/overview/) is the test runner used to run this contribution unit tests.
The tests run against bundled versions of Chromium, Firefox and Webkit, using [Playwright](https://www.npmjs.com/package/playwright)

#### Functional Tests

```shell script
npm run ftest
```

To run the functional tests, [Nuxeo Web UI Functional Testing Framework](https://github.com/nuxeo/nuxeo-web-ui/tree/maintenance-3.0.x/packages/nuxeo-web-ui-ftest) is used.
Due to its inner dependencies, it only works using NodeJS `lts/dubnium`, i.e., `v10`.

## Development Workflow

### Frontend

*Disclaimer:* In order to contribute and develop Nuxeo Cold Storage UI, it is assumed that there is a Nuxeo server running with Nuxeo Cold Storage package installed and properly configured according the documentation above.

#### Install Dependencies  

```sh
npm install
```

#### Linting & Code Style

The UI contribution has linting to help making the code simpler and safer.

```sh
npm run lint
```

To help on code style and formatting the following command is available.

```sh
npm run format
```

Both `lint` and `format` commands run automatically before performing a commit in order to help us keeping the code base consistent with the rules defined.

#### Integration with Web UI

Despite being an "independent" project, this frontend contribution is build and aims to run as part of Nuxeo Web UI. So, most of the development will be done under that context.
To have the best experience possible, it is recommended to follow the `Web UI Development workflow` on [repository's README](https://github.com/nuxeo/nuxeo-web-ui/tree/maintenance-3.0.x).

Since it already contemplates the possibility of integrating packages/addons, it is possible to serve it with `NUXEO_PACKAGES` environment variable pointing to the desired packages/addons.


## CI/CD

Continuous Integration & Continuous Deployment(and Delivery) are an important part of the development process.

Nuxeo Cold Storage integrates [Jenkins pipelines](https://jenkins.platform.dev.nuxeo.com/job/coldstorage/job/nuxeo-coldstorage/) for each maintenance branch, for _LTS_ (fast track) and also for each opened PR.

The following features are available:
- Each PR merge to _10.10_/_lts-2021_/_lts-2023_ branch will generate a "release candidate" package

### Localization Management

Nuxeo Cold Storage manages multilingual content with a [Crowdin](https://crowdin.com/) integration.

The [Crowdin](.github/workflows/crowdin.yml) GitHub Actions workflow handles automatic translations and related pull requests.

# About Nuxeo

The [Nuxeo Platform](http://www.nuxeo.com/products/content-management-platform/) is an open source customizable and extensible content management platform for building business applications. It provides the foundation for developing [document management](http://www.nuxeo.com/solutions/document-management/), [digital asset management](http://www.nuxeo.com/solutions/digital-asset-management/), [case management application](http://www.nuxeo.com/solutions/case-management/) and [knowledge management](http://www.nuxeo.com/solutions/advanced-knowledge-base/). You can easily add features using ready-to-use addons or by extending the platform using its extension point system.

The Nuxeo Platform is developed and supported by Nuxeo, with contributions from the community.

Nuxeo dramatically improves how content-based applications are built, managed and deployed, making customers more agile, innovative and successful. Nuxeo provides a next generation, enterprise ready platform for building traditional and cutting-edge content oriented applications. Combining a powerful application development environment with
SaaS-based tools and a modular architecture, the Nuxeo Platform and Products provide clear business value to some of the most recognizable brands including Verizon, Electronic Arts, Sharp, FICO, the U.S. Navy, and Boeing. Nuxeo is headquartered in New York and Paris.
More information is available at [www.nuxeo.com](http://www.nuxeo.com).
