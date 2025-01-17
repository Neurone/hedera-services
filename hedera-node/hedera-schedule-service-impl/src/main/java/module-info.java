module com.hedera.node.app.service.schedule.impl {
    requires transitive com.hedera.node.app.service.schedule;
    requires transitive com.hedera.node.app.spi;
    requires transitive com.hedera.node.hapi;
    requires transitive dagger;
    requires transitive javax.inject;

    // Only ScheduleServiceStateTranslator requires this item, when that is removed, this should also be removed.
    requires transitive com.hedera.node.app.service.mono;
    // Required for ReadableAccountStore to read payer account details on create, sign, or query
    requires com.hedera.node.app.service.token;
    requires com.hedera.node.config;
    requires com.hedera.pbj.runtime;
    requires com.swirlds.config.api;
    requires com.swirlds.common;
    //
    requires org.apache.logging.log4j;
    requires com.google.common;
    requires static com.github.spotbugs.annotations;

    exports com.hedera.node.app.service.schedule.impl;
    exports com.hedera.node.app.service.schedule.impl.handlers;
    exports com.hedera.node.app.service.schedule.impl.codec;

    provides com.hedera.node.app.service.schedule.ScheduleService with
            com.hedera.node.app.service.schedule.impl.ScheduleServiceImpl;
}
