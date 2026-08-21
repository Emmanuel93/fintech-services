package com.fintech.channels.application.service;

import com.fintech.channels.application.ChannelsProperties;
import com.fintech.channels.application.port.out.ChannelRepository;
import com.fintech.channels.application.port.out.ChannelsEventPublisher;
import com.fintech.channels.application.port.out.LeadRequestRepository;
import com.fintech.channels.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@Transactional
public class LeadService {

    private final ChannelRepository channelRepository;
    private final LeadRequestRepository leadRepository;
    private final ChannelsEventPublisher eventPublisher;
    private final ChannelsProperties properties;

    public LeadService(ChannelRepository channelRepository,
                       LeadRequestRepository leadRepository,
                       ChannelsEventPublisher eventPublisher,
                       ChannelsProperties properties) {
        this.channelRepository = channelRepository;
        this.leadRepository    = leadRepository;
        this.eventPublisher    = eventPublisher;
        this.properties        = properties;
    }

    public LeadRequest createLead(UUID channelId, IntentType intentType,
                                  String firstName, String lastName1,
                                  String phone, String email,
                                  UUID promoterPartyId) {
        Channel channel = channelRepository.findById(channelId)
                .orElseThrow(() -> new ChannelNotFoundException(channelId.toString()));

        // TTL: 7d for FIELD_PROMOTER, 30d for digital channels
        int ttlDays = ChannelType.FIELD_PROMOTER.name().equals(channel.getChannelType())
                ? properties.getLeadTtlDaysPromoter()
                : properties.getLeadTtlDaysDigital();

        LeadRequest lead = LeadRequest.create(channelId, intentType,
                firstName, lastName1, phone, email, promoterPartyId, ttlDays);
        leadRepository.save(lead);
        eventPublisher.publishLeadCreated(lead.getLeadId(), channel.getChannelType(), promoterPartyId);
        return lead;
    }

    @Transactional(readOnly = true)
    public LeadRequest getLead(UUID leadId) {
        return leadRepository.findById(leadId)
                .orElseThrow(() -> new LeadNotFoundException(leadId.toString()));
    }

    public LeadRequest convertLead(UUID leadId, UUID convertedPartyId) {
        LeadRequest lead = leadRepository.findById(leadId)
                .orElseThrow(() -> new LeadNotFoundException(leadId.toString()));

        lead.convert(convertedPartyId);
        leadRepository.save(lead);
        eventPublisher.publishLeadConverted(lead.getLeadId(), convertedPartyId);
        return lead;
    }
}
