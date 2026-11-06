package com.fintech.statement.application.service.assembly;

import com.fintech.documents.DocumentModel;
import java.math.BigDecimal;

/** El documento listo para la plantilla, con las dos cifras que se guardan junto a él. */
public record Assembled(DocumentModel model, BigDecimal amountDue, BigDecimal minimumPayment) {}
