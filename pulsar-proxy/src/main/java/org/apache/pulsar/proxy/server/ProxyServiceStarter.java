/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.pulsar.proxy.server;

import static com.google.common.base.Preconditions.checkArgument;
import static org.apache.commons.lang3.StringUtils.isBlank;
import static org.apache.commons.lang3.StringUtils.isEmpty;
import static org.apache.commons.lang3.StringUtils.isNotBlank;
import static org.apache.pulsar.common.stats.JvmMetrics.getJvmDirectMemoryUsed;
import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.google.common.annotations.VisibleForTesting;
import io.prometheus.client.CollectorRegistry;
import io.prometheus.client.Gauge;
import io.prometheus.client.Gauge.Child;
import io.prometheus.client.hotspot.DefaultExports;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import lombok.Getter;
import org.apache.logging.log4j.core.util.datetime.FixedDateFormat;
import org.apache.pulsar.broker.PulsarServerException;
import org.apache.pulsar.broker.ServiceConfiguration;
import org.apache.pulsar.broker.authentication.AuthenticationService;
import org.apache.pulsar.broker.stats.prometheus.PrometheusMetricsServlet;
import org.apache.pulsar.broker.web.plugin.servlet.AdditionalServletWithClassLoader;
import org.apache.pulsar.common.configuration.PulsarConfigurationLoader;
import org.apache.pulsar.common.configuration.VipStatus;
import org.apache.pulsar.common.policies.data.ClusterData;
import org.apache.pulsar.common.util.CmdGenerateDocs;
import org.apache.pulsar.common.util.DirectMemoryUtils;
import org.apache.pulsar.proxy.stats.ProxyStats;
import org.apache.pulsar.websocket.WebSocketConsumerServlet;
import org.apache.pulsar.websocket.WebSocketPingPongServlet;
import org.apache.pulsar.websocket.WebSocketProducerServlet;
import org.apache.pulsar.websocket.WebSocketReaderServlet;
import org.apache.pulsar.websocket.WebSocketService;
import org.eclipse.jetty.proxy.ProxyServlet;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.websocket.servlet.WebSocketServlet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * Starts an instance of the Pulsar ProxyService.
 *
 */
public class ProxyServiceStarter {

    @Parameter(names = { "-c", "--config" }, description = "Configuration file path", required = true)
    private String configFile;

    @Deprecated
    @Parameter(names = { "-zk", "--zookeeper-servers" },
            description = "Local zookeeper connection string, please use --metadata-store instead")
    private String zookeeperServers = "";
    @Parameter(names = { "-md", "--metadata-store" }, description = "Metadata Store service url. eg: zk:my-zk:2181")
    private String metadataStoreUrl = "";

    @Deprecated
    @Parameter(names = { "-gzk", "--global-zookeeper-servers" },
            description = "Global zookeeper connection string, please use --configuration-metadata-store instead")
    private String globalZookeeperServers = "";

    @Deprecated
    @Parameter(names = { "-cs", "--configuration-store-servers" },
                    description = "Configuration store connection string, "
                            + "please use --configuration-metadata-store instead")
    private String configurationStoreServers = "";
    @Parameter(names = { "-cms", "--configuration-metadata-store" },
            description = "The metadata store URL for the configuration data")
    private String configurationMetadataStoreUrl = "";

    @Parameter(names = { "-h", "--help" }, description = "Show this help message")
    private boolean help = false;

    @Parameter(names = {"-g", "--generate-docs"}, description = "Generate docs")
    private boolean generateDocs = false;

    private ProxyConfiguration config;

    @Getter
    private ProxyService proxyService;

    private WebServer server;

    public ProxyServiceStarter(String[] args) throws Exception {
        try {
            DateFormat dateFormat = new SimpleDateFormat(
                FixedDateFormat.FixedFormat.ISO8601_OFFSET_DATE_TIME_HHMM.getPattern());
            Thread.setDefaultUncaughtExceptionHandler((thread, exception) -> {
                System.out.printf("%s [%s] error Uncaught exception in thread %s: %s%n", dateFormat.format(new Date()),
                        thread.getContextClassLoader(), thread.getName(), exception.getMessage());
                exception.printStackTrace(System.out);
            });

            JCommander jcommander = new JCommander();
            try {
                jcommander.addObject(this);
                jcommander.parse(args);
                if (help || isBlank(configFile)) {
                    jcommander.usage();
                    return;
                }

                if (this.generateDocs) {
                    CmdGenerateDocs cmd = new CmdGenerateDocs("pulsar");
                    cmd.addCommand("proxy", this);
                    cmd.run(null);
                    System.exit(0);
                }
            } catch (Exception e) {
                jcommander.usage();
                System.exit(-1);
            }

            // load config file
            config = PulsarConfigurationLoader.create(configFile, ProxyConfiguration.class);

            if (!isBlank(metadataStoreUrl)) {
                // Use metadataStoreUrl from command line
                config.setMetadataStoreUrl(metadataStoreUrl);
            } else if (!isBlank(zookeeperServers)){
                // Use zookeeperServers from command line if metadataStoreUrl is empty;
                config.setMetadataStoreUrl(zookeeperServers);
            } else {
                // use "metadataStoreUrl" property in "proxy.conf".
            }

            if (!isBlank(globalZookeeperServers)) {
                config.setConfigurationMetadataStoreUrl(globalZookeeperServers);
            }
            if (!isBlank(configurationStoreServers)) {
                // Use configurationMetadataStoreUrl from command line
                config.setConfigurationMetadataStoreUrl(configurationStoreServers);
            }
            if (!isBlank(configurationMetadataStoreUrl)) {
                config.setConfigurationMetadataStoreUrl(configurationMetadataStoreUrl);
            }

            if (isNotBlank(config.getBrokerServiceURL())) {
                checkArgument(config.getBrokerServiceURL().startsWith("pulsar://"),
                        "brokerServiceURL must start with pulsar://");
            }

            if (isNotBlank(config.getBrokerServiceURLTLS())) {
                checkArgument(config.getBrokerServiceURLTLS().startsWith("pulsar+ssl://"),
                        "brokerServiceURLTLS must start with pulsar+ssl://");
            }

            if ((isBlank(config.getBrokerServiceURL()) && isBlank(config.getBrokerServiceURLTLS()))
                    || config.isAuthorizationEnabled()) {
                checkArgument(!isEmpty(config.getMetadataStoreUrl()), "metadataStoreUrl must be provided");
                checkArgument(!isEmpty(config.getConfigurationMetadataStoreUrl()),
                        "configurationMetadataStoreUrl must be provided");
            }

            if ((!config.isTlsEnabledWithBroker() && isBlank(config.getBrokerWebServiceURL()))
                    || (config.isTlsEnabledWithBroker() && isBlank(config.getBrokerWebServiceURLTLS()))) {
                checkArgument(!isEmpty(config.getMetadataStoreUrl()), "metadataStoreUrl must be provided");
            }

        } catch (Exception e) {
            log.error("Failed to start pulsar proxy service. error msg " + e.getMessage(), e);
            throw new PulsarServerException(e);
        }
    }

    public static void main(String[] args) throws Exception {
        ProxyServiceStarter serviceStarter = new ProxyServiceStarter(args);
        try {
            serviceStarter.start();
        } catch (Throwable t) {
            log.error("Failed to start proxy.", t);
            Runtime.getRuntime().halt(1);
        }
    }

    public void start() throws Exception {
        AuthenticationService authenticationService = new AuthenticationService(
                PulsarConfigurationLoader.convertFrom(config));
        // create proxy service
        proxyService = new ProxyService(config, authenticationService);
        // create a web-service
        server = new WebServer(config, authenticationService);

        Runtime.getRuntime().addShutdownHook(new Thread(this::close));

        proxyService.start();

        // Setup metrics
        DefaultExports.initialize();

        // Report direct memory from Netty counters
        Gauge.build("jvm_memory_direct_bytes_used", "-").create().setChild(new Child() {
            @Override
            public double get() {
                return getJvmDirectMemoryUsed();
            }
        }).register(CollectorRegistry.defaultRegistry);

        Gauge.build("jvm_memory_direct_bytes_max", "-").create().setChild(new Child() {
            @Override
            public double get() {
                return DirectMemoryUtils.jvmMaxDirectMemory();
            }
        }).register(CollectorRegistry.defaultRegistry);

        addWebServerHandlers(server, config, proxyService, proxyService.getDiscoveryProvider());

        // start web-service
        server.start();
    }

    public void close() {
        try {
            if (proxyService != null) {
                proxyService.close();
            }
            if (server != null) {
                server.stop();
            }
        } catch (Exception e) {
            log.warn("server couldn't stop gracefully {}", e.getMessage(), e);
        }
    }

    public static void addWebServerHandlers(WebServer server,
                                     ProxyConfiguration config,
                                     ProxyService service,
                                     BrokerDiscoveryProvider discoveryProvider) throws Exception {
        if (service != null) {
            PrometheusMetricsServlet metricsServlet = service.getMetricsServlet();
            if (metricsServlet != null) {
                server.addServlet("/metrics", new ServletHolder(metricsServlet),
                        Collections.emptyList(), config.isAuthenticateMetricsEndpoint());
            }
        }
        server.addRestResource("/", VipStatus.ATTRIBUTE_STATUS_FILE_PATH, config.getStatusFilePath(), VipStatus.class);
        server.addRestResource("/proxy-stats", ProxyStats.ATTRIBUTE_PULSAR_PROXY_NAME, service, ProxyStats.class);

        AdminProxyHandler adminProxyHandler = new AdminProxyHandler(config, discoveryProvider);
        ServletHolder servletHolder = new ServletHolder(adminProxyHandler);
        servletHolder.setInitParameter("preserveHost", "true");
        server.addServlet("/admin", servletHolder);
        server.addServlet("/lookup", servletHolder);

        for (ProxyConfiguration.HttpReverseProxyConfig revProxy : config.getHttpReverseProxyConfigs()) {
            log.debug("Adding reverse proxy with config {}", revProxy);
            ServletHolder proxyHolder = new ServletHolder(ProxyServlet.Transparent.class);
            proxyHolder.setInitParameter("proxyTo", revProxy.getProxyTo());
            proxyHolder.setInitParameter("prefix", "/");
            server.addServlet(revProxy.getPath(), proxyHolder);
        }

        // add proxy additional servlets
        if (service != null && service.getProxyAdditionalServlets() != null) {
            Collection<AdditionalServletWithClassLoader> additionalServletCollection =
                    service.getProxyAdditionalServlets().getServlets().values();
            for (AdditionalServletWithClassLoader servletWithClassLoader : additionalServletCollection) {
                servletWithClassLoader.loadConfig(config);
                server.addServlet(servletWithClassLoader.getBasePath(), servletWithClassLoader.getServletHolder(),
                        Collections.emptyList(), config.isAuthenticationEnabled());
                log.info("proxy add additional servlet basePath {} ", servletWithClassLoader.getBasePath());
            }
        }

        if (config.isWebSocketServiceEnabled()) {
            // add WebSocket servlet
            // Use local broker address to avoid different IP address when using a VIP for service discovery
            ServiceConfiguration serviceConfiguration = PulsarConfigurationLoader.convertFrom(config);
            serviceConfiguration.setBrokerClientTlsEnabled(config.isTlsEnabledWithBroker());
            WebSocketService webSocketService = new WebSocketService(createClusterData(config), serviceConfiguration);
            webSocketService.start();
            final WebSocketServlet producerWebSocketServlet = new WebSocketProducerServlet(webSocketService);
            server.addServlet(WebSocketProducerServlet.SERVLET_PATH,
                    new ServletHolder(producerWebSocketServlet));
            server.addServlet(WebSocketProducerServlet.SERVLET_PATH_V2,
                    new ServletHolder(producerWebSocketServlet));

            final WebSocketServlet consumerWebSocketServlet = new WebSocketConsumerServlet(webSocketService);
            server.addServlet(WebSocketConsumerServlet.SERVLET_PATH,
                    new ServletHolder(consumerWebSocketServlet));
            server.addServlet(WebSocketConsumerServlet.SERVLET_PATH_V2,
                    new ServletHolder(consumerWebSocketServlet));

            final WebSocketServlet readerWebSocketServlet = new WebSocketReaderServlet(webSocketService);
            server.addServlet(WebSocketReaderServlet.SERVLET_PATH,
                    new ServletHolder(readerWebSocketServlet));
            server.addServlet(WebSocketReaderServlet.SERVLET_PATH_V2,
                    new ServletHolder(readerWebSocketServlet));

            final WebSocketServlet pingPongWebSocketServlet = new WebSocketPingPongServlet(webSocketService);
            server.addServlet(WebSocketPingPongServlet.SERVLET_PATH,
                    new ServletHolder(pingPongWebSocketServlet));
            server.addServlet(WebSocketPingPongServlet.SERVLET_PATH_V2,
                    new ServletHolder(pingPongWebSocketServlet));
        }
    }

    private static ClusterData createClusterData(ProxyConfiguration config) {
        if (isNotBlank(config.getBrokerServiceURL()) || isNotBlank(config.getBrokerServiceURLTLS())) {
            return ClusterData.builder()
                    .serviceUrl(config.getBrokerWebServiceURL())
                    .serviceUrlTls(config.getBrokerWebServiceURLTLS())
                    .brokerServiceUrl(config.getBrokerServiceURL())
                    .brokerServiceUrlTls(config.getBrokerServiceURLTLS())
                    .build();
        } else if (isNotBlank(config.getBrokerWebServiceURL()) || isNotBlank(config.getBrokerWebServiceURLTLS())) {
            return ClusterData.builder()
                    .serviceUrl(config.getBrokerWebServiceURL())
                    .serviceUrlTls(config.getBrokerWebServiceURLTLS())
                    .build();
        } else {
            return null;
        }
    }

    @VisibleForTesting
    public ProxyConfiguration getConfig() {
        return config;
    }

    @VisibleForTesting
    public WebServer getServer() {
        return server;
    }

    private static final Logger log = LoggerFactory.getLogger(ProxyServiceStarter.class);

}
