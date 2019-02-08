module tcbot {
    exports org.apache.ignite.ci.web;
    requires ignite.slf4j;
    requires ignite.direct.io;
    requires ignite.indexing;
    requires annotations;
    requires slf4j.api;
    requires aopalliance;
    requires jsr305;
    requires jetty.server;
    requires jetty.webapp;
    requires com.google.common;
    requires java.xml.bind;
    requires javax.inject;
    requires gson;
    requires com.google.guice;
    requires ignite.core;
    requires cache.api;
}