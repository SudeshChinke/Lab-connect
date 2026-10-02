module com.labconnect.core {
    requires java.logging;
    requires java.base;
    requires java.naming;
    requires javafx.controls;
    requires javafx.fxml;
    requires org.slf4j;
    requires com.fasterxml.jackson.databind;
    requires com.fasterxml.jackson.datatype.jsr310;
    requires org.bouncycastle.provider;
    requires org.bouncycastle.pkix;
    requires org.yaml.snakeyaml;

    exports com.labconnect.core.protocol;
    exports com.labconnect.core.networking;
    exports com.labconnect.core.discovery;
    exports com.labconnect.core.messaging;
    exports com.labconnect.core.transfer;
    exports com.labconnect.core.security;
    exports com.labconnect.core.models;
    exports com.labconnect.core.config;
    exports com.labconnect.core.diagnostics;

    opens com.labconnect.core.protocol to com.fasterxml.jackson.databind;
    opens com.labconnect.core.models to com.fasterxml.jackson.databind;
    opens com.labconnect.core.config to com.fasterxml.jackson.databind;
    opens com.labconnect.core.discovery to com.fasterxml.jackson.databind;
    opens com.labconnect.core.security to com.fasterxml.jackson.databind;
}