package com.lookout.gradle;

import org.gradle.api.Action;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.model.Model;
import org.gradle.model.RuleSource;

import org.gradle.nativeplatform.NativeDependencySet;
import org.gradle.nativeplatform.NativeLibraryBinarySpec;
import org.gradle.nativeplatform.SharedLibraryBinarySpec;
import org.gradle.nativeplatform.StaticLibraryBinarySpec;
import org.gradle.language.nativeplatform.HeaderExportingSourceSet;
import org.gradle.platform.base.BinaryContainer;

import com.android.build.gradle.internal.NdkHandler;
import com.android.build.gradle.internal.NativeDependencyLinkage;
import com.android.build.gradle.internal.dependency.ArtifactContainer;
import com.android.build.gradle.internal.dependency.NativeLibraryArtifact;

import static AndroidNativeComponents.PREFIX;


public class AndroidNativeArtifacts implements Plugin<Project> {

    @Override
    void apply(Project project) {
        project.getPlugins().apply(AndroidNativeComponents.class);
    }

    public static class Rules extends RuleSource {
        @Model("artifacts")
        void createArtifactsContainer(ArtifactContainer artifacts, BinaryContainer binaries, final AndroidNdkSpec android) {
            // In the future we may want to add the ability to specify which components are published.
            // But this build does not yet require it, so publish all binaries that are targeting one
            // of the android platforms.
            for (NativeLibraryBinarySpec bug : binaries.withType(NativeLibraryBinarySpec)) {
                final NativeLibraryBinarySpec binary = bug;
                String platformName = binary.getTargetPlatform().getName();
                if (platformName.startsWith(PREFIX)) {
                    // Publish the artifact.
                    String name = binary.getLibrary().getBaseName();
                    String buildType = binary.getBuildType().getName();
                    String flavor = binary.getFlavor().getName();
                    // flavor+[s/b/B]uildtype
                    // | Note that gradle uses the opposite when naming binaries (buildtypeFlavor).
                    final String variant = flavor.equals("")
                            ? buildType
                            : flavor + buildType.substring(0, 1).toUpperCase() + buildType.substring(1);
                    final String architecture = platformName.substring(PREFIX.length(), platformName.length());
                    final String linkage = binary instanceof SharedLibraryBinarySpec ? "shared" : "static";

                    // This is different from android's naming convention. It doesn't matter. This is better.
                    String artifactName = name + "-" + variant + "-" + architecture + "-" + linkage;

                    // For some reason the artifacts container *has a* ModelMap of NativeLibraryArtifacts
                    // rather than simply *is a* ModelMap of NativeLibraryArtifacts..
                    artifacts.nativeArtifacts.create(artifactName, new Action<NativeLibraryArtifact>() {
                            @Override
                            void execute(NativeLibraryArtifact artifact) {
                                artifact.setVariantName(variant);

                                // These four values are the only ones that matter for dependency resolution. Of course, things
                                // still won't build/run if header sources and libraries aren't specified correctly.
                                artifact.setAbi(architecture);
                                artifact.setBuildType(buildType);
                                artifact.getProductFlavors().add(flavor);
                                artifact.setLinkage("shared".equals(linkage)
                                        ? NativeDependencyLinkage.SHARED
                                        : NativeDependencyLinkage.STATIC);

                                // Make sure to include our own library in the list of libraries associated with this artifact.
                                File output  = binary instanceof SharedLibraryBinarySpec
                                        ? ((SharedLibraryBinarySpec) binary).getSharedLibraryFile()
                                        : ((StaticLibraryBinarySpec) binary).getStaticLibraryFile();
                                artifact.getLibraries().add(output);

                                // Publish our exported headers..
                                for (HeaderExportingSourceSet source : binary.getInputs().withType(HeaderExportingSourceSet)) {
                                    artifact.getExportedHeaderDirectories().addAll(source.getExportedHeaders().getSrcDirs());
                                }
                                // Publish library objects we depend upon.
                                for (NativeDependencySet lib : binary.getLibs()) {
                                    artifact.getLibraries().addAll(lib.getLinkFiles());

                                    // Here's the big question: should the include paths of all libraries be added to the artifact
                                    // so they are transitively available to android projects that consume artifacts generated by
                                    // this plugin? The android plugins do not make such headers available, however, some build
                                    // systems (such as Xcode) do. For now, we will export dependencies' public headers.
                                    //
                                    // Transitively expose dependencies' public headers.
                                    artifact.getExportedHeaderDirectories().addAll(lib.getIncludeRoots());
                                }

                                // Do not publish the stl includes or library. There are a few reasons to diverge from the android
                                // convention here.
                                //     1. You can only use one stl in your app (per NDK user-guide restriction).
                                //     2. The android plugins already handle adding the specified stl to your app/aar.
                                //
                                // So, if you depend upon an artifact pusblished here that needs the stl, specify the stl in the
                                // ndk config of the aar/app project requiring such dependency and it will be added.
                                //
                                // // Add stl libraries and includes:
                                // if (android.stl.endsWith("_shared")) {
                                //     // Here we go again..
                                //     DefaultNativePlatform platform = (DefaultNativePlatform) binary.getTargetPlatform();
                                //     NativePlatform abi = new DefaultNativePlatform(architecture,
                                //             platform.getOperatingSystem(), platform.getArchitecture());
                                //
                                //     StlNativeToolSpecification stlSpec = new StlNativeToolSpecification(android.ndk, android.stl, null, abi);
                                //     artifact.getLibraries().add(stlSpec.getStlLib(abi.getName()));
                                // }

                                // builtBy is updated to simply take the binary spec and not a list of binary specs in later sources.
                                List<NativeLibraryBinarySpec> bingleton = new ArrayList<>();
                                bingleton.add(binary);
                                artifact.setBuiltBy(bingleton);
                            }
                    });
                }
            }
        }
    }
}