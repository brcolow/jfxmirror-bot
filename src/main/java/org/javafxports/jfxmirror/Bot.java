package org.javafxports.jfxmirror;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import javax.ws.rs.core.UriBuilder;

import org.glassfish.grizzly.http.server.HttpServer;
import org.glassfish.jersey.grizzly2.httpserver.GrizzlyHttpServerFactory;
import org.glassfish.jersey.server.ResourceConfig;
import org.glassfish.jersey.server.ServerProperties;

import com.aragost.javahg.Repository;
import com.fasterxml.jackson.jaxrs.json.JacksonJaxbJsonProvider;

public class Bot {

    protected static Repository upstreamRepo;
    private static final String upstreamRepoUrl = "http://hg.openjdk.java.net/openjfx/jfx-dev/rt";
    private static final Path upstreamRepoPath = Paths.get(System.getProperty("user.home"), "jfxmirror", "upstream");

    public static void main(String[] args) throws IOException
    {
        if (!Files.exists(upstreamRepoPath)) {
            // Probably the first time running, clone the upstream OpenJFX repository.
            System.out.println("Upstream mercurial repository directory not found, creating it...");
            System.out.println("Creating " + upstreamRepoPath);
            try {
                Files.createDirectories(upstreamRepoPath);
            } catch (IOException e) {
                System.err.println("Could not create mercurial repository directory: " + upstreamRepoPath);
                e.printStackTrace();
                System.exit(1);
            }

            System.out.println("Cloning upstream OpenJFX mercurial repository...");
            upstreamRepo = Repository.clone(upstreamRepoPath.toFile(), upstreamRepoUrl);
        } else {
            // Repository already exists.
            upstreamRepo = Repository.open(upstreamRepoPath.toFile());
        }

        System.out.println("Initialized OpenJFX upstream repository: " + upstreamRepo.getDirectory());
        System.out.println("Using mercurial version: " + upstreamRepo.getHgVersion());

        ResourceConfig resourceConfig = new ResourceConfig()
                .packages("org.javafxports.jfxmirror")
                .property(ServerProperties.WADL_FEATURE_DISABLE, true)
                .register(JacksonJaxbJsonProvider.class);

        HttpServer grizzlyServer = GrizzlyHttpServerFactory.createHttpServer(getBaseURI(), resourceConfig);
        try {
            grizzlyServer.start();
        } catch (IOException e) {
            e.printStackTrace();
            cleanup();
            System.exit(1);
        }

        System.out.println("HTTP server started, press any key to stop.");
        System.in.read();
        cleanup();
        System.exit(0);
    }

    private static void cleanup() {
        upstreamRepo.close();
    }

    private static URI getBaseURI() {
        return UriBuilder.fromUri("http://localhost/").port(8433).build();
    }
}
