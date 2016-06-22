# Android Native Components

Cross-compile native libraries for Android using the new Gradle component
model approach with standard Android Development Tools.

## Overview

Support for building Android software in Gradle is evolving. Even so,
there is not a natural solution enabling Android binaries to be built
within Gradle. Applying the `com.android.model.native` plugin adds a
lot of assumptions to your project structure. And, doing so leaves
little room for compiling binaries for other targets such as your host
machine or another mobile platform. For example, you cannot:

* have more than one native module per project,
* configure the native component outside the Android DSL,
* utilize Gradle's native test plugins,
* specify libraries using Gradle's repositories mechanism,
* or depend on projects that do not use the native plugin.

Furthermore, a lot of complexity introduced by the Android native
plugin relates to the creation of build types and flavors, which is now
possible within Gradle proper. The plugin does add an Android binary
specification, but it's only used to convey the c++ STL required by each
binary—which is arguably one of the few things that should be global to
an entire project (since you can't mix and match them). It's also worth
mentioning that the notion of a "jni" source set doesn't really apply to
a general Android binary, yet the native plugin adds jni source sets
anyway.

Given the generality of Gradle, you should be able to build an Android
binary just as easily as you build any other binary: apply the native
plugin of choice (`c`, `cpp`, etc.) and create a native component with an
Android `targetPlatform` specified. Usage of the Android native plugins
should not be exclusive outside the context of an Android app or aar.

To address these issues, we've built a collection of 3 Gradle plugins:

* com.lookout.android.native-component
* com.lookout.android.native-binary
* com.lookout.android.native-artifact

that should enable Android binaries to be build naturally withing the
new Gradle component model.

<sub>*Please note, this is by no means an attempt to replicate the
wonderful work the Android Dev Tools team has done giving Android a
home within Gradle. In fact, it builds upon it. However, it does
attempt to address early issues with, specifically, the experimental
component model standalone native plugin. And it is only intended for
use in parts of a project that should be compiled for more than just
Android platforms. The build products of these plugins are still best
consumed by either of the current Android experimental component model
application, library, or standalone native plugins.*</sub>

## Usage

All 3 plugins depend on the new gradle model, and they assume you are
familiar with [Gradle's component-based approach to building software]
[1].

[1]: https://docs.gradle.org/current/userguide/software_model_concepts.html

First off, you should have the standard Gradle native plugins applied
for the sources you want to build:

    apply plugin: 'c'
    apply plugin: 'cpp'
    ...

Although note that this does not need to happen *first*. The Android
native plugins can be applied broadly across all sub-projects, for
example, while each sub-project specifies whether it should build `c`, or
`c++` sources (or both).

### native-component

    apply plugin: 'com.lookout.android.native-component'

The `native-component` plugin adds Android toolchains and platforms to
the Gradle component model, allowing a `NativeLibrarySpec` to include
any such platform in the list of platforms it targets. It also contains
logic to configure any binary generated because of an Android platform
listed as one of the library's targets.

    model {
        components {
            foo(NativeLibrarySpec) {
                targetPlatform 'android-x86_64'
                targetPlatform 'android-armv8'
                targetPlatform 'x86' // supported by gradle
                ...
            }
        }
    }

You can view the supoprted platforms by running the `model` task.

To support building for Android platforms, the plugin also adds
toolchains to Gradle's `ToolChainRegistry`. Toolchains need minimal
configuration, however. In theory it's possible to build Android using
the system clang, but we wanted to support building with the NDK since
it contains the officially supported tooling.

### native-binary

    apply plugin: 'com.lookout.android.native-binary'

The `native-binary` plugin does not actually contain logic to configure
binaries as the platform and toolchain models provided by the
`native-component` plugin would be pretty useless otherwise. Instead,
it simply configures all `NativeBinarySpec` components to build for all
supported android platforms. This may change in the future if there's a
use case for building Android binaries without the binary configuration
(meaning such configuration should be move into this plugin).

Roughly equivalent to:

    model {
        components {
            withType(NativeComponentSpec) {
                for (String abi : $.android.ndk.supportedAbis*.name) {
                    targetPlatform "android-${abi}"
                }
            }
        }
    }

If this is too general, simply omit this plugin and add the desired
target platforms as shown earlier.

### native-artifact

    apply plugin: 'com.lookout.android.native-artifact'

The `native-artifact` plugin publishes all binaries targeting a
platform added by the `native-component` plugin into a model that can
be consumed by the Android experimental Gradle model plugins. The
artifact specification includes information such as task dependencies,
library dependencies, and public header information.

Only projects that export headers to an android aar or app (or
standalone native) project should be published. Library dependencies
are included, but headers are not exported transitively (lest we publish
the world).

Note for c++ projects, the STL is not currently published as a
dependency. The rationale is that Android plugins already provide the
STL when one is specified, and it does not make sense to have multiple
versions or implementations of the library in a single project, so the
responsibility of acquiring the STL is left to such plugins. (In fact,
all deployment and packaging logic is left to the vanilla plugins, see
the caveat below.)

### ndk-spec

All three plugins share an Android NDK model: `AndroidNdkSpec`.
Configurable options include (with default values shown):

    android {
        platformVersion 'android-21'
        toolchain 'clang'
        toolchainVersion ''
        stl 'c++_shared'
    }

The complete list of values for each option depends on your NDK
revision. For example, ndk-r10e provides up to platform version
`android-21` while the newer ndk-r11c also contains `android-23` and
`android-24`. Clang is noteably missing from earlier versions of the NDK.

Options for `toolchain` are 'gcc' and 'clang'. It should be noted that
the NDK gcc toolchain is officially deprecated. New projects should use
clang where possible. The companion STL is 'c++_shared'.

For convenience, the instance of [NdkHandler][] used to configure the
the build is exposed via the `ndk` property on a finalized `android`
model.

[ndkhandler]: https://android.googlesource.com/platform/tools/base/+/studio-master-dev/build-system/gradle-core/src/main/groovy/com/android/build/gradle/internal/NdkHandler.java

The current syntax:

    $.android

(also shown earlier) can be used within a configuration rule to access a
model element after it has been finalized.

## Caveats

The plugins rely on classes published in the [gradle-experimental][]
artifact. The current plugins work with:

[gradle-experimental]: https://bintray.com/android/android-tools/com.android.tools.build.gradle-experimental/view

    com.android.tools.build:gradle-experimental:0.7.0-alpha5

This version must be synced with all other projects that are part of the
same build regardless of which plugins (vanilla ADT, or the ones
described here) they use. Additionally, the version of Android Studio, if
used, must roughly match the meta-tag ('alpha5' in this case).

The plugins do not specifically support flavor groups in the same way
the vanilla plugins do. You must create each "flavor group" manually.
There is no support for "abi splits", or any other packaging or
deployment logic (e.g. `ndk-strip`, `gdb`/`lldb`) since that is all
handled by the vanilla plugins.

Gradle does not make it easy to configure binaries for given build
types (e.g. 'debug' vs 'release') or flavors yet. Look for better
support from Gradle in the future.

Arguably the most annoying quirk of the current approach: the
`native-component` plugin iterates over `NativeBinarySpec`s from the
`BinaryContainer` in order to configure the default compiler and linker
flags used by the NDK when building Android binaries. If you need to
enable a compiler feature that is disabled by the NDK, you must make sure
the feature flag comes *after* the defaults added by the plugin. This can
be done simply be making sure your mutate rule is applied, similarly, to
the binary container (not the `NativeComponentSpec`), and is defined
after the `native-component` plugin has added its rules to configure
Android binaries.

## So, what?

If the gradle-experimental plugins evolve to the point where they can
coexist alonside a standard native Gradle build, these plugins will be
deprecated.
