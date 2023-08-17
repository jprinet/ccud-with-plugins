package com.gradle.plugin;

import com.gradle.GradleEnterprisePluginApi;
import com.gradle.maven.extension.api.GradleEnterpriseApi;
import org.apache.ivy.Ivy;
import org.apache.ivy.core.module.descriptor.DefaultDependencyDescriptor;
import org.apache.ivy.core.module.descriptor.DefaultModuleDescriptor;
import org.apache.ivy.core.module.id.ModuleRevisionId;
import org.apache.ivy.core.report.ResolveReport;
import org.apache.ivy.core.resolve.ResolveOptions;
import org.apache.ivy.core.settings.IvySettings;
import org.apache.ivy.plugins.resolver.IBiblioResolver;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.text.ParseException;
import java.util.*;

public class PluginLoaderFromLocalRepository {

    public void configurePlugins(GradleEnterpriseApi api, MavenSession session) {
        try {
            List<URL> plugins = new ArrayList<>();

            // read plugin descriptor
            MavenXpp3Reader reader = new MavenXpp3Reader();
            Model model = reader.read(new FileReader(".mvn/ge-plugins.xml"));

            // Iterate over plugins
            for(Dependency dependency : model.getDependencies()) {
                String pluginFileLocation = getLocationWithIvy(dependency.getGroupId(), dependency.getArtifactId(), dependency.getVersion(), session.getLocalRepository().getBasedir());
                if(pluginFileLocation != null) {
                    System.out.println("Adding " + pluginFileLocation);
                    plugins.add(new File(pluginFileLocation.replace("file:","")).toURI().toURL());
                }
            }

            if(!plugins.isEmpty()) {
                try(URLClassLoader urlClassLoader = new URLClassLoader(plugins.toArray(new URL[0]), this.getClass().getClassLoader())) {
                    ServiceLoader<GradleEnterprisePluginApi> loader = ServiceLoader.load(GradleEnterprisePluginApi.class, urlClassLoader);

                    for (GradleEnterprisePluginApi plugin : loader) {
                        System.out.println("Loading " + plugin.getClass().getSimpleName());
                        plugin.configure(api, session);
                    }
                }
            }
        } catch (IOException | XmlPullParserException | ParseException e) {
            throw new RuntimeException(e);
        }
    }

    private String getLocationWithIvy(String groupId, String artifactId, String version, String localRepository) throws ParseException, IOException {
        // create an ivy instance
        IvySettings ivySettings = new IvySettings();
        ivySettings.setDefaultCache(new File("target/ivy/cache"));

        // Configure the biblio resolver
        IBiblioResolver br = new IBiblioResolver();
        br.setM2compatible(true);
        br.setUsepoms(true);
        br.setRoot("file://" + localRepository);
        br.setName("local");

        ivySettings.addResolver(br);
        ivySettings.setDefaultResolver(br.getName());

        Ivy ivy = Ivy.newInstance(ivySettings);

        // Step 1: you always need to resolve before you can retrieve
        ResolveOptions ro = new ResolveOptions();
        ro.setTransitive(false);
        ro.setDownload(true);

        DefaultModuleDescriptor md = DefaultModuleDescriptor.newDefaultInstance(
            ModuleRevisionId.newInstance(
                groupId,
                artifactId+"-envelope",
                version
            )
        );

        ModuleRevisionId ri = ModuleRevisionId.newInstance(
            groupId,
            artifactId,
            version
        );

        DefaultDependencyDescriptor dd = new DefaultDependencyDescriptor(md, ri, false, false, false);
        dd.addDependencyConfiguration("default", "master");
        md.addDependency(dd);

        ResolveReport rr = ivy.resolve(md,ro);
        if (rr.hasError()) {
            throw new RuntimeException(rr.getAllProblemMessages().toString());
        }

        if(rr.getAllArtifactsReports().length > 0) {
            return rr.getAllArtifactsReports()[0].getArtifactOrigin().getLocation();
        } else {
            return null;
        }


        // Step 2: retrieve
//        ModuleDescriptor m = rr.getModuleDescriptor();

//        RetrieveReport report = ivy.retrieve(
//            m.getModuleRevisionId(),
//            new RetrieveOptions()
//                .setConfs(new String[]{"default"})
//                .setDestArtifactPattern("/[artifact](-[classifier]).[ext]")
//        );
//
//        for(File f : report.getRetrievedFiles()) {
//            System.out.println("Retrieved " + f.getAbsolutePath());
//        }
    }
}
