package com.gradle;

import com.gradle.maven.extension.api.GradleEnterpriseApi;
import org.apache.maven.execution.MavenSession;

public interface GradleEnterprisePluginApi {

    void configure(GradleEnterpriseApi api, MavenSession session);

}
