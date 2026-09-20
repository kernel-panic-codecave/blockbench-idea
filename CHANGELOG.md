<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# blockbench-idea Changelog

## [Unreleased]
### Added
- Development changes for the next release.

## [0.1.1] - 2026-09-19
### Added
- Added a Blockbench Project action using IntelliJ's standard New File dialog.
- Added the Blockbench icon to the project creation action and template.

### Changed
- New projects open Blockbench's native start screen so project setup uses Blockbench's own formats and workflow.
- Applied IntelliJ UI colors only to Blockbench interface chrome without changing the native 3D viewport.
- Improved compatibility with IDEs without JCEF and modern IntelliJ service registration.

## [0.1.0] - 2026-09-19
### Added
- Embedded Blockbench editor for `.bbmodel` files with JCEF.
- Load and save support through Blockbench's own web codecs.
- Blockbench runtime discovery, download, build, and local web-root configuration.
- Synchronization of Blockbench plugins and settings with managed runtimes.
- Blockbench file type and plugin icon.
- Optional JCEF support for IDE versions where JCEF is unavailable.
- GPL-3.0-or-later licensing.

### Changed
- Replaced the deprecated `FileTypeFactory` registration with declarative file type metadata.
- Added JetBrains Marketplace publishing and plugin signing configuration.
