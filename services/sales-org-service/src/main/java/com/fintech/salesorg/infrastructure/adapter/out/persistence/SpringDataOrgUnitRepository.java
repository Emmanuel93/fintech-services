package com.fintech.salesorg.infrastructure.adapter.out.persistence;

import com.fintech.salesorg.domain.OrgUnit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface SpringDataOrgUnitRepository extends JpaRepository<OrgUnit, UUID> {

    /**
     * El IVA que aplica en una unidad, heredado del ancestro más cercano que lo declare.
     *
     * <p>Se resuelve con el path materializado: los ancestros de {@code MX.R_NORTE.Z_TIJ.S_TIJ} son
     * los prefijos de su ruta, y gana el más profundo con tasa. Así se declara una vez en la región
     * fronteriza y la heredan sus zonas y sucursales, en vez de repetirla en cada nodo —donde la
     * próxima sucursal que se abra nacería con la tasa equivocada.
     *
     * <p>Devuelve {@code null} si ningún ancestro la declara: manda el nacional, que decide quien
     * pregunta.
     */
    @Query(value = "SELECT a.vat_rate FROM sales_org.org_units u "
                 + "JOIN sales_org.org_units a ON u.path::ltree <@ a.path::ltree "
                 + "WHERE u.code = :code AND a.vat_rate IS NOT NULL "
                 + "ORDER BY nlevel(a.path::ltree) DESC LIMIT 1", nativeQuery = true)
    java.math.BigDecimal findEffectiveVatRate(@org.springframework.data.repository.query.Param("code") String code);

    Optional<OrgUnit> findByCode(String code);

    boolean existsByCode(String code);

    boolean existsByLevelId(UUID levelId);

    List<OrgUnit> findByParentUnitIdOrderByCodeAsc(UUID parentUnitId);

    List<OrgUnit> findByPartyRef(UUID partyRef);

    /**
     * El subárbol bajo (e incluyendo) la unidad cuyo path es {@code ancestorPath}. El operador LTREE
     * {@code <@} ("es descendiente de o igual a") se resuelve con el índice GIST: una consulta,
     * cualquiera sea la profundidad. El parámetro se castea a ltree explícitamente.
     */
    @Query(value = "SELECT * FROM sales_org.org_units "
                 + "WHERE path::public.ltree <@ CAST(:ancestorPath AS public.ltree) ORDER BY path",
            nativeQuery = true)
    List<OrgUnit> findSubtree(@Param("ancestorPath") String ancestorPath);

    /**
     * Los {@code party_ref} de los nodos vigentes de un nivel dado dentro del subárbol (p.ej. todos
     * los distribuidores o ejecutivos bajo una unidad). Une con org_levels para filtrar por código de
     * nivel; una sola consulta indexada por GIST. Es lo que acota la cartera al alcance del usuario.
     */
    @Query(value = "SELECT u.party_ref FROM sales_org.org_units u "
                 + "JOIN sales_org.org_levels l ON l.level_id = u.level_id "
                 + "WHERE u.active AND u.party_ref IS NOT NULL AND l.code = :levelCode "
                 + "AND u.path::public.ltree <@ CAST(:ancestorPath AS public.ltree)",
            nativeQuery = true)
    List<UUID> findPartyRefsInSubtreeByLevel(@Param("ancestorPath") String ancestorPath,
                                             @Param("levelCode") String levelCode);

    /** party_ref del nodo vigente de un nivel dado con un código dado (resuelve promoterCode→distribuidor). */
    @Query(value = "SELECT u.party_ref FROM sales_org.org_units u "
                 + "JOIN sales_org.org_levels l ON l.level_id = u.level_id "
                 + "WHERE u.code = :code AND l.code = :levelCode AND u.active AND u.party_ref IS NOT NULL",
            nativeQuery = true)
    Optional<UUID> findPartyRefByCodeAndLevel(@Param("code") String code, @Param("levelCode") String levelCode);
}
