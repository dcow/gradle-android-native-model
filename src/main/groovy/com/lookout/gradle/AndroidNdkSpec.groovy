package com.lookout.gradle;

import org.gradle.model.Managed;
import org.gradle.model.Unmanaged;

import com.android.build.gradle.internal.NdkHandler;

@Managed
public interface AndroidNdkSpec {
    String toolchain;
    String getToolchain();
    void setToolchain(String toolchain);

    String toolchainVersion;
    String getToolchainVersion();
    void setToolchainVersion(String toolchain);

    String stl;
    String getStl();
    void setStl(String stl);

    String platformVersion;
    String getPlatformVersion();
    void setPlatformVersion(String version);

    NdkHandler ndk;
    @Unmanaged NdkHandler getNdk();
    void setNdk(NdkHandler ndk);
}