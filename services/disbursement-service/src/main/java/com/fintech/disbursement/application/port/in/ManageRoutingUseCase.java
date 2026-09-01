package com.fintech.disbursement.application.port.in;

import com.fintech.disbursement.domain.CompanyMapping;

import java.util.List;
import java.util.UUID;

/**
 * Resolver la empresa a partir de la procedencia (DB-07).
 *
 * <p><b>Las reglas de ruteo ya no viven aquí.</b> Elegir proveedor y cuenta es una decisión de
 * tesorería, y tenerla partida en dos —el proveedor en este servicio, la cuenta en el conector—
 * era lo que dejaba que el dinero saliera por una cuenta que nadie eligió. La configuración está
 * en {@code banking}; este servicio la consulta por su puerto ACL.
 */
public interface ManageRoutingUseCase {

    CompanyMapping mapCompany(String sourceSystem, String sourceKey, UUID companyId);

    List<CompanyMapping> listMappings();
}
