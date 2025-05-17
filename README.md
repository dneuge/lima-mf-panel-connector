# LiMa-MF Flight Simulation Panel Connector

[![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE.md)

This application connects flight simulation hardware panels based on [MobiFlight](https://www.mobiflight.com/) for platforms where the
official MobiFlight Connector is not available.

Users capable of running MobiFlight should follow official installation instructions instead of using this unofficial
application.

Only Linux and Mac® computers are supported by this application.

As MobiFlight is natively available on Windows, there are currently no plans to add Windows support.

## Supported panels/aircraft

Currently, all panels require custom implementations within this project.

* [Avionique Simulation](https://avioniquesimulation.com/) Challenger DCP/CCP:
    * [Hot Start Challenger 650](https://www.x-aviation.com/catalog/product_info.php/take-command-hot-start-challenger-650-p-212)

## Supported simulators

* X-Plane can currently be interfaced with over the legacy UDP interface

## Requirements

Java is required to run this application. All generally available current LTS versions are supported (at time of writing
Java 8, 11, 17, 21).

Linux users are advised to simply install Java™ (possibly named `openjdk`) through their distribution's package manager.

Users of the macOS® operating system need to download a Java distribution manually.

Several OpenJDK™-based Java distributions exist. If in doubt, our recommendation is to install
[Adoptium/Eclipse Temurin™](https://adoptium.net).

## Build Dependencies

When developing/building this application, the following Maven artifacts need to be built separately:

* https://github.com/dneuge/app-utils-misc-java
* https://github.com/dneuge/lima-mf-compat
* https://github.com/dneuge/xplane-java-udp (forked from https://github.com/spainer/xplane-java/)

## License

All sources and original files of this project are provided under [MIT license](LICENSE.md), unless declared otherwise
(e.g. by source code comments). Please be aware that dependencies (e.g. libraries and/or external data used by this
project) are subject to their own respective licenses which can affect distribution, particularly in binary/packaged
form.

MobiFlight-specific classes have been based on upstream sources, which are also available under MIT license. Please
see the separate [library project](https://github.com/dneuge/lima-mf-compat) for details.

### Note on the use of/for AI

Usage for AI training is subject to individual source licenses, there is no exception. This generally means that proper
attribution must be given and disclaimers may need to be retained when reproducing relevant portions of training data.
When incorporating source code, AI models generally become derived projects. As such, they remain subject to the
requirements set out by individual licenses associated with the input used during training. When in doubt, all files
shall be regarded as proprietary until clarified.

Unless you can comply with the licenses of this project you obviously are not permitted to use it for your AI training
set. Although it may not be required by those licenses, you are additionally asked to make your AI model publicly
available under an open license and for free, to play fair and contribute back to the open community you take from.

AI tools are not permitted to be used for contributions to this project. The main reason is that, as of time of writing,
no tool/model offers traceability nor can today's AI models understand and reason about what they are actually doing.
Apart from potential copyright/license violations the quality of AI output is doubtful and generally requires more
effort to be reviewed and cleaned/fixed than actually contributing original work. Contributors will be asked to confirm
and permanently record compliance with these guidelines.

## Disclaimer

This is an unofficial project, both in terms of MobiFlight and hardware
manufacturers. Unless endorsed by a manufacturer, please direct
questions/bug reports only to the author(s) of this application, not to
panel manufacturers or MobiFlight. Correct operation of hardware panels
should always be verified according to official instructions (i.e.
using original MobiFlight software on a supported platform).

As this application interfaces with actual hardware, there is a **risk of
damage being caused**, for example but not limited to e.g. hardware,
health or property. **That risk is amplified** by interfacing with a
firmware not controlled by the author(s) of this application. By using
this application, users accept that none of the authors of this
application or its dependencies/libraries, nor any of the authors of
MobiFlight, nor panel manufacturers shall be held liable for any damage
or harm being potentially caused by or being associated with the use of
this application. Carefully read all licenses and their disclaimers for
details, as well as the license/disclaimer texts associated with
MobiFlight's firmware (see MobiFlight for details).

Using this application may void the warranty of your hardware modules.

## Acknowledgements

[X-Plane](https://www.x-plane.com/) is a trademark of Laminar Research, registered by [Aerosoft GmbH](https://www.aerosoft.com/) within the European Union.

Java and OpenJDK are trademarks or registered trademarks of Oracle and/or its affiliates.

Adoptium, Eclipse and Eclipse Temurin are trademarks of Eclipse Foundation, Inc.

Mac and macOS are trademarks of Apple Inc., registered in the U.S. and other countries and regions.

This application interfaces using the original firmware and configuration files for MobiFlight and hardware panels.

Special thanks go out to all MobiFlight developers and the device manufacturers who chose to prefer MobiFlight over
having a proprietary firmware.

