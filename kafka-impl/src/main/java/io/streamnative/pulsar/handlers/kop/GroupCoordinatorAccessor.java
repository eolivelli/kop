package io.streamnative.pulsar.handlers.kop;

import io.streamnative.pulsar.handlers.kop.coordinator.group.GroupCoordinator;

public interface GroupCoordinatorAccessor {
    GroupCoordinator getGroupCoordinator(String tenant);
}
