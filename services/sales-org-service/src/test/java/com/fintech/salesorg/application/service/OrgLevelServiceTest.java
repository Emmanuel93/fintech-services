package com.fintech.salesorg.application.service;

import com.fintech.salesorg.application.port.out.OrgLevelRepository;
import com.fintech.salesorg.application.port.out.OrgUnitRepository;
import com.fintech.salesorg.domain.DuplicateCodeException;
import com.fintech.salesorg.domain.InvalidHierarchyException;
import com.fintech.salesorg.domain.OrgLevel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class OrgLevelServiceTest {

    @Mock OrgLevelRepository levelRepository;
    @Mock OrgUnitRepository  unitRepository;

    OrgLevelService service;

    @BeforeEach
    void setUp() {
        service = new OrgLevelService(levelRepository, unitRepository);
        lenient().when(levelRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void append_onEmptyLadder_startsAtDepthZero() {
        given(levelRepository.existsByCode("NATIONAL")).willReturn(false);
        given(levelRepository.maxDepth()).willReturn(Optional.empty());

        OrgLevel created = service.append("NATIONAL", "Nacional");

        assertThat(created.getDepth()).isZero();
    }

    @Test
    void append_placesAtMaxDepthPlusOne() {
        given(levelRepository.existsByCode("BRANCH")).willReturn(false);
        given(levelRepository.maxDepth()).willReturn(Optional.of(2));

        OrgLevel created = service.append("BRANCH", "Sucursal");

        assertThat(created.getDepth()).isEqualTo(3);
    }

    @Test
    void append_duplicateCode_throws() {
        given(levelRepository.existsByCode("REGION")).willReturn(true);

        assertThatThrownBy(() -> service.append("REGION", "Región"))
                .isInstanceOf(DuplicateCodeException.class);
        verify(levelRepository, never()).save(any());
    }

    @Test
    void insertAt_whenNoUnitsOccupyThosePositions_shiftsDownAndInserts() {
        given(levelRepository.existsByCode("SUBREGION")).willReturn(false);
        // Niveles en profundidad >= 1, del más profundo al menos: zona(2), región(1).
        OrgLevel region = OrgLevel.create("REGION", "Región", 1);
        OrgLevel zone   = OrgLevel.create("ZONE", "Zona", 2);
        given(levelRepository.findFromDepthDescending(1)).willReturn(List.of(zone, region));
        given(unitRepository.existsByLevelId(any())).willReturn(false);

        OrgLevel created = service.insertAt(1, "SUBREGION", "Subregión");

        // Los existentes corrieron uno hacia abajo y el nuevo ocupa la posición 1.
        assertThat(zone.getDepth()).isEqualTo(3);
        assertThat(region.getDepth()).isEqualTo(2);
        assertThat(created.getDepth()).isEqualTo(1);
        verify(levelRepository, times(3)).save(any()); // 2 corridos + 1 nuevo
    }

    @Test
    void insertAt_whenUnitsAlreadyOccupyThosePositions_throws() {
        given(levelRepository.existsByCode("SUBREGION")).willReturn(false);
        OrgLevel zone = OrgLevel.create("ZONE", "Zona", 2);
        given(levelRepository.findFromDepthDescending(2)).willReturn(List.of(zone));
        given(unitRepository.existsByLevelId(zone.getLevelId())).willReturn(true);

        assertThatThrownBy(() -> service.insertAt(2, "SUBREGION", "Subregión"))
                .isInstanceOf(InvalidHierarchyException.class);
        verify(levelRepository, never()).save(any());
    }

    @Test
    void insertAt_negativeDepth_throws() {
        assertThatThrownBy(() -> service.insertAt(-1, "X", "X"))
                .isInstanceOf(InvalidHierarchyException.class);
    }

    @Test
    void list_returnsLadderFromRepository() {
        given(levelRepository.findAllOrderByDepthAsc())
                .willReturn(List.of(OrgLevel.create("NATIONAL", "Nacional", 0)));

        assertThat(service.list()).hasSize(1);
    }
}
