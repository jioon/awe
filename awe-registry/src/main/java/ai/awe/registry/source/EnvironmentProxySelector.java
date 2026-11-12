package ai.awe.registry.source;

import java.io.IOException;
import java.net.*;
import java.util.*;

/** Uses the environment's supplied routing; never replaces the platform proxy or its TLS trust. */
final class EnvironmentProxySelector extends ProxySelector {
    private final Map<String, String> env;
    EnvironmentProxySelector(Map<String, String> env) { this.env = Map.copyOf(env); }

    @Override public List<Proxy> select(URI target) {
        String host = target.getHost();
        if (host == null || isLoopback(host) || bypassed(host)) return List.of(Proxy.NO_PROXY);
        String scheme = target.getScheme().toLowerCase(Locale.ROOT);
        String value = env.getOrDefault(scheme + "_proxy", env.getOrDefault(scheme.toUpperCase(Locale.ROOT) + "_PROXY", ""));
        if (value.isBlank()) return List.of(Proxy.NO_PROXY);
        URI proxy = URI.create(value);
        if (proxy.getHost() == null || !"http".equalsIgnoreCase(proxy.getScheme()))
            throw new IllegalArgumentException("Unsupported inherited source proxy configuration");
        return List.of(new Proxy(Proxy.Type.HTTP, new InetSocketAddress(proxy.getHost(), proxy.getPort() > 0 ? proxy.getPort() : 80)));
    }
    private boolean bypassed(String host) {
        String raw = env.getOrDefault("no_proxy", env.getOrDefault("NO_PROXY", ""));
        for (String entry : raw.split(",")) {
            String rule = entry.trim().toLowerCase(Locale.ROOT);
            if (rule.equals("*")) return true;
            if (rule.isEmpty()) continue;
            if (rule.contains(":") && !rule.startsWith("[")) rule = rule.substring(0, rule.lastIndexOf(':'));
            if (rule.startsWith(".")) rule = rule.substring(1);
            if (host.equalsIgnoreCase(rule) || host.toLowerCase(Locale.ROOT).endsWith("." + rule)) return true;
        }
        return false;
    }
    static boolean isLoopback(String host) {
        return host.equalsIgnoreCase("localhost") || host.equals("127.0.0.1") || host.equals("::1") || host.equals("[::1]");
    }
    @Override public void connectFailed(URI uri, SocketAddress sa, IOException ioe) { /* Caller returns a sanitized status. */ }
}
