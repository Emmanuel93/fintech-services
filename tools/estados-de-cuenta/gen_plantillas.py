#!/usr/bin/env python3
"""Generates the JasperReports 7 template of the account statement for every product.

    python3 tools/estados-de-cuenta/gen_plantillas.py services/statement-service/src/main/resources/reports

The nine templates share one layout — Kredius design system: Inter, blue #0D47A1, the green accent of the
logo, ink, paper — and differ in their product title, product section and "what you should know" box. The
assemblers fill the header, the boxes, the summary and the legends through parameters (text already formatted in
es-MX); the tables are the data source.

What a SOFOM ENR statement must carry (CONDUSEF DT-ENR 2015, arts. 33-37; LTOSF 13; Banxico C13/2011, C21/2009) is
laid out here, not left to each product:

  · issuer: company name with "SOFOM, E.N.R.", RFC, address and contact (art. 35-I);
  · holder, contract, period, cutoff, due date and the non-business-day notice (35-II/III, LTOSF 18 Bis 4);
  · the product's detail: payment plan (36), purchases in installments (37) or placements (36-VI);
  · every movement of the period with date and description (35-IV) and the fees charged, with their total (35-V/VI);
  · a "cost of your credit" box: CAT without IVA, annual ordinary and moratorium rates, interest of the period and
    the basis it is computed on (36-IV/IX, 37, LTOSF 9);
  · how to pay, the UNE/CONDUSEF notice and the 90-day window for clarifications (35-VIII), the consumer legends
    (36-X, 6-IV), the SOFOM ENR legend (LGOAAC 87-J) and the fiscal notice.

The tables share one data source. Each row carries its `section` and up to nine text columns (c1..c9); the report
groups by section, and each section has its own header and detail band, printed only for its rows. The assembler
emits the rows in the order they are read: product detail, movements, fees.

The colors written here are the design system's defaults. The renderer replaces each one with the value configured
under `fintech.statement.branding.palette` when the service compiles the template, so the files stay valid for
Jaspersoft Studio and the palette stays configurable (see BrandingPalette).
"""
import sys
from xml.sax.saxutils import escape

OUT = sys.argv[1]

W = 535          # usable width (A4 595 − 2 × 30)

# Kredius design system (fintech-app/packages/fa_design_system).
PRIMARY = "#0D47A1"            # Azul Kredius
ACCENT = "#00B894"             # the logo's green, the only accent
INK = "#0F1A2E"                # body text
MUTED = "#7A8296"              # secondary text
PAPER = "#EBF0F7"              # section fills
SOFT = "#F6F8FC"               # zebra rows, cards
LINE = "#EDEFF3"               # dividers
PRIMARY_CONTAINER = "#D6E4FF"  # highlighted boxes
ON_PRIMARY_CONTAINER = "#001A42"
WHITE = "#FFFFFF"

FONT = "Inter"                 # fonts/kredius-fonts.xml: Regular / Bold
FONT_DISPLAY = "Inter Black"   # ExtraBold, the weight of the wordmark: big amounts only


def tf(x, y, w, h, expr, size=8, bold=False, color=None, align=None, bg=None, style="base",
       extra="", box="", when=None, stretch=False, font=None, float_=False):
    attrs = [f'kind="textField"', f'x="{x}"', f'y="{y}"', f'width="{w}"', f'height="{h}"',
             f'style="{style}"', f'fontSize="{size}"', 'blankWhenNull="true"']
    if float_: attrs.append('positionType="Float"')
    if font: attrs.append(f'fontName="{font}"')
    if bold: attrs.append('bold="true"')
    if color: attrs.append(f'forecolor="{color}"')
    if align: attrs.append(f'hTextAlign="{align}"')
    attrs.append('vTextAlign="Middle"' if not stretch else 'vTextAlign="Top"')
    if bg: attrs += ['mode="Opaque"', f'backcolor="{bg}"']
    if stretch: attrs.append('textAdjust="StretchHeight"')
    if extra: attrs.append(extra)
    inner = ""
    if when: inner += f"<printWhenExpression><![CDATA[{when}]]></printWhenExpression>"
    inner += f"<expression><![CDATA[{expr}]]></expression>"
    if box: inner += box
    return f'<element {" ".join(attrs)}>{inner}</element>'


def label(x, y, w, h, text, **kw):
    return tf(x, y, w, h, '"' + text.replace('"', '\\"') + '"', **kw)


def rect(x, y, w, h, bg, radius=0, float_=False):
    pos = ' positionType="Float"' if float_ else ""
    return (f'<element kind="rectangle" x="{x}" y="{y}" width="{w}" height="{h}" mode="Opaque" '
            f'backcolor="{bg}" radius="{radius}"{pos}><pen lineWidth="0.0"/></element>')


def hline(x, y, w, color, width=0.75, float_=False):
    pos = ' positionType="Float"' if float_ else ""
    return (f'<element kind="line" x="{x}" y="{y}" width="{w}" height="1"{pos}>'
            f'<pen lineWidth="{width}" lineColor="{color}"/></element>')


def pad(l=4, r=4, bottom=None, bottom_width=0.5):
    b = f'<box leftPadding="{l}" rightPadding="{r}"'
    if bottom:
        return b + f'><bottomPen lineWidth="{bottom_width}" lineColor="{bottom}"/></box>'
    return b + "/>"


def section_title(y, text_expr, float_=False):
    return tf(0, y, W, 14, text_expr, size=9, bold=True, color=PRIMARY, box=pad(0, 0, PRIMARY, 0.75), float_=float_)


# ── Title band: issuer, holder, period, key figures, your credit and the summary ─────────────────────────────────

INFO_ROWS = 8
SUM_ROWS = 7
LADDER_ROWS = 5     # the distributor's payment table: the due date, three days of discount and the day past them


def ladder_table(e, y):
    """
    The distributor's payment table, at the top of the statement right under the key figures: what she delivers by
    the day she pays. The first row is the due date, with her early-payment discount whole; the next ones, each day
    after it with less discount; the last one, the first day with none, the whole total. The legal documents call her
    commission an "early-payment discount" ("descuento por pronto pago"), so does the statement.
    """
    e.append(tf(0, y, W, 14, "$P{LADDER_TITLE}", size=9, bold=True, color=PRIMARY, box=pad(0, 0, PRIMARY, 0.75)))
    y += 16
    cols = [("WHEN", "Si pagas", 0, 250, "Left"), ("RATE", "Descuento", 250, 80, "Center"),
            ("DISCOUNT", "Tu descuento", 330, 95, "Right"), ("DELIVER", "Entregas", 425, W - 425, "Right")]
    for _, head, x, w, a in cols:
        e.append(label(x, y, w, 12, head, size=6.5, bold=True, color=MUTED, align=a, box=pad(6, 6, LINE)))
    y += 12
    for i in range(LADDER_ROWS):
        n = i + 1
        first = n == 1
        bg = PRIMARY_CONTAINER if first else (SOFT if i % 2 == 1 else WHITE)
        color = ON_PRIMARY_CONTAINER if first else INK
        for key, _, x, w, a in cols:
            e.append(tf(x, y, w, 13, f"$P{{LADDER{n}_{key}}}", size=8.5 if first else 7.5,
                        bold=first or key == "DELIVER", color=color, align=a, bg=bg, box=pad(6, 6, LINE),
                        when=f"$P{{LADDER{n}_WHEN}} != null", extra='textAdjust="ScaleFont"'))
        y += 13
    e.append(tf(0, y + 1, W, 9, "$P{LADDER_NOTE}", size=6, color=MUTED, extra='textAdjust="ScaleFont"'))
    return y + 12


def header_band(spec):
    e = []
    # Brand: the logo when configured, the issuer's name when not.
    e.append('<element kind="image" x="0" y="0" width="130" height="30" scaleImage="RetainShape" '
             'hImageAlign="Left" vImageAlign="Middle" onErrorType="Blank">'
             '<printWhenExpression><![CDATA[$P{LOGO} != null]]></printWhenExpression>'
             '<expression><![CDATA[$P{LOGO}]]></expression></element>')
    e.append(tf(0, 0, 200, 30, "$P{ISSUER_NAME}", size=18, color=PRIMARY, font=FONT_DISPLAY,
                when="$P{LOGO} == null"))
    e.append(label(300, 0, W - 300, 18, "Estado de cuenta", size=13, bold=True, color=INK, align="Right"))
    e.append(label(300, 18, W - 300, 12, spec["product"], size=8, bold=True, color=PRIMARY, align="Right"))
    e.append(hline(0, 34, W, PRIMARY))
    e.append(rect(0, 33, 56, 3, ACCENT))
    # Issuer (DT-ENR 35-I): company name, RFC, address and contact, in small type under the brand.
    e.append(tf(0, 39, W, 10, "$P{ISSUER_LEGAL_NAME}", size=7.5, bold=True, color=INK))
    e.append(tf(0, 49, W, 9, "$P{ISSUER_DETAILS}", size=6.5, color=MUTED, extra='textAdjust="ScaleFont"'))
    e.append(tf(0, 58, W, 9, "$P{ISSUER_CONTACT}", size=6.5, color=MUTED, extra='textAdjust="ScaleFont"'))
    # Holder
    top = 74
    e.append(tf(0, top, 270, 11, "$P{HOLDER_KIND_LABEL}", size=7, color=MUTED))
    e.append(tf(0, top + 11, 270, 16, "$P{HOLDER_NAME}", size=12, bold=True, color=INK, extra='textAdjust="ScaleFont"'))
    e.append(tf(0, top + 28, 270, 12, '"RFC: " + ($P{HOLDER_RFC} == null ? "—" : $P{HOLDER_RFC})', size=8))
    e.append(tf(0, top + 40, 270, 12, '"Contrato: " + $P{CONTRACT_NUMBER}', size=8))
    e.append(tf(0, top + 52, 270, 12, '"Folio: " + $P{STATEMENT_FOLIO}', size=8, color=MUTED))
    # Period
    rows = [("Periodo", "$P{PERIOD}"), ("Fecha de corte", "$P{CUTOFF_DATE}"),
            ("Fecha límite de pago", "$P{DUE_DATE}"), ("Ciclo", "$P{CYCLE}")]
    for i, (lab, val) in enumerate(rows):
        y = top + i * 15
        e.append(label(285, y, 100, 15, lab, size=7, color=MUTED, bg=SOFT, box=pad(6, 4, LINE)))
        # ScaleFont: «16 de octubre al 15 de noviembre de 2026» fits by shrinking the font, not by cutting it.
        e.append(tf(385, y, W - 385, 15, val, size=8, bold=True, color=INK, align="Right", bg=SOFT,
                    box=pad(4, 6, LINE), extra='textAdjust="ScaleFont"'))
    e.append(tf(285, top + 61, W - 285, 9, "$P{DUE_NOTICE}", size=6, color=MUTED, align="Right",
                extra='textAdjust="ScaleFont"'))
    # Key figures: the first box carries the amount that matters; the other two give it context.
    by = 146
    bw = (W - 2 * 10) // 3
    for i in range(3):
        x = i * (bw + 10)
        n = i + 1
        if n == 1:
            e.append(rect(x, by, bw, 48, PRIMARY, radius=6))
            e.append(rect(x + 10, by, 36, 2, ACCENT))
            e.append(tf(x + 10, by + 4, bw - 20, 12, f"$P{{BOX{n}_LABEL}}", size=7, color=WHITE))
            e.append(tf(x + 10, by + 17, bw - 20, 26, f"$P{{BOX{n}_VALUE}}", size=15, color=WHITE, font=FONT_DISPLAY,
                        extra='textAdjust="ScaleFont"'))
        else:
            e.append(rect(x, by, bw, 48, PRIMARY_CONTAINER, radius=6))
            e.append(tf(x + 10, by + 4, bw - 20, 12, f"$P{{BOX{n}_LABEL}}", size=7, color=ON_PRIMARY_CONTAINER))
            e.append(tf(x + 10, by + 17, bw - 20, 26, f"$P{{BOX{n}_VALUE}}", size=13, bold=True,
                        color=ON_PRIMARY_CONTAINER, extra='textAdjust="ScaleFont"'))
    # A distributor's payment table goes first: what she delivers by the day she pays is what she reads first.
    sy = 206
    if spec.get("ladder"):
        sy = ladder_table(e, 202) + 6
    # Your credit / summary of the period
    e.append(label(0, sy, 255, 14, "Tu crédito", size=9, bold=True, color=PRIMARY, box=pad(0, 0, PRIMARY, 0.75)))
    for i in range(INFO_ROWS):
        y = sy + 16 + i * 12
        n = i + 1
        e.append(tf(0, y, 135, 12, f"$P{{INFO{n}_LABEL}}", size=7.5, color=MUTED, box=pad(0, 0, LINE)))
        e.append(tf(135, y, 120, 12, f"$P{{INFO{n}_VALUE}}", size=7.5, color=INK, align="Right", box=pad(0, 0, LINE),
                    extra='textAdjust="ScaleFont"'))
    e.append(tf(280, sy, W - 280, 14, "$P{SUMMARY_TITLE}", size=9, bold=True, color=PRIMARY,
                box=pad(0, 0, PRIMARY, 0.75)))
    for i in range(SUM_ROWS):
        y = sy + 16 + i * 12
        n = i + 1
        e.append(tf(280, y, 160, 12, f"$P{{SUM{n}_LABEL}}", size=7.5, color=MUTED, box=pad(0, 0, LINE)))
        e.append(tf(440, y, W - 440, 12, f"$P{{SUM{n}_VALUE}}", size=7.5, color=INK, align="Right", box=pad(0, 0, LINE)))
    ty = sy + 16 + SUM_ROWS * 12 + 2
    e.append(tf(280, ty, 160, 16, "$P{SUM_TOTAL_LABEL}", size=9, bold=True, color=INK, bg=PAPER, box=pad(6, 4)))
    e.append(tf(440, ty, W - 440, 16, "$P{SUM_TOTAL_VALUE}", size=9, bold=True, color=INK, align="Right", bg=PAPER,
                box=pad(4, 6)))
    height = max(sy + 16 + INFO_ROWS * 12, ty + 16) + 8
    return "\n      ".join(e), height


# ── Sections (tables) ────────────────────────────────────────────────────────────────────────────────────────────

FIELDS = ["section", "c1", "c2", "c3", "c4", "c5", "c6", "c7", "c8", "c9", "emphasis"]

MOV = dict(key="MOV", title="Movimientos del periodo",
           columns=[("c1", "Fecha", 75, "Left"), ("c2", "Concepto", 260, "Left"), ("c3", "Cargo", 100, "Right"),
                    ("c4", "Abono", 100, "Right")])
COM = dict(key="COM", title="Comisiones cobradas en el periodo",
           columns=[("c1", "Fecha", 75, "Left"), ("c2", "Concepto", 300, "Left"), ("c3", "Monto", 160, "Right")])
PLAN = dict(key="PLAN", title="Plan de pagos",
            columns=[("c1", "", 15, "Center"), ("c2", "No.", 30, "Center"), ("c3", "Vence", 80, "Left"),
                     ("c4", "Capital", 85, "Right"), ("c5", "Interés", 80, "Right"), ("c6", "IVA", 70, "Right"),
                     ("c7", "Total", 90, "Right"), ("c8", "Estado", 85, "Left")])
MSI = dict(key="MSI", title="Compras y disposiciones a plazos",
           columns=[("c1", "Fecha", 60, "Left"), ("c2", "Compra", 130, "Left"), ("c3", "Monto original", 75, "Right"),
                    ("c4", "Pago", 55, "Center"), ("c5", "Del periodo", 75, "Right"),
                    ("c6", "Saldo insoluto", 80, "Right"), ("c7", "Vencidos", 60, "Center")])
COLOC = dict(key="COLOC", title="Colocaciones vigentes",
             columns=[("c1", "Cliente", 100, "Left"), ("c2", "Colocada", 52, "Left"), ("c3", "Monto", 54, "Right"),
                      ("c4", "Pago", 40, "Center"), ("c5", "Vencidos", 54, "Center"), ("c6", "A pagar", 58, "Right"),
                      ("c7", "Saldo insoluto", 60, "Right"), ("c8", "Tu descuento", 62, "Right"), ("c9", "CAT", 55, "Right")])

# How a distributor's total to pay is formed: her clients' payments and what goes on top of them.
PAGO = dict(key="PAGO", title="Cómo se forma tu total a pagar",
            columns=[("c1", "Concepto", 435, "Left"), ("c2", "Monto", 100, "Right")])

# What a distributor earns, her early-payment discount (her commission, in the system): on this statement's payments,
# what it is of their interest, and what she already applied in the deliveries of the period.
TUCOM = dict(key="TUCOM", title="Tu descuento por pronto pago",
             columns=[("c1", "Concepto", 275, "Left"), ("c2", "Base", 90, "Right"), ("c3", "Tasa", 60, "Center"),
                      ("c4", "Descuento", 110, "Right")])

# The plans settled in the period, apart from the live ones: a table titled "vigentes" never lists a settled one.
LIQ = dict(key="LIQ", title="Colocaciones liquidadas en el periodo",
           columns=[("c1", "Cliente", 150, "Left"), ("c2", "Colocada", 70, "Left"), ("c3", "Monto", 85, "Right"),
                    ("c4", "Plazo", 70, "Center"), ("c5", "Liquidada el", 75, "Left"),
                    ("c6", "Cómo se liquidó", 85, "Left")])


def variant(base, **kw):
    s = dict(base)
    s.update({k: v for k, v in kw.items() if k != "headers"})
    if "headers" in kw:
        s["columns"] = [(f, kw["headers"].get(f, h), w, a) for f, h, w, a in base["columns"]]
    return s


def group_header_bands(sections):
    bands = []
    for s in sections:
        cond = f'"{s["key"]}".equals($F{{section}})'
        els = [label(0, 6, W, 14, s["title"], size=9.5, bold=True, color=INK)]
        x = 0
        for field, title, width, align in s["columns"]:
            els.append(label(x, 22, width, 16, title, size=7, bold=True, color=PRIMARY, bg=PAPER, align=align,
                             box=pad(4, 4, PRIMARY, 0.75)))
            x += width
        assert x == W, (s["key"], x)
        bands.append(f'<band height="38" splitType="Prevent"><printWhenExpression><![CDATA[{cond}]]></printWhenExpression>'
                     + "\n        " + "\n        ".join(els) + "\n      </band>")
    return "\n      ".join(bands)


def detail_bands(sections):
    bands = []
    for s in sections:
        cond = f'"{s["key"]}".equals($F{{section}}) && !"EMPTY".equals($F{{emphasis}})'
        els = []
        x = 0
        for field, title, width, align in s["columns"]:
            els.append(tf(x, 0, width, 13, f"$F{{{field}}}", size=7.5, align=align, stretch=True, style="fila",
                          box=pad(4, 4, LINE)))
            x += width
        bands.append(f'<band height="13"><printWhenExpression><![CDATA[{cond}]]></printWhenExpression>'
                     + "\n        " + "\n        ".join(els) + "\n      </band>")
    # A section with nothing to list says so in one row (the movements of a quiet period, a plan not issued yet).
    empty = tf(0, 0, W, 14, "$F{c1}", size=7.5, color=MUTED, box=pad(4, 4, LINE))
    bands.append('<band height="14"><printWhenExpression><![CDATA["EMPTY".equals($F{emphasis})]]></printWhenExpression>'
                 + "\n        " + empty + "\n      </band>")
    return "\n      ".join(bands)


# ── Summary band: costs, fees total, product notice, how to pay, clarifications and legends ──────────────────────

COST_SLOTS = 6


def summary_band(spec):
    e = []
    y = 8
    e.append(section_title(y, '"Costos de tu crédito"'))
    y += 18
    cw = (W - 2 * 8) // 3
    for i in range(COST_SLOTS):
        n = i + 1
        col, row = i % 3, i // 3
        x = col * (cw + 8)
        yy = y + row * 30
        e.append(rect(x, yy, cw, 26, SOFT, radius=4))
        e.append(tf(x + 8, yy + 2, cw - 16, 10, f"$P{{COST{n}_LABEL}}", size=6.5, color=MUTED))
        e.append(tf(x + 8, yy + 12, cw - 16, 12, f"$P{{COST{n}_VALUE}}", size=9, bold=True, color=INK,
                    extra='textAdjust="ScaleFont"'))
    y += 2 * 30 + 2
    # The fees box (DT-ENR 35-VI): the total, always, even when it is zero.
    e.append(tf(0, y, 380, 16, '"Total de comisiones cobradas en el periodo"', size=8, bold=True, color=INK, bg=PAPER,
                box=pad(6, 4)))
    e.append(tf(380, y, W - 380, 16, "$P{COMMISSIONS_TOTAL}", size=8, bold=True, color=INK, align="Right", bg=PAPER,
                box=pad(4, 6)))
    y += 24
    notice_box = (f'<box leftPadding="10" rightPadding="8" topPadding="4" bottomPadding="4">'
                  f'<leftPen lineWidth="3.0" lineColor="{ACCENT}"/></box>')
    blocks = [
        (spec["notice_title"], None, '"' + spec["notice"].replace('"', '\\"') + '"', True),
        (None, None, "$P{PRODUCT_NOTE}", False),
        ("Cómo pagar", None, "$P{PAYMENT_INSTRUCTIONS}", False),
        ("Aclaraciones y quejas", None, "$P{CLARIFICATIONS_NOTICE}", False),
        (None, None, "$P{UNE_NOTICE}", False),
        ("Lo que debes saber", None, "$P{WARNINGS}", False),
    ]
    for title, _, expr, boxed in blocks:
        if title:
            e.append(section_title(y, '"' + title + '"', float_=True))
            y += 18
        if boxed:
            e.append(tf(0, y, W, 24, expr, size=7.5, color=INK, bg=SOFT, stretch=True, box=notice_box, float_=True))
            y += 28
        else:
            e.append(tf(0, y, W, 12, expr, size=7.5, color=INK, stretch=True, float_=True))
            y += 14
    e.append(hline(0, y + 2, W, LINE, 0.5, float_=True))
    y += 6
    for expr in ("$P{REGULATORY_LEGEND}", "$P{FISCAL_NOTICE}", "$P{LEGAL_NOTICE}"):
        e.append(tf(0, y, W, 10, expr, size=6, color=MUTED, stretch=True, float_=True))
        y += 11
    return "\n      ".join(e), y + 4


def params(spec):
    names = ["ISSUER_NAME", "ISSUER_LEGEND", "ISSUER_LEGAL_NAME", "ISSUER_DETAILS", "ISSUER_CONTACT",
             "HOLDER_KIND_LABEL", "HOLDER_NAME", "HOLDER_RFC", "CONTRACT_NUMBER", "STATEMENT_FOLIO", "PERIOD",
             "CUTOFF_DATE", "DUE_DATE", "DUE_NOTICE", "CYCLE", "SUMMARY_TITLE", "SUM_TOTAL_LABEL", "SUM_TOTAL_VALUE",
             "EMPTY_TABLE_MESSAGE", "PAYMENT_INSTRUCTIONS", "LEGAL_NOTICE", "GENERATED_AT", "COMMISSIONS_TOTAL",
             "PRODUCT_NOTE", "CLARIFICATIONS_NOTICE", "UNE_NOTICE", "WARNINGS", "REGULATORY_LEGEND", "FISCAL_NOTICE"]
    for n in range(1, 4): names += [f"BOX{n}_LABEL", f"BOX{n}_VALUE"]
    for n in range(1, INFO_ROWS + 1): names += [f"INFO{n}_LABEL", f"INFO{n}_VALUE"]
    for n in range(1, SUM_ROWS + 1): names += [f"SUM{n}_LABEL", f"SUM{n}_VALUE"]
    for n in range(1, COST_SLOTS + 1): names += [f"COST{n}_LABEL", f"COST{n}_VALUE"]
    if spec.get("ladder"):
        names += ["LADDER_TITLE", "LADDER_NOTE"]
        for n in range(1, LADDER_ROWS + 1): names += [f"LADDER{n}_{k}" for k in ("WHEN", "RATE", "DISCOUNT", "DELIVER")]
    declared = "\n  ".join(f'<parameter name="{p}" class="java.lang.String"/>' for p in names)
    # The logo is the renderer's, not the assembler's: branding, not content.
    return declared + '\n  <parameter name="LOGO" class="java.awt.Image"/>'


def report(name, spec):
    sections = [spec["detail"]] + spec.get("settled", []) + spec.get("extra", []) + [MOV, COM]
    title_comment = spec["product"] + ". " + spec["comment"]
    head, head_h = header_band(spec)
    summary, summary_h = summary_band(spec)
    fields = "\n  ".join(f'<field name="{f}" class="java.lang.String"/>' for f in FIELDS)
    footer = [
        hline(0, 2, W, LINE, 0.5),
        tf(0, 6, 360, 12, '"Folio " + $P{STATEMENT_FOLIO} + " · Generado " + $P{GENERATED_AT}', size=6, color=MUTED),
        tf(360, 6, 140, 12, '"Página " + $V{PAGE_NUMBER} + " de"', size=6, color=MUTED, align="Right"),
        tf(500, 6, 35, 12, '" " + $V{PAGE_NUMBER}', size=6, color=MUTED, extra='evaluationTime="Report"'),
    ]
    j = lambda els: "\n      ".join(els)
    return f'''<?xml version="1.0" encoding="UTF-8"?>
<!-- {escape(title_comment)}
     Generated by tools/estados-de-cuenta/gen_plantillas.py in the JasperReports 7 format. Header, boxes, costs and
     legends are filled through parameters (text already formatted in es-MX); the tables are one data source grouped
     by section (product detail, movements, fees). Colors are the Kredius design system's defaults; the renderer swaps
     them for the configured palette (fintech.statement.branding) when it compiles the template. -->
<jasperReport name="{name}" language="java" pageWidth="595" pageHeight="842" columnWidth="{W}" leftMargin="30" rightMargin="30" topMargin="28" bottomMargin="24" whenNoDataType="AllSectionsNoDetail">
  <style name="base" default="true" fontName="{FONT}" fontSize="8.0" forecolor="{INK}"/>
  <style name="fila" fontName="{FONT}" fontSize="7.5" forecolor="{INK}" mode="Opaque" backcolor="{WHITE}">
    <conditionalStyle mode="Opaque" backcolor="{SOFT}">
      <conditionExpression><![CDATA["1".equals($F{{emphasis}})]]></conditionExpression>
    </conditionalStyle>
  </style>
  {params(spec)}
  {fields}
  <group name="seccion" minHeightToStartNewPage="64">
    <expression><![CDATA[$F{{section}}]]></expression>
    <groupHeader>
      {group_header_bands(sections)}
    </groupHeader>
  </group>
  <title height="{head_h}">
      {head}
  </title>
  <detail>
      {detail_bands(sections)}
  </detail>
  <pageFooter height="20">
      {j(footer)}
  </pageFooter>
  <summary height="{summary_h}">
      {summary}
  </summary>
</jasperReport>
'''


# The legal warning about the minimum payment goes in "Lo que debes saber" (the assembler adds it for consumer
# revolving credit); the product's box says what to do instead.
MINIMO = ("Cubre el pago para no generar intereses antes de la fecha límite y tus compras del periodo no pagan "
          "intereses. Las compras a meses se pagan en sus mensualidades, incluidas en tu pago mínimo.")
PLAZO_NOTICE = ("Pagas en cuotas fijas según tu plan de pagos. Cada pago cubre primero los intereses moratorios, luego "
                "los ordinarios y al final el capital. Un pago anticipado se aplica a capital; tu plan actualizado "
                "aparece en tu siguiente estado de cuenta.")

LIQ_MSI = variant(LIQ, title="Compras a meses liquidadas en el periodo", headers={"c1": "Compra", "c2": "Fecha"})

templates = {
    "estado-cuenta-prestamo-personal": dict(
        product="Préstamo personal", detail=PLAN,
        comment="El detalle es el plan de pagos con su avance.",
        notice_title="Tu préstamo personal", notice=PLAZO_NOTICE),
    "estado-cuenta-prestamo-nomina": dict(
        product="Préstamo de nómina",
        detail=variant(PLAN, title="Descuentos programados", headers={"c3": "Se descuenta", "c7": "Importe"}),
        comment="Plan de pagos que se cobra por descuento de nómina.",
        notice_title="Tu préstamo de nómina",
        notice="Tu cuota se descuenta de tu nómina. Si el descuento no se aplicó antes de la fecha límite, paga la "
               "cuota por SPEI para no generar intereses moratorios. Un pago anticipado se aplica a capital."),
    "estado-cuenta-prestamo-grupal": dict(
        product="Préstamo grupal", detail=variant(PLAN, title="Calendario de pagos del grupo"),
        comment="Plan de pagos de un crédito grupal.",
        notice_title="Tu préstamo grupal",
        notice="El grupo responde en conjunto por los pagos. Paga tu cuota antes de la fecha límite para que el grupo "
               "se mantenga al corriente."),
    "estado-cuenta-microcredito": dict(
        product="Microcrédito", detail=variant(PLAN, headers={"c2": "Pago"}),
        comment="Plan de pagos frecuentes de monto fijo.",
        notice_title="Tu microcrédito",
        notice="Pagas montos fijos con la frecuencia de tu plan. Pagar cada cuota a tiempo evita intereses moratorios."),
    "estado-cuenta-credito-pyme": dict(
        product="Crédito PyME", detail=variant(PLAN, title="Plan de amortización"),
        comment="Crédito a plazo a nombre de una empresa.",
        notice_title="El crédito de tu empresa",
        notice="Crédito a nombre de la empresa. Cada cuota desglosa capital, intereses e IVA. Pagar después de la fecha "
               "límite genera intereses moratorios sobre el capital vencido."),
    "estado-cuenta-tarjeta-credito": dict(
        product="Tarjeta de crédito", detail=variant(MSI, title="Compras a meses y promociones a plazo"), settled=[LIQ_MSI],
        comment="Movimientos, compras a meses, pago mínimo y pago para no generar intereses.",
        notice_title="Tu tarjeta de crédito", notice=MINIMO),
    "estado-cuenta-linea-revolvente": dict(
        product="Línea de crédito revolvente", detail=MSI, settled=[LIQ_MSI],
        comment="Movimientos y disposiciones a plazos de una línea revolvente.",
        notice_title="Tu línea de crédito",
        notice="Cada disposición se suma al saldo de tu línea y cada pago libera disponible. " + MINIMO),
    "estado-cuenta-linea-empresarial": dict(
        product="Línea revolvente empresarial", detail=MSI, settled=[LIQ_MSI],
        comment="Movimientos de la línea revolvente de una empresa.",
        notice_title="La línea de tu empresa",
        notice="Línea a nombre de la empresa. Cada disposición se suma al saldo y cada pago libera disponible."),
    "estado-cuenta-linea-distribuidora": dict(
        product="Línea de distribuidora", detail=COLOC, settled=[LIQ], extra=[PAGO, TUCOM], ladder=True,
        comment="Arriba, el total a pagar, el descuento por pronto pago y la tabla de lo que se entrega según el día de "
                "pago; el detalle son las colocaciones vigentes, cada una con el pago que se cobra, su descuento y su CAT; "
                "las liquidadas en el periodo van aparte; luego cómo se forma el total y el descuento de la distribuidora.",
        notice_title="Tu línea de distribuidora",
        notice="Cada fila trae el pago de tu cliente en esta quincena (capital, interés e IVA de su cuota) y el descuento "
               "por pronto pago que ganas con él si entregas a tiempo. El total a pagar suma esos pagos, los moratorios y "
               "lo que quede pendiente de cortes anteriores. Cubrirlo completo mantiene tu línea al corriente y tu "
               "disponible para seguir colocando."),
}

for name, spec in templates.items():
    with open(f"{OUT}/{name}.jrxml", "w", encoding="utf-8") as f:
        f.write(report(name, spec))
    print("ok", name)
