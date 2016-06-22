package com.lookout.gradle;

import org.gradle.api.Action;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.model.Mutate;
import org.gradle.model.RuleSource;

import org.gradle.platform.base.ComponentSpec;
import org.gradle.platform.base.ComponentSpecContainer;

import com.android.build.gradle.internal.core.Abi;

import static AndroidNativeComponents.PREFIX;

public class AndroidNativeBinaries implements Plugin<Project> {

    @Override
    void apply(Project project) {
        project.getPlugins().apply(AndroidNativeComponents.class);
    }

    public static class Rules extends RuleSource{
        @Mutate
        void addAllSupportedPlatforms(ComponentSpecContainer components, AndroidNdkSpec android) {
            components.all(new Action<ComponentSpec>() {
                    @Override
                    void execute(ComponentSpec component) {
                        for (Abi abi : android.ndk.getSupportedAbis()) {
                            component.targetPlatform(PREFIX + abi.getName());
                        }
                    }
            });
        }
    }
}