package com.teknologiia.dmarc.config;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

import java.io.IOException;

/**
 * Serves the built dashboard from the same origin as the API.
 *
 * <p>In development the interface runs on Vite's own port and proxies {@code /api}
 * across. That needs two ports open, which is one more than a free tunnel gives —
 * and it means CORS has to be relaxed for whatever address the tunnel hands out.
 * Building the frontend into {@code resources/static} removes both problems: one
 * port, one origin, and {@code /api} is simply a path on it.
 *
 * <p>The fallback below exists because the dashboard routes in the browser. A visitor
 * who reloads on {@code /analysis} asks the server for a file that was never built;
 * without this they get a 404 on a page that works perfectly when reached by
 * clicking. Anything under {@code /api} is deliberately excluded — a mistyped
 * endpoint must answer 404, not hand back the HTML shell.
 */
@Component
public class SinglePageAppConfig implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/**")
                .addResourceLocations("classpath:/static/")
                .resourceChain(true)
                .addResolver(new PathResourceResolver() {
                    @Override
                    protected Resource getResource(String path, Resource location) throws IOException {
                        Resource requested = location.createRelative(path);
                        if (requested.exists() && requested.isReadable()) {
                            return requested;
                        }

                        // Never answer an API or documentation path with the shell.
                        if (path.startsWith("api/") || path.startsWith("error")) {
                            return null;
                        }

                        Resource shell = new ClassPathResource("/static/index.html");
                        return shell.exists() ? shell : null;
                    }
                });
    }
}
