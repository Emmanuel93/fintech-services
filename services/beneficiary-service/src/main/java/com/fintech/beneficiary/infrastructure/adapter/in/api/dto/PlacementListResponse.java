package com.fintech.beneficiary.infrastructure.adapter.in.api.dto;

import java.util.List;

/**
 * Envoltura de {@code GET /placements}: {@code { "placements": [ … ] }}.
 *
 * <p>Un objeto y no un arreglo pelón porque es lo que la app ya parsea, y porque deja lugar para
 * paginación el día que la cartera de un distribuidor pase de decenas a miles sin romper a nadie.
 */
public record PlacementListResponse(List<PlacementResponse> placements) {}
