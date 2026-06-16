package org.swim.dungeonTrials;

import io.papermc.paper.plugin.loader.PluginClasspathBuilder;
import io.papermc.paper.plugin.loader.PluginLoader;
import io.papermc.paper.plugin.loader.library.impl.MavenLibraryResolver;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.repository.RemoteRepository;

@SuppressWarnings("UnstableApiUsage")
class DungeonTrialsLoader implements PluginLoader {

    @Override
    public void classloader(final PluginClasspathBuilder builder) {
        MavenLibraryResolver resolver = new MavenLibraryResolver();

        resolver.addDependency(new Dependency(
                new DefaultArtifact("org.xerial:sqlite-jdbc:3.53.2.0"), null));
        resolver.addDependency(new Dependency(
                new DefaultArtifact("com.github.ben-manes.caffeine:caffeine:3.2.4"), null));
        resolver.addDependency(new Dependency(
                new DefaultArtifact("net.luckperms:api:5.5"), null));

        resolver.addRepository(new RemoteRepository.Builder(
                "central", "default", MavenLibraryResolver.MAVEN_CENTRAL_DEFAULT_MIRROR
        ).build());

        builder.addLibrary(resolver);
    }
}
