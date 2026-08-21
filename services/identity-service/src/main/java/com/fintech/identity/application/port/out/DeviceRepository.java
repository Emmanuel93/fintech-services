package com.fintech.identity.application.port.out;

import com.fintech.identity.domain.Device;

import java.util.Optional;
import java.util.UUID;

public interface DeviceRepository {

    Optional<Device> findByPartyIdAndClientDeviceId(UUID partyId, String clientDeviceId);

    Device save(Device device);
}
