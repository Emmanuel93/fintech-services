package com.fintech.channelbackoffice.infrastructure.adapter.in.api;

import com.fintech.channelbackoffice.infrastructure.adapter.out.client.IdentityClient;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ExecutivesControllerTest {

    @Test
    void list_passesThroughIdentityDirectory() {
        IdentityClient identity = mock(IdentityClient.class);
        UUID id = UUID.randomUUID();
        when(identity.listExecutives(any()))
                .thenReturn(List.of(new IdentityClient.ExecutiveResponse(id, "Ana Torres")));

        MockHttpServletRequest http = new MockHttpServletRequest();
        http.addHeader("Authorization", "Bearer test-token");

        List<IdentityClient.ExecutiveResponse> out = new ExecutivesController(identity).list(http);

        assertThat(out).extracting(IdentityClient.ExecutiveResponse::name).containsExactly("Ana Torres");
    }
}
