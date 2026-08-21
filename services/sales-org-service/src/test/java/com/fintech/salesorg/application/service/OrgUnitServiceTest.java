package com.fintech.salesorg.application.service;

import com.fintech.salesorg.application.port.in.CreateOrgUnitCommand;
import com.fintech.salesorg.application.port.out.OrgLevelRepository;
import com.fintech.salesorg.application.port.out.OrgUnitRepository;
import com.fintech.salesorg.domain.DuplicateCodeException;
import com.fintech.salesorg.domain.InvalidHierarchyException;
import com.fintech.salesorg.domain.OrgLevel;
import com.fintech.salesorg.domain.OrgUnit;
import com.fintech.salesorg.domain.OrgUnitNotFoundException;
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
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class OrgUnitServiceTest {

    @Mock OrgUnitRepository  unitRepository;
    @Mock OrgLevelRepository levelRepository;

    OrgUnitService service;

    // Escalera: NATIONAL(0) -> REGION(1) -> ZONE(2)
    OrgLevel national;
    OrgLevel region;
    OrgLevel zone;

    @BeforeEach
    void setUp() {
        service = new OrgUnitService(unitRepository, levelRepository);
        national = OrgLevel.create("NATIONAL", "Nacional", 0);
        region   = OrgLevel.create("REGION", "Región", 1);
        zone     = OrgLevel.create("ZONE", "Zona", 2);
        lenient().when(unitRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void create_root_setsPathToOwnCode() {
        given(unitRepository.existsByCode("MX")).willReturn(false);
        given(levelRepository.findById(national.getLevelId())).willReturn(Optional.of(national));

        OrgUnit unit = service.create(new CreateOrgUnitCommand(
                national.getLevelId(), null, "MX", "México", null, "staff-1"));

        assertThat(unit.getPath()).isEqualTo("MX");
        assertThat(unit.isRoot()).isTrue();
    }

    @Test
    void create_rootAtNonZeroLevel_throws() {
        given(unitRepository.existsByCode("NORTE")).willReturn(false);
        given(levelRepository.findById(region.getLevelId())).willReturn(Optional.of(region));

        assertThatThrownBy(() -> service.create(new CreateOrgUnitCommand(
                region.getLevelId(), null, "NORTE", "Norte", null, "staff-1")))
                .isInstanceOf(InvalidHierarchyException.class);
        verify(unitRepository, never()).save(any());
    }

    @Test
    void create_child_buildsPathFromParent() {
        OrgUnit root = OrgUnit.create(national.getLevelId(), null, "MX", "México", "MX", null, "sys");
        given(unitRepository.existsByCode("NORTE")).willReturn(false);
        given(levelRepository.findById(region.getLevelId())).willReturn(Optional.of(region));
        given(unitRepository.findById(root.getUnitId())).willReturn(Optional.of(root));
        given(levelRepository.findById(national.getLevelId())).willReturn(Optional.of(national));

        OrgUnit unit = service.create(new CreateOrgUnitCommand(
                region.getLevelId(), root.getUnitId(), "NORTE", "Norte", null, "staff-1"));

        assertThat(unit.getPath()).isEqualTo("MX.NORTE");
    }

    @Test
    void create_childSkippingALevel_throws() {
        // Padre nacional (0), hijo declarado ZONE (2): salta REGION. Debe rechazarse.
        OrgUnit root = OrgUnit.create(national.getLevelId(), null, "MX", "México", "MX", null, "sys");
        given(unitRepository.existsByCode("MTY")).willReturn(false);
        given(levelRepository.findById(zone.getLevelId())).willReturn(Optional.of(zone));
        given(unitRepository.findById(root.getUnitId())).willReturn(Optional.of(root));
        given(levelRepository.findById(national.getLevelId())).willReturn(Optional.of(national));

        assertThatThrownBy(() -> service.create(new CreateOrgUnitCommand(
                zone.getLevelId(), root.getUnitId(), "MTY", "Monterrey", null, "staff-1")))
                .isInstanceOf(InvalidHierarchyException.class);
    }

    @Test
    void create_duplicateCode_throws() {
        given(unitRepository.existsByCode("MX")).willReturn(true);

        assertThatThrownBy(() -> service.create(new CreateOrgUnitCommand(
                national.getLevelId(), null, "MX", "México", null, "staff-1")))
                .isInstanceOf(DuplicateCodeException.class);
    }

    @Test
    void create_invalidLtreeLabel_throws() {
        given(unitRepository.existsByCode("BAD-CODE")).willReturn(false);
        given(levelRepository.findById(national.getLevelId())).willReturn(Optional.of(national));

        assertThatThrownBy(() -> service.create(new CreateOrgUnitCommand(
                national.getLevelId(), null, "BAD-CODE", "Malo", null, "staff-1")))
                .isInstanceOf(InvalidHierarchyException.class);
    }

    @Test
    void subtree_queriesByUnitPath() {
        OrgUnit norte = OrgUnit.create(region.getLevelId(),
                UUID.randomUUID(), "NORTE", "Norte", "MX.NORTE", null, "sys");
        given(unitRepository.findById(norte.getUnitId())).willReturn(Optional.of(norte));
        given(unitRepository.findSubtree("MX.NORTE")).willReturn(List.of(norte));

        List<OrgUnit> subtree = service.subtree(norte.getUnitId());

        assertThat(subtree).containsExactly(norte);
    }

    @Test
    void get_notFound_throws() {
        UUID id = UUID.randomUUID();
        given(unitRepository.findById(id)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(id)).isInstanceOf(OrgUnitNotFoundException.class);
    }

    @Test
    void create_executiveNode_underBranch_carriesPartyRef() {
        // Escalera extendida: BRANCH(3) -> EXECUTIVE(4). El nodo ejecutivo referencia su staffUserId.
        OrgLevel branch    = OrgLevel.create("BRANCH", "Sucursal", 3);
        OrgLevel executive = OrgLevel.create("EXECUTIVE", "Ejecutivo", 4);
        OrgUnit branchUnit = OrgUnit.create(branch.getLevelId(), UUID.randomUUID(),
                "SUC1", "Sucursal 1", "MX.NORTE.MTY.SUC1", null, "sys");
        UUID staffId = UUID.randomUUID();
        given(unitRepository.existsByCode("EXEC1")).willReturn(false);
        given(levelRepository.findById(executive.getLevelId())).willReturn(Optional.of(executive));
        given(unitRepository.findById(branchUnit.getUnitId())).willReturn(Optional.of(branchUnit));
        given(levelRepository.findById(branch.getLevelId())).willReturn(Optional.of(branch));

        OrgUnit node = service.create(new CreateOrgUnitCommand(
                executive.getLevelId(), branchUnit.getUnitId(), "EXEC1", "Ana Torres", staffId, "admin"));

        assertThat(node.getPartyRef()).isEqualTo(staffId);
        assertThat(node.getPath()).isEqualTo("MX.NORTE.MTY.SUC1.EXEC1");
    }

    @Test
    void distributorsInSubtree_queriesRepoByUnitPathAndDistributorLevel() {
        UUID unitId = UUID.randomUUID();
        OrgUnit unit = OrgUnit.create(UUID.randomUUID(), null, "MX", "México", "MX", null, "sys");
        given(unitRepository.findById(unitId)).willReturn(Optional.of(unit));
        UUID distId = UUID.randomUUID();
        given(unitRepository.findPartyRefsInSubtreeByLevel("MX", "DISTRIBUTOR")).willReturn(List.of(distId));

        assertThat(service.distributorsInSubtree(unitId)).containsExactly(distId);
    }

    @Test
    void resolveDistributorByCode_delegatesWithDistributorLevel() {
        UUID distId = UUID.randomUUID();
        given(unitRepository.findPartyRefByCodeAndLevel("DIST0001", "DISTRIBUTOR"))
                .willReturn(Optional.of(distId));

        assertThat(service.resolveDistributorByCode("DIST0001")).contains(distId);
    }
}
