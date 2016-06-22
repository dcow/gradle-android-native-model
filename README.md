gradle-android-native-model
===

Native software model components for building Android binaries.

## Usage

Three plugins:

* `com.lookout.android.native-component`
* `com.lookout.android.native-binary`
* `com.lookout.android.native-artifact`

aid in [cross compiling core components for Android][plugins].

[plugins]: doc/android-native-component.md

These plugins allow a Gradle component spec to target any supported
Android platform in addition to other platforms normally supported by
Gradle (or otherwise enabled by a Gradle build). We use the plugins so
that unit tests can be run natively on the host machine (osx or linux)
while at the same time cross-compiled for mobile platforms, thus
eliminating the need to run an emulator during the pre-checkin build
verification process.

### Roadmap:

* iOS sibling plugins

