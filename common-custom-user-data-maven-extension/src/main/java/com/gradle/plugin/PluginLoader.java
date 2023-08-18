package com.gradle.plugin;

import com.gradle.GradleEnterprisePluginApi;
import com.gradle.maven.extension.api.GradleEnterpriseApi;
import org.apache.ivy.Ivy;
import org.apache.ivy.core.module.descriptor.DefaultDependencyDescriptor;
import org.apache.ivy.core.module.descriptor.DefaultModuleDescriptor;
import org.apache.ivy.core.module.descriptor.ModuleDescriptor;
import org.apache.ivy.core.module.id.ModuleRevisionId;
import org.apache.ivy.core.report.ArtifactDownloadReport;
import org.apache.ivy.core.report.ResolveReport;
import org.apache.ivy.core.resolve.ResolveOptions;
import org.apache.ivy.core.retrieve.RetrieveOptions;
import org.apache.ivy.core.retrieve.RetrieveReport;
import org.apache.ivy.core.settings.IvySettings;
import org.apache.ivy.plugins.resolver.ChainResolver;
import org.apache.ivy.plugins.resolver.IBiblioResolver;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.text.ParseException;
import java.util.*;

public class PluginLoader {

    private static final String PLUGIN_DIRECTORY = ".mvn/ge-plugins/";
    private static final String PLUGIN_DESCRIPTOR = ".mvn/ge-plugins.xml";

    private static final String IVY_LOCAL_CACHE = "target/ivy/cache";

    private final Logger logger = LoggerFactory.getLogger(PluginLoader.class);

    public void loadAndConfigurePlugins(GradleEnterpriseApi api, MavenSession session) {
        Set<URL> plugins = loadPlugins(session);
        configurePlugins(api, session, plugins);
    }

    private Set<URL> loadPlugins(MavenSession session) {
        try {
            Set<URL> plugins = new HashSet<>();

            plugins.addAll(loadPluginsFromPluginDirectory());
            plugins.addAll(loadPluginsFromPluginDescriptor(session));

            return plugins;
        } catch (IOException | XmlPullParserException | ParseException e) {
            throw new IllegalStateException("Error loading plugins", e);
        }
    }

    private Collection<URL> loadPluginsFromPluginDirectory() {
        return new HashSet<>();
    }

    private Collection<URL> loadPluginsFromPluginDescriptor(MavenSession session) throws IOException, XmlPullParserException, ParseException {
        Set<URL> plugins = new HashSet<>();

        // read plugin descriptor
        MavenXpp3Reader reader = new MavenXpp3Reader();
        Model model = reader.read(new FileReader(PLUGIN_DESCRIPTOR));

        // Iterate over plugins
        for (Dependency dependency : model.getDependencies()) {
            logger.info("Fetching " + dependency.getArtifactId());
            URL plugin = getPluginWithIvy(dependency.getGroupId(), dependency.getArtifactId(), dependency.getVersion(), session.getLocalRepository().getBasedir());
            if (plugin != null) {
                logger.info("Adding " + plugin);
                //plugins.add(new File(plugin.replace("file:", "")).toURI().toURL());
                plugins.add(plugin);
            }
        }

        return plugins;
    }

    private void configurePlugins(GradleEnterpriseApi api, MavenSession session, Set<URL> plugins) {
        if (!plugins.isEmpty()) {
            try (URLClassLoader urlClassLoader = new URLClassLoader(plugins.toArray(new URL[0]), this.getClass().getClassLoader())) {
                ServiceLoader<GradleEnterprisePluginApi> loader = ServiceLoader.load(GradleEnterprisePluginApi.class, urlClassLoader);

                for (GradleEnterprisePluginApi plugin : loader) {
                    System.out.println("Loading " + plugin.getClass().getSimpleName());
                    plugin.configure(api, session);
                }
            } catch (IOException e) {
                throw new IllegalStateException("Error configuring plugins", e);
            }
        }
    }

    private URL getPluginWithIvy(String groupId, String artifactId, String version, String localRepository) throws ParseException, IOException {
        // Configure local resolver
        IBiblioResolver local = new IBiblioResolver();
        local.setM2compatible(true);
        local.setUsepoms(true);
        local.setRoot("file://" + localRepository);
        local.setName("local");

        // Configure remote resolver
        IBiblioResolver remote = new IBiblioResolver();
        remote.setM2compatible(true);
        remote.setUsepoms(true);
        remote.setName("central");

        // Configure chain
        ChainResolver chain = new ChainResolver();
        chain.add(local);
        chain.add(remote);
        chain.setName("chain");

        IvySettings ivySettings = new IvySettings();
        ivySettings.setDefaultCache(new File(IVY_LOCAL_CACHE));
        ivySettings.addResolver(chain);
        ivySettings.setDefaultResolver(chain.getName());
        Ivy ivy = Ivy.newInstance(ivySettings);

        ResolveOptions resolveOptions = new ResolveOptions();
        resolveOptions.setTransitive(true);
        resolveOptions.setDownload(true);

        DefaultModuleDescriptor defaultModuleDescriptor = DefaultModuleDescriptor.newDefaultInstance(
            ModuleRevisionId.newInstance(
                groupId,
                artifactId + "-envelope",
                version
            )
        );

        ModuleRevisionId moduleRevisionId = ModuleRevisionId.newInstance(
            groupId,
            artifactId,
            version
        );

        DefaultDependencyDescriptor dependencyDescriptor = new DefaultDependencyDescriptor(defaultModuleDescriptor, moduleRevisionId, false, false, false);
        dependencyDescriptor.addDependencyConfiguration("default", "master");
        defaultModuleDescriptor.addDependency(dependencyDescriptor);

        ResolveReport resolveReport = ivy.resolve(defaultModuleDescriptor, resolveOptions);
        if (resolveReport.hasError()) {
            throw new RuntimeException(resolveReport.getAllProblemMessages().toString());
        }

        if (resolveReport.getAllArtifactsReports().length > 0) {
            ArtifactDownloadReport artifactsReport = resolveReport.getAllArtifactsReports()[0];
            File plugin = artifactsReport.getLocalFile();
            if(plugin.exists()) {
                logger.info("Fetching plugin from local repository");
                return plugin.toURI().toURL();
            } else {
                logger.info("Fetching plugin from remote repository");
                ModuleDescriptor moduleDescriptor = resolveReport.getModuleDescriptor();

                RetrieveReport retrieveReport = ivy.retrieve(
                    moduleDescriptor.getModuleRevisionId(),
                    new RetrieveOptions()
                        .setConfs(new String[]{"default"})
                        .setDestArtifactPattern(IVY_LOCAL_CACHE + "/[artifact](-[classifier]).[ext]")
                );

                if(retrieveReport.getRetrievedFiles().size() > 0) {
                    for(File f : retrieveReport.getRetrievedFiles()) {
                        logger.info("f = " + f.getAbsolutePath());
                    }
                    logger.info("f = " + retrieveReport.getRetrieveRoot().getAbsolutePath());
                    return retrieveReport.getRetrievedFiles().iterator().next().toURI().toURL();
                } else {
                    logger.info("No file found");
                }
            }

            return plugin.toURI().toURL();
        }

        return null;
    }
}
