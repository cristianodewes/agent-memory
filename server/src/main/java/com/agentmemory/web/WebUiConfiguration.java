package com.agentmemory.web;

import com.agentmemory.config.AgentMemoryConfig;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Mounts the embedded read-only {@code /web} browser (issue #36): a static, dependency-light SPA over
 * the {@code /api/v1} read API. The reference UI ships on the classpath ({@code web-ui/index.html});
 * an operator can point {@code --web-ui-dir} ({@code agent-memory.server.web-ui-dir}) at a custom SPA
 * build instead and the reference UI steps aside.
 *
 * <h2>Same-origin + base-path</h2>
 * The UI is served from the same server as {@code /api/v1} and {@code /mcp} and fetches the API at a
 * <em>relative</em> path ({@code ../api/v1}). Because relocation is done with
 * {@code server.servlet.context-path} (see {@link BasePathEnvironmentPostProcessor}), {@code /web},
 * {@code /api/v1} and {@code /mcp} all move under {@code --base-path} together with no change here —
 * the relative fetch keeps resolving.
 *
 * <h2>Read-only</h2>
 * This only registers a <strong>static-resource</strong> handler (GET/HEAD) and an index redirect;
 * it mounts no write endpoints. The shipped UI issues only {@code GET}s. Auth that guards non-GET
 * browser requests is #38, deliberately out of scope here.
 *
 * <p>Registered as a plain component-scanned {@code WebMvcConfigurer} (not an {@code @AutoConfiguration})
 * — it needs no {@code DataSource} and is always present so {@code /web} is reachable even before a
 * project exists (the UI then shows an empty state). It depends only on {@link AgentMemoryConfig} for
 * the optional custom-dir knob.
 */
@Configuration(proxyBeanMethods = false)
public class WebUiConfiguration implements WebMvcConfigurer {

    private static final Logger log = LoggerFactory.getLogger(WebUiConfiguration.class);

    /** Where the reference UI lives on the classpath. */
    static final String CLASSPATH_UI = "classpath:/web-ui/";

    /** The mount point for the browser UI (relocated under {@code --base-path} by the context path). */
    static final String WEB_PATH = "/web";

    private final AgentMemoryConfig config;

    public WebUiConfiguration(AgentMemoryConfig config) {
        this.config = config;
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        String location = resolveLocation();
        log.info("web UI ({}) served at {}/ from {}", "#36", WEB_PATH, location);
        // Serve everything under /web/** from the chosen location. GET/HEAD only — Spring's resource
        // handler does not accept mutating methods, so the surface stays read-only.
        registry.addResourceHandler(WEB_PATH + "/**")
                .addResourceLocations(location)
                .setCachePeriod(0);
    }

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        // Bare /web -> /web/ so the page's relative "../api/v1" base resolves against /web/ (one dir
        // up = the context root), not against /. A 301 keeps the browser address bar canonical.
        registry.addRedirectViewController(WEB_PATH, WEB_PATH + "/");
        // A directory request (/web/) does not auto-resolve an index file through a resource handler,
        // so forward it to the served index.html — the SPA entry point.
        registry.addViewController(WEB_PATH + "/").setViewName("forward:" + WEB_PATH + "/index.html");
    }

    /**
     * The resource location for {@code /web}: a custom {@code --web-ui-dir} as a {@code file:} URL when
     * set and usable, otherwise the bundled classpath UI.
     */
    private String resolveLocation() {
        String dir = config.server().webUiDir();
        if (dir == null || dir.isBlank()) {
            return CLASSPATH_UI;
        }
        Path p = Path.of(dir.trim()).toAbsolutePath().normalize();
        // file: location must end with a slash for the resource handler to treat it as a directory.
        String uri = p.toUri().toString();
        if (!uri.endsWith("/")) {
            uri = uri + "/";
        }
        return uri;
    }
}
