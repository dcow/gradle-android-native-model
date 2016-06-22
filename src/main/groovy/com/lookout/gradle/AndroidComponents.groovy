package com.lookout.gradle;

import org.gradle.api.Action;
import org.gradle.api.InvalidUserDataException;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.internal.project.ProjectIdentifier;

import org.gradle.model.Defaults;
import org.gradle.model.Finalize;
import org.gradle.model.Model;
import org.gradle.model.Mutate;
import org.gradle.model.Path;
import org.gradle.model.RuleSource;
import org.gradle.model.Validate;

import org.gradle.nativeplatform.NativeBinarySpec;
import org.gradle.nativeplatform.plugins.NativeComponentPlugin;
import org.gradle.nativeplatform.platform.internal.DefaultNativePlatform;
import org.gradle.nativeplatform.toolchain.Clang;
import org.gradle.nativeplatform.toolchain.Gcc;
import org.gradle.nativeplatform.toolchain.GccCompatibleToolChain;
import org.gradle.nativeplatform.toolchain.GccPlatformToolChain;
import org.gradle.nativeplatform.toolchain.NativeToolChainRegistry;
import org.gradle.nativeplatform.platform.NativePlatform;
import org.gradle.platform.base.BinaryContainer;
import org.gradle.platform.base.PlatformContainer;

import com.android.build.gradle.internal.NdkHandler;
import com.android.build.gradle.internal.core.Abi;
import com.android.build.gradle.internal.core.Toolchain;
import com.android.build.gradle.ndk.internal.DefaultNativeToolSpecification;
import com.android.build.gradle.ndk.internal.NativeToolSpecification;
import com.android.build.gradle.ndk.internal.NativeToolSpecificationFactory;
import com.android.build.gradle.ndk.internal.StlNativeToolSpecification;


public class AndroidNativeComponents implements Plugin<Project> {
    static final String PREFIX = "android-"

    @Override
    void apply(Project project) {
        project.getPluginManager().apply(NativeComponentPlugin.class);
    }

    public static class Rules extends RuleSource {
        @Model("android")
        void createModel(AndroidNdkSpec android) {}

        @Defaults
        void defaultModel(AndroidNdkSpec android, ProjectIdentifier projectId) {
            android.setToolchain("clang"); // gcc is deprecated
            android.setToolchainVersion(""); // latest
            android.setStl("c++_shared"); // llvm c++
            android.setPlatformVersion("android-21"); // Is there an ndk-latest helper?
        }

        @Finalize
        void finalizeModel(AndroidNdkSpec android, ProjectIdentifier projectId) {
            // grab the root project identifier
            while (projectId.getParentIdentifier() != null) {
                projectId = projectId.getParentIdentifier();
            }

            android.ndk = new NdkHandler(projectId.projectDir, android.platformVersion, android.toolchain, android.toolchainVersion);
        }

        @Validate
        void checkNdkDir(AndroidNdkSpec android) {
            if (!android.ndk.isNdkDirConfigured()) {
                throw new InvalidUserDataException(
                        "NDK location not found.  Define location with ndk.dir in the "
                                + "local.properties file or with an ANDROID_NDK_HOME environment "
                                + "variable.");
            } else if (!android.ndk.getNdkDirectory().exists()) {
                throw new InvalidUserDataException(
                        "Specified NDK location does not exists.  Please ensure ndk.dir in "
                                + "local.properties file or ANDROID_NDK_HOME is configured "
                                + "correctly.");
            }
        }

        @Mutate
        void addPlatforms(PlatformContainer platforms, @Path("android.ndk") NdkHandler ndk) {
            for (Abi abi : ndk.getSupportedAbis()) {
                // We should probably actually fail here (not maybeCreate) if the platform already exists so
                // we don't mangle an existing platform with the same name.
                NativePlatform platform = platforms.maybeCreate(PREFIX + abi.getName(), NativePlatform.class);
                platform.architecture(abi.getArchitecture());
                platform.operatingSystem("android"); // this resolves to UNIX for now.. which is not incorrect..
            }
        }

        // Should this be @Finalize so that users can easily add support for compiling on their host machine?
        @Mutate
        void addToolchain(NativeToolChainRegistry toolchains, AndroidNdkSpec android) {
            final Toolchain ndkToolchain = Toolchain.getByName(android.toolchain);
            Class<? extends GccCompatibleToolChain> toolType = android.toolchain.equals("gcc") ? Gcc.class : Clang.class;
            // Create and configure each toolchain target. Adapted from the ToolchainConfiguration class.
            if (Toolchain.GCC.equals(ndkToolchain)) {
                for (/*final*/ Abi bug : android.ndk.getSupportedAbis()) {
                    final Abi abi = bug; // This is the weirdest quirk I think I've seen in java. It would appear that a
                    // final loop varable is only "final" for the lifetime of the loop. It then gets updated on the next
                    // iteration. This means that all invocations of the closure below use the last value of the loop
                    // counter assign whilst iterating. The fix is to copy the reference loop-local.

                    // Setup the toolchain. As with platforms, it might be better to fail hard if the user has already
                    // specified a toolchain with the same name so we don't silently mangle theirs.
                    GccCompatibleToolChain toolchain = toolchains.maybeCreate("ndk-"+ abi.getName(), toolType);

                    // Path is actually a per-toolchain not per-target thing. And path differes for all the gcc toolchains.
                    // As long as this is the case we must make a new toolchain for each gcc target arch. Clang solves this
                    // and only needs one toolchain.
                    toolchain.path(android.ndk.getCCompiler(abi).getParentFile());
                    toolchain.target("android-" + abi.getName(), new Action<GccPlatformToolChain>() {
                            @Override
                            public void execute(GccPlatformToolChain target) {
                                String gccPrefix = abi.getGccExecutablePrefix();
                                target.getcCompiler().setExecutable(gccPrefix + "-gcc");
                                target.getCppCompiler().setExecutable(gccPrefix + "-g++");
                                target.getLinker().setExecutable(gccPrefix + "-g++");
                                target.getAssembler().setExecutable(gccPrefix + "-as");
                                target.getStaticLibArchiver().setExecutable(gccPrefix + "-ar");
                            }
                    });
                }
            } else {
                GccCompatibleToolChain toolchain = toolchains.maybeCreate("ndk", toolType);
                // There's a new set of issues to solve here. There is only one path for clang since llvm is a
                // cross-compiler-by-design. But, we don't actually have a way to get the path short of constucting
                // it ourselves. I'd rather not do that in the event it changes in the future. For now adding
                // everything to a set and checking the size of the set after we finish iterating works.
                Set<File> path = new HashSet<>();
                for (/*final*/ Abi bug : android.ndk.getSupportedAbis()) {
                    final Abi abi = bug;
                    path.add(android.ndk.getCCompiler(abi).getParentFile());
                    toolchain.target(PREFIX + abi.getName(), new Action<GccPlatformToolChain>() {
                            @Override
                            public void execute(GccPlatformToolChain target) {
                                // ndk r10e comes with `llvm-ar`
                                // ndk r11c does not, use gcc binutils ar..`
                                File gcc = android.ndk.getDefaultGccToolchainPath(abi);
                                File prefix = new File(gcc, abi.getGccExecutablePrefix());
                                File bin = new File (prefix, "bin");
                                File ar = new File(bin, "ar");
                                target.getStaticLibArchiver().setExecutable(ar.getPath());

                                // If using llvm-ar, the arguments must be slightly reformatted
                                // target.getStaticLibArchiver().setExecutable('llvm-ar');
                                // target.getStaticLibArchiver().withArguments(new Action<List<String>>() {
                                //         @Override
                                //         public void execute(List<String> args) {
                                //             args.remove("-rcs");
                                //             args.add(0, "rcs"); // llvm-ar don't like hyphens
                                //         }
                                // });
                            }
                    });
                }
                if (path.size() != 1) {
                    String paths = Arrays.toString(path.toArray());
                    throw new InvalidUserDataException("Clang should not have multiple paths: " + paths);
                }
                for (File p : path) {
                    toolchain.path(p);
                }
            }
        }

        @Mutate
        void configureTooling(BinaryContainer binaries, AndroidNdkSpec android) {
            // This is going to be ugly, but Gradle does not offer a better solution at the moment.
            // One might also think we could be smarter and attach these options to the toolchain
            // targets themselves. Gradle discourages this for opinionated reasons, so we'll keep
            // the configuration here for now.
            //
            // A potentially more flexible alternative is to iterate over library and test suite
            // binaries seperately and set the configuration there. The advantage of such approach
            // is that arguments and definitions supplied here get added before the user specifies
            // anything in their buildscript.
            binaries.withType(NativeBinarySpec.class, new Action<NativeBinarySpec>() {
                    @Override
                    void execute(NativeBinarySpec binary) {
                        // We can't do targetPlatform.operatingSystem.ANDROID )=
                        DefaultNativePlatform platform = (DefaultNativePlatform) binary.getTargetPlatform();
                        String platformName = platform.getName();
                        if (platformName.startsWith(PREFIX)) {
                            // Configure Android
                            binary.getcCompiler().define("ANDROID");
                            binary.getCppCompiler().define("ANDROID");
                            binary.getcCompiler().define("ANDROID_NDK");
                            binary.getCppCompiler().define("ANDROID_NDK");

                            // Android Platforms =/= Gradle Platforms cf. ToolchainConfiguration.java from the experimental
                            // plugin
                            String androidPlatformName = platformName.substring(PREFIX.length(), platformName.length());
                            // The tool specs expect a platform with name = Abi.name.
                            NativePlatform platformForSpec = new DefaultNativePlatform(androidPlatformName,
                                    platform.getOperatingSystem(), platform.getArchitecture());
                            // c++
                            // 3rd parameter is "stlVersion". It appears this parameter has been removed in later versions.
                            new StlNativeToolSpecification(android.ndk, android.stl, null, platformForSpec).apply(binary);

                            // Toolchain specific flags:
                            boolean isDebugBuild = binary.getBuildType().getName().toLowerCase().contains("debug");
                            NativeToolSpecificationFactory.create(android.ndk, platformForSpec, isDebugBuild).apply(binary);
                            // General flags (turn off rtti and exceptions for cpp by default and add android-tailored linker
                            // options)
                            new DefaultNativeToolSpecification().apply(binary);

                            // Don't forget sysroot.
                            String sysroot = android.ndk.getSysroot(Abi.getByName(androidPlatformName));
                            binary.getcCompiler().args("--sysroot=" + sysroot);
                            binary.getCppCompiler().args("--sysroot=" + sysroot);
                            binary.getLinker().args("--sysroot=" + sysroot);
                            // It would be cool to support crystax ndk and thus support objective-c[++].

                            // --sysroot is not enough when linking executables using ld (which even clang still uses).
                            // We must tell the linker where to look for libs.
                            File usrDir = new File(sysroot + "/usr");
                            String[] libDirNames = usrDir.list(new FilenameFilter() {
                                    @Override
                                    boolean accept(File dir, String name) {
                                        return name.startsWith("lib");
                                    }
                            });
                            StringBuilder rpaths = new StringBuilder("-Wl");
                            for (String libDirName : libDirNames) {
                                File libDir = new File (usrDir, libDirName);
                                rpaths.append(",-rpath-link=").append(libDir.getAbsolutePath());
                            }
                            binary.getLinker().args(rpaths.toString());

                            // Android ids elfs.
                            binary.getLinker().args("-Wl,--build-id");

                            // We do not add ndk-strip tasks for binaries because the anroid application/library plugins strip
                            // all inbound libraries before packaging them.
                        }
                    }
            });
        }
    }
}