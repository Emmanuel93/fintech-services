package com.fintech.receipt.application.service.assembly;

import com.fintech.documents.DocumentModel;
import com.fintech.documents.Formats;
import com.fintech.receipt.application.ReceiptProperties;
import com.fintech.receipt.domain.CutoffFacts;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static com.fintech.documents.Formats.date;
import static com.fintech.documents.Formats.money;

/**
 * The receipt of a distributor's client: what he pays this fortnight for every loan he has with her, how to pay it,
 * and who he owes.
 *
 * <p>The amount is without her early-payment discount: that discount is hers, for delivering on time, and the client
 * pays the whole installment. By construction the receipts of a statement add up to its row «Pagos de tus clientes»:
 * each loan's payment is the same sum of what the statement bills (principal, interest and IVA of its installments).
 */
@Component
public class ReceiptAssembler {

    public DocumentModel assemble(ReceiptInputs in, ReceiptProperties properties, ZoneId zone) {
        CutoffFacts f = in.cutoff();
        ReceiptProperties.Issuer issuer = properties.getIssuer();
        BigDecimal total = in.totalDue();
        String distributor = in.distributor() != null && in.distributor().name() != null ? in.distributor().name() : "tu distribuidora";
        int live = in.live().size();

        DocumentModel m = new DocumentModel()
                .put("ISSUER_NAME", issuer.getName())
                .put("ISSUER_LEGAL_NAME", issuer.getLegalName())
                .put("ISSUER_DETAILS", "RFC " + issuer.getRfc() + " · " + issuer.getAddress())
                .put("ISSUER_CONTACT", "Atención a clientes: " + issuer.getPhone() + " · " + issuer.getUneEmail()
                        + " · " + issuer.getWebsite())
                .put("CLIENT_NAME", in.client() != null && in.client().name() != null ? in.client().name() : "Cliente")
                .put("CLIENT_RFC", in.client() != null ? in.client().rfc() : null)
                .put("CLIENT_TYPE_LABEL", typeLabel(in.clientType(), in.loansPaid()))
                .put("RECEIPT_FOLIO", in.folio())
                .put("DISTRIBUTOR_NAME", distributor)
                .put("PERIOD", Formats.period(f.effectivePeriodStart(), f.cutoffDate()))
                .put("CUTOFF_DATE", date(f.cutoffDate()))
                .put("DUE_DATE", date(f.paymentDueDate()))
                .put("DUE_NOTICE", "Si la fecha límite es día inhábil, puedes pagar el siguiente día hábil.")
                .put("CYCLE", String.valueOf(f.cycleNumber()))
                .slot("BOX", 1, "Total a pagar", money(total))
                .slot("BOX", 2, "Fecha límite de pago", date(f.paymentDueDate()))
                .slot("BOX", 3, "Préstamos con tu distribuidora", live == 1 ? "1 vigente" : live + " vigentes")
                .put("PAY_TITLE", "Cómo pagar")
                .put("PAY_CLABE", in.clabe() != null ? in.clabe() : "Pídela a tu distribuidora")
                .put("PAY_REFERENCE", in.paymentReference() != null ? String.valueOf(in.paymentReference()) : "—")
                // The collection CLABE is Kredius's (the SPEI connector's account, assigned to her): the same holder her
                // statement names, so what he types in his bank matches what she reads.
                .put("PAY_BENEFICIARY", issuer.getLegalName())
                .put("PAY_NOTE", "Deposita por SPEI a la CLABE de cobro de tu distribuidora en " + issuer.getName()
                        + " con tu referencia: tu pago se aplica directo a tus préstamos. También puedes entregarle tu "
                        + "pago a tu distribuidora.")
                .put("RED_LIST_WARNING", redListWarning(in.flags()))
                .put("PRODUCT_NOTE", "Paga a más tardar el " + date(f.paymentDueDate()) + ". Kredius no te cobra intereses "
                        + "moratorios: si pagas tarde, tu distribuidora puede cobrarte el cargo que hayan acordado, y lo "
                        + "que quede vencido aparece en tu siguiente recibo hasta que lo pagues.")
                .put("LEGAL_RECEIPT", "Este recibo lo genera " + issuer.getName() + " para que sepas cuánto y cómo pagar. La "
                        + "obligación de tu deuda es con tu distribuidora, " + distributor + ", que te prestó con su línea de "
                        + "crédito en " + issuer.getName() + ". Si no cumples con tus pagos, " + issuer.getName()
                        + " puede marcarte para que otras distribuidoras conozcan tu comportamiento de pago.")
                .put("CLARIFICATIONS_NOTICE", "Si no estás de acuerdo con un monto de este recibo, acláralo primero con tu "
                        + "distribuidora; si no se resuelve, tienes 90 días naturales a partir de la fecha de corte para "
                        + "presentar tu aclaración ante " + issuer.getName() + ".")
                .put("UNE_NOTICE", issuer.getLegalName() + " recibe las consultas, reclamaciones o aclaraciones en su "
                        + "Unidad Especializada de Atención a Usuarios, ubicada en " + issuer.getUneAddress()
                        + ", y por correo electrónico " + issuer.getUneEmail() + " o teléfono " + issuer.getUnePhone()
                        + ". En el caso de no obtener una respuesta satisfactoria, podrá acudir a la CONDUSEF: "
                        + issuer.getCondusefWebsite() + ", teléfono " + issuer.getCondusefPhone() + ".")
                .put("REGULATORY_LEGEND", issuer.getLegalName() + " para su constitución y operación con tal carácter no "
                        + "requiere de autorización de la Secretaría de Hacienda y Crédito Público y está sujeta a la "
                        + "supervisión de la Comisión Nacional Bancaria y de Valores únicamente para efectos de lo "
                        + "dispuesto por el artículo 56 de la Ley General de Organizaciones y Actividades Auxiliares del "
                        + "Crédito.")
                .put("GENERATED_AT", Formats.stamp(in.issuedAt(), zone));

        int n = 0;
        for (ReceiptInputs.Loan loan : in.live()) {
            n++;
            List<ReceiptInputs.Installment> due = loan.dueOrEmpty();
            m.row("section", "PREST", "emphasis", loan.overdueInstallments() > 0 ? "1" : "",
                  "c1", String.valueOf(n),
                  "c2", date(loan.placedAt(), zone),
                  "c3", money(loan.amount()),
                  "c4", paymentNumber(loan),
                  "c5", money(sum(due, ReceiptInputs.Installment::principal)),
                  "c6", money(sum(due, ReceiptInputs.Installment::interest)),
                  "c7", money(sum(due, ReceiptInputs.Installment::tax)),
                  "c8", money(loan.payment()),
                  "c9", money(loan.outstandingPrincipal()),
                  "c10", loan.overdueInstallments() == 0 ? "—"
                          : loan.oldestOverdueDue() == null ? String.valueOf(loan.overdueInstallments())
                          : loan.overdueInstallments() + " desde " + date(loan.oldestOverdueDue()));
        }
        if (in.live().isEmpty()) {
            m.row("section", "PREST", "emphasis", "EMPTY", "c1", "No tienes préstamos vigentes con tu distribuidora.");
        } else {
            m.row("section", "PREST", "emphasis", "TOTAL", "c1", "Total a pagar en esta quincena", "c8", money(total));
        }
        for (ReceiptInputs.Loan loan : in.settled()) {
            n++;
            m.row("section", "LIQ", "emphasis", "",
                  "c1", String.valueOf(n),
                  "c2", date(loan.placedAt(), zone),
                  "c3", money(loan.amount()),
                  "c4", loan.totalInstallments() + " pagos",
                  "c5", date(loan.settledOn()));
        }
        return m;
    }

    /** «3 de 12»: the number of the payment this receipt bills, out of all of them; «—» when it bills none. */
    static String paymentNumber(ReceiptInputs.Loan loan) {
        return loan.dueOrEmpty().stream().mapToInt(ReceiptInputs.Installment::number).max()
                .stream().mapToObj(k -> k + " de " + loan.totalInstallments()).findFirst().orElse("—");
    }

    static String typeLabel(String type, int loansPaid) {
        if ("REVOLVENTE".equalsIgnoreCase(type)) {
            return "Cliente revolvente · " + (loansPaid == 1 ? "1 préstamo pagado" : loansPaid + " préstamos pagados");
        }
        if ("NUEVO".equalsIgnoreCase(type)) return "Cliente nuevo";
        return null;
    }

    /** His red-list marks, in his words: what they mean for him and what lifts each one. */
    static String redListWarning(List<String> flags) {
        if (flags == null || flags.isEmpty()) return null;
        List<String> why = new ArrayList<>();
        if (flags.contains("VOUCHER_IN_ARREARS")) why.add("tienes cuotas vencidas");
        if (flags.contains("DISTRIBUTOR_IN_ARREARS")) why.add("tu distribuidora tiene pagos vencidos de tus cuotas");
        if (flags.contains("DISTRIBUTOR_REPORT")) why.add("una distribuidora te reportó");
        return "Tienes una marca en tu historial de pagos con Kredius (" + String.join("; ", why) + "). Mientras siga, no "
                + "puedes recibir préstamos nuevos. Ponerte al día con tus cuotas la quita; si te reportaron, habla con tu "
                + "distribuidora.";
    }

    private static BigDecimal sum(List<ReceiptInputs.Installment> due,
                                  Function<ReceiptInputs.Installment, BigDecimal> part) {
        return due.stream().map(part).map(Formats::nz).reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
