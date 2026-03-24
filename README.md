# SIX Rule Runner and Query Framework

## Introduction

At SIX Group AG, we have developed a framework and a set of tools that help us
operate the SailPoint IdentityIQ (IIQ) system and analyze IIQ data. We share
this software with the IIQ community to enable broad adoption - for the benefit
of the community as well as ourselves.

We also explicitly encourage SailPoint to incorporate this framework into
IdentityIQ and other products, making it - and the tools built on top of it -
directly available to all IIQ customers. At the same time, the core components
of this framework, including the two DSLs, their supporting utility classes,
and tools such as the Generic Query and the Thread Watcher, are **not tied to
IIQ** at all. They form a powerful, flexible platform that can be reused in a
wide range of contexts independently of IIQ, and even ported to other
programming languages or runtime environments.

The software and all accompanying documentation are provided as we use them
internally.

## Beyond IIQ

Although originally created to support IdentityIQ, this project contains
substantial technology that is **domain‑agnostic** and broadly reusable:

* Two domain‑specific languages (DSLs) designed for concise, expressive
  querying, object model traversal and condition checking
* Core utility classes that provide generic data handling and transformation
  capabilities
* Generic Query – a high‑level query engine usable on arbitrary object graphs,
  not limited to IIQ
* Thread Watcher – a monitoring and diagnostic tool for integration into Java
  applications

The framework has no external dependencies and can be integrated into other
Java ecosystems or ported to different languages with minimal effort. Although
most components are fully generic and usable independently of IdentityIQ, the
persistence layer is still directly tied to IIQ’s internal storage model. This
integration will need to be refactored to become pluggable. Our long‑term goal
is to isolate these IIQ‑specific parts behind a clean abstraction so that
alternative persistence solutions can be integrated via a plugin mechanism
or adapter layer.

## License

This project is licensed under the **MIT No Attribution License (MIT‑0)**.
This license grants maximum freedom to use, modify, relicense, and redistribute
the software, including for commercial purposes and incorporation into
GPL‑licensed or proprietary products.

The complete license text is available in the LICENSE file.

### Scope of License

The MIT‑0 license applies to **all files in this repository**, including:

* source code
* documentation
* examples
* tests

### Use of the Name “SIX Group”

The name “**SIX Group**” is a registered trademark. While the MIT‑0 license
imposes no attribution requirement, **our company name may not be used to
endorse or promote products derived from this software without prior written
permission**.

This is a standard trademark protection statement and does not limit your
rights under the MIT‑0 license.

## Disclaimer

This software is provided “**as is**”, without warranty of any kind.
We operate it in production ourselves, but every environment is different.
Use it at your own risk. SIX Group AG assumes no liability for any damages
arising from its use.

## Features

What you will find in the package:

* The Rule Runner – a platform to run rules from the IIQ GUI with customized
  input forms and true HTML output, synchronously or as background tasks
* A set of rules written for this platform, the most notable being:
  * The Generic Query – a powerful tool to query the IIQ database based on the
    IIQ object model as well as other data sources like log files, offering
    sophisticated query and presentation possibilities
  * The Thread Watcher – a tool to monitor thread execution for diagnosing
    hangs and other unwanted conditions
  * The Beanshell Runner – a small tool to run Beanshell snippets on the
    application server for development and prototyping purposes
  * Rules for interactive and batch testing of application connector
    functionality and other tasks
* A small but powerful framework with no external dependencies that provides
  the basis for these tools and many other possible applications:
  * QueryHelper – a thin wrapper around the SailPointContext `search()` methods
    that simplifies programming by giving direct access to extended attributes
    and returning records as `Map<String, Object>` instead of `Object[]`
  * IndexedData and TabularData - utility classes for working with in-memory
    indexed data and tabular data
  * CerberusLogic - a lightweight business rules engine using a simple syntax
    and offering powerful selectors resembling Sailpoint filters that can be
    used in flexible ways 
  * OrionQL - a data query and transformation engine using a pipeline notation
    for navigating object models and transforming data
* Extensive documentation

## Getting Started

The repository is located at
https://github.com/six-group/rulerunner-query-framework.
The project does *not* include a build to produce artefacts to be referenced
from other projects. Instead, it hosts code to be *integrated* into
*existing* SSB projects.

The code was *intentionally* not encapsulated as a plugin since this would
prevent using the framework in backend code.

To integrate the framework into your IIQ installation, proceed as follows:

* Copy the config, src, test and web directories into your SSB project.
* Merge the bean definition from `web/WEB-INF/merge-into-faces-config.xml`
  into your `faces-config.xml`. If you don't yet have one, copy it from the
  extracted WAR file (make sure from then on to update with every IIQ release).
  Delete `merge-into-faces-config.xml` when finished.
* The SSB will now include the framework into your deployment. Test the
  installation by going to the following link (replace the hostname according to
  your setup and be sure you have the SystemAdministrator capability):
  [Test the installation](https://iam.localdomain.com/identityiq/rulerunner/rulerunner.jsf?rule=six_generic_query&className=Identity&filter=name+%3D%3D+%24%22%3Aenv%28username%29%22&output=user%3D%24toAugmentedName%24%0Aroles%3DassignedRoles%3Amap%28%24toAugmentedName%24%29%3Asort%3Ajoin) 

Consult the TODO file and the documentation in the doc directory.

## Dependencies

You might need to add the following libraries to your SSB build if you don’t have them yet:

* in the lib directory:
  * annotations-19.0.0.jar
  * hamcrest-2.2.jar
  * hamcrest-core-2.2.jar
  * junit-4.12.jar
  * mockito-core-3.3.3.jar
* in the web/WEB-INF/lib directory:
  * javax.servlet-api-4.0.1.jar

## Contributing

We welcome contributions, issues, and feature requests. Feel free to submit
merge requests or open issues to help improve the framework.