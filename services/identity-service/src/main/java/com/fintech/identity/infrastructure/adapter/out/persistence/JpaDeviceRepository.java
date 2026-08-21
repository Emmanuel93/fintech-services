package com.fintech.identity.infrastructure.adapter.out.persistence;

import com.fintech.identity.application.port.out.DeviceRepository;
import com.fintech.identity.domain.Device;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface JpaDeviceRepository
        extends JpaRepository<Device, UUID>, DeviceRepository {

    @Override
    Optional<Device> findByPartyIdAndClientDeviceId(UUID partyId, String clientDeviceId);
}
