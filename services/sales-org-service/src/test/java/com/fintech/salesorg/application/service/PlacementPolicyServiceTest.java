package com.fintech.salesorg.application.service;

import com.fintech.salesorg.application.EffectivePlacementPolicy;
import com.fintech.salesorg.application.port.out.OrgLevelRepository;
import com.fintech.salesorg.application.port.out.OrgUnitRepository;
import com.fintech.salesorg.application.port.out.PlacementPolicyEventPublisher;
import com.fintech.salesorg.application.port.out.PlacementPolicyRepository;
import com.fintech.salesorg.application.port.out.ProductEnvelopePort;
import com.fintech.salesorg.domain.ClientLimits;
import com.fintech.salesorg.domain.ClientType;
import com.fintech.salesorg.domain.InvalidPlacementPolicyException;
import com.fintech.salesorg.domain.OrgLevel;
import com.fintech.salesorg.domain.OrgUnit;
import com.fintech.salesorg.domain.PlacementPolicy;
import com.fintech.salesorg.domain.ProductEnvelope;
import com.fintech.salesorg.domain.UnitPlacementRule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.atLeastOnce;

@ExtendWith(MockitoExtension.class)
class PlacementPolicyServiceTest {

    @Mock OrgUnitRepository units;
    @Mock OrgLevelRepository levels;
    @Mock PlacementPolicyRepository rules;
    @Mock ProductEnvelopePort products;
    @Mock PlacementPolicyEventPublisher publisher;

    PlacementPolicyService service;

    // MX → R → Z → S (branch) → E (executive) → D (distributor), one level each.
    OrgLevel national = OrgLevel.create("NATIONAL", "Nacional", 0);
    OrgLevel region = OrgLevel.create("REGION", "Región", 1);
    OrgLevel zone = OrgLevel.create("ZONE", "Zona", 2);
    OrgLevel branch = OrgLevel.create("BRANCH", "Sucursal", 3);
    OrgLevel executive = OrgLevel.create("EXECUTIVE", "Ejecutivo", 4);
    OrgLevel distributor = OrgLevel.create("DISTRIBUTOR", "Distribuidor", 5);

    OrgUnit mx = OrgUnit.create(national.getLevelId(), null, "MX", "México", "MX", null, "s");
    OrgUnit r = OrgUnit.create(region.getLevelId(), mx.getUnitId(), "R", "Región", "MX.R", null, "s");
    OrgUnit z = OrgUnit.create(zone.getLevelId(), r.getUnitId(), "Z", "Zona", "MX.R.Z", null, "s");
    OrgUnit s = OrgUnit.create(branch.getLevelId(), z.getUnitId(), "S", "Sucursal", "MX.R.Z.S", null, "s");
    OrgUnit e = OrgUnit.create(executive.getLevelId(), s.getUnitId(), "E", "Ejecutivo", "MX.R.Z.S.E", UUID.randomUUID(), "s");
    UUID herParty = UUID.randomUUID();
    OrgUnit d = OrgUnit.create(distributor.getLevelId(), e.getUnitId(), "D", "Distribuidora", "MX.R.Z.S.E.D", herParty, "s");

    List<UnitPlacementRule> stored = new ArrayList<>();

    static final ProductEnvelope LINE = new ProductEnvelope("DL-001", new BigDecimal("1000"), new BigDecimal("10000"), 12, 24);

    @BeforeEach
    void setUp() {
        service = new PlacementPolicyService(units, levels, rules, products, publisher);
        lenient().when(levels.findAllOrderByDepthAsc()).thenReturn(List.of(national, region, zone, branch, executive, distributor));
        lenient().when(products.distributorLine()).thenReturn(Optional.of(LINE));
        for (OrgUnit u : List.of(mx, r, z, s, e, d)) {
            lenient().when(units.findByCode(u.getCode())).thenReturn(Optional.of(u));
        }
        lenient().when(units.findAncestorsInclusive("MX.R.Z.S")).thenReturn(List.of(mx, r, z, s));
        lenient().when(units.findAncestorsInclusive("MX.R.Z.S.E.D")).thenReturn(List.of(mx, r, z, s, e, d));
        lenient().when(units.findSubtree("MX.R.Z.S")).thenReturn(List.of(s, e, d));
        lenient().when(units.findSubtree("MX.R.Z.S.E.D")).thenReturn(List.of(d));
        // An in-memory rule store: what declare() saves is what resolution reads back.
        lenient().when(rules.findByUnitId(any())).thenAnswer(inv -> stored.stream().filter(x -> x.getUnitId().equals(inv.getArgument(0))).toList());
        lenient().when(rules.findByUnitIds(anyCollection())).thenAnswer(inv -> {
            Collection<UUID> ids = inv.getArgument(0);
            return stored.stream().filter(x -> ids.contains(x.getUnitId())).toList();
        });
        lenient().doAnswer(inv -> { Collection<UnitPlacementRule> rs = inv.getArgument(0); rs.forEach(x -> { if (!stored.contains(x)) stored.add(x); }); return null; })
                .when(rules).saveAll(anyCollection());
        lenient().doAnswer(inv -> { Collection<UnitPlacementRule> rs = inv.getArgument(0); stored.removeAll(rs); return null; })
                .when(rules).deleteAll(anyCollection());
    }

    static ClientLimits limits(String amount, String outstanding, Integer loans, Integer term) {
        return new ClientLimits(amount == null ? null : new BigDecimal(amount), outstanding == null ? null : new BigDecimal(outstanding), loans, term);
    }

    static PlacementPolicy branchPolicy() {
        return PlacementPolicy.of(limits("5000", "5000", 1, 16), limits("10000", "15000", 3, 24));
    }

    @Test
    void aBranchDeclaresItsPolicy_andEveryUnitBelowReceivesItResolved() {
        EffectivePlacementPolicy result = service.declare("S", branchPolicy(), "staff-1");

        assertThat(result.configured()).isTrue();
        assertThat(result.inheritedFrom()).isEqualTo("S");
        assertThat(result.effective()).isEqualTo(branchPolicy());
        assertThat(stored).hasSize(2);

        ArgumentCaptor<EffectivePlacementPolicy> published = ArgumentCaptor.forClass(EffectivePlacementPolicy.class);
        verify(publisher, atLeastOnce()).publishChanged(published.capture());
        // The branch, the executive and the distributor: one message each, all carrying the branch's policy.
        assertThat(published.getAllValues()).extracting(EffectivePlacementPolicy::unitCode).containsExactly("S", "E", "D");
        assertThat(published.getAllValues()).allSatisfy(p -> {
            assertThat(p.effective()).isEqualTo(branchPolicy());
            assertThat(p.inheritedFrom()).isEqualTo("S");
        });
        assertThat(published.getAllValues().get(2).partyRef()).isEqualTo(herParty);
        assertThat(published.getAllValues().get(2).levelCode()).isEqualTo("DISTRIBUTOR");
    }

    @Test
    void aDistributorMayOnlyBeStricterThanHerBranch() {
        service.declare("S", branchPolicy(), "staff-1");

        assertThatThrownBy(() -> service.declare("D", PlacementPolicy.of(limits("8000", null, null, null), null), "staff-1"))
                .isInstanceOf(InvalidPlacementPolicyException.class)
                .hasMessageContaining("sólo puede ser más estricta")
                .hasMessageContaining("NUEVO: maxLoanAmount 8000 > 5000");

        EffectivePlacementPolicy hers = service.declare("D", PlacementPolicy.of(limits("3000", null, null, 12), null), "staff-1");
        assertThat(hers.effective().forType(ClientType.NUEVO)).isEqualTo(limits("3000", "5000", 1, 12));
        assertThat(hers.effective().forType(ClientType.REVOLVENTE)).isEqualTo(limits("10000", "15000", 3, 24));
        assertThat(hers.declared().forType(ClientType.NUEVO)).isEqualTo(limits("3000", null, null, 12));
        assertThat(hers.inheritedFrom()).isEqualTo("D");
    }

    @Test
    void thePolicyIsValidatedAgainstTheCatalogsLine_andNothingIsSavedOrPublishedWhenItDoesNotFit() {
        assertThatThrownBy(() -> service.declare("S", PlacementPolicy.of(limits("12000", "12000", 1, 16), null), "staff-1"))
                .isInstanceOf(InvalidPlacementPolicyException.class)
                .hasMessageContaining("rebasa el máximo del producto 10000");
        assertThat(stored).isEmpty();
        verify(publisher, never()).publishChanged(any());

        given(products.distributorLine()).willReturn(Optional.empty());
        assertThatThrownBy(() -> service.declare("S", branchPolicy(), "staff-1"))
                .isInstanceOf(InvalidPlacementPolicyException.class)
                .hasMessageContaining("no tiene una línea de distribuidora activa");
    }

    @Test
    void clearingGoesBackToInheriting_andTheSubtreeHearsIt() {
        service.declare("S", branchPolicy(), "staff-1");
        service.declare("D", PlacementPolicy.of(limits("3000", null, null, 12), null), "staff-1");

        EffectivePlacementPolicy cleared = service.clear("D", "staff-2");

        assertThat(cleared.declared().isEmpty()).isTrue();
        assertThat(cleared.effective()).isEqualTo(branchPolicy());
        assertThat(cleared.inheritedFrom()).isEqualTo("S");

        EffectivePlacementPolicy nothing = service.clear("S", "staff-2");
        assertThat(nothing.configured()).isFalse();
        assertThat(nothing.inheritedFrom()).isNull();
        ArgumentCaptor<EffectivePlacementPolicy> published = ArgumentCaptor.forClass(EffectivePlacementPolicy.class);
        verify(publisher, atLeastOnce()).publishChanged(published.capture());
        EffectivePlacementPolicy lastForD = published.getAllValues().stream().filter(p -> p.unitCode().equals("D")).reduce((a, b) -> b).orElseThrow();
        assertThat(lastForD.configured()).isFalse();
    }

    @Test
    void redeclaringReplacesTheRowsInsteadOfPilingThemUp() {
        service.declare("S", branchPolicy(), "staff-1");
        service.declare("S", PlacementPolicy.of(limits("4000", "4000", 1, 14), null), "staff-1");

        assertThat(stored).hasSize(1);
        assertThat(service.declared("S").forType(ClientType.NUEVO)).isEqualTo(limits("4000", "4000", 1, 14));
        assertThat(service.declared("S").forType(ClientType.REVOLVENTE).isEmpty()).isTrue();
    }

    @Test
    void pathPrefixesAreTheChainFromTheRoot() {
        assertThat(PlacementPolicyService.prefixesOf("MX.R.Z.S")).containsExactly("MX", "MX.R", "MX.R.Z", "MX.R.Z.S");
        assertThat(PlacementPolicyService.prefixesOf("MX")).containsExactly("MX");
    }
}
